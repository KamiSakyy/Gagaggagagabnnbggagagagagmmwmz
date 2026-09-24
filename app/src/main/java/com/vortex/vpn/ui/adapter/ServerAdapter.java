package com.vortex.vpn.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.vortex.vpn.R;
import com.vortex.vpn.model.Server;
import com.vortex.vpn.sub.Geo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ServerAdapter extends RecyclerView.Adapter<ServerAdapter.Holder> {

    public interface Listener {
        void onSelect(Server server);

        void onFavorite(Server server);

        void onTest(Server server);
    }

    private final Context context;
    private final Listener listener;
    private final List<Server> items = new ArrayList<>();
    private Map<String, Integer> pings;
    private String activeTag = "";

    public ServerAdapter(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void submit(List<Server> servers) {
        items.clear();
        if (servers != null) {
            items.addAll(servers);
        }
        notifyDataSetChanged();
    }

    public void setPings(Map<String, Integer> pings) {
        this.pings = pings;
        notifyDataSetChanged();
    }

    public void setActiveTag(String tag) {
        this.activeTag = tag == null ? "" : tag;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_server, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        final Server server = items.get(position);
        holder.flag.setText(Geo.flag(server.country));
        holder.name.setText(server.displayName());
        holder.name.setTextColor(ContextCompat.getColor(context,
                server.selected ? R.color.accent : R.color.text_primary));

        StringBuilder meta = new StringBuilder();
        meta.append(server.type == null ? "" : server.type.toUpperCase(Locale.US));
        if (server.server != null && !server.server.isEmpty()) {
            meta.append(" \u2022 ").append(server.server).append(':').append(server.port);
        }
        holder.meta.setText(meta.toString());

        int latency = server.ping;
        if (pings != null && server.tag != null && pings.get(server.tag) != null) {
            latency = pings.get(server.tag);
        }
        if (latency > 0) {
            holder.ping.setText(latency + " мс");
            holder.ping.setTextColor(ContextCompat.getColor(context, pingColor(latency)));
        } else if (latency < 0) {
            holder.ping.setText(R.string.ping_timeout);
            holder.ping.setTextColor(ContextCompat.getColor(context, R.color.danger));
        } else {
            holder.ping.setText(R.string.ping_unknown);
            holder.ping.setTextColor(ContextCompat.getColor(context, R.color.text_tertiary));
        }

        holder.favorite.setImageResource(server.favorite ? R.drawable.ic_star_filled : R.drawable.ic_star);
        boolean active = activeTag.equals(server.tag) || server.selected;
        holder.marker.setVisibility(active ? View.VISIBLE : View.INVISIBLE);
        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onSelect(server);
            }
        });
        holder.favorite.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onFavorite(server);
            }
        });
        holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                listener.onTest(server);
                return true;
            }
        });
    }

    private int pingColor(int latency) {
        if (latency < 150) {
            return R.color.accent;
        }
        if (latency < 400) {
            return R.color.warning;
        }
        return R.color.danger;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView flag;
        final TextView name;
        final TextView meta;
        final TextView ping;
        final ImageButton favorite;
        final ImageView marker;

        Holder(View itemView) {
            super(itemView);
            flag = itemView.findViewById(R.id.text_flag);
            name = itemView.findViewById(R.id.text_name);
            meta = itemView.findViewById(R.id.text_meta);
            ping = itemView.findViewById(R.id.text_ping);
            favorite = itemView.findViewById(R.id.btn_favorite);
            marker = itemView.findViewById(R.id.marker);
        }
    }
}

package com.vortex.vpn.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.vortex.vpn.R;
import com.vortex.vpn.core.Bridge;
import com.vortex.vpn.core.VpnServiceVortex;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Live connection table from the engine. */
public class ConnectionAdapter extends RecyclerView.Adapter<ConnectionAdapter.Holder> {

    private final Context context;
    private final List<Bridge.ConnectionInfo> items = new ArrayList<>();

    public ConnectionAdapter(Context context) {
        this.context = context;
    }

    public void submit(List<Bridge.ConnectionInfo> connections) {
        items.clear();
        if (connections != null) {
            items.addAll(connections);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_connection, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Bridge.ConnectionInfo info = items.get(position);
        String host = info.domain == null || info.domain.isEmpty() ? info.destination : info.domain;
        holder.host.setText(host == null ? "" : host);
        StringBuilder meta = new StringBuilder();
        if (info.network != null && !info.network.isEmpty()) {
            meta.append(info.network.toUpperCase(Locale.US));
        }
        if (info.protocol != null && !info.protocol.isEmpty()) {
            meta.append(meta.length() > 0 ? " \u2022 " : "").append(info.protocol);
        }
        if (info.outbound != null && !info.outbound.isEmpty()) {
            meta.append(meta.length() > 0 ? " \u2022 " : "").append(info.outbound);
        }
        if (info.rule != null && !info.rule.isEmpty()) {
            meta.append(meta.length() > 0 ? " \u2022 " : "").append(info.rule);
        }
        holder.meta.setText(meta.toString());
        holder.traffic.setText(context.getString(R.string.connection_traffic,
                VpnServiceVortex.formatBytes(info.download), VpnServiceVortex.formatBytes(info.upload)));
        holder.source.setText(info.source == null ? "" : info.source);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView host;
        final TextView meta;
        final TextView traffic;
        final TextView source;

        Holder(View itemView) {
            super(itemView);
            host = itemView.findViewById(R.id.text_host);
            meta = itemView.findViewById(R.id.text_meta);
            traffic = itemView.findViewById(R.id.text_traffic);
            source = itemView.findViewById(R.id.text_source);
        }
    }
}

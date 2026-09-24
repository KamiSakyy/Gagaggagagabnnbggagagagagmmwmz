package com.vortex.vpn.ui.adapter;

import android.content.Context;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.vortex.vpn.R;
import com.vortex.vpn.model.Subscription;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SubscriptionAdapter extends RecyclerView.Adapter<SubscriptionAdapter.Holder> {

    public interface Listener {
        void onRefresh(Subscription subscription);

        void onEdit(Subscription subscription);

        void onMenu(View anchor, Subscription subscription);
    }

    private final Context context;
    private final Listener listener;
    private final List<Subscription> items = new ArrayList<>();
    private long busyId = -1;

    public SubscriptionAdapter(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void submit(List<Subscription> subscriptions) {
        items.clear();
        if (subscriptions != null) {
            items.addAll(subscriptions);
        }
        notifyDataSetChanged();
    }

    public void setBusy(long subscriptionId) {
        busyId = subscriptionId;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_subscription, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        final Subscription subscription = items.get(position);
        holder.name.setText(subscription.name == null || subscription.name.isEmpty()
                ? context.getString(R.string.untitled_profile) : subscription.name);
        holder.count.setText(context.getString(R.string.profile_locations, subscription.serverCount));

        StringBuilder details = new StringBuilder();
        if (subscription.url == null || subscription.url.isEmpty()) {
            details.append(context.getString(R.string.kind_local));
        } else {
            details.append(subscription.url);
        }
        if (subscription.lastUpdate > 0) {
            details.append(" \u2022 ").append(DateUtils.getRelativeTimeSpanString(subscription.lastUpdate));
        }
        if (subscription.trafficTotal > 0) {
            details.append(" \u2022 ").append(context.getString(R.string.profile_traffic,
                    human(subscription.trafficUsed), human(subscription.trafficTotal)));
        }
        holder.url.setText(details.toString());
        holder.error.setText(subscription.lastError == null ? "" : subscription.lastError);
        holder.error.setVisibility(subscription.lastError == null || subscription.lastError.isEmpty()
                ? View.GONE : View.VISIBLE);

        boolean busy = busyId == subscription.id;
        holder.progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        holder.refresh.setVisibility(busy ? View.GONE : View.VISIBLE);

        holder.refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onRefresh(subscription);
            }
        });
        holder.menu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onMenu(v, subscription);
            }
        });
        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onEdit(subscription);
            }
        });
    }

    private String human(long bytes) {
        if (bytes <= 0) {
            return "0";
        }
        double gb = bytes / 1024.0 / 1024.0 / 1024.0;
        if (gb >= 1) {
            return String.format(Locale.US, "%.1f ГБ", gb);
        }
        return String.format(Locale.US, "%.0f МБ", bytes / 1024.0 / 1024.0);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView url;
        final TextView count;
        final TextView error;
        final ImageButton refresh;
        final ImageButton menu;
        final ProgressBar progress;

        Holder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.text_name);
            url = itemView.findViewById(R.id.text_url);
            count = itemView.findViewById(R.id.text_count);
            error = itemView.findViewById(R.id.text_error);
            refresh = itemView.findViewById(R.id.btn_refresh);
            menu = itemView.findViewById(R.id.btn_menu);
            progress = itemView.findViewById(R.id.progress);
        }
    }
}

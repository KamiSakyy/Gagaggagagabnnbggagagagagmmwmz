package com.vortex.vpn.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.vortex.vpn.R;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Installed application picker used for per-app routing. */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    public static class Entry {
        public String packageName;
        public String label;
        public android.graphics.drawable.Drawable icon;
        public boolean selected;
        public boolean system;
    }

    private final Context context;
    private final List<Entry> all = new ArrayList<>();
    private final List<Entry> visible = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private String query = "";

    public AppAdapter(Context context, Set<String> initial) {
        this.context = context;
        if (initial != null) {
            selected.addAll(initial);
        }
    }

    public void submit(List<Entry> entries) {
        all.clear();
        all.addAll(entries);
        for (Entry entry : all) {
            entry.selected = selected.contains(entry.packageName);
        }
        filter(query);
    }

    public Set<String> selection() {
        return selected;
    }

    public void setQuery(String value) {
        filter(value == null ? "" : value.trim().toLowerCase(Locale.ROOT));
    }

    private void filter(String value) {
        query = value;
        visible.clear();
        for (Entry entry : all) {
            if (query.isEmpty()
                    || entry.label.toLowerCase(Locale.ROOT).contains(query)
                    || entry.packageName.toLowerCase(Locale.ROOT).contains(query)) {
                visible.add(entry);
            }
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        final Entry entry = visible.get(position);
        holder.label.setText(entry.label);
        holder.pkg.setText(entry.packageName);
        holder.icon.setImageDrawable(entry.icon);
        holder.check.setOnCheckedChangeListener(null);
        holder.check.setChecked(entry.selected);
        holder.check.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                entry.selected = isChecked;
                if (isChecked) {
                    selected.add(entry.packageName);
                } else {
                    selected.remove(entry.packageName);
                }
            }
        });
        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                holder.check.setChecked(!holder.check.isChecked());
            }
        });
    }

    @Override
    public int getItemCount() {
        return visible.size();
    }

    public int total() {
        return all.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView label;
        final TextView pkg;
        final CheckBox check;

        Holder(View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.icon);
            label = itemView.findViewById(R.id.text_label);
            pkg = itemView.findViewById(R.id.text_package);
            check = itemView.findViewById(R.id.check);
        }
    }
}

package com.vortex.vpn.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.vortex.vpn.R;
import com.vortex.vpn.core.ScreenAudit;
import com.vortex.vpn.core.Bridge;

import java.util.ArrayList;
import java.util.List;

/** Engine log viewer. */
public class LogsActivity extends AppCompatActivity {

    private final List<String> lines = new ArrayList<>();
    private LineAdapter adapter;
    private RecyclerView list;
    private TextView empty;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logs);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.title_logs);
        }
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        empty = findViewById(R.id.text_empty);
        list = findViewById(R.id.list);
        adapter = new LineAdapter();
        LinearLayoutManager manager = new LinearLayoutManager(this);
        manager.setStackFromEnd(true);
        list.setLayoutManager(manager);
        list.setAdapter(adapter);

        Bridge.logVersion.observe(this, new Observer<Integer>() {
            @Override
            public void onChanged(Integer value) {
                reload();
            }
        });
        reload();
        ScreenAudit.handOff(this);
    }

    /** Crash reports are shown first: they explain why the app closed last time. */
    private java.util.List<String> crashLines() {
        java.util.List<String> result = new java.util.ArrayList<>();
        try {
            java.io.File file = com.vortex.vpn.App.crashLogFile();
            if (file.exists()) {
                result.add("=== отчёт о последнем сбое ===");
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(new java.io.FileInputStream(file), "UTF-8"));
                String line;
                while ((line = reader.readLine()) != null) {
                    result.add(line);
                }
                reader.close();
                result.add("=== конец отчёта ===");
            }
        } catch (Throwable ignored) {
            // an unreadable report must never break the log screen
        }
        return result;
    }

    private void reload() {
        lines.clear();
        lines.addAll(crashLines());
        lines.addAll(Bridge.logSnapshot());
        adapter.notifyDataSetChanged();
        empty.setVisibility(lines.isEmpty() ? View.VISIBLE : View.GONE);
        if (!lines.isEmpty()) {
            list.scrollToPosition(lines.size() - 1);
        }
    }

    private void copyAll() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            builder.append(line).append('\n');
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("vortex-log", builder.toString()));
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.logs, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_copy) {
            copyAll();
            return true;
        }
        if (id == R.id.action_clear) {
            Bridge.clearAll();
            reload();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private class LineAdapter extends RecyclerView.Adapter<LineAdapter.Holder> {

        @Override
        public Holder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            View view = getLayoutInflater().inflate(R.layout.item_log, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(Holder holder, int position) {
            holder.text.setText(lines.get(position));
        }

        @Override
        public int getItemCount() {
            return lines.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView text;

            Holder(View itemView) {
                super(itemView);
                text = itemView.findViewById(R.id.text_line);
            }
        }
    }
}

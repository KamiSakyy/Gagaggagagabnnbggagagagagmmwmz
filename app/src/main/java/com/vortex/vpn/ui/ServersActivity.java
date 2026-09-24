package com.vortex.vpn.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.vortex.vpn.Prefs;
import com.vortex.vpn.R;
import com.vortex.vpn.core.VpnServiceVortex;
import com.vortex.vpn.core.VpnState;
import com.vortex.vpn.db.Repo;
import com.vortex.vpn.model.Server;
import com.vortex.vpn.sub.SubImporter;
import com.vortex.vpn.ui.adapter.ServerAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Location picker: search, favourites, live latency and one-tap switching. */
public class ServersActivity extends AppCompatActivity {

    private ServerAdapter adapter;
    private SwipeRefreshLayout refresh;
    private EditText search;
    private TextView empty;
    private List<Server> all = new ArrayList<>();
    private String query = "";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_servers);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.title_servers);
        }
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        refresh = findViewById(R.id.refresh);
        search = findViewById(R.id.search);
        empty = findViewById(R.id.text_empty);

        adapter = new ServerAdapter(this, new ServerAdapter.Listener() {
            @Override
            public void onSelect(Server server) {
                select(server);
            }

            @Override
            public void onFavorite(Server server) {
                Repo.setFavorite(ServersActivity.this, server.id, !server.favorite);
                load();
            }

            @Override
            public void onTest(Server server) {
                test(server);
            }
        });
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT);
                applyFilter();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        refresh.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                refresh.setRefreshing(false);
                load();
            }
        });

        VpnState.pings.observe(this, new Observer<Map<String, Integer>>() {
            @Override
            public void onChanged(Map<String, Integer> value) {
                adapter.setPings(value);
            }
        });
        VpnState.activeTag.observe(this, new Observer<String>() {
            @Override
            public void onChanged(String value) {
                adapter.setActiveTag(value);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
        adapter.setActiveTag(VpnState.activeTag.getValue());
        adapter.setPings(VpnState.pings.getValue());
    }

    private void load() {
        all = Repo.servers(this);
        applyFilter();
    }

    private void applyFilter() {
        List<Server> filtered = new ArrayList<>();
        String selectedFp = Prefs.selectedFp();
        for (Server server : all) {
            if (!query.isEmpty()) {
                String haystack = (server.displayName() + " " + server.server + " " + server.type
                        + " " + server.country).toLowerCase(Locale.ROOT);
                if (!haystack.contains(query)) {
                    continue;
                }
            }
            server.selected = selectedFp != null && !selectedFp.isEmpty()
                    && selectedFp.equals(SubImporter.fingerprint(server));
            filtered.add(server);
        }
        adapter.submit(filtered);
        empty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
        empty.setText(all.isEmpty() ? getString(R.string.servers_empty) : getString(R.string.search_empty));
    }

    private void select(Server server) {
        Prefs.setSelectedFp(SubImporter.fingerprint(server));
        Prefs.setSelectedServerId(server.id);
        Prefs.setRawSubId(0);
        adapter.setActiveTag(server.tag);
        VpnState.activeTag.postValue(server.tag);
        toast(getString(R.string.location_selected, server.displayName()));
        if (VpnServiceVortex.isActive()) {
            VpnServiceVortex.get().selectOutbound(server.tag);
        }
        applyFilter();
        finish();
    }

    /**
     * Latency probe: through the engine (url-test) while the tunnel runs, otherwise a
     * direct TCP handshake so the list is useful before connecting.
     */
    private void test(final Server server) {
        if (VpnServiceVortex.isActive() && server.tag != null && !server.tag.isEmpty()) {
            VpnServiceVortex.get().urlTest(server.tag);
            return;
        }
        toast(getString(R.string.ping_measuring, server.displayName()));
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int latency = com.vortex.vpn.core.PingUtil.tcpPing(server, 3000);
                if (latency > 0) {
                    Repo.updatePing(ServersActivity.this, server.id, latency);
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        adapter.notifyDataSetChanged();
                        load();
                    }
                });
            }
        }, "vortex-ping").start();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.servers, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_refresh) {
            load();
            return true;
        }
        if (id == R.id.action_ping_all) {
            pingAll();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void pingAll() {
        if (VpnServiceVortex.isActive()) {
            VpnServiceVortex.get().urlTest(com.vortex.vpn.core.ConfigTags.AUTO);
            toast(getString(R.string.ping_measuring, getString(R.string.title_servers)));
        } else {
            toast(getString(R.string.ping_needs_tunnel));
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}

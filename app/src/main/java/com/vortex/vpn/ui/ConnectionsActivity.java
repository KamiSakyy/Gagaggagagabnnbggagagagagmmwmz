package com.vortex.vpn.ui;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.vortex.vpn.R;
import com.vortex.vpn.core.ScreenAudit;
import com.vortex.vpn.core.Bridge;
import com.vortex.vpn.core.VpnServiceVortex;
import com.vortex.vpn.ui.adapter.ConnectionAdapter;

import java.util.ArrayList;
import java.util.List;

/** Live view of the connections handled by the engine. */
public class ConnectionsActivity extends AppCompatActivity {

    private ConnectionAdapter adapter;
    private TextView empty;
    private TextView summary;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_connections);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.title_connections);
        }
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        empty = findViewById(R.id.text_empty);
        summary = findViewById(R.id.text_summary);
        adapter = new ConnectionAdapter(this);
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        Bridge.connections.observe(this, new Observer<List<Bridge.ConnectionInfo>>() {
            @Override
            public void onChanged(List<Bridge.ConnectionInfo> value) {
                List<Bridge.ConnectionInfo> items = value == null
                        ? new ArrayList<Bridge.ConnectionInfo>() : value;
                adapter.submit(items);
                empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                summary.setText(getString(R.string.connections_total, items.size()));
            }
        });
        ScreenAudit.handOff(this);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.connections, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_close_all && VpnServiceVortex.isActive()) {
            VpnServiceVortex.get().closeConnections();
            Bridge.connections.postValue(new ArrayList<Bridge.ConnectionInfo>());
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}

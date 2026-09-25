package com.vortex.vpn.ui;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.vortex.vpn.Prefs;
import com.vortex.vpn.R;
import com.vortex.vpn.core.ScreenAudit;
import com.vortex.vpn.core.VpnServiceVortex;
import com.vortex.vpn.ui.adapter.AppAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Per-application VPN routing (include or exclude list). */
public class AppListActivity extends AppCompatActivity {

    private AppAdapter adapter;
    private TextView empty;
    private MaterialSwitch includeSwitch;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_list);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.title_apps);
        }
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        empty = findViewById(R.id.text_empty);
        includeSwitch = findViewById(R.id.sw_include);
        includeSwitch.setChecked(Prefs.perAppInclude());
        includeSwitch.setText(Prefs.perAppInclude() ? R.string.apps_include : R.string.apps_exclude);
        includeSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                Prefs.setBoolean(Prefs.KEY_PER_APP, true);
                Prefs.setBoolean(Prefs.KEY_PER_APP_INCLUDE, isChecked);
                includeSwitch.setText(isChecked ? R.string.apps_include : R.string.apps_exclude);
            }
        });

        adapter = new AppAdapter(this, Prefs.perAppList());
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        EditText search = findViewById(R.id.search);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.setQuery(s == null ? "" : s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        loadApps();
        ScreenAudit.handOff(this);
    }

    private void loadApps() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<AppAdapter.Entry> entries = new ArrayList<>();
                PackageManager manager = getPackageManager();
                Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> resolved = manager.queryIntentActivities(launcher, 0);
                Set<String> seen = new LinkedHashSet<>();
                for (ResolveInfo info : resolved) {
                    if (info.activityInfo == null || info.activityInfo.applicationInfo == null) {
                        continue;
                    }
                    ApplicationInfo application = info.activityInfo.applicationInfo;
                    if (!seen.add(application.packageName)) {
                        continue;
                    }
                    AppAdapter.Entry entry = new AppAdapter.Entry();
                    entry.packageName = application.packageName;
                    entry.label = String.valueOf(application.loadLabel(manager));
                    entry.icon = application.loadIcon(manager);
                    entry.system = (application.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                    entries.add(entry);
                }
                Collections.sort(entries, new Comparator<AppAdapter.Entry>() {
                    @Override
                    public int compare(AppAdapter.Entry left, AppAdapter.Entry right) {
                        return left.label.compareToIgnoreCase(right.label);
                    }
                });
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        adapter.submit(entries);
                        empty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
                    }
                });
            }
        }, "vortex-apps").start();
    }

    private void save() {
        Set<String> selection = adapter.selection();
        Prefs.setBoolean(Prefs.KEY_PER_APP, true);
        Prefs.setStringSet(Prefs.KEY_PER_APP_LIST, selection);
        Toast.makeText(this, getString(R.string.apps_saved, selection.size()), Toast.LENGTH_SHORT).show();
        if (VpnServiceVortex.isActive()) {
            VpnServiceVortex.restart(this);
        }
        finish();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.app_list, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_save) {
            save();
            return true;
        }
        if (id == R.id.action_disable) {
            Prefs.setBoolean(Prefs.KEY_PER_APP, false);
            Prefs.setStringSet(Prefs.KEY_PER_APP_LIST, new LinkedHashSet<String>());
            Toast.makeText(this, R.string.apps_disabled, Toast.LENGTH_SHORT).show();
            if (VpnServiceVortex.isActive()) {
                VpnServiceVortex.restart(this);
            }
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}

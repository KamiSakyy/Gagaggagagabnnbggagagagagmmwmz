package com.vortex.vpn.ui;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.VpnService;
import android.util.Log;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Observer;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.navigation.NavigationBarView;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.vortex.vpn.Prefs;
import com.vortex.vpn.R;
import com.vortex.vpn.cfg.ConfigSettings;
import com.vortex.vpn.core.ConfigTags;
import com.vortex.vpn.cfg.SampleServers;
import com.vortex.vpn.core.EngineError;
import com.vortex.vpn.core.EngineSelfTest;
import com.vortex.vpn.core.ScreenAudit;
import com.vortex.vpn.core.VpnServiceVortex;
import com.vortex.vpn.core.VpnState;
import com.vortex.vpn.core.SubscriptionUpdater;
import com.vortex.vpn.db.Repo;
import com.vortex.vpn.model.Server;
import com.vortex.vpn.ui.view.PowerView;
import com.vortex.vpn.ui.view.SpeedChartView;
import com.vortex.vpn.sub.Geo;
import com.vortex.vpn.sub.SubImporter;
import com.vortex.vpn.sub.SubFetcher;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Dashboard: connection control, live traffic and the active location. */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "VortexMain";
    private static final int REQUEST_VPN = 0x5611;
    /** Set by the CI smoke test: {@code adb shell am start ... --ez vortex_selftest true}. */
    private static final String EXTRA_SELF_TEST = "vortex_selftest";
    /**
     * Set by the CI smoke test to fill the dashboard with the sample locations before taking
     * screenshots: {@code adb shell am start ... --ez vortex_demo_servers true}. It only ever
     * imports the sample links that ship inside the app (see {@link SampleServers}).
     */
    private static final String EXTRA_DEMO = "vortex_demo_servers";
    private static final long SAMPLE_INTERVAL = 900L;

    private PowerView power;
    private TextView textStatus;
    private TextView textHint;
    private TextView textFlag;
    private TextView textServer;
    private TextView textServerMeta;
    private TextView textDownSpeed;
    private TextView textUpSpeed;
    private TextView textDownTotal;
    private TextView textUpTotal;
    private TextView textConnections;
    private TextView textMemory;
    private TextView textError;
    private TextView textErrorHint;
    private TextView textErrorDetails;
    private View errorBox;
    private MaterialButton btnConnect;
    private ChipGroup modeGroup;
    private SpeedChartView chart;

    private long lastSample;
    private boolean pendingStart;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        bindState();
        bindActions();
        requestNotificationPermission();
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_SELF_TEST, false)) {
            runEngineSelfTest();
        }
        // CI fills the screen with sample locations before taking screenshots (see below).
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_DEMO, false)) {
            loadDemoServers();
        }
        // CI opens a single screen for a screenshot, then walks through all of them; both are
        // no-ops in normal use (see ScreenAudit).
        ScreenAudit.openRequested(this);
        ScreenAudit.handOff(this);
    }

    /** Exercises the real engine on this device (see {@link EngineSelfTest}). */
    private void runEngineSelfTest() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String result = EngineSelfTest.run();
                EngineSelfTest.logResult(result, "ci");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        toast(result);
                    }
                });
            }
        }, "engine-self-test").start();
    }

    /** Imports the sample locations so CI can screenshot a filled dashboard. */
    private void loadDemoServers() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (Repo.countServers(MainActivity.this) == 0) {
                        SubscriptionUpdater.Result result =
                                SubscriptionUpdater.addFromInputDetailed(MainActivity.this,
                                        SampleServers.subscriptionText(), "Демо-локации");
                        Log.i(TAG, "demo locations: " + result.imported + " imported");
                    }
                    final List<Server> servers = Repo.servers(MainActivity.this);
                    if (!servers.isEmpty()) {
                        Prefs.setSelectedServerId(servers.get(0).id);
                    }
                    Log.i(TAG, "demo locations: " + servers.size() + " in the list");
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            updateServerCard();
                        }
                    });
                } catch (Throwable error) {
                    Log.e(TAG, "demo locations failed", error);
                }
            }
        }, "demo-servers").start();
    }

    private void requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 0x5612);
            }
        }
    }

    private void bindViews() {
        power = findViewById(R.id.power);
        textStatus = findViewById(R.id.text_status);
        textHint = findViewById(R.id.text_hint);
        textFlag = findViewById(R.id.text_flag);
        textServer = findViewById(R.id.text_server);
        textServerMeta = findViewById(R.id.text_server_meta);
        textDownSpeed = findViewById(R.id.text_down_speed);
        textUpSpeed = findViewById(R.id.text_up_speed);
        textDownTotal = findViewById(R.id.text_down_total);
        textUpTotal = findViewById(R.id.text_up_total);
        textConnections = findViewById(R.id.text_connections);
        textMemory = findViewById(R.id.text_memory);
        textError = findViewById(R.id.text_error);
        textErrorHint = findViewById(R.id.text_error_hint);
        textErrorDetails = findViewById(R.id.text_error_details);
        errorBox = findViewById(R.id.card_error);
        btnConnect = findViewById(R.id.btn_connect);
        modeGroup = findViewById(R.id.chip_group_mode);
        chart = findViewById(R.id.chart);
    }

    private void bindState() {
        VpnState.status.observe(this, new Observer<Integer>() {
            @Override
            public void onChanged(Integer value) {
                applyStatus(value == null ? VpnState.STOPPED : value);
            }
        });
        VpnState.stats.observe(this, new Observer<VpnState.Stats>() {
            @Override
            public void onChanged(VpnState.Stats stats) {
                applyStats(stats);
            }
        });
        VpnState.error.observe(this, new Observer<String>() {
            @Override
            public void onChanged(String message) {
                boolean visible = message != null && !message.isEmpty();
                errorBox.setVisibility(visible ? View.VISIBLE : View.GONE);
                if (!visible) {
                    return;
                }
                // The engine reports in its own language; show what the user can act on and keep
                // the original text as technical details.
                textError.setText(EngineError.titleRes(message));
                textErrorHint.setText(EngineError.hintRes(message));
                textErrorDetails.setText(getString(R.string.error_details) + ": " + message);
                Log.w(TAG, "engine error: " + message);
            }
        });
        VpnState.activeTag.observe(this, new Observer<String>() {
            @Override
            public void onChanged(String tag) {
                updateServerCard();
            }
        });
        VpnState.pings.observe(this, new Observer<Map<String, Integer>>() {
            @Override
            public void onChanged(Map<String, Integer> value) {
                updateServerCard();
            }
        });
    }

    private void bindActions() {
        btnConnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onConnectClicked();
            }
        });
        power.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onConnectClicked();
            }
        });
        power.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (VpnState.isRunning()) {
                    VpnServiceVortex.restart(MainActivity.this);
                    toast(getString(R.string.action_restart));
                }
                return true;
            }
        });
        findViewById(R.id.card_server).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, ServersActivity.class));
            }
        });
        findViewById(R.id.btn_servers).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, ServersActivity.class));
            }
        });
        BottomNavigationView navigation = findViewById(R.id.bottom_nav);
        navigation.setSelectedItemId(R.id.nav_home);
        navigation.setOnItemSelectedListener(new NavigationBarView.OnItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                int id = item.getItemId();
                if (id == R.id.nav_servers) {
                    startActivity(new Intent(MainActivity.this, ServersActivity.class));
                } else if (id == R.id.nav_profiles) {
                    startActivity(new Intent(MainActivity.this, ProfilesActivity.class));
                } else if (id == R.id.nav_settings) {
                    startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                }
                return true;
            }
        });
        modeGroup.setOnCheckedStateChangeListener(new ChipGroup.OnCheckedStateChangeListener() {
            @Override
            public void onCheckedChanged(@NonNull ChipGroup group, @NonNull java.util.List<Integer> checkedIds) {
                if (checkedIds.isEmpty()) {
                    return;
                }
                int mode = ConfigSettings.MODE_SMART;
                int id = checkedIds.get(0);
                if (id == R.id.chip_global) {
                    mode = ConfigSettings.MODE_GLOBAL;
                } else if (id == R.id.chip_bypass) {
                    mode = ConfigSettings.MODE_BYPASS;
                }
                if (Prefs.routeMode() != mode) {
                    Prefs.setInt(Prefs.KEY_MODE, mode);
                    VpnServiceVortex.reload(MainActivity.this);
                    updateModeHint();
                }
            }
        });
    }

    /**
     * The activity is {@code singleTop}: when it is already on screen the system delivers the new
     * intent here instead of calling {@code onCreate} again, so the self-test extra must be checked
     * in both places.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_SELF_TEST, false)) {
            runEngineSelfTest();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        BottomNavigationView navigation = findViewById(R.id.bottom_nav);
        if (navigation != null) {
            navigation.setSelectedItemId(R.id.nav_home);
        }
        updateServerCard();
        updateModeHint();
        applyStatus(VpnState.status.getValue() == null ? VpnState.STOPPED : VpnState.status.getValue());
    }

    private void onConnectClicked() {
        if (VpnState.isRunning()) {
            VpnServiceVortex.stop(this);
            return;
        }
        Integer status = VpnState.status.getValue();
        boolean busy = status != null && (status == VpnState.STARTING || status == VpnState.STOPPING);
        if (busy) {
            return;
        }
        if (Repo.countServers(this) == 0 && Prefs.rawSubId() <= 0) {
            toast(getString(R.string.error_no_servers));
            startActivity(new Intent(this, ProfilesActivity.class));
            return;
        }
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            pendingStart = true;
            startActivityForResult(prepare, REQUEST_VPN);
            return;
        }
        VpnServiceVortex.start(this);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VPN) {
            boolean granted = resultCode == RESULT_OK;
            if (granted && pendingStart) {
                VpnServiceVortex.start(this);
            } else if (!granted) {
                toast(getString(R.string.error_no_permission));
            }
            pendingStart = false;
        }
    }

    private void selectAuto() {
        if (!VpnState.isRunning()) {
            toast(getString(R.string.error_not_running));
            return;
        }
        VpnServiceVortex.get().selectOutbound(ConfigTags.AUTO);
        toast(getString(R.string.mode_auto_selected));
    }

    private void applyStatus(int status) {
        power.setStatus(status);
        power.setFailed(false);
        switch (status) {
            case VpnState.STARTED:
                textStatus.setText(R.string.status_connected);
                textStatus.setTextColor(0xFF00E0A0);
                textHint.setText(R.string.hint_tap_to_disconnect);
                btnConnect.setText(R.string.action_disconnect);
                btnConnect.setEnabled(true);
                styleConnectButton(false);
                break;
            case VpnState.STARTING:
                textStatus.setText(R.string.status_connecting);
                textHint.setText(R.string.hint_starting);
                btnConnect.setText(R.string.action_stop);
                btnConnect.setEnabled(true);
                break;
            case VpnState.STOPPING:
                textStatus.setText(R.string.status_stopping);
                textHint.setText("");
                btnConnect.setText(R.string.action_stop);
                btnConnect.setEnabled(false);
                break;
            default:
                textStatus.setText(R.string.status_disconnected);
                textStatus.setTextColor(0xFFF3F5F9);
                textHint.setText(R.string.hint_tap_to_connect);
                btnConnect.setText(R.string.action_connect);
                btnConnect.setEnabled(true);
                styleConnectButton(true);
                chart.reset();
                break;
        }
    }

    /**
     * The primary button is filled while the tunnel is down (the inviting action) and turns into a
     * quiet outline once it is up, so the screen has exactly one loud element at a time.
     */
    private void styleConnectButton(boolean filled) {
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_enabled},
                new int[]{-android.R.attr.state_enabled},
        };
        if (filled) {
            btnConnect.setBackgroundTintList(new ColorStateList(states, new int[]{
                    0xFF00E0A0, 0xFF1E4C40}));
            btnConnect.setTextColor(0xFF04150F);
            btnConnect.setIconTint(new ColorStateList(states, new int[]{0xFF04150F, 0xFF7A8C86}));
            btnConnect.setStrokeWidth(0);
        } else {
            btnConnect.setBackgroundTintList(new ColorStateList(states, new int[]{
                    Color.TRANSPARENT, Color.TRANSPARENT}));
            btnConnect.setTextColor(0xFFF3F5F9);
            btnConnect.setIconTint(new ColorStateList(states, new int[]{
                    0xFFF3F5F9, 0xFF6C7382}));
            btnConnect.setStrokeWidth((int) (getResources().getDisplayMetrics().density * 1.2f));
            btnConnect.setStrokeColor(new ColorStateList(states, new int[]{0xFF23232E, 0xFF23232E}));
        }
    }

    private void applyStats(VpnState.Stats stats) {
        if (stats == null) {
            return;
        }
        textDownSpeed.setText(getString(R.string.speed_format, VpnServiceVortex.formatBytes(stats.downlink)));
        textUpSpeed.setText(getString(R.string.speed_format, VpnServiceVortex.formatBytes(stats.uplink)));
        textDownTotal.setText(VpnServiceVortex.formatBytes(stats.downlinkTotal));
        textUpTotal.setText(VpnServiceVortex.formatBytes(stats.uplinkTotal));
        textConnections.setText(getString(R.string.connections_format, stats.connectionsOut, stats.connectionsIn));
        textMemory.setText(com.vortex.vpn.core.VpnServiceVortex.formatBytes(stats.memory));
        long now = System.currentTimeMillis();
        if (now - lastSample >= SAMPLE_INTERVAL) {
            lastSample = now;
            chart.push(stats.downlink, stats.uplink);
        }
    }

    private void updateServerCard() {
        String tag = VpnState.activeTag.getValue();
        Server server = null;
        if (Prefs.selectedServerId() > 0) {
            server = Repo.server(this, Prefs.selectedServerId());
        }
        if (server == null) {
            String fp = Prefs.selectedFp();
            if (fp != null && !fp.isEmpty()) {
                server = Repo.serverByFingerprint(this, fp);
            }
        }
        if (server == null) {
            textFlag.setText("\uD83C\uDF10");
            textServer.setText(R.string.no_server_selected);
            textServerMeta.setText(R.string.tap_to_choose);
            return;
        }
        textFlag.setText(Geo.flag(server.country));
        String name = server.displayName();
        textServer.setText(name);
        Integer ping = null;
        Map<String, Integer> pings = VpnState.pings.getValue();
        if (pings != null && server.tag != null) {
            ping = pings.get(server.tag);
        }
        int latency = ping == null || ping <= 0 ? server.ping : ping;
        StringBuilder meta = new StringBuilder();
        meta.append(server.type == null ? "" : server.type.toUpperCase(Locale.US));
        if (server.server != null && !server.server.isEmpty()) {
            meta.append(" \u2022 ").append(server.server).append(':').append(server.port);
        }
        if (latency > 0) {
            meta.append(" \u2022 ").append(latency).append(" мс");
        }
        if (!server.isSupported()) {
            meta.append(" \u2022 ").append(getString(R.string.unsupported_protocol));
        }
        if (tag != null && ConfigTags.AUTO.equals(tag) && server.tag != null && !ConfigTags.AUTO.equals(server.tag)) {
            meta.append(" \u2022 ").append(getString(R.string.via_auto));
        }
        textServerMeta.setText(meta.toString());
    }

    private void updateModeHint() {
        int mode = Prefs.routeMode();
        int id = mode == ConfigSettings.MODE_GLOBAL ? R.id.chip_global
                : mode == ConfigSettings.MODE_BYPASS ? R.id.chip_bypass : R.id.chip_smart;
        modeGroup.check(id);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_auto) {
            selectAuto();
            return true;
        }
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (id == R.id.action_logs) {
            startActivity(new Intent(this, LogsActivity.class));
            return true;
        }
        if (id == R.id.action_connections) {
            startActivity(new Intent(this, ConnectionsActivity.class));
            return true;
        }
        if (id == R.id.action_config) {
            startActivity(new Intent(this, ConfigEditorActivity.class));
            return true;
        }
        if (id == R.id.action_about) {
            startActivity(new Intent(this, AboutActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    static String userAgentLabel() {
        return SubFetcher.DEFAULT_USER_AGENT;
    }

    static String selectedFingerprint(Server server) {
        return SubImporter.fingerprint(server);
    }
}

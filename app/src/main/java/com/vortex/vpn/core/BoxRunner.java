package com.vortex.vpn.core;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import com.vortex.vpn.App;
import com.vortex.vpn.Prefs;
import com.vortex.vpn.R;
import com.vortex.vpn.cfg.ConfigBuilder;
import com.vortex.vpn.cfg.ConfigSettings;
import com.vortex.vpn.db.Repo;
import com.vortex.vpn.model.Outbound;
import com.vortex.vpn.model.Server;
import com.vortex.vpn.model.Subscription;
import com.vortex.vpn.sub.SubImporter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.nekohasekai.libbox.CommandServer;
import io.nekohasekai.libbox.CommandServerHandler;
import io.nekohasekai.libbox.OverrideOptions;
import io.nekohasekai.libbox.SystemProxyStatus;

/**
 * Owns the sing-box command server for one tunnel session: builds the configuration,
 * starts/reloads the engine and exposes the live {@link Bridge} to the UI.
 */
public class BoxRunner implements CommandServerHandler {

    private static final String TAG = "Vortex/Box";
    private static final int MAX_SERVERS_IN_CONFIG = 600;

    private final VpnServiceVortex service;
    private CommandServer server;
    private Bridge bridge;
    private volatile boolean running;

    /** Tags currently present in the running configuration (proxy group order). */
    private static volatile List<String> currentTags = new ArrayList<>();

    public BoxRunner(VpnServiceVortex service) {
        this.service = service;
    }

    public static List<String> currentTags() {
        return currentTags;
    }

    public Bridge bridge() {
        return bridge;
    }

    public boolean isRunning() {
        return running;
    }

    /** Builds and starts the engine. Must be called from a background thread. */
    public void start() throws Exception {
        String config = buildConfig();
        CommandServer local = new CommandServer(this, service);
        local.start();
        server = local;
        OverrideOptions override = buildOverride();
        try {
            local.startOrReloadService(config, override);
        } catch (Exception e) {
            if (isStackFailure(e)) {
                Log.w(TAG, "falling back to the system tun stack", e);
                Prefs.setString(Prefs.KEY_STACK, "system");
                local.startOrReloadService(buildConfig(), override);
            } else {
                throw e;
            }
        }
        bridge = new Bridge();
        bridge.connect();
        running = true;
        Bridge.appendLog("I", "движок запущен (" + currentTags.size() + " локаций в конфиге)");
    }

    /** Applies an already running engine to a freshly built configuration. */
    public void reload() throws Exception {
        if (server == null) {
            return;
        }
        server.startOrReloadService(buildConfig(), buildOverride());
        Bridge.appendLog("I", "конфигурация перезагружена");
    }

    /** Stops the engine and releases the tun descriptor. */
    public void stop() {
        running = false;
        Bridge local = bridge;
        bridge = null;
        if (local != null) {
            try {
                local.disconnect();
            } catch (Throwable ignored) {
            }
        }
        if (server != null) {
            final CommandServer localServer = server;
            server = null;
            try {
                localServer.closeService();
            } catch (Throwable t) {
                Log.w(TAG, "closeService", t);
            }
            try {
                localServer.close();
            } catch (Throwable t) {
                Log.w(TAG, "close", t);
            }
        }
    }

    // ------------------------------------------------------------------ configuration

    /** Full sing-box configuration for the current selection. */
    public String buildConfig() {
        Context context = App.get();
        ConfigSettings settings = Prefs.toConfigSettings();

        long rawProfile = Prefs.rawSubId();
        if (rawProfile > 0) {
            Subscription subscription = Repo.subscription(context, rawProfile);
            if (subscription != null && subscription.rawConfig != null && !subscription.rawConfig.trim().isEmpty()) {
                currentTags = new ArrayList<>();
                return subscription.rawConfig;
            }
        }

        List<Server> servers = Repo.servers(context);
        List<Outbound> outbounds = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        used.add(ConfigBuilder.TAG_PROXY);
        used.add(ConfigBuilder.TAG_AUTO);
        used.add(ConfigBuilder.TAG_DIRECT);

        String activeTag = "";
        String selectedFp = Prefs.getString(Prefs.KEY_SERVER_FP, "");
        int index = 0;
        int skipped = 0;
        for (Server server : servers) {
            if (index >= MAX_SERVERS_IN_CONFIG) {
                break;
            }
            if (!server.isSupported()) {
                skipped++;
                continue;
            }
            String tag = uniqueName(server.displayName(), used);
            used.add(tag);
            server.tag = tag;
            outbounds.add(server);
            if (selectedFp != null && !selectedFp.isEmpty()
                    && selectedFp.equals(SubImporter.fingerprint(server))) {
                activeTag = tag;
            }
            index++;
        }
        if (activeTag.isEmpty() && !outbounds.isEmpty()) {
            activeTag = outbounds.get(0).tag;
        }
        if (skipped > 0) {
            Bridge.appendLog("W", "пропущено неподдерживаемых локаций: " + skipped);
        }
        settings.selectedTag = activeTag;
        Prefs.setString(Prefs.KEY_ACTIVE_TAG, activeTag);

        List<String> tags = new ArrayList<>();
        for (Outbound outbound : outbounds) {
            tags.add(outbound.tag);
        }
        currentTags = tags;
        return ConfigBuilder.build(settings, outbounds);
    }

    private static String uniqueName(String name, Set<String> used) {
        String base = name == null || name.trim().isEmpty() ? "server" : name.trim();
        if (base.length() > 64) {
            base = base.substring(0, 64);
        }
        String tag = base;
        int attempt = 2;
        while (used.contains(tag)) {
            tag = base + " #" + attempt;
            attempt++;
        }
        return tag;
    }

    private OverrideOptions buildOverride() {
        OverrideOptions options = new OverrideOptions();
        options.setAutoRedirect(false);
        if (Prefs.perAppEnabled()) {
            List<String> packages = new ArrayList<>(Prefs.perAppList());
            if (Prefs.perAppInclude()) {
                if (!packages.contains(App.get().getPackageName())) {
                    packages.add(App.get().getPackageName());
                }
                options.setIncludePackage(new StringArray(packages));
            } else {
                packages.remove(App.get().getPackageName());
                options.setExcludePackage(new StringArray(packages));
            }
        }
        return options;
    }

    private static boolean isStackFailure(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("gvisor") || lower.contains("stack");
    }

    // ------------------------------------------------------------------ CommandServerHandler

    @Override
    public void serviceStop() {
        Bridge.appendLog("I", "движок запросил остановку");
        service.requestStop();
    }

    @Override
    public void serviceReload() {
        Bridge.appendLog("I", "движок запросил перезагрузку конфигурации");
        service.requestReload();
    }

    @Nullable
    @Override
    public SystemProxyStatus getSystemProxyStatus() {
        SystemProxyStatus status = new SystemProxyStatus();
        status.setAvailable(false);
        status.setEnabled(false);
        return status;
    }

    @Override
    public void setSystemProxyEnabled(boolean enabled) {
        // Android has no system proxy toggling from a VPN app.
    }

    @Override
    public int connectSSHAgent() {
        return -1;
    }

    @Override
    public void triggerNativeCrash() {
        throw new UnsupportedOperationException("crash report disabled");
    }

    @Override
    public void writeDebugMessage(String message) {
        Log.d(TAG, message);
    }

    @Nullable
    public String statusText(Context context) {
        return context.getString(R.string.status_connected);
    }
}

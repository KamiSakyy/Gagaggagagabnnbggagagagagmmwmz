package com.vortex.vpn.core;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.system.OsConstants;
import android.util.Log;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.vortex.vpn.App;
import com.vortex.vpn.Prefs;
import com.vortex.vpn.R;
import com.vortex.vpn.db.Repo;
import com.vortex.vpn.model.Server;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import io.nekohasekai.libbox.BridgeOptions;
import io.nekohasekai.libbox.BridgeSession;
import io.nekohasekai.libbox.ConnectionOwner;
import io.nekohasekai.libbox.InterfaceUpdateListener;
import io.nekohasekai.libbox.Libbox;
import io.nekohasekai.libbox.LocalDNSTransport;
import io.nekohasekai.libbox.NeighborUpdateListener;
import io.nekohasekai.libbox.NetworkInterfaceIterator;
import io.nekohasekai.libbox.PlatformInterface;
import io.nekohasekai.libbox.PlatformUser;
import io.nekohasekai.libbox.RoutePrefix;
import io.nekohasekai.libbox.RoutePrefixIterator;
import io.nekohasekai.libbox.ShellSession;
import io.nekohasekai.libbox.StringBox;
import io.nekohasekai.libbox.StringIterator;
import io.nekohasekai.libbox.TunOptions;
import io.nekohasekai.libbox.WIFIState;

/**
 * The tunnel: implements Android's {@link VpnService} and the engine's
 * {@link PlatformInterface} (tun creation, socket protection, network monitoring).
 */
public class VpnServiceVortex extends VpnService implements PlatformInterface {

    private static final String TAG = "Vortex/Vpn";

    public static final String ACTION_START = "com.vortex.vpn.action.START";
    public static final String ACTION_STOP = "com.vortex.vpn.action.STOP";
    public static final String ACTION_RESTART = "com.vortex.vpn.action.RESTART";
    public static final String ACTION_RELOAD = "com.vortex.vpn.action.RELOAD";
    public static final String ACTION_TOGGLE = "com.vortex.vpn.action.TOGGLE";

    private static VpnServiceVortex instance;

    private BoxRunner runner;
    private ParcelFileDescriptor tunFd;
    private ConnMonitor monitor;
    private Thread worker;
    private final AtomicBoolean starting = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final Handler main = new Handler(Looper.getMainLooper());
    private long lastNotificationUpdate;

    public static VpnServiceVortex get() {
        return instance;
    }

    public static boolean isActive() {
        return instance != null;
    }

    // ------------------------------------------------------------------ control helpers

    public static void start(Context context) {
        try {
            Intent intent = new Intent(context, VpnServiceVortex.class).setAction(ACTION_START);
            ContextCompat.startForegroundService(context, intent);
        } catch (Throwable t) {
            Log.e(TAG, "start", t);
            VpnState.reportError(t.getMessage());
        }
    }

    public static void stop(Context context) {
        try {
            Intent intent = new Intent(context, VpnServiceVortex.class).setAction(ACTION_STOP);
            if (instance != null) {
                context.startService(intent);
            } else {
                VpnState.setStatus(VpnState.STOPPED);
            }
        } catch (Throwable t) {
            Log.e(TAG, "stop", t);
        }
    }

    public static void restart(Context context) {
        try {
            Intent intent = new Intent(context, VpnServiceVortex.class).setAction(ACTION_RESTART);
            ContextCompat.startForegroundService(context, intent);
        } catch (Throwable t) {
            Log.e(TAG, "restart", t);
        }
    }

    public static void reload(Context context) {
        try {
            Intent intent = new Intent(context, VpnServiceVortex.class).setAction(ACTION_RELOAD);
            if (instance != null) {
                context.startService(intent);
            }
        } catch (Throwable t) {
            Log.e(TAG, "reload", t);
        }
    }

    public static void toggle(Context context) {
        if (VpnState.isRunning()) {
            stop(context);
        } else {
            start(context);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        monitor = new ConnMonitor(this);
        Notifications.createChannels(this);
        observeStats();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : String.valueOf(intent.getAction());
        try {
            switch (action) {
                case ACTION_STOP:
                    stopTunnel(false);
                    break;
                case ACTION_RELOAD:
                    reloadConfig();
                    break;
                case ACTION_RESTART:
                    stopTunnel(true);
                    break;
                case ACTION_TOGGLE:
                    if (VpnState.isRunning()) {
                        stopTunnel(false);
                    } else {
                        startTunnel();
                    }
                    break;
                case ACTION_START:
                default:
                    startTunnel();
                    break;
            }
        } catch (Throwable t) {
            Log.e(TAG, "onStartCommand " + action, t);
            VpnState.reportError(describe(t));
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onRevoke() {
        Log.w(TAG, "vpn revoked by the system");
        Bridge.appendLog("W", "VPN отозван системой");
        stopTunnel(false);
    }

    @Override
    public void onDestroy() {
        instance = null;
        stopTunnel(false);
        monitor.stop();
        forgetObservers();
        super.onDestroy();
    }

    private void startTunnel() {
        if (!starting.compareAndSet(false, true)) {
            return;
        }
        stopping.set(false);
        Integer status = VpnState.status.getValue();
        if (status != null && status == VpnState.STARTED) {
            starting.set(false);
            return;
        }
        VpnState.setStatus(VpnState.STARTING);
        VpnState.reportError("");
        startForegroundCompat(getString(R.string.notif_title_starting), getString(R.string.status_connecting));

        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (Repo.countServers(App.get()) == 0 && Prefs.rawSubId() <= 0) {
                        throw new IllegalStateException(getString(R.string.error_no_servers));
                    }
                    Intent prepare = VpnService.prepare(VpnServiceVortex.this);
                    if (prepare != null) {
                        throw new IllegalStateException(getString(R.string.error_no_permission));
                    }
                    BoxRunner local = new BoxRunner(VpnServiceVortex.this);
                    runner = local;
                    local.start();
                    monitor.start();
                    VpnState.reportError("");
                    VpnState.setStatus(VpnState.STARTED);
                    updateNotification(true, false);
                } catch (Throwable t) {
                    Log.e(TAG, "start failed", t);
                    Bridge.appendLog("E", describe(t));
                    VpnState.reportError(describe(t));
                    stopTunnelInternal();
                } finally {
                    starting.set(false);
                }
            }
        }, "vortex-start");
        worker.start();
    }

    /** Stops the tunnel; {@code restartAfter} brings it back up with a fresh config. */
    private void stopTunnel(boolean restartAfter) {
        if (stopping.compareAndSet(false, true)) {
            VpnState.setStatus(VpnState.STOPPING);
            final boolean restart = restartAfter;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    stopTunnelInternal();
                    if (restart) {
                        stopping.set(false);
                        startTunnelOnMain();
                    }
                }
            }, "vortex-stop").start();
        }
    }

    private void startTunnelOnMain() {
        main.post(new Runnable() {
            @Override
            public void run() {
                startTunnel();
            }
        });
    }

    private void stopTunnelInternal() {
        try {
            BoxRunner local = runner;
            runner = null;
            if (local != null) {
                local.stop();
            }
        } catch (Throwable t) {
            Log.w(TAG, "runner stop", t);
        }
        closeTun();
        try {
            monitor.stop();
        } catch (Throwable ignored) {
        }
        Bridge.appendLog("I", "туннель остановлен");
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_REMOVE);
                    } else {
                        //noinspection deprecation
                        stopForeground(true);
                    }
                } catch (Throwable ignored) {
                }
            }
        });
        VpnState.setStatus(VpnState.STOPPED);
        VpnState.stats.postValue(new VpnState.Stats());
        try {
            stopSelf();
        } catch (Throwable ignored) {
        }
    }

    private void reloadConfig() {
        if (runner == null) {
            startTunnel();
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runner.reload();
                    VpnState.reportError("");
                } catch (Throwable t) {
                    Log.e(TAG, "reload", t);
                    VpnState.reportError(describe(t));
                }
            }
        }, "vortex-reload").start();
    }

    /** Called from the engine thread when sing-box asks the platform to stop. */
    public void requestStop() {
        main.post(new Runnable() {
            @Override
            public void run() {
                stopTunnel(false);
            }
        });
    }

    /** Called from the engine thread when sing-box asks for a configuration reload. */
    public void requestReload() {
        reloadConfig();
    }

    public void selectOutbound(final String tag) {
        if (runner == null || runner.bridge() == null) {
            return;
        }
        final io.nekohasekai.libbox.CommandClient client = runner.bridge().client();
        if (client == null) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    client.selectOutbound(ConfigTags.PROXY, tag);
                    Bridge.appendLog("I", "активная локация: " + tag);
                    VpnState.activeTag.postValue(tag);
                } catch (Throwable t) {
                    Log.w(TAG, "selectOutbound", t);
                    Bridge.appendLog("W", "не удалось переключить локацию: " + t.getMessage());
                }
            }
        }, "vortex-select").start();
    }

    public void urlTest(final String tag) {
        if (runner == null || runner.bridge() == null) {
            return;
        }
        final io.nekohasekai.libbox.CommandClient client = runner.bridge().client();
        if (client == null) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    client.urlTest(tag);
                } catch (Throwable t) {
                    Log.w(TAG, "urlTest", t);
                }
            }
        }, "vortex-urltest").start();
    }

    public void closeConnections() {
        if (runner == null || runner.bridge() == null) {
            return;
        }
        final io.nekohasekai.libbox.CommandClient client = runner.bridge().client();
        if (client == null) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    client.closeConnections();
                    Bridge.appendLog("I", "все соединения закрыты");
                } catch (Throwable t) {
                    Log.w(TAG, "closeConnections", t);
                }
            }
        }, "vortex-close-conns").start();
    }

    private void closeTun() {
        ParcelFileDescriptor descriptor = tunFd;
        tunFd = null;
        if (descriptor != null) {
            try {
                descriptor.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private void startForegroundCompat(String title, String text) {
        android.app.Notification notification = Notifications.build(this, true, title, text);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notifications.ID_VPN, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(Notifications.ID_VPN, notification);
            }
        } catch (Throwable t) {
            Log.w(TAG, "startForeground", t);
        }
    }

    private androidx.lifecycle.Observer<VpnState.Stats> statsObserver;
    private androidx.lifecycle.Observer<String> tagObserver;

    private void observeStats() {
        try {
            statsObserver = new androidx.lifecycle.Observer<VpnState.Stats>() {
                @Override
                public void onChanged(VpnState.Stats stats) {
                    long now = System.currentTimeMillis();
                    if (now - lastNotificationUpdate < 2500) {
                        return;
                    }
                    lastNotificationUpdate = now;
                    if (VpnState.isRunning()) {
                        updateNotification(true, false);
                    }
                }
            };
            tagObserver = new androidx.lifecycle.Observer<String>() {
                @Override
                public void onChanged(String tag) {
                    if (VpnState.isRunning()) {
                        updateNotification(true, true);
                    }
                }
            };
            VpnState.stats.observeForever(statsObserver);
            VpnState.activeTag.observeForever(tagObserver);
        } catch (Throwable t) {
            Log.w(TAG, "observe", t);
        }
    }

    private void forgetObservers() {
        try {
            if (statsObserver != null) {
                VpnState.stats.removeObserver(statsObserver);
            }
            if (tagObserver != null) {
                VpnState.activeTag.removeObserver(tagObserver);
            }
        } catch (Throwable ignored) {
        }
    }

    private void updateNotification(boolean running, boolean force) {
        try {
            String server = activeServerName();
            VpnState.Stats stats = VpnState.stats.getValue();
            String traffic = "";
            if (stats != null) {
                traffic = "\u2191 " + formatBytes(stats.uplink) + "/s  \u2193 " + formatBytes(stats.downlink) + "/s";
            }
            String text = server.isEmpty() ? getString(R.string.status_connected) : server;
            if (!traffic.isEmpty()) {
                text = text + "  \u2022  " + traffic;
            }
            NotificationManagerCompat.from(this).notify(Notifications.ID_VPN,
                    Notifications.build(this, running, getString(R.string.app_name), text));
        } catch (Throwable t) {
            Log.w(TAG, "updateNotification", t);
        }
    }

    private String activeServerName() {
        long id = Prefs.selectedServerId();
        Server server = id > 0 ? Repo.server(this, id) : null;
        if (server != null) {
            return server.displayName();
        }
        String tag = VpnState.activeTag.getValue();
        return tag == null ? "" : tag;
    }

    public static String formatBytes(long value) {
        if (value < 1024) {
            return value + " Б";
        }
        double kb = value / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.1f КБ", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.US, "%.1f МБ", mb);
        }
        return String.format(Locale.US, "%.2f ГБ", mb / 1024.0);
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.isEmpty()) {
            message = t.getClass().getSimpleName();
        }
        return message;
    }

    // ------------------------------------------------------------------ PlatformInterface

    @Override
    public int openTun(TunOptions options) throws Exception {
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            throw new IllegalStateException("android: missing vpn permission");
        }
        Builder builder = new Builder();
        builder.setSession(getString(R.string.app_name));
        int mtu = options.getMTU();
        builder.setMtu(mtu > 0 ? mtu : 9000);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false);
        }
        if (Prefs.getBoolean(Prefs.KEY_ALLOW_BYPASS, false)) {
            builder.allowBypass();
        }

        RoutePrefixIterator inet4Address = options.getInet4Address();
        if (inet4Address != null) {
            while (inet4Address.hasNext()) {
                RoutePrefix prefix = inet4Address.next();
                if (prefix == null) {
                    continue;
                }
                builder.addAddress(prefix.address(), prefix.prefix());
            }
        }
        RoutePrefixIterator inet6Address = options.getInet6Address();
        if (inet6Address != null) {
            while (inet6Address.hasNext()) {
                RoutePrefix prefix = inet6Address.next();
                if (prefix == null) {
                    continue;
                }
                builder.addAddress(prefix.address(), prefix.prefix());
            }
        }

        if (options.getAutoRoute()) {
            StringBox dnsMode = options.getDNSMode();
            if (dnsMode != null && !Libbox.DNSModeDisabled.equals(dnsMode.getValue())) {
                StringIterator dns = options.getDNSServerAddress();
                if (dns != null) {
                    while (dns.hasNext()) {
                        String address = dns.next();
                        if (address != null && !address.isEmpty()) {
                            builder.addDnsServer(address);
                        }
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                addRoutes(builder, options.getInet4RouteAddress(), true);
                addRoutes(builder, options.getInet6RouteAddress(), true);
                addRoutes(builder, options.getInet4RouteExcludeAddress(), false);
                addRoutes(builder, options.getInet6RouteExcludeAddress(), false);
            } else {
                addRouteRanges(builder, options.getInet4RouteRange());
                addRouteRanges(builder, options.getInet6RouteRange());
            }

            StringIterator includePackages = options.getIncludePackage();
            if (includePackages != null) {
                while (includePackages.hasNext()) {
                    String packageName = includePackages.next();
                    try {
                        builder.addAllowedApplication(packageName);
                    } catch (PackageManager.NameNotFoundException e) {
                        Log.w(TAG, "unknown package " + packageName);
                    }
                }
            }
            StringIterator excludePackages = options.getExcludePackage();
            if (excludePackages != null) {
                while (excludePackages.hasNext()) {
                    String packageName = excludePackages.next();
                    try {
                        builder.addDisallowedApplication(packageName);
                    } catch (PackageManager.NameNotFoundException e) {
                        Log.w(TAG, "unknown package " + packageName);
                    }
                }
            }
        }

        ParcelFileDescriptor descriptor = builder.establish();
        if (descriptor == null) {
            throw new IllegalStateException("android: the application is not prepared or is revoked");
        }
        tunFd = descriptor;
        Bridge.appendLog("I", "TUN создан (fd=" + descriptor.getFd() + ", mtu=" + (mtu > 0 ? mtu : 9000) + ")");
        return descriptor.getFd();
    }

    private void addRoutes(Builder builder, RoutePrefixIterator iterator, boolean include) {
        if (iterator == null) {
            return;
        }
        while (iterator.hasNext()) {
            RoutePrefix prefix = iterator.next();
            if (prefix == null) {
                continue;
            }
            try {
                android.net.IpPrefix ipPrefix = new android.net.IpPrefix(
                        java.net.InetAddress.getByName(prefix.address()), prefix.prefix());
                if (include) {
                    builder.addRoute(ipPrefix);
                } else {
                    builder.excludeRoute(ipPrefix);
                }
            } catch (Throwable t) {
                Log.w(TAG, "route " + prefix.address(), t);
            }
        }
    }

    private void addRouteRanges(Builder builder, RoutePrefixIterator iterator) {
        if (iterator == null) {
            return;
        }
        while (iterator.hasNext()) {
            RoutePrefix prefix = iterator.next();
            if (prefix == null) {
                continue;
            }
            try {
                builder.addRoute(prefix.address(), prefix.prefix());
            } catch (Throwable t) {
                Log.w(TAG, "route " + prefix.address(), t);
            }
        }
    }

    @Override
    public void autoDetectInterfaceControl(int fd) {
        try {
            protect(fd);
        } catch (Throwable t) {
            Log.w(TAG, "protect fd " + fd, t);
        }
    }

    @Override
    public boolean usePlatformAutoDetectInterfaceControl() {
        return true;
    }

    @Override
    public boolean useProcFS() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q;
    }

    @Override
    public boolean usePlatformShell() {
        return false;
    }

    @Override
    public boolean usePlatformBridge() {
        return false;
    }

    @Override
    public void checkPlatformShell() {
        // platform shell is not used
    }

    @Override
    public void clearDNSCache() {
        // handled by the engine
    }

    @Override
    public BridgeSession createBridge(BridgeOptions options) {
        throw new UnsupportedOperationException("bridge service is not available on Android");
    }

    @Override
    public ConnectionOwner findConnectionOwner(int ipProtocol, String sourceAddress, int sourcePort,
                                               String destinationAddress, int destinationPort) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw new UnsupportedOperationException("connection owner lookup requires Android 10");
        }
        ConnectivityManager connectivity =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivity == null) {
            throw new IllegalStateException("no connectivity service");
        }
        int uid = connectivity.getConnectionOwnerUid(ipProtocol,
                InetSocketAddress.createUnresolved(sourceAddress, sourcePort),
                InetSocketAddress.createUnresolved(destinationAddress, destinationPort));
        if (uid == Process.INVALID_UID) {
            throw new IllegalStateException("connection owner not found");
        }
        ConnectionOwner owner = new ConnectionOwner();
        owner.setUserId(uid);
        String[] packages = getPackageManager().getPackagesForUid(uid);
        List<String> names = new ArrayList<>();
        if (packages != null) {
            for (String name : packages) {
                names.add(name);
            }
        }
        owner.setUserName(names.isEmpty() ? "" : names.get(0));
        owner.setAndroidPackageNames(new StringArray(names));
        return owner;
    }

    @Override
    public void startDefaultInterfaceMonitor(InterfaceUpdateListener listener) {
        monitor.setListener(listener);
    }

    @Override
    public void closeDefaultInterfaceMonitor(InterfaceUpdateListener listener) {
        monitor.setListener(null);
    }

    @Override
    public void startNeighborMonitor(NeighborUpdateListener listener) {
        // neighbor table monitoring is not required on Android
    }

    @Override
    public void closeNeighborMonitor(NeighborUpdateListener listener) {
        // no-op
    }

    @Override
    public NetworkInterfaceIterator getInterfaces() {
        List<io.nekohasekai.libbox.NetworkInterface> result = new ArrayList<>();
        try {
            ConnectivityManager connectivity =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivity == null) {
                return new InterfaceArray(result);
            }
            for (Network network : connectivity.getAllNetworks()) {
                LinkProperties properties = connectivity.getLinkProperties(network);
                NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
                if (properties == null || capabilities == null || properties.getInterfaceName() == null) {
                    continue;
                }
                String name = properties.getInterfaceName();
                java.net.NetworkInterface raw;
                try {
                    raw = java.net.NetworkInterface.getByName(name);
                } catch (Throwable t) {
                    continue;
                }
                if (raw == null) {
                    continue;
                }
                io.nekohasekai.libbox.NetworkInterface boxInterface =
                        new io.nekohasekai.libbox.NetworkInterface();
                boxInterface.setName(name);
                boxInterface.setIndex(raw.getIndex());
                try {
                    boxInterface.setMTU(raw.getMTU());
                } catch (Throwable ignored) {
                }
                List<String> addresses = new ArrayList<>();
                for (java.net.InterfaceAddress address : raw.getInterfaceAddresses()) {
                    if (address.getAddress() == null) {
                        continue;
                    }
                    String host = address.getAddress().getHostAddress();
                    int percent = host.indexOf('%');
                    if (percent > 0) {
                        host = host.substring(0, percent);
                    }
                    addresses.add(host + "/" + address.getNetworkPrefixLength());
                }
                boxInterface.setAddresses(new StringArray(addresses));
                int flags = 0;
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    flags |= OsConstants.IFF_UP | OsConstants.IFF_RUNNING;
                }
                if (raw.isLoopback()) {
                    flags |= OsConstants.IFF_LOOPBACK;
                }
                if (raw.isPointToPoint()) {
                    flags |= OsConstants.IFF_POINTOPOINT;
                }
                if (raw.supportsMulticast()) {
                    flags |= OsConstants.IFF_MULTICAST;
                }
                boxInterface.setFlags(flags);
                result.add(boxInterface);
            }
        } catch (Throwable t) {
            Log.w(TAG, "getInterfaces", t);
        }
        return new InterfaceArray(result);
    }

    @Override
    public boolean includeAllNetworks() {
        return false;
    }

    @Override
    public LocalDNSTransport localDNSTransport() {
        return null;
    }

    @Override
    public String lookupSFTPServer() {
        throw new UnsupportedOperationException("sftp is not supported");
    }

    @Override
    public PlatformUser lookupUser(String username) {
        throw new UnsupportedOperationException("user lookup is not supported");
    }

    @Override
    public ShellSession openShellSession(PlatformUser user, String command, StringIterator environ,
                                         String term, int rows, int cols) {
        throw new UnsupportedOperationException("shell sessions are not supported");
    }

    @Override
    public String readSystemSSHHostKey() {
        return null;
    }

    @Override
    public WIFIState readWIFIState() {
        try {
            WifiManager manager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (manager == null) {
                return null;
            }
            //noinspection deprecation
            WifiInfo info = manager.getConnectionInfo();
            if (info == null || info.getSSID() == null) {
                return null;
            }
            String ssid = info.getSSID();
            if ("<unknown ssid>".equals(ssid)) {
                return new WIFIState("", "");
            }
            if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() > 1) {
                ssid = ssid.substring(1, ssid.length() - 1);
            }
            return new WIFIState(ssid, info.getBSSID() == null ? "" : info.getBSSID());
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void registerMyInterface(String name) {
        // no-op
    }

    @Override
    public void sendNotification(io.nekohasekai.libbox.Notification notification) {
        if (notification == null) {
            return;
        }
        String title = notification.getTitle();
        String subtitle = notification.getSubtitle();
        Notifications.engine(this, title == null ? getString(R.string.app_name) : title,
                subtitle == null ? "" : subtitle);
    }

    @Override
    public void cancelNotification(String identifier, int typeID) {
        Notifications.cancelEngine(this, (identifier == null ? "" : identifier).hashCode() + typeID);
    }

    @Override
    public String tailscaleHostname() {
        String model = Build.MODEL == null ? "android" : Build.MODEL;
        return model.toLowerCase(Locale.US).replaceAll("[^a-z0-9-]", "-");
    }

    @Override
    public boolean underNetworkExtension() {
        return false;
    }
}

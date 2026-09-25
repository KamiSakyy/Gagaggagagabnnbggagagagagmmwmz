package com.vortex.vpn.core;

import android.util.Log;

import androidx.lifecycle.MutableLiveData;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.nekohasekai.libbox.CommandClient;
import io.nekohasekai.libbox.CommandClientHandler;
import io.nekohasekai.libbox.CommandClientOptions;
import io.nekohasekai.libbox.Connection;
import io.nekohasekai.libbox.ConnectionEvent;
import io.nekohasekai.libbox.ConnectionEventIterator;
import io.nekohasekai.libbox.ConnectionEvents;
import io.nekohasekai.libbox.Libbox;
import io.nekohasekai.libbox.LogEntry;
import io.nekohasekai.libbox.LogIterator;
import io.nekohasekai.libbox.OutboundGroup;
import io.nekohasekai.libbox.OutboundGroupItem;
import io.nekohasekai.libbox.OutboundGroupItemIterator;
import io.nekohasekai.libbox.OutboundGroupIterator;
import io.nekohasekai.libbox.StatusMessage;
import io.nekohasekai.libbox.StringIterator;

/**
 * Live link to the running engine: logs, traffic counters, outbound latency and
 * the connection table. Implemented as a {@link CommandClientHandler}, i.e. it talks
 * to the in-process {@code CommandServer} over the local command socket.
 */
public class Bridge implements CommandClientHandler {

    public static final int MAX_LOGS = 1200;

    private static final LinkedList<String> LOGS = new LinkedList<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss", Locale.US);

    public static final MutableLiveData<Integer> logVersion = new MutableLiveData<>(0);
    public static final MutableLiveData<List<ConnectionInfo>> connections =
            new MutableLiveData<>(new ArrayList<ConnectionInfo>());
    public static final MutableLiveData<Boolean> connected = new MutableLiveData<>(false);

    private CommandClient client;
    private final Map<String, ConnectionInfo> connectionMap = new LinkedHashMap<>();
    private volatile boolean opened;

    public static class ConnectionInfo {
        public String id;
        public String domain = "";
        public String destination = "";
        public String network = "";
        public String protocol = "";
        public String outbound = "";
        public String source = "";
        public String rule = "";
        public long upload;
        public long download;
        public long start;
        public boolean closed;
    }

    public void connect() {
        try {
            CommandClientOptions options = new CommandClientOptions();
            options.addCommand(Libbox.CommandLog);
            options.addCommand(Libbox.CommandStatus);
            options.addCommand(Libbox.CommandOutbounds);
            options.addCommand(Libbox.CommandGroup);
            options.addCommand(Libbox.CommandConnections);
            // Interval is expressed in nanoseconds by the engine (1 second here).
            options.setStatusInterval(1_000_000_000L);
            client = Libbox.newCommandClient(this, options);
            client.connect();
            opened = true;
        } catch (Throwable t) {
            Log.e("Vortex", "command client failed", t);
            appendLog("E", "не удалось подключить монитор движка: " + t.getMessage());
            opened = false;
        }
    }

    public void disconnect() {
        opened = false;
        connectionMap.clear();
        connections.postValue(new ArrayList<ConnectionInfo>());
        connected.postValue(false);
        try {
            if (client != null) {
                client.disconnect();
            }
        } catch (Throwable ignored) {
        }
        client = null;
    }

    public boolean isOpened() {
        return opened;
    }

    public CommandClient client() {
        return client;
    }

    // ------------------------------------------------------------------ logging

    public static void appendLog(String level, String message) {
        synchronized (LOGS) {
            LOGS.add(TIME.format(new Date()) + "  " + level + "  " + message);
            while (LOGS.size() > MAX_LOGS) {
                LOGS.removeFirst();
            }
        }
        Integer version = logVersion.getValue();
        logVersion.postValue(version == null ? 0 : version + 1);
    }

    public static List<String> logSnapshot() {
        synchronized (LOGS) {
            return new ArrayList<>(LOGS);
        }
    }

    /** Clears the buffered engine log (UI entry point). */
    public static void clearAll() {
        synchronized (LOGS) {
            LOGS.clear();
        }
        logVersion.postValue(0);
    }

    private static String levelName(int level) {
        switch (level) {
            case 0:
                return "T";
            case 1:
                return "D";
            case 2:
                return "I";
            case 3:
                return "W";
            case 4:
                return "E";
            case 5:
                return "F";
            default:
                return "?";
        }
    }

    // ------------------------------------------------------------------ callbacks

    @Override
    public void connected() {
        opened = true;
        connected.postValue(true);
        appendLog("I", "монитор движка подключён");
    }

    @Override
    public void disconnected(String message) {
        opened = false;
        connected.postValue(false);
        appendLog("W", "монитор движка отключён: " + message);
    }

    @Override
    public void clearLogs() {
        clearAll();
    }

    @Override
    public void writeLogs(LogIterator messageList) {
        boolean changed = false;
        synchronized (LOGS) {
            while (messageList.hasNext()) {
                LogEntry entry = messageList.next();
                if (entry == null) {
                    continue;
                }
                String message = entry.getMessage();
                if (message == null) {
                    continue;
                }
                LOGS.add(TIME.format(new Date()) + "  " + levelName(entry.getLevel()) + "  " + message);
                changed = true;
            }
            while (LOGS.size() > MAX_LOGS) {
                LOGS.removeFirst();
            }
        }
        if (changed) {
            Integer version = logVersion.getValue();
            logVersion.postValue(version == null ? 0 : version + 1);
        }
    }

    @Override
    public void writeStatus(StatusMessage message) {
        if (message == null) {
            return;
        }
        VpnState.Stats stats = new VpnState.Stats();
        stats.uplink = message.getUplink();
        stats.downlink = message.getDownlink();
        stats.uplinkTotal = message.getUplinkTotal();
        stats.downlinkTotal = message.getDownlinkTotal();
        stats.connectionsIn = message.getConnectionsIn();
        stats.connectionsOut = message.getConnectionsOut();
        stats.memory = message.getMemory();
        stats.trafficAvailable = message.getTrafficAvailable();
        stats.goroutines = message.getGoroutines();
        stats.timestamp = System.currentTimeMillis();
        VpnState.stats.postValue(stats);
    }

    @Override
    public void writeGroups(OutboundGroupIterator message) {
        if (message == null) {
            return;
        }
        while (message.hasNext()) {
            OutboundGroup group = message.next();
            if (group == null) {
                continue;
            }
            String tag = group.getTag();
            if (ConfigTags.PROXY.equals(tag)) {
                String selected = group.getSelected();
                if (selected != null) {
                    VpnState.activeTag.postValue(selected);
                }
            }
        }
    }

    @Override
    public void writeOutbounds(OutboundGroupItemIterator message) {
        if (message == null) {
            return;
        }
        Map<String, Integer> pings = VpnState.pings.getValue();
        Map<String, Integer> updated = pings == null
                ? new LinkedHashMap<String, Integer>() : new LinkedHashMap<>(pings);
        boolean changed = false;
        while (message.hasNext()) {
            OutboundGroupItem item = message.next();
            if (item == null) {
                continue;
            }
            String tag = item.getTag();
            int delay = item.getURLTestDelay();
            if (tag == null) {
                continue;
            }
            Integer previous = updated.get(tag);
            if (previous == null || previous != delay) {
                updated.put(tag, delay);
                changed = true;
            }
        }
        if (changed) {
            VpnState.pings.postValue(updated);
        }
    }

    @Override
    public void writeConnectionEvents(ConnectionEvents events) {
        if (events == null) {
            return;
        }
        boolean changed = false;
        if (events.getReset()) {
            connectionMap.clear();
            changed = true;
        }
        ConnectionEventIterator iterator = events.iterator();
        while (iterator.hasNext()) {
            ConnectionEvent event = iterator.next();
            if (event == null) {
                continue;
            }
            int type = event.getType();
            String id = event.getID();
            Connection connection = event.getConnection();
            if (id == null || connection == null) {
                continue;
            }
            if (type == (int) Libbox.ConnectionEventClosed) {
                connectionMap.remove(id);
                changed = true;
                continue;
            }
            ConnectionInfo info = connectionMap.get(id);
            if (info == null) {
                info = new ConnectionInfo();
                info.id = id;
                info.start = System.currentTimeMillis();
                connectionMap.put(id, info);
            }
            String domain = connection.getDomain();
            info.domain = domain == null ? "" : domain;
            String destination = connection.getDestination();
            info.destination = destination == null ? "" : destination;
            info.network = connection.getNetwork() == null ? "" : connection.getNetwork();
            info.protocol = connection.getProtocol() == null ? "" : connection.getProtocol();
            info.outbound = connection.getOutbound() == null ? "" : connection.getOutbound();
            info.source = connection.getSource() == null ? "" : connection.getSource();
            info.rule = connection.getRule() == null ? "" : connection.getRule();
            info.upload += event.getUplinkDelta();
            info.download += event.getDownlinkDelta();
            changed = true;
        }
        if (changed) {
            connections.postValue(new ArrayList<>(connectionMap.values()));
        }
    }

    @Override
    public void initializeClashMode(StringIterator modeList, String currentMode) {
        // Clash modes are not exposed in the UI.
    }

    @Override
    public void updateClashMode(String newMode) {
        // Clash modes are not exposed in the UI.
    }

    @Override
    public void setDefaultLogLevel(int level) {
        // no-op
    }
}

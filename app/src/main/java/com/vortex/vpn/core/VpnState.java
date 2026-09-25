package com.vortex.vpn.core;

import androidx.lifecycle.MutableLiveData;

import java.util.HashMap;
import java.util.Map;

/** Shared, observable state of the tunnel (status, traffic counters, latency). */
public final class VpnState {

    public static final int STOPPED = 0;
    public static final int STARTING = 1;
    public static final int STARTED = 2;
    public static final int STOPPING = 3;

    public static final MutableLiveData<Integer> status = new MutableLiveData<>(STOPPED);
    public static final MutableLiveData<Stats> stats = new MutableLiveData<>(new Stats());
    public static final MutableLiveData<String> error = new MutableLiveData<>("");
    public static final MutableLiveData<String> activeTag = new MutableLiveData<>("");
    public static final MutableLiveData<Map<String, Integer>> pings = new MutableLiveData<>(new HashMap<String, Integer>());

    private VpnState() {
    }

    public static final class Stats {
        public long uplink;
        public long downlink;
        public long uplinkTotal;
        public long downlinkTotal;
        public int connectionsIn;
        public int connectionsOut;
        public long memory;
        /** The engine only exposes traffic counters when its traffic manager is running. */
        public boolean trafficAvailable;
        public int goroutines;
        public long timestamp;

        public long total() {
            return uplinkTotal + downlinkTotal;
        }
    }

    public static void setStatus(int value) {
        status.postValue(value);
    }

    public static boolean isRunning() {
        Integer value = status.getValue();
        return value != null && (value == STARTED || value == STARTING);
    }

    public static void reportError(String message) {
        error.postValue(message == null ? "" : message);
    }
}

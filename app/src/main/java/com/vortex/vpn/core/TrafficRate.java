package com.vortex.vpn.core;

/**
 * Turns the engine's cumulative byte counters into a current speed.
 *
 * <p>The engine reports traffic as two monotonic totals plus a rate that it computes between two
 * of its own status messages. Recomputing the rate here has two advantages: it is independent of
 * the engine's message pacing (a missed or delayed message cannot leave the dashboard showing
 * "0 Б/с" while data is actually flowing) and it never reports a spike after the app was paused
 * in the background.</p>
 *
 * <p>Pure Java on purpose: the arithmetic is covered by unit tests on the JVM.</p>
 */
public final class TrafficRate {

    /** Below this interval the sample is ignored (the rate would be noise). */
    private static final long MIN_INTERVAL_MS = 200L;

    private boolean hasBaseline;
    private long lastUplinkTotal;
    private long lastDownlinkTotal;
    private long lastStamp;
    private long uplink;
    private long downlink;

    /** Current upload speed in bytes per second. */
    public long uplink() {
        return uplink;
    }

    /** Current download speed in bytes per second. */
    public long downlink() {
        return downlink;
    }

    /** Forgets the counters: used when the tunnel stops or the engine restarts. */
    public void reset() {
        hasBaseline = false;
        lastUplinkTotal = 0L;
        lastDownlinkTotal = 0L;
        lastStamp = 0L;
        uplink = 0L;
        downlink = 0L;
    }

    /**
     * Feeds one sample of the cumulative counters.
     *
     * @param uplinkTotal   bytes sent since the tunnel started
     * @param downlinkTotal bytes received since the tunnel started
     * @param stamp         monotonic-ish timestamp of the sample in milliseconds
     */
    public void sample(long uplinkTotal, long downlinkTotal, long stamp) {
        if (!hasBaseline || uplinkTotal < lastUplinkTotal || downlinkTotal < lastDownlinkTotal) {
            // first sample, or the engine restarted and the counters went back to zero
            hasBaseline = true;
            lastUplinkTotal = uplinkTotal;
            lastDownlinkTotal = downlinkTotal;
            lastStamp = stamp;
            uplink = 0L;
            downlink = 0L;
            return;
        }
        long elapsed = stamp - lastStamp;
        if (elapsed < MIN_INTERVAL_MS) {
            return;
        }
        long sent = uplinkTotal - lastUplinkTotal;
        long received = downlinkTotal - lastDownlinkTotal;
        lastUplinkTotal = uplinkTotal;
        lastDownlinkTotal = downlinkTotal;
        lastStamp = stamp;
        uplink = sent * 1000L / elapsed;
        downlink = received * 1000L / elapsed;
    }
}

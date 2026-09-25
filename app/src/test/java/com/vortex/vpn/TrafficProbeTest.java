package com.vortex.vpn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.vortex.vpn.core.TrafficProbe;

import org.junit.Test;

/**
 * Fallback traffic counter: the dashboard must still show real numbers if the engine's own
 * counters are unavailable.
 */
public class TrafficProbeTest {

    /** A trimmed copy of a real /proc/net/dev on an Android device with a tunnel. */
    private static final String PROC = ""
            + "Inter-|   Receive                                                |  Transmit\n"
            + " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed\n"
            + "    lo:  120000     1500    0    0    0     0          0         0   120000     1500    0    0    0     0       0          0\n"
            + " wlan0: 9000000    12000    0    0    0     0          0         0  3000000     9000    0    0    0     0       0          0\n"
            + "  tun0: 5000000     8000    0    0    0     0          0         0  1000000     4000    0    0    0     0       0          0\n";

    @Test
    public void theTunnelInterfaceIsReadAsUplinkAndDownlink() {
        long[] totals = TrafficProbe.parse(PROC, "tun0");
        assertEquals("upload = bytes the device sent into the tunnel", 1_000_000L, totals[0]);
        assertEquals("download = bytes the tunnel delivered", 5_000_000L, totals[1]);
    }

    @Test
    public void otherInterfacesOrBrokenDataMeanNoCounters() {
        assertNull("the loopback device is not the tunnel", TrafficProbe.parse(PROC, "lo"));
        assertNull(TrafficProbe.parse(PROC, "tun9"));
        assertNull(TrafficProbe.parse(null, "tun0"));
        assertNull(TrafficProbe.parse(PROC, null));
        assertNull(TrafficProbe.parse("tun0: 1 2\n", "tun0"));
        assertNull(TrafficProbe.parse("garbage without a colon", "tun0"));
    }

    @Test
    public void extraSpacesAndRealDeviceFormattingAreAccepted() {
        String content = "  tun0:   1234567   42 0 0 0 0 0 0   7654321  21 0 0 0 0 0 0\n";
        long[] totals = TrafficProbe.parse(content, "tun0");
        assertEquals(7_654_321L, totals[0]);
        assertEquals(1_234_567L, totals[1]);
    }
}

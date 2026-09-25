package com.vortex.vpn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import android.widget.TextView;

import com.vortex.vpn.core.VpnState;
import com.vortex.vpn.ui.MainActivity;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

import java.time.Duration;

/**
 * The dashboard must show the traffic the user asked about: a real speed once data flows, and a
 * clearly labelled RAM figure instead of a bare "200 МБ" that looked like consumed mobile data.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DashboardTrafficTest {

    private static final long TWO_HUNDRED_MB = 210L * 1024 * 1024;

    private MainActivity activity;

    @Before
    public void setUp() {
        VpnState.setStatus(VpnState.STOPPED);
        VpnState.reportError("");
        activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        idle();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private void publish(long uplinkTotal, long downlinkTotal, boolean trafficAvailable, long memory) {
        VpnState.Stats stats = new VpnState.Stats();
        stats.uplinkTotal = uplinkTotal;
        stats.downlinkTotal = downlinkTotal;
        stats.trafficAvailable = trafficAvailable;
        stats.memory = memory;
        stats.timestamp = System.currentTimeMillis();
        VpnState.stats.postValue(stats);
        idle();
    }

    private String text(int id) {
        TextView view = activity.findViewById(id);
        assertNotNull("view " + id + " is missing", view);
        return String.valueOf(view.getText());
    }

    @Test
    public void aRunningTunnelShowsTheRealSpeed() {
        VpnState.setStatus(VpnState.STARTED);
        idle();

        publish(0L, 0L, true, TWO_HUNDRED_MB);
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        publish(120_000L, 3_000_000L, true, TWO_HUNDRED_MB);

        String down = text(R.id.text_down_speed);
        String up = text(R.id.text_up_speed);
        assertTrue("the download speed is not formatted as a speed: " + down, down.contains("/с"));
        assertFalse("the download speed is zero while data flows: " + down, down.startsWith("0 "));
        assertFalse("the upload speed is zero while data flows: " + up, up.startsWith("0 "));
    }

    @Test
    public void theMemoryFigureIsLabelledAsRam() {
        VpnState.setStatus(VpnState.STARTED);
        idle();
        publish(0L, 0L, true, TWO_HUNDRED_MB);

        String memory = text(R.id.text_memory);
        assertTrue("the memory line does not mention RAM: " + memory, memory.contains("ОЗУ"));
        // and the card explains that the traffic figures are the user's own data
        assertEquals(activity.getString(R.string.traffic_explainer), text(R.id.text_traffic_note));
        assertTrue("the session totals are not shown", text(R.id.text_down_total).length() > 0);
    }

    @Test
    public void aStoppedTunnelDoesNotPretendToMeasureAnything() {
        VpnState.setStatus(VpnState.STOPPED);
        idle();
        publish(0L, 0L, false, 0L);

        assertEquals("—", text(R.id.text_down_speed));
        assertEquals("—", text(R.id.text_up_speed));
    }
}

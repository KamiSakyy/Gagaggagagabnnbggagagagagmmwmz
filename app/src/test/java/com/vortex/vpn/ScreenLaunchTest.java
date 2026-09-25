package com.vortex.vpn;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;

import com.vortex.vpn.cfg.ConfigBuilder;
import com.vortex.vpn.cfg.ConfigSettings;
import com.vortex.vpn.cfg.JsonReader;
import com.vortex.vpn.cfg.SampleServers;
import com.vortex.vpn.model.Outbound;
import com.vortex.vpn.ui.AboutActivity;
import com.vortex.vpn.ui.AppListActivity;
import com.vortex.vpn.ui.ConfigEditorActivity;
import com.vortex.vpn.ui.ConnectionsActivity;
import com.vortex.vpn.ui.LogsActivity;
import com.vortex.vpn.ui.MainActivity;
import com.vortex.vpn.ui.ProfilesActivity;
import com.vortex.vpn.ui.ServersActivity;
import com.vortex.vpn.ui.SettingsActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Starts every screen on the JVM (Robolectric inflates the real layouts, runs the real
 * {@code onCreate}/{@code onResume} and casts views exactly like a device would). A wrong
 * findViewById type, a broken drawable or a missing string fails this test instead of the
 * user's phone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ScreenLaunchTest {

    private static final Class<?>[] SCREENS = {
            MainActivity.class,
            ServersActivity.class,
            ProfilesActivity.class,
            SettingsActivity.class,
            LogsActivity.class,
            ConnectionsActivity.class,
            AboutActivity.class,
            ConfigEditorActivity.class,
            AppListActivity.class,
    };

    @Test
    public void everyScreenStartsAndStops() {
        for (Class<?> screen : SCREENS) {
            @SuppressWarnings("unchecked")
            ActivityController<? extends Activity> controller =
                    Robolectric.buildActivity((Class<? extends Activity>) screen).setup();
            Activity activity = controller.get();
            assertNotNull(screen.getSimpleName() + " did not start", activity);
            assertFalse(screen.getSimpleName() + " finished right after starting",
                    activity.isFinishing());
            controller.pause().stop().destroy();
            assertTrue(screen.getSimpleName() + " was destroyed", activity.isDestroyed());
        }
    }

    @Test
    public void everyProtocolProducesAValidConfiguration() throws Exception {
        List<Outbound> servers = SampleServers.all();
        assertTrue("sample locations are missing", servers.size() > 10);

        Set<String> protocols = new LinkedHashSet<>();
        for (Outbound outbound : servers) {
            if (outbound.isSupported()) {
                protocols.add(outbound.type);
            }
        }
        assertTrue("too few protocols: " + protocols, protocols.size() >= 14);

        for (int mode = ConfigSettings.MODE_GLOBAL; mode <= ConfigSettings.MODE_BYPASS; mode++) {
            ConfigSettings settings = new ConfigSettings();
            settings.mode = mode;
            String content = ConfigBuilder.build(settings, servers);
            Object parsed = JsonReader.parse(content);
            assertTrue("root is not an object", parsed instanceof Map);
            Map<?, ?> root = (Map<?, ?>) parsed;
            for (String key : new String[]{"log", "dns", "inbounds", "outbounds", "route"}) {
                assertTrue("missing " + key + " in mode " + mode, root.containsKey(key));
            }
            assertFalse("shadowsocksr must be filtered out of the configuration",
                    content.contains("shadowsocksr"));
        }

        String direct = ConfigBuilder.buildDirectOnly(new ConfigSettings());
        assertTrue(JsonReader.parse(direct) instanceof Map);
    }
}

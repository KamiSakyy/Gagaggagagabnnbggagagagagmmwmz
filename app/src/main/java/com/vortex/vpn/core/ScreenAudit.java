package com.vortex.vpn.core;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;

import com.vortex.vpn.ui.AboutActivity;
import com.vortex.vpn.ui.AppListActivity;
import com.vortex.vpn.ui.ConfigEditorActivity;
import com.vortex.vpn.ui.ConnectionsActivity;
import com.vortex.vpn.ui.LogsActivity;
import com.vortex.vpn.ui.MainActivity;
import com.vortex.vpn.ui.ProfilesActivity;
import com.vortex.vpn.ui.ServersActivity;
import com.vortex.vpn.ui.SettingsActivity;

/**
 * Opens every screen once, one after another, so CI can prove on a real device that no screen
 * crashes while it is being created.
 *
 * <p>Triggered from the outside with
 * {@code adb shell am start -n com.vortex.vpn/.ui.MainActivity --ez vortex_screens_audit true}.
 * Each screen hands over to the next one and logs its name; the last screen returns to the
 * dashboard with a final {@code SCREENAUDIT OK opened=N} line. If a screen crashes, the chain stops
 * and the last {@code SCREENAUDIT step=...} line names the culprit.</p>
 */
public final class ScreenAudit {

    /** Extra that turns the audit on (release builds cannot be started screen by screen from adb). */
    public static final String EXTRA = "vortex_screens_audit";
    private static final String EXTRA_STEP = "vortex_screens_audit_step";
    private static final String TAG = "VortexSelfTest";

    /** Every screen except the dashboard, in the order they are checked. */
    private static final Class<? extends Activity>[] SCREENS = new Class[]{
            ServersActivity.class,
            ProfilesActivity.class,
            SettingsActivity.class,
            LogsActivity.class,
            ConnectionsActivity.class,
            AboutActivity.class,
            ConfigEditorActivity.class,
            AppListActivity.class,
    };

    private ScreenAudit() {
    }

    /** Called at the end of every screen's {@code onCreate}; does nothing without the extra. */
    public static void handOff(Activity activity) {
        Intent intent = activity.getIntent();
        if (intent == null || !intent.getBooleanExtra(EXTRA, false)) {
            return;
        }
        try {
            int step = intent.getIntExtra(EXTRA_STEP, -1);
            if (activity instanceof MainActivity && step >= SCREENS.length) {
                Log.i(TAG, "SCREENAUDIT OK opened=" + step);
                return;
            }
            Class<? extends Activity> next = step + 1 < SCREENS.length
                    ? SCREENS[step + 1] : MainActivity.class;
            Log.i(TAG, "SCREENAUDIT step=" + (step + 1) + " " + next.getSimpleName());
            Intent target = new Intent(activity, next);
            target.putExtra(EXTRA, true);
            target.putExtra(EXTRA_STEP, step + 1);
            activity.startActivity(target);
            if (!(activity instanceof MainActivity)) {
                // each checked screen disappears again, so the chain stays one activity deep
                activity.finish();
            }
        } catch (Throwable error) {
            Log.e(TAG, "SCREENAUDIT FAIL " + error);
        }
    }
}

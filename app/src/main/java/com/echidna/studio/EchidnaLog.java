package com.echidna.studio;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Logging for the app. Besides Logcat it keeps a small ring buffer of milestones, which is what the
 * automated emulator run greps for when it checks that the model really loaded and every show
 * really started.
 */
public final class EchidnaLog {
    public static final String TAG = "EchidnaStudio";

    private static final int MILESTONE_LIMIT = 240;
    private static final List<String> MILESTONES = new ArrayList<String>();
    private static volatile boolean dumpToLogcat = true;

    private EchidnaLog() {
    }

    public static void i(String area, String message) {
        if (dumpToLogcat) {
            Log.i(TAG, area + " | " + message);
        }
        milestone(area + " " + message);
    }

    public static void w(String area, String message) {
        if (dumpToLogcat) {
            Log.w(TAG, area + " | " + message);
        }
        milestone("WARN " + area + " " + message);
    }

    public static void e(String area, String message) {
        Log.e(TAG, area + " | " + message);
        milestone("ERROR " + area + " " + message);
    }

    public static void e(String area, String message, Throwable error) {
        Log.e(TAG, area + " | " + message, error);
        milestone("ERROR " + area + " " + message + " (" + error.getClass().getSimpleName() + ")");
    }

    private static synchronized void milestone(String text) {
        MILESTONES.add(text);
        if (MILESTONES.size() > MILESTONE_LIMIT) {
            MILESTONES.remove(0);
        }
    }

    /** All milestones of this process, separated by " ;; ". */
    public static synchronized String milestones() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < MILESTONES.size(); i++) {
            if (i > 0) {
                builder.append(" ;; ");
            }
            builder.append(MILESTONES.get(i));
        }
        return builder.toString();
    }

    public static synchronized void clearMilestones() {
        MILESTONES.clear();
    }
}

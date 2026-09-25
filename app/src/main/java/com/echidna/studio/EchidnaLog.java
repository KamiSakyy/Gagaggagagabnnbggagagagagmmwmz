package com.echidna.studio;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

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

    /** Full milestone list plus a reason, for the share sheet and the diagnostics button. */
    public static synchronized String diagnostics() {
        final StringBuilder builder = new StringBuilder();
        builder.append("Echidna Studio, версия ").append(VERSION).append('\n');
        builder.append("устройство: ").append(android.os.Build.MANUFACTURER).append(" ")
                .append(android.os.Build.MODEL).append(", Android ")
                .append(android.os.Build.VERSION.RELEASE)
                .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        builder.append("архитектуры: ");
        for (String abi : android.os.Build.SUPPORTED_ABIS) {
            builder.append(abi).append(" ");
        }
        builder.append("\n");
        builder.append("модель: ").append(MODEL_NOTE).append("\n\n");
        builder.append("--- события ---\n");
        for (String line : MILESTONES) {
            builder.append(line).append('\n');
        }
        final String crash = LAST_CRASH;
        if (crash != null && !crash.isEmpty()) {
            builder.append("\n--- прошлая ошибка ---\n").append(crash).append('\n');
        }
        return builder.toString();
    }

    /** Set by whoever loads the model, so the diagnostics carry the load report. */
    public static volatile String MODEL_NOTE = "не загружена";

    /** Version string, filled in by the application from the package manager. */
    public static volatile String VERSION = "неизвестно";

    private static volatile String LAST_CRASH = "";

    private static File crashFile(Context context) {
        return new File(context.getFilesDir(), "last-crash.txt");
    }

    /** Writes the throwable, the device info and the recent milestones to a file. */
    public static void saveCrashReport(Context context, Throwable error) {
        if (error == null) {
            return;
        }
        final StringWriter writer = new StringWriter();
        final PrintWriter printer = new PrintWriter(writer);
        printer.println(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        error.printStackTrace(printer);
        printer.flush();
        final String report = writer.toString();
        LAST_CRASH = report;
        try {
            final FileOutputStream out = new FileOutputStream(crashFile(context), false);
            out.write(report.getBytes("UTF-8"));
            out.write("\n--- события ---\n".getBytes("UTF-8"));
            out.write(milestones().replace(" ;; ", "\n").getBytes("UTF-8"));
            out.close();
        } catch (IOException e) {
            Log.e(TAG, "не удалось записать отчёт об ошибке: " + e);
        }
    }

    public static String readCrashReport(Context context) {
        final File file = crashFile(context);
        if (!file.exists()) {
            return "";
        }
        InputStream stream = null;
        try {
            stream = new java.io.FileInputStream(file);
            final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            final byte[] chunk = new byte[4096];
            int read;
            while ((read = stream.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toString("UTF-8");
        } catch (IOException e) {
            return "";
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        }
    }

    /** Clears the stored crash, called after the user has seen it. */
    public static void clearCrashReport(Context context) {
        LAST_CRASH = "";
        final File file = crashFile(context);
        if (file.exists()) {
            file.delete();
        }
    }
}

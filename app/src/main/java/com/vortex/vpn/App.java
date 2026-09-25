package com.vortex.vpn;

import android.app.Application;
import android.os.Build;
import android.util.Log;

import com.vortex.vpn.core.Notifications;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Date;
import java.util.Locale;

import io.nekohasekai.libbox.Libbox;
import io.nekohasekai.libbox.SetupOptions;

/** Application entry point: initialises the sing-box engine and local storage. */
public class App extends Application {

    private static final String TAG = "Vortex";
    private static App instance;

    /** File the crash handler writes to; LogsActivity shows it on the next launch. */
    public static final String CRASH_LOG = "crash.log";

    public static App get() {
        return instance;
    }

    /** Where a crash report is written (see {@link #installCrashHandler()}). */
    public static File crashLogFile() {
        return new File(new File(instance.getFilesDir(), "logs"), CRASH_LOG);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        installCrashHandler();
        Prefs.init(this);
        Notifications.createChannels(this);
        setupEngine();
    }

    /**
     * Keeps evidence of an unexpected crash: logcat (tag {@code VortexCrash}) plus a file that the
     * in-app log shows after the next start. The default handler still runs, so the system
     * behaviour (app closes, "app crashed" dialog) is unchanged.
     */
    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable error) {
                try {
                    StringWriter buffer = new StringWriter();
                    error.printStackTrace(new PrintWriter(buffer));
                    String text = buffer.toString();
                    Log.e(TAG, "FATAL EXCEPTION in " + thread.getName() + "\n" + text);
                    File file = crashLogFile();
                    File parent = file.getParentFile();
                    if (parent != null) {
                        //noinspection ResultOfMethodCallIgnored
                        parent.mkdirs();
                    }
                    FileWriter writer = new FileWriter(file, false);
                    writer.write(new Date().toString());
                    writer.write(" ");
                    writer.write(thread.getName());
                    writer.write("\n");
                    writer.write(text);
                    writer.close();
                } catch (Throwable ignored) {
                    // never mask the original failure
                }
                if (previous != null) {
                    previous.uncaughtException(thread, error);
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid());
                }
            }
        });
    }

    private void setupEngine() {
        try {
            File base = getFilesDir();
            File working = getExternalFilesDir(null);
            if (working == null) {
                working = base;
            }
            File temp = getCacheDir();
            //noinspection ResultOfMethodCallIgnored
            base.mkdirs();
            //noinspection ResultOfMethodCallIgnored
            working.mkdirs();
            //noinspection ResultOfMethodCallIgnored
            temp.mkdirs();

            SetupOptions options = new SetupOptions();
            options.setBasePath(base.getAbsolutePath());
            options.setWorkingPath(working.getAbsolutePath());
            options.setTempPath(temp.getAbsolutePath());
            options.setFixAndroidStack(fixAndroidStack());
            options.setLogMaxLines(3000);
            options.setDebug(BuildConfig.DEBUG);
            options.setCrashReportSource("VortexVPN");
            options.setAppVersion(String.valueOf(BuildConfig.VERSION_CODE));
            options.setAppMarketingVersion(BuildConfig.VERSION_NAME);
            options.setOomKillerEnabled(true);
            Libbox.setup(options);
        } catch (Throwable t) {
            Log.e(TAG, "engine setup failed", t);
        }
    }

    /**
     * Go runtime workaround for the Android stack guard issue
     * (golang/go#68760). Required on Android 7.0/7.1 and 9.0+.
     */
    public static boolean fixAndroidStack() {
        return BuildConfig.DEBUG
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1)
                || Build.VERSION.SDK_INT >= Build.VERSION_CODES.P;
    }

    public static String deviceLocale() {
        Locale locale = Locale.getDefault();
        return locale == null ? "en" : locale.toLanguageTag();
    }
}

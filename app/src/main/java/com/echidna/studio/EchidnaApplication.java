package com.echidna.studio;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import com.live2d.sdk.cubism.framework.CubismFramework;
import com.live2d.sdk.cubism.framework.CubismFrameworkConfig;

/**
 * Application entry point.
 *
 * <p>Two things have to happen before any window is created:</p>
 *
 * <ol>
 *   <li><b>The Live2D framework is started up.</b> {@code CubismFramework.startUp()} prepares the
 *       id manager and the native core binding. Without it {@code getIdManager()} returns null and
 *       the very first model never gets built - the app used to die on that before drawing a single
 *       frame.</li>
 *   <li><b>A crash handler is installed.</b> If anything in the native core, in MediaPipe or in the
 *       renderer throws, the reason is written to the app's own files and to Logcat, and the next
 *       launch shows it on screen instead of silently disappearing. A crash with no explanation is
 *       the worst possible behaviour for a tool somebody wants to stream with.</li>
 * </ol>
 */
public final class EchidnaApplication extends Application {

    private static EchidnaApplication instance;

    public static EchidnaApplication get() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        readVersion();
        startCubism();
        installCrashHandler();
    }

    private void readVersion() {
        try {
            final android.content.pm.PackageInfo info = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            EchidnaLog.VERSION = info.versionName + " (" + info.versionCode + ")";
        } catch (Throwable error) {
            EchidnaLog.w("APP", "версию не прочитать: " + error);
        }
    }

    /**
     * Prepares the Live2D framework. The option carries a logger so that the native core reports its
     * version and any trouble it runs into.
     */
    private void startCubism() {
        try {
            final CubismFramework.Option option = new CubismFramework.Option();
            // The interface of the core logger is print(String); a lambda keeps it short.
            option.logFunction = message -> Log.i(EchidnaLog.TAG, "CORE | " + message);
            option.loggingLevel = CubismFrameworkConfig.LogLevel.WARNING;
            final boolean started = CubismFramework.startUp(option);
            EchidnaLog.i("APP", "Live2D Cubism готов к работе: " + started);
        } catch (Throwable error) {
            // A missing or mismatched native library lands here; the app stays alive and reports it.
            EchidnaLog.e("APP", "не удалось запустить Live2D", error);
            EchidnaLog.saveCrashReport(this, error);
        }
    }

    /**
     * Keeps the last exception of a dying thread for the next launch. Both the file (works even if
     * the process is killed brutally) and the in-memory copy are written.
     */
    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                Log.e(EchidnaLog.TAG, "НЕОБРАБОТАННАЯ ОШИБКА в " + thread.getName(), error);
                EchidnaLog.saveCrashReport(this, error);
            } catch (Throwable ignored) {
                // Never let the handler itself take the process down differently.
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
    }

    /** Marker used by the UI to tell that the previous session ended badly. */
    public static String lastCrashReport(Context context) {
        return EchidnaLog.readCrashReport(context);
    }
}

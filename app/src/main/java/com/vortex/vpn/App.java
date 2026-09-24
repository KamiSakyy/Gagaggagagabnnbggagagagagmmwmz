package com.vortex.vpn;

import android.app.Application;
import android.os.Build;
import android.util.Log;

import com.vortex.vpn.core.Notifications;

import java.io.File;
import java.util.Locale;

import io.nekohasekai.libbox.Libbox;
import io.nekohasekai.libbox.SetupOptions;

/** Application entry point: initialises the sing-box engine and local storage. */
public class App extends Application {

    private static final String TAG = "Vortex";
    private static App instance;

    public static App get() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Prefs.init(this);
        Notifications.createChannels(this);
        setupEngine();
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

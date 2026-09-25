package com.vortex.vpn.core;

import android.util.Log;

import com.vortex.vpn.cfg.ConfigBuilder;
import com.vortex.vpn.cfg.ConfigSettings;
import com.vortex.vpn.cfg.SampleServers;
import com.vortex.vpn.model.Outbound;

import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.nekohasekai.libbox.Libbox;

/**
 * Runs the real engine on the device: every configuration the client can generate is handed
 * to {@code Libbox.checkConfig}, which builds the whole sing-box instance and therefore rejects
 * anything the engine does not accept (unknown protocol, removed option, bad key, ...).
 *
 * <p>Used from the About screen and from CI: the emulator smoke test starts MainActivity with the
 * {@code vortex_selftest} extra and asserts {@code SELFTEST OK} in logcat.</p>
 */
public final class EngineSelfTest {

    public static final String TAG = "VortexSelfTest";
    public static final String REPORT_FILE = "selftest.txt";

    private EngineSelfTest() {
    }

    /** Blocking; call from a background thread. Never throws. */
    public static String run() {
        StringBuilder report = new StringBuilder();
        try {
            report.append("engine=").append(Libbox.version());
            report.append(" go=").append(Libbox.goVersion());

            List<Outbound> all = SampleServers.all();
            Set<String> protocols = new LinkedHashSet<>();
            for (Outbound outbound : all) {
                if (outbound.isSupported()) {
                    protocols.add(outbound.type);
                }
            }

            int failed = 0;
            ConfigSettings smart = new ConfigSettings();
            smart.mode = ConfigSettings.MODE_SMART;
            smart.stack = "mixed";
            failed += check("smart-mixed", smart, all, report);

            ConfigSettings global = new ConfigSettings();
            global.mode = ConfigSettings.MODE_GLOBAL;
            global.stack = "gvisor";
            failed += check("global-gvisor", global, all, report);

            ConfigSettings hardened = new ConfigSettings();
            hardened.fakeIp = true;
            hardened.mux = true;
            hardened.tlsFragment = true;
            hardened.recordFragment = true;
            hardened.remoteDnsDoh = true;
            hardened.directDomains.addAll(Arrays.asList("example.com", "domain:ru", "geoip:ru"));
            hardened.blockDomains.addAll(Arrays.asList("ads.example.org", "regexp:.*\\.tracker\\.net"));
            failed += check("hardened", hardened, all, report);

            ConfigSettings direct = new ConfigSettings();
            try {
                Libbox.checkConfig(ConfigBuilder.buildDirectOnly(direct));
                report.append(" direct-only=ok");
            } catch (Throwable error) {
                failed++;
                report.append(" direct-only=FAIL(").append(describe(error)).append(')');
            }

            report.append(" outbounds=").append(all.size());
            report.append(" protocols=").append(protocols.size()).append('/').append(protocols);
            if (failed != 0) {
                return "SELFTEST FAIL " + failed + " configuration(s) rejected | " + report;
            }
            return "SELFTEST OK configs=4 " + report;
        } catch (Throwable error) {
            return "SELFTEST FAIL " + describe(error) + " | " + report;
        }
    }

    private static int check(String name, ConfigSettings settings, List<Outbound> servers,
                             StringBuilder report) {
        try {
            String content = ConfigBuilder.build(settings.copy(), servers);
            Libbox.checkConfig(content);
            report.append(' ').append(name).append("=ok(").append(content.length()).append("b)");
            return 0;
        } catch (Throwable error) {
            report.append(' ').append(name).append("=FAIL(").append(describe(error)).append(')');
            return 1;
        }
    }

    /**
     * Keeps the verdict on disk ({@code Android/data/<package>/files/selftest.txt}) so it can be
     * checked without touching logcat - CI does exactly that on the emulator.
     */
    public static void writeReport(String result, String source) {
        try {
            com.vortex.vpn.App app = com.vortex.vpn.App.get();
            if (app == null) {
                return;
            }
            File dir = app.getExternalFilesDir(null);
            if (dir == null) {
                dir = app.getFilesDir();
            }
            if (dir == null) {
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            java.io.FileWriter writer = new java.io.FileWriter(new File(dir, REPORT_FILE), false);
            writer.write(java.text.DateFormat.getDateTimeInstance()
                    .format(new java.util.Date()) + "  " + source + "\n");
            writer.write(result);
            writer.write("\n");
            writer.close();
        } catch (Throwable ignored) {
            // diagnostics must never break the app
        }
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isEmpty()) {
            message = error.getClass().getSimpleName();
        }
        return message.replace('\n', ' ').trim();
    }

    /** Runs the self-test and records the verdict in logcat and in the in-app log. */
    public static void runAndLog(String source) {
        logResult(run(), source);
    }

    /** Records an already computed verdict in logcat and in the in-app log. */
    public static void logResult(String result, String source) {
        writeReport(result, source);
        if (result.startsWith("SELFTEST OK")) {
            Log.i(TAG, result);
            Bridge.appendLog("I", "проверка ядра (" + source + "): " + result);
        } else {
            Log.e(TAG, result);
            Bridge.appendLog("E", "проверка ядра (" + source + "): " + result);
        }
    }
}

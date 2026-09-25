import com.vortex.vpn.cfg.ConfigBuilder;
import com.vortex.vpn.cfg.ConfigSettings;
import com.vortex.vpn.cfg.JsonReader;
import com.vortex.vpn.cfg.SampleServers;
import com.vortex.vpn.model.Outbound;
import com.vortex.vpn.model.Server;
import com.vortex.vpn.sub.B64;
import com.vortex.vpn.sub.Geo;
import com.vortex.vpn.sub.SubImporter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java harness: generates every configuration the app can produce so that CI can
 * validate them with the real engine ({@code sing-box check}). Runs on the JVM only and
 * therefore never touches the Android SDK.
 *
 * Usage: java ConfigCheck &lt;output-directory&gt;
 */
public final class ConfigCheck {

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "configs");
        if (!out.exists() && !out.mkdirs()) {
            throw new IllegalStateException("cannot create " + out);
        }

        List<Outbound> servers = SampleServers.all();
        System.out.println("total outbounds in configurations: " + servers.size());
        List<String> variants = new ArrayList<>();

        for (int mode = ConfigSettings.MODE_GLOBAL; mode <= ConfigSettings.MODE_BYPASS; mode++) {
            for (String stack : Arrays.asList("mixed", "gvisor", "system")) {
                ConfigSettings settings = baseSettings();
                settings.mode = mode;
                settings.stack = stack;
                write(out, "mode" + mode + "-" + stack, ConfigBuilder.build(settings, servers), variants);
            }
        }

        ConfigSettings hardened = baseSettings();
        hardened.fakeIp = true;
        hardened.ipv6 = false;
        hardened.mux = true;
        hardened.tlsFragment = true;
        hardened.recordFragment = true;
        hardened.remoteDnsDoh = true;
        hardened.directDomains.addAll(Arrays.asList("example.com", "domain:ru", "geoip:ru"));
        hardened.blockDomains.addAll(Arrays.asList("ads.example.org", "regexp:.*\\\\.tracker\\\\.net"));
        write(out, "hardened", ConfigBuilder.build(hardened, servers), variants);

        ConfigSettings minimal = baseSettings();
        minimal.remoteDnsDoh = false;
        minimal.dnsCache = false;
        minimal.sniff = false;
        minimal.autoRoute = false;
        minimal.ipv6 = false;
        write(out, "minimal", ConfigBuilder.build(minimal, servers), variants);

        write(out, "direct-only", ConfigBuilder.buildDirectOnly(baseSettings()), variants);

        for (String name : variants) {
            File file = new File(out, name + ".json");
            String content = read(file);
            // Round trip through the shared reader: catches malformed JSON early.
            Object parsed = JsonReader.parse(content);
            if (!(parsed instanceof Map)) {
                throw new IllegalStateException(name + ": root is not an object");
            }
            Map<?, ?> root = (Map<?, ?>) parsed;
            for (String key : Arrays.asList("log", "dns", "inbounds", "outbounds", "route")) {
                if (!root.containsKey(key)) {
                    throw new IllegalStateException(name + ": missing section " + key);
                }
            }
            if (!root.containsKey("outbounds") || ((List<?>) root.get("outbounds")).isEmpty()) {
                throw new IllegalStateException(name + ": no outbounds");
            }
            if (!name.startsWith("direct-only")) {
                // wireguard lives in "endpoints" and removed protocols are filtered out
                int expectedOutbounds = 0;
                int expectedEndpoints = 0;
                for (Outbound outbound : servers) {
                    if (!outbound.isSupported()) {
                        continue;
                    }
                    if ("wireguard".equals(outbound.type)) {
                        expectedEndpoints++;
                    } else {
                        expectedOutbounds++;
                    }
                }
                for (Object raw : (List<?>) root.get("outbounds")) {
                    Map<?, ?> outbound = (Map<?, ?>) raw;
                    if ("naive".equals(outbound.get("type")) && outbound.get("tls") instanceof Map) {
                        Map<?, ?> tls = (Map<?, ?>) outbound.get("tls");
                        if (Boolean.TRUE.equals(tls.get("fragment"))) {
                            throw new IllegalStateException(
                                    name + ": tls.fragment is not supported on naive outbound");
                        }
                    }
                }
                List<?> outbounds = (List<?>) root.get("outbounds");
                // selector + urltest groups are added on top of the servers themselves
                if (outbounds.size() < expectedOutbounds) {
                    throw new IllegalStateException(name + ": outbounds were dropped ("
                            + outbounds.size() + " < " + expectedOutbounds + ")");
                }
                if (expectedEndpoints > 0) {
                    if (!root.containsKey("endpoints")) {
                        throw new IllegalStateException(name + ": wireguard endpoints are missing");
                    }
                    List<?> endpoints = (List<?>) root.get("endpoints");
                    if (endpoints.isEmpty()) {
                        throw new IllegalStateException(name + ": no wireguard endpoints");
                    }
                }
                List<?> inbounds = (List<?>) root.get("inbounds");
                Map<?, ?> first = (Map<?, ?>) inbounds.get(0);
                if (!"tun".equals(first.get("type"))) {
                    throw new IllegalStateException(name + ": the first inbound is not a tun");
                }
                Map<?, ?> dns = (Map<?, ?>) root.get("dns");
                if (!(dns.get("servers") instanceof List) || ((List<?>) dns.get("servers")).isEmpty()) {
                    throw new IllegalStateException(name + ": no dns servers");
                }
                Map<?, ?> route = (Map<?, ?>) root.get("route");
                if (!(route.get("rules") instanceof List)) {
                    throw new IllegalStateException(name + ": no route rules");
                }
            }
        }
        List<String> types = new ArrayList<>();
        for (Outbound outbound : servers) {
            if (!types.contains(outbound.type)) {
                types.add(outbound.type);
            }
        }
        System.out.println("protocol types covered: " + types);
        System.out.println("ConfigCheck: " + variants.size() + " configurations written to " + out.getPath());
        System.out.println("ConfigCheck: OK");
        String sample = read(new File(out, "mode1-mixed.json"));
        System.out.println("=== sample configuration (" + sample.length() + " bytes) ===");
        System.out.println(sample);
        System.out.println("=== end of sample ===");
    }

    private static ConfigSettings baseSettings() {
        ConfigSettings settings = new ConfigSettings();
        settings.mode = ConfigSettings.MODE_SMART;
        settings.stack = "mixed";
        settings.selectedTag = "";
        return settings;
    }

    private static void write(File dir, String name, String content, List<String> variants)
            throws Exception {
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalStateException(name + ": empty configuration");
        }
        File file = new File(dir, name + ".json");
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write(content);
        }
        variants.add(name);
    }

    private static String read(File file) throws Exception {
        byte[] buffer = new byte[(int) file.length()];
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            int read = in.read(buffer);
            if (read != buffer.length) {
                throw new IllegalStateException("short read");
            }
        }
        return new String(buffer, StandardCharsets.UTF_8);
    }
}

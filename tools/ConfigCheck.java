import com.vortex.vpn.cfg.ConfigBuilder;
import com.vortex.vpn.cfg.ConfigSettings;
import com.vortex.vpn.cfg.JsonReader;
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

        List<Outbound> servers = new ArrayList<>(subscriptionServers());
        servers.addAll(sampleServers());
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
                List<?> outbounds = (List<?>) root.get("outbounds");
                if (outbounds.size() < servers.size()) {
                    throw new IllegalStateException(name + ": outbounds were dropped ("
                            + outbounds.size() + " < " + servers.size() + ")");
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

    /**
     * Validates the subscription pipeline: share links (raw and base64 v2ray format) and
     * Clash YAML are parsed into outbounds, exactly like the application does when it
     * "unpacks" a subscription into locations.
     */
    private static List<Outbound> subscriptionServers() {
        List<String> links = Arrays.asList(
                "vless://0f1c5f36-4f6b-4f0b-8f1f-4d1a6b4f2e11@example.com:443"
                        + "?security=reality&pbk=8hRk3Q0m5m3XhI1K6m3n0dHIZt6WZ1nYk5K0aVpG1S0"
                        + "&sid=6ba85179e30d4fc2&fp=chrome&flow=xtls-rprx-vision&type=tcp"
                        + "&sni=example.com#%F0%9F%87%A9%F0%9F%87%AA%20Germany%20Reality",
                "vmess://" + B64.encode(vmessJson().getBytes(StandardCharsets.UTF_8)),
                "trojan://trojan-password@trojan.example.com:443?security=tls&sni=trojan.example.com&type=ws&path=%2Ftrojan&host=trojan.example.com#%F0%9F%87%B3%F0%9F%87%B1%20Netherlands%20Trojan",
                "ss://" + B64.encode("2022-blake3-aes-128-gcm:ss-password".getBytes(StandardCharsets.UTF_8))
                        + "@ss.example.com:8388#%F0%9F%87%AB%F0%9F%87%AE%20Finland%20SS",
                "ssr://" + B64.encode(("ssr.example.com:8388:auth_aes128_md5:aes-256-cfb:tls1.2_ticket_auth:"
                        + B64.encode("ssr-password".getBytes(StandardCharsets.UTF_8))
                        + "/?obfsparam=" + B64.encode("cloud.example.com".getBytes(StandardCharsets.UTF_8))
                        + "&protoparam=" + B64.encode("1234:password".getBytes(StandardCharsets.UTF_8))
                        + "&remarks=" + B64.encode("Japan SSR".getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.UTF_8)),
                "hysteria2://hy2-password@hy.example.com:443?sni=hy.example.com&insecure=1"
                        + "&obfs=salamander&obfs-password=obfs-password&upmbps=200&downmbps=500"
                        + "#%F0%9F%87%B8%F0%9F%87%AA%20Sweden%20Hysteria2",
                "tuic://3f1c5f36-4f6b-4f0b-8f1f-4d1a6b4f2e22:tuic-password@tuic.example.com:443"
                        + "?sni=tuic.example.com&congestion_control=bbr&udp_relay_mode=native#Canada%20TUIC",
                "anytls://anytls-password@anytls.example.com:443?sni=anytls.example.com&fp=chrome#France%20AnyTLS",
                "socks5://user:password@socks.example.com:1080#Turkey%20SOCKS",
                "wireguard://" + "aF9d3QW1mZ0F1bXl2V3J0a1p5c2Q0ZTFnMm0zcDRzNXQ2dw="
                        + "@wg.example.com:2408?address=10.10.0.2%2F32&mtu=1408"
                        + "&public_key=bG9uZ3JhbmRvbWtleWZvcnRlc3Rpbmdvbmx5MTIzNDU2Nzg5MA="
                        + "&preshared_key=cHJlc2hhcmVka2V5Zm9ydGVzdGluZ29ubHk="
                        + "&keepalive=25#WireGuard"
        );

        List<Outbound> raw = SubImporter.parse(String.join("\n", links)).servers;
        require(!raw.isEmpty(), "share links produced no outbounds");
        List<Outbound> base64 = SubImporter.parse(B64.encode(
                String.join("\n", links).getBytes(StandardCharsets.UTF_8))).servers;
        require(!base64.isEmpty(), "base64 subscription produced no outbounds");
        System.out.println("share links: " + raw.size() + " outbounds, base64 subscription: "
                + base64.size() + " outbounds");

        List<Outbound> clash = SubImporter.parse(clashYaml()).servers;
        require(!clash.isEmpty(), "clash subscription produced no outbounds");
        System.out.println("clash yaml: " + clash.size() + " outbounds");

        List<Outbound> all = new ArrayList<>(raw);
        all.addAll(clash);
        List<Outbound> unique = SubImporter.dedupe(all);
        System.out.println("after dedupe: " + unique.size() + " locations");
        for (Outbound outbound : unique) {
            require(outbound.country == null || outbound.country.isEmpty()
                            || Geo.flag(outbound.country) != null,
                    "unknown country code for " + outbound.tag);
        }
        return unique;
    }

    private static String vmessJson() {
        return "{"
                + "\"v\":\"2\","
                + "\"ps\":\"United States VMess\","
                + "\"add\":\"vmess.example.com\","
                + "\"port\":\"443\","
                + "\"id\":\"5c1a9c9e-9f4b-4d3a-9c8e-1b2d3e4f5a6b\","
                + "\"aid\":\"0\","
                + "\"scy\":\"auto\","
                + "\"net\":\"ws\","
                + "\"type\":\"none\","
                + "\"host\":\"vmess.example.com\","
                + "\"path\":\"/ws\","
                + "\"tls\":\"tls\","
                + "\"sni\":\"vmess.example.com\""
                + "}";
    }

    private static String clashYaml() {
        return String.join("\n", Arrays.asList(
                "port: 7890",
                "proxies:",
                "  - name: \"\uD83C\uDDE9\uD83C\uDDEA Germany Clash\"",
                "    type: vless",
                "    server: clash.example.com",
                "    port: 443",
                "    uuid: 0f1c5f36-4f6b-4f0b-8f1f-4d1a6b4f2e11",
                "    tls: true",
                "    servername: clash.example.com",
                "    flow: xtls-rprx-vision",
                "    network: tcp",
                "    reality-opts:",
                "      public-key: 8hRk3Q0m5m3XhI1K6m3n0dHIZt6WZ1nYk5K0aVpG1S0",
                "      short-id: 6ba85179e30d4fc2",
                "    client-fingerprint: chrome",
                "  - name: Clash WebSocket",
                "    type: vmess",
                "    server: ws.example.com",
                "    port: 443",
                "    uuid: 5c1a9c9e-9f4b-4d3a-9c8e-1b2d3e4f5a6b",
                "    alterId: 0",
                "    cipher: auto",
                "    tls: true",
                "    network: ws",
                "    ws-opts:",
                "      path: /clash",
                "      headers:",
                "        Host: ws.example.com",
                "  - name: Clash Hysteria2",
                "    type: hysteria2",
                "    server: hy2.example.com",
                "    port: 443",
                "    password: hy2-password",
                "    sni: hy2.example.com",
                "    skip-cert-verify: true",
                "proxy-groups:",
                "  - name: PROXY",
                "    type: select",
                "    proxies:",
                "      - \"\uD83C\uDDE9\uD83C\uDDEA Germany Clash\""
        ));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("subscription check failed: " + message);
        }
    }

    /** One outbound per supported protocol, including their transport variants. */
    private static List<Outbound> sampleServers() {
        List<Outbound> list = new ArrayList<>();
        list.add(vless("Reality vision", "example.com", 443, "xtls-rprx-vision", true));
        list.add(vless("Reality ws", "example.com", 8443, null, true));
        list.add(vmessWs());
        list.add(vmessGrpc());
        list.add(trojan());
        list.add(shadowsocks());
        list.add(shadowsocksR());
        list.add(hysteria2());
        list.add(tuic());
        list.add(anytls());
        list.add(snell());
        list.add(shadowtls());
        list.add(ssh());
        list.add(wireguard());
        list.add(socks());
        list.add(httpProxy());
        return list;
    }

    private static Outbound base(String type, String tag, String server, int port) {
        Outbound outbound = new Outbound();
        outbound.type = type;
        outbound.tag = tag;
        outbound.server = server;
        outbound.port = port;
        return outbound;
    }

    private static Outbound vless(String tag, String server, int port, String flow, boolean reality) {
        Outbound outbound = base("vless", tag, server, port);
        outbound.uuid = "0f1c5f36-4f6b-4f0b-8f1f-4d1a6b4f2e11";
        outbound.flow = flow;
        outbound.tls = true;
        outbound.sni = server;
        outbound.clientFingerprint = "chrome";
        if (reality) {
            outbound.realityPublicKey = "8hRk3Q0m5m3XhI1K6m3n0dHIZt6WZ1nYk5K0aVpG1S0";
            outbound.realityShortId = "6ba85179e30d4fc2";
            outbound.network = "tcp";
        } else {
            outbound.network = "ws";
            outbound.wsPath = "/ws";
            outbound.wsHost = server;
        }
        if ("ws".equals(outbound.network)) {
            outbound.wsPath = "/ws";
            outbound.wsHost = server;
        }
        return outbound;
    }

    private static Outbound vmessWs() {
        Outbound outbound = base("vmess", "VMess ws", "vmess.example.com", 443);
        outbound.uuid = "5c1a9c9e-9f4b-4d3a-9c8e-1b2d3e4f5a6b";
        outbound.alterId = 0;
        outbound.security = "auto";
        outbound.tls = true;
        outbound.sni = outbound.server;
        outbound.network = "ws";
        outbound.wsPath = "/vmess";
        outbound.wsHost = outbound.server;
        return outbound;
    }

    private static Outbound vmessGrpc() {
        Outbound outbound = base("vmess", "VMess grpc", "grpc.example.com", 443);
        outbound.uuid = "5c1a9c9e-9f4b-4d3a-9c8e-1b2d3e4f5a6c";
        outbound.alterId = 0;
        outbound.security = "auto";
        outbound.tls = true;
        outbound.sni = outbound.server;
        outbound.network = "grpc";
        outbound.grpcServiceName = "GunService";
        return outbound;
    }

    private static Outbound trojan() {
        Outbound outbound = base("trojan", "Trojan", "trojan.example.com", 443);
        outbound.password = "trojan-password";
        outbound.tls = true;
        outbound.sni = outbound.server;
        outbound.clientFingerprint = "chrome";
        outbound.network = "ws";
        outbound.wsPath = "/trojan";
        return outbound;
    }

    private static Outbound shadowsocks() {
        Outbound outbound = base("shadowsocks", "Shadowsocks", "ss.example.com", 8388);
        outbound.method = "2022-blake3-aes-128-gcm";
        outbound.password = "ss-password";
        return outbound;
    }

    private static Outbound shadowsocksR() {
        Outbound outbound = base("shadowsocksr", "SSR", "ssr.example.com", 8388);
        outbound.method = "aes-256-cfb";
        outbound.password = "ssr-password";
        outbound.ssrProtocol = "auth_aes128_md5";
        outbound.ssrProtocolParam = "1234:password";
        outbound.ssrObfs = "tls1.2_ticket_auth";
        outbound.ssrObfsParam = "cloud.example.com";
        return outbound;
    }

    private static Outbound hysteria2() {
        Outbound outbound = base("hysteria2", "Hysteria2", "hy2.example.com", 443);
        outbound.password = "hy2-password";
        outbound.tls = true;
        outbound.sni = outbound.server;
        outbound.insecure = true;
        outbound.obfsType = "salamander";
        outbound.obfsPassword = "obfs-password";
        outbound.portHoppingRange = "20000-30000";
        outbound.upMbps = 200;
        outbound.downMbps = 500;
        return outbound;
    }

    private static Outbound tuic() {
        Outbound outbound = base("tuic", "TUIC", "tuic.example.com", 443);
        outbound.uuid = "3f1c5f36-4f6b-4f0b-8f1f-4d1a6b4f2e22";
        outbound.password = "tuic-password";
        outbound.tls = true;
        outbound.sni = outbound.server;
        outbound.congestionControl = "bbr";
        outbound.udpRelayMode = "native";
        return outbound;
    }

    private static Outbound anytls() {
        Outbound outbound = base("anytls", "AnyTLS", "anytls.example.com", 443);
        outbound.password = "anytls-password";
        outbound.tls = true;
        outbound.sni = outbound.server;
        outbound.clientFingerprint = "chrome";
        return outbound;
    }

    private static Outbound snell() {
        Outbound outbound = base("snell", "Snell", "snell.example.com", 443);
        outbound.snellPsk = "snell-psk";
        outbound.snellVersion = 4;
        outbound.snellObfsMode = "tls";
        outbound.snellObfsHost = "example.org";
        return outbound;
    }

    private static Outbound shadowtls() {
        Outbound outbound = base("shadowtls", "ShadowTLS", "stls.example.com", 443);
        outbound.shadowtlsVersion = 3;
        outbound.shadowtlsPassword = "shadowtls-password";
        outbound.tls = true;
        outbound.sni = "example.org";
        outbound.insecure = true;
        return outbound;
    }

    private static Outbound ssh() {
        Outbound outbound = base("ssh", "SSH", "ssh.example.com", 22);
        outbound.username = "root";
        outbound.password = "ssh-password";
        outbound.sshPrivateKey = null;
        return outbound;
    }

    private static Outbound wireguard() {
        Outbound outbound = base("wireguard", "WireGuard", "wg.example.com", 2408);
        outbound.wgPrivateKey = "aF9d3QW1mZ0F1bXl2V3J0a1p5c2Q0ZTFnMm0zcDRzNXQ2dw=";
        outbound.wgPeerPublicKey = "bG9uZ3JhbmRvbWtleWZvcnRlc3Rpbmdvbmx5MTIzNDU2Nzg5MA=";
        outbound.wgLocalAddress = "10.10.0.2/32";
        outbound.wgMtu = 1408;
        return outbound;
    }

    private static Outbound socks() {
        Outbound outbound = base("socks", "SOCKS", "socks.example.com", 1080);
        outbound.username = "user";
        outbound.password = "password";
        return outbound;
    }

    private static Outbound httpProxy() {
        Outbound outbound = base("http", "HTTP", "http.example.com", 8080);
        outbound.username = "user";
        outbound.password = "password";
        outbound.tls = true;
        return outbound;
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

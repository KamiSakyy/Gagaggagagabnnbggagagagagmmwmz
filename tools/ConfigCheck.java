import com.vortex.vpn.cfg.ConfigBuilder;
import com.vortex.vpn.cfg.ConfigSettings;
import com.vortex.vpn.cfg.JsonReader;
import com.vortex.vpn.model.Outbound;

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

        List<Outbound> servers = sampleServers();
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
            List<?> outbounds = (List<?>) root.get("outbounds");
            if (outbounds.size() < servers.size()) {
                throw new IllegalStateException(name + ": outbounds were dropped");
            }
        }
        System.out.println("ConfigCheck: " + variants.size() + " configurations written to " + out.getPath());
    }

    private static ConfigSettings baseSettings() {
        ConfigSettings settings = new ConfigSettings();
        settings.mode = ConfigSettings.MODE_SMART;
        settings.stack = "mixed";
        settings.selectedTag = "";
        return settings;
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

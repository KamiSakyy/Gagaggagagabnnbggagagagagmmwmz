package com.vortex.vpn.cfg;

import com.vortex.vpn.model.Outbound;
import com.vortex.vpn.sub.B64;
import com.vortex.vpn.sub.Geo;
import com.vortex.vpn.sub.SubImporter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Sample locations used by the built-in engine self-test.
 *
 * <p>Pure Java on purpose: the very same code feeds the JVM harness in {@code tools/ConfigCheck}
 * and the on-device self-test ({@code com.vortex.vpn.core.EngineSelfTest}), so what CI validates
 * with {@code sing-box check} is exactly what the phone validates with {@code Libbox.checkConfig}.</p>
 */
public final class SampleServers {

    private SampleServers() {
    }

    /** Everything the client can generate a configuration from: subscription + protocol matrix. */
    public static List<Outbound> all() {
        List<Outbound> list = new ArrayList<>(subscriptions());
        list.addAll(samples());
        return list;
    }

    /**
     * Validates the subscription pipeline: share links (raw and base64 v2ray format) and
     * Clash YAML are parsed into outbounds, exactly like the application does when it
     * "unpacks" a subscription into locations.
     */
    public static List<Outbound> subscriptions() {
        List<String> links = Arrays.asList(
                "vless://0f1c5f36-4f6b-4f0b-8f1f-4d1a6b4f2e11@example.com:443"
                        + "?security=reality&pbk=8hRk3Q0m5m3XhI1K6m3n0dHIZt6WZ1nYk5K0aVpG1S0"
                        + "&sid=6ba85179e30d4fc2&fp=chrome&flow=xtls-rprx-vision&type=tcp"
                        + "&sni=example.com#%F0%9F%87%A9%F0%9F%87%AA%20Germany%20Reality",
                "vmess://" + B64.encode(vmessJson().getBytes(StandardCharsets.UTF_8)),
                "trojan://trojan-password@trojan.example.com:443?security=tls&sni=trojan.example.com&type=ws&path=%2Ftrojan&host=trojan.example.com#%F0%9F%87%B3%F0%9F%87%B1%20Netherlands%20Trojan",
                "ss://" + B64.encode(("2022-blake3-aes-128-gcm:" + SS2022_KEY).getBytes(StandardCharsets.UTF_8))
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
                "wireguard://" + "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA="
                        + "@wg.example.com:2408?address=10.10.0.2%2F32&mtu=1408"
                        + "&public_key=ISIjJCUmJygpKissLS4vMDEyMzQ1Njc4OTo7PD0+P0A="
                        + "&preshared_key=QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVpbXF1eX2A="
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
        System.out.println("after dedupe: " + unique.size() + " locations (including one "
                + "shadowsocksr location that is intentionally filtered out of the config)");
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
    public static List<Outbound> samples() {
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
        list.add(naive());
        list.add(hysteria());
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
        // 2022 methods require a base64 pre-shared key (16 bytes for aes-128-gcm)
        outbound.password = SS2022_KEY;
        return outbound;
    }

    /** base64("0123456789abcdef") - a valid shadowsocks-2022 key. */
    private static final String SS2022_KEY = "MDEyMzQ1Njc4OWFiY2RlZg==";

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
        outbound.wgPrivateKey = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=";
        outbound.wgPeerPublicKey = "ISIjJCUmJygpKissLS4vMDEyMzQ1Njc4OTo7PD0+P0A=";
        outbound.wgLocalAddress = "10.10.0.2/32";
        outbound.wgMtu = 1408;
        return outbound;
    }

    private static Outbound naive() {
        Outbound outbound = base("naive", "Naive", "naive.example.com", 443);
        outbound.username = "user";
        outbound.password = "naive-password";
        outbound.tls = true;
        outbound.sni = outbound.server;
        return outbound;
    }

    private static Outbound hysteria() {
        Outbound outbound = base("hysteria", "Hysteria", "hysteria.example.com", 443);
        outbound.password = "hysteria-auth";
        outbound.tls = true;
        outbound.sni = outbound.server;
        outbound.upMbps = 100;
        outbound.downMbps = 200;
        outbound.obfsType = "xplus";
        outbound.obfsPassword = "obfs-password";
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
}

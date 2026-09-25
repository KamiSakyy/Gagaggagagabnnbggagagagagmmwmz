package com.vortex.vpn.sub;

import com.vortex.vpn.model.Outbound;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Converts a Clash / Clash.Meta / mihomo subscription into sing-box outbounds.
 * Covers the proxy types that the sing-box core can run.
 */
public final class ClashParser {

    private ClashParser() {
    }

    @SuppressWarnings("unchecked")
    public static List<Outbound> parse(String content) {
        List<Outbound> result = new ArrayList<>();
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(true);
        options.setMaxAliasesForCollections(200);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        Object loaded = yaml.load(content);
        if (!(loaded instanceof Map)) {
            return result;
        }
        Object proxies = ((Map<String, Object>) loaded).get("proxies");
        if (!(proxies instanceof List)) {
            return result;
        }
        for (Object entry : (List<Object>) proxies) {
            if (!(entry instanceof Map)) {
                continue;
            }
            try {
                Outbound outbound = convert((Map<String, Object>) entry);
                if (outbound != null) {
                    result.add(outbound);
                }
            } catch (Exception ignored) {
                // skip broken proxy entries
            }
        }
        return result;
    }

    private static Outbound convert(Map<String, Object> proxy) {
        String type = str(proxy, "type").toLowerCase(Locale.ROOT);
        Outbound o = new Outbound();
        o.rawLink = null;
        o.sourceType = "clash";
        o.tag = str(proxy, "name");
        o.server = str(proxy, "server");
        o.port = num(proxy, "port", 443);
        if (o.server.isEmpty()) {
            return null;
        }
        o.tls = bool(proxy, "tls", false);
        o.insecure = bool(proxy, "skip-cert-verify", false);
        String sni = firstNonEmpty(str(proxy, "servername"), str(proxy, "sni"), str(proxy, "peer"));
        if (!sni.isEmpty()) {
            o.sni = sni;
        }
        Object alpn = proxy.get("alpn");
        if (alpn instanceof List) {
            List<String> list = new ArrayList<>();
            for (Object item : (List<?>) alpn) {
                list.add(String.valueOf(item));
            }
            o.alpn = list.toArray(new String[0]);
        }
        String fingerprint = str(proxy, "client-fingerprint");
        if (!fingerprint.isEmpty()) {
            o.clientFingerprint = fingerprint;
        }
        String certificate = str(proxy, "ca-str");
        if (!certificate.isEmpty()) {
            o.echConfig = null;
        }

        switch (type) {
            case "ss":
                o.type = "shadowsocks";
                o.method = str(proxy, "cipher");
                o.password = str(proxy, "password");
                o.plugin = clashPlugin(str(proxy, "plugin"));
                o.pluginOpts = clashPluginOpts(proxy);
                break;
            case "ssr":
                o.type = "shadowsocksr";
                o.warn = "ShadowsocksR \u0443\u0434\u0430\u043b\u0451\u043d \u0438\u0437 sing-box 1.6 \u2014 \u043b\u043e\u043a\u0430\u0446\u0438\u044f \u043d\u0435 \u0431\u0443\u0434\u0435\u0442 \u0438\u0441\u043f\u043e\u043b\u044c\u0437\u043e\u0432\u0430\u043d\u0430";
                o.method = str(proxy, "cipher");
                o.password = str(proxy, "password");
                o.ssrProtocol = str(proxy, "protocol");
                o.ssrProtocolParam = str(proxy, "protocol-param");
                o.ssrObfs = str(proxy, "obfs");
                o.ssrObfsParam = str(proxy, "obfs-param");
                break;
            case "vmess":
                o.type = "vmess";
                o.uuid = str(proxy, "uuid");
                o.alterId = num(proxy, "alterId", 0);
                o.security = firstNonEmpty(str(proxy, "cipher"), "auto");
                break;
            case "vless":
                o.type = "vless";
                o.uuid = str(proxy, "uuid");
                o.flow = str(proxy, "flow");
                o.tls = true;
                Map<String, Object> reality = map(proxy.get("reality-opts"));
                if (reality != null) {
                    o.realityPublicKey = str(reality, "public-key");
                    o.realityShortId = str(reality, "short-id");
                    if (o.clientFingerprint == null) {
                        o.clientFingerprint = "chrome";
                    }
                }
                break;
            case "trojan":
                o.type = "trojan";
                o.password = str(proxy, "password");
                o.tls = true;
                break;
            case "hysteria":
                o.type = "hysteria";
                o.password = firstNonEmpty(str(proxy, "auth-str"), str(proxy, "auth_str"));
                o.upMbps = num(proxy, "up", num(proxy, "up-mbps", 0));
                o.downMbps = num(proxy, "down", num(proxy, "down-mbps", 0));
                o.obfsType = str(proxy, "obfs");
                o.tls = true;
                break;
            case "hysteria2":
                o.type = "hysteria2";
                o.password = str(proxy, "password");
                o.obfsType = str(proxy, "obfs");
                o.obfsPassword = str(proxy, "obfs-password");
                o.upMbps = num(proxy, "up", num(proxy, "up-mbps", 0));
                o.downMbps = num(proxy, "down", num(proxy, "down-mbps", 0));
                o.portHoppingRange = str(proxy, "ports");
                o.tls = true;
                break;
            case "tuic":
                o.type = "tuic";
                o.uuid = str(proxy, "uuid");
                o.password = str(proxy, "password");
                o.congestionControl = firstNonEmpty(str(proxy, "congestion-controller"), str(proxy, "congestion-control"));
                o.udpRelayMode = str(proxy, "udp-relay-mode");
                o.tls = true;
                break;
            case "anytls":
                o.type = "anytls";
                o.password = str(proxy, "password");
                o.tls = true;
                if (o.sni == null) {
                    o.sni = o.server;
                }
                break;
            case "snell":
                o.type = "snell";
                o.snellPsk = str(proxy, "psk");
                o.snellVersion = num(proxy, "version", 4);
                Map<String, Object> obfsOpts = map(proxy.get("obfs-opts"));
                if (obfsOpts != null) {
                    o.snellObfsMode = str(obfsOpts, "mode");
                    o.snellObfsHost = str(obfsOpts, "host");
                }
                break;
            case "wireguard":
                o.type = "wireguard";
                o.wgPrivateKey = str(proxy, "private-key");
                o.wgPeerPublicKey = str(proxy, "public-key");
                o.wgPreSharedKey = str(proxy, "pre-shared-key");
                o.wgLocalAddress = firstNonEmpty(str(proxy, "ip"), "10.0.0.2/32");
                o.wgMtu = num(proxy, "mtu", 1408);
                Object reserved = proxy.get("reserved");
                if (reserved instanceof List) {
                    List<String> r = new ArrayList<>();
                    for (Object item : (List<?>) reserved) {
                        r.add(String.valueOf(item));
                    }
                    o.wgReserved = r.toArray(new String[0]);
                }
                break;
            case "socks5":
            case "socks":
                o.type = "socks";
                o.username = str(proxy, "username");
                o.password = str(proxy, "password");
                break;
            case "http":
                o.type = "http";
                o.username = str(proxy, "username");
                o.password = str(proxy, "password");
                o.tls = bool(proxy, "tls", false);
                break;
            default:
                return null;
        }

        applyTransport(o, proxy);
        return o;
    }

    private static void applyTransport(Outbound o, Map<String, Object> proxy) {
        String network = str(proxy, "network").toLowerCase(Locale.ROOT);
        if (network.isEmpty()) {
            network = "tcp";
        }
        Map<String, Object> wsOpts = map(proxy.get("ws-opts"));
        Map<String, Object> grpcOpts = map(proxy.get("grpc-opts"));
        Map<String, Object> h2Opts = map(proxy.get("h2-opts"));
        switch (network) {
            case "ws":
                o.network = "ws";
                if (wsOpts != null) {
                    o.wsPath = str(wsOpts, "path");
                    Map<String, Object> headers = map(wsOpts.get("headers"));
                    if (headers != null) {
                        o.wsHost = firstNonEmpty(str(headers, "Host"), str(headers, "host"));
                    }
                    o.wsMaxEarlyData = num(wsOpts, "max-early-data", 0);
                    String header = str(wsOpts, "early-data-header-name");
                    if (!header.isEmpty()) {
                        o.wsEarlyDataHeader = header;
                    }
                }
                break;
            case "grpc":
                o.network = "grpc";
                if (grpcOpts != null) {
                    o.grpcServiceName = firstNonEmpty(str(grpcOpts, "grpc-service-name"), str(grpcOpts, "service-name"));
                }
                break;
            case "h2":
            case "http":
                o.network = "http";
                if (h2Opts != null) {
                    o.httpPath = str(h2Opts, "path");
                    Object host = h2Opts.get("host");
                    if (host instanceof List && !((List<?>) host).isEmpty()) {
                        o.httpHost = String.valueOf(((List<?>) host).get(0));
                    } else if (host != null) {
                        o.httpHost = String.valueOf(host);
                    }
                }
                break;
            default:
                o.network = "tcp";
        }
    }

    private static String clashPlugin(String plugin) {
        if (plugin == null || plugin.isEmpty()) {
            return null;
        }
        if (plugin.contains("obfs")) {
            return "obfs-local";
        }
        if (plugin.contains("v2ray")) {
            return "v2ray-plugin";
        }
        return plugin;
    }

    @SuppressWarnings("unchecked")
    private static String clashPluginOpts(Map<String, Object> proxy) {
        Map<String, Object> opts = map(proxy.get("plugin-opts"));
        if (opts == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        String mode = str(opts, "mode");
        if (!mode.isEmpty()) {
            sb.append("obfs=").append(mode);
        }
        String host = str(opts, "host");
        if (!host.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append("obfs-host=").append(host);
        }
        String path = str(opts, "path");
        if (!path.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append("path=").append(path);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    private static String str(Map<String, Object> map, String key) {
        if (map == null) {
            return "";
        }
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static int num(Map<String, Object> map, String key, int fallback) {
        if (map == null) {
            return fallback;
        }
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            try {
                return (int) Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private static boolean bool(Map<String, Object> map, String key, boolean fallback) {
        if (map == null) {
            return fallback;
        }
        Object value = map.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value != null) {
            return "true".equalsIgnoreCase(String.valueOf(value));
        }
        return fallback;
    }
}

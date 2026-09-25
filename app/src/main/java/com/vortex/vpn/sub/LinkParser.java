package com.vortex.vpn.sub;

import com.vortex.vpn.cfg.JsonReader;
import com.vortex.vpn.model.Outbound;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Parser for every proxy share link format used in the wild:
 * vless, vmess, trojan, shadowsocks, shadowsocksr, shadowtls, snell, hysteria, hysteria2,
 * tuic, anytls, naive, ssh, socks, http, wireguard.
 *
 * Pure Java (no Android APIs) so the CI harness can verify the produced outbounds.
 */
public final class LinkParser {

    private LinkParser() {
    }

    public static boolean looksLikeLink(String line) {
        if (line == null) {
            return false;
        }
        String trimmed = line.trim();
        int index = trimmed.indexOf("://");
        if (index <= 0) {
            return false;
        }
        return schemeSupported(trimmed.substring(0, index).toLowerCase(Locale.ROOT));
    }

    private static boolean schemeSupported(String scheme) {
        switch (scheme) {
            case "vless":
            case "vmess":
            case "trojan":
            case "trojan-go":
            case "ss":
            case "ssr":
            case "shadowtls":
            case "snell":
            case "hysteria":
            case "hysteria2":
            case "hy2":
            case "tuic":
            case "anytls":
            case "naive":
            case "naive+https":
            case "ssh":
            case "socks":
            case "socks5":
            case "sk5":
            case "wireguard":
            case "wg":
                return true;
            default:
                return false;
        }
    }

    /** Parses a single share link, returns null when the link is not supported. */
    public static Outbound parse(String link) {
        if (link == null) {
            return null;
        }
        String trimmed = link.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("#")) {
            return null;
        }
        int schemeIndex = trimmed.indexOf("://");
        if (schemeIndex <= 0) {
            return null;
        }
        String scheme = trimmed.substring(0, schemeIndex).toLowerCase(Locale.ROOT);
        if (!schemeSupported(scheme)) {
            return null;
        }
        try {
            switch (scheme) {
                case "vmess":
                    return parseVmess(trimmed);
                case "ss":
                    return parseShadowsocks(trimmed);
                case "ssr":
                    return parseShadowsocksR(trimmed);
                case "hysteria":
                    return parseHysteria(trimmed);
                case "hysteria2":
                case "hy2":
                    return parseHysteria2(trimmed);
                case "tuic":
                    return parseTuic(trimmed);
                case "anytls":
                case "shadowtls":
                case "snell":
                case "naive":
                case "naive+https":
                case "ssh":
                case "socks":
                case "socks5":
                case "sk5":
                case "wireguard":
                case "wg":
                case "trojan":
                case "trojan-go":
                case "vless":
                default:
                    return parseStandard(scheme, trimmed);
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ standard URI form

    private static Outbound parseStandard(String scheme, String link) {
        Uri uri = Uri.parse(link);
        if (uri.host == null || uri.host.isEmpty()) {
            return null;
        }
        Outbound o = new Outbound();
        o.rawLink = link;
        o.server = uri.host;
        o.port = uri.port > 0 ? uri.port : defaultPort(scheme);
        o.tag = uri.name();
        String user = uri.user;
        String password = uri.password;

        switch (scheme) {
            case "vless":
                o.type = "vless";
                o.uuid = user;
                o.flow = uri.param("flow", null);
                o.packetEncoding = uri.param("packetEncoding", uri.param("packet_encoding", null));
                break;
            case "trojan":
            case "trojan-go":
                o.type = "trojan";
                o.password = user;
                if (o.password == null || o.password.isEmpty()) {
                    o.password = password;
                }
                break;
            case "anytls":
                o.type = "anytls";
                o.password = user != null && !user.isEmpty() ? user : password;
                break;
            case "shadowtls":
                o.type = "shadowtls";
                o.password = user != null && !user.isEmpty() ? user : password;
                o.shadowtlsPassword = o.password;
                o.shadowtlsVersion = uri.intParam("version", 3);
                break;
            case "snell":
                o.type = "snell";
                o.snellPsk = user != null && !user.isEmpty() ? user : password;
                o.snellVersion = uri.intParam("version", 4);
                o.snellObfsMode = uri.param("obfs", uri.param("obfs_mode", null));
                o.snellObfsHost = uri.param("obfs-host", uri.param("obfs_host", null));
                break;
            case "naive":
            case "naive+https":
                o.type = "naive";
                o.username = user;
                o.password = password;
                o.tls = true;
                if (user != null && password == null) {
                    o.password = user;
                    o.username = null;
                }
                break;
            case "ssh":
                o.type = "ssh";
                o.username = user;
                o.password = password;
                o.sshPrivateKey = uri.param("privateKey", uri.param("private_key", null));
                break;
            case "socks":
            case "socks5":
            case "sk5":
                o.type = "socks";
                o.username = user;
                o.password = password;
                if (password == null && user != null && user.indexOf(':') > 0) {
                    o.username = user.substring(0, user.indexOf(':'));
                    o.password = user.substring(user.indexOf(':') + 1);
                }
                break;
            case "wireguard":
            case "wg": {
                o.type = "wireguard";
                o.wgPrivateKey = user;
                o.wgPeerPublicKey = uri.param("publickey", uri.param("public_key",
                        uri.param("peerPublicKey", null)));
                o.wgPreSharedKey = uri.param("presharedkey", uri.param("pre_shared_key", uri.param("preshared_key", null)));
                o.wgLocalAddress = uri.param("address", uri.param("ip", "10.0.0.2/32"));
                o.wgMtu = uri.intParam("mtu", 1408);
                o.wgKeepAlive = uri.intParam("keepalive", uri.intParam("persistent_keepalive_interval", 0));
                String reserved = uri.param("reserved", null);
                if (reserved != null && !reserved.isEmpty()) {
                    o.wgReserved = reserved.split("[,\\-]");
                }
                o.tls = false;
                return o;
            }
            default:
                return null;
        }

        applyTls(o, uri);
        applyTransport(o, uri);
        return o;
    }

    private static int defaultPort(String scheme) {
        switch (scheme) {
            case "socks":
            case "socks5":
            case "sk5":
                return 1080;
            case "ssh":
                return 22;
            case "wireguard":
            case "wg":
                return 51820;
            case "naive":
            case "naive+https":
                return 443;
            default:
                return 443;
        }
    }

    private static void applyTls(Outbound o, Uri uri) {
        String security = uri.param("security", null);
        String sni = firstNonNull(uri.param("sni", null), uri.param("peer", null), uri.param("serverName", null));
        boolean insecure = uri.boolParam("allowInsecure") || uri.boolParam("insecure")
                || uri.boolParam("allow_insecure") || uri.boolParam("skip-cert-verify");
        o.insecure = insecure;
        o.sni = sni;
        String alpn = uri.param("alpn", null);
        if (alpn != null && !alpn.isEmpty()) {
            o.alpn = alpn.split(",");
        }
        String fingerprint = firstNonNull(uri.param("fp", null), uri.param("fingerprint", null));
        if (fingerprint != null && !fingerprint.isEmpty()) {
            o.clientFingerprint = fingerprint;
        }

        boolean reality = "reality".equalsIgnoreCase(security);
        boolean tlsRequested = "tls".equalsIgnoreCase(security) || "xtls".equalsIgnoreCase(security) || reality;

        switch (o.type == null ? "" : o.type) {
            case "hysteria2":
            case "hysteria":
            case "tuic":
            case "anytls":
            case "naive":
            case "shadowtls":
                o.tls = true;
                break;
            default:
                o.tls = tlsRequested;
        }

        if (reality) {
            o.realityPublicKey = uri.param("pbk", uri.param("publicKey", null));
            o.realityShortId = uri.param("sid", uri.param("shortId", null));
            o.tls = true;
            if (o.clientFingerprint == null) {
                o.clientFingerprint = "chrome";
            }
        }
        if (uri.boolParam("fragment")) {
            o.fragment = true;
        }
        if (uri.boolParam("recordFragment")) {
            o.recordFragment = true;
        }
    }

    private static void applyTransport(Outbound o, Uri uri) {
        String type = uri.param("type", uri.param("net", "tcp"));
        String host = uri.param("host", null);
        String path = uri.param("path", null);
        String serviceName = firstNonNull(uri.param("serviceName", null), uri.param("service_name", null));
        if (type == null || type.isEmpty()) {
            type = "tcp";
        }
        switch (type.toLowerCase(Locale.ROOT)) {
            case "ws":
            case "websocket":
                o.network = "ws";
                o.wsPath = path;
                o.wsHost = host;
                String ed = uri.param("ed", uri.param("maxEarlyData", null));
                if (ed != null) {
                    try {
                        o.wsMaxEarlyData = Integer.parseInt(ed);
                    } catch (NumberFormatException ignored) {
                    }
                }
                o.wsEarlyDataHeader = uri.param("eh", uri.param("earlyDataHeaderName", null));
                break;
            case "grpc":
                o.network = "grpc";
                o.grpcServiceName = serviceName != null ? serviceName : path;
                break;
            case "http":
            case "h2":
                o.network = "http";
                o.httpHost = host;
                o.httpPath = path;
                o.httpMethod = uri.param("method", null);
                break;
            case "httpupgrade":
                o.network = "httpupgrade";
                o.httpHost = host;
                o.httpPath = path;
                break;
            case "quic":
                o.network = "quic";
                break;
            case "xhttp":
            case "splithttp":
            case "mkcp":
            case "kcp":
                // Not supported by the sing-box core yet - keep tcp so the server still imports.
                o.network = "tcp";
                o.warn = "transport " + type + " -> tcp";
                break;
            case "tcp":
            default:
                o.network = "tcp";
                if (host != null) {
                    o.httpHost = host;
                }
                break;
        }
    }

    // ------------------------------------------------------------------ vmess

    private static Outbound parseVmess(String link) {
        String payload = link.substring("vmess://".length());
        int hash = payload.indexOf('#');
        if (hash >= 0) {
            payload = payload.substring(0, hash);
        }
        String decoded = B64.decodeToString(payload);
        if (decoded == null) {
            return null;
        }
        decoded = decoded.trim();
        Outbound o = new Outbound();
        o.type = "vmess";
        o.rawLink = link;

        if (decoded.startsWith("{")) {
            Map<String, Object> map = JsonReader.parseObject(decoded);
            o.server = JsonReader.string(map, "add", "");
            o.port = JsonReader.integer(map, "port", 443);
            o.uuid = JsonReader.string(map, "id", "");
            o.alterId = JsonReader.integer(map, "aid", 0);
            o.security = JsonReader.string(map, "scy", "auto");
            if (o.security == null || o.security.isEmpty()) {
                o.security = "auto";
            }
            o.tag = JsonReader.string(map, "ps", "");
            String net = JsonReader.string(map, "net", "tcp");
            String host = JsonReader.string(map, "host", "");
            String path = JsonReader.string(map, "path", "");
            String tls = JsonReader.string(map, "tls", "");
            String sni = JsonReader.string(map, "sni", "");
            String alpn = JsonReader.string(map, "alpn", "");
            String fp = JsonReader.string(map, "fp", "");
            if (!alpn.isEmpty()) {
                o.alpn = alpn.split(",");
            }
            if (!fp.isEmpty()) {
                o.clientFingerprint = fp;
            }
            o.tls = "tls".equalsIgnoreCase(tls) || "reality".equalsIgnoreCase(tls);
            if (!sni.isEmpty()) {
                o.sni = sni;
            }
            if (net == null || net.isEmpty()) {
                net = "tcp";
            }
            switch (net.toLowerCase(Locale.ROOT)) {
                case "ws":
                    o.network = "ws";
                    o.wsPath = path.isEmpty() ? null : path;
                    o.wsHost = host.isEmpty() ? null : host;
                    break;
                case "grpc":
                    o.network = "grpc";
                    o.grpcServiceName = path.isEmpty() ? null : path;
                    break;
                case "h2":
                case "http":
                    o.network = "http";
                    o.httpHost = host.isEmpty() ? null : host;
                    o.httpPath = path.isEmpty() ? null : path;
                    break;
                case "httpupgrade":
                    o.network = "httpupgrade";
                    o.httpHost = host.isEmpty() ? null : host;
                    o.httpPath = path.isEmpty() ? null : path;
                    break;
                case "quic":
                    o.network = "quic";
                    break;
                case "kcp":
                case "mkcp":
                    o.network = "tcp";
                    o.warn = "transport kcp -> tcp";
                    break;
                default:
                    o.network = "tcp";
            }
            if (o.server == null || o.server.isEmpty()) {
                return null;
            }
            o.tag = decode(o.tag);
            return o;
        }

        // legacy vmess: base64(cipher:uuid@host:port/?params)
        String body = decoded;
        int at = body.lastIndexOf('@');
        if (at < 0) {
            return null;
        }
        String credentials = body.substring(0, at);
        String rest = body.substring(at + 1);
        int colon = credentials.indexOf(':');
        if (colon < 0) {
            return null;
        }
        o.security = credentials.substring(0, colon);
        o.uuid = credentials.substring(colon + 1);
        int slash = rest.indexOf('/');
        String address = slash >= 0 ? rest.substring(0, slash) : rest;
        int portColon = address.lastIndexOf(':');
        if (portColon < 0) {
            return null;
        }
        o.server = address.substring(0, portColon);
        try {
            o.port = Integer.parseInt(address.substring(portColon + 1));
        } catch (NumberFormatException e) {
            o.port = 443;
        }
        o.network = "tcp";
        o.tag = "";
        return o;
    }

    // ------------------------------------------------------------------ shadowsocks

    private static Outbound parseShadowsocks(String link) {
        String body = link.substring("ss://".length());
        String name = null;
        int hash = body.indexOf('#');
        if (hash >= 0) {
            name = decode(body.substring(hash + 1));
            body = body.substring(0, hash);
        }
        String query = null;
        int question = body.indexOf('?');
        if (question >= 0) {
            query = body.substring(question + 1);
            body = body.substring(0, question);
        }

        Outbound o = new Outbound();
        o.type = "shadowsocks";
        o.rawLink = link;

        String method;
        String password;
        String host;
        int port;

        int at = body.lastIndexOf('@');
        if (at >= 0) {
            String userInfo = body.substring(0, at);
            String hostPort = body.substring(at + 1);
            String decodedUser = userInfo;
            if (decodedUser.indexOf(':') < 0) {
                decodedUser = B64.decodeToString(userInfo);
            }
            int colon = decodedUser.indexOf(':');
            if (colon < 0) {
                return null;
            }
            method = decodedUser.substring(0, colon);
            password = decodedUser.substring(colon + 1);
            int[] hp = splitHostPort(hostPort);
            if (hp == null) {
                return null;
            }
            host = hostPort.substring(0, hp[1] > 0 ? hostPort.lastIndexOf(':') : hostPort.length());
            port = hp[0];
        } else {
            String plain = B64.decodeToString(body);
            int at2 = plain.lastIndexOf('@');
            if (at2 < 0) {
                return null;
            }
            String credentials = plain.substring(0, at2);
            String hostPort = plain.substring(at2 + 1);
            int colon = credentials.indexOf(':');
            if (colon < 0) {
                return null;
            }
            method = credentials.substring(0, colon);
            password = credentials.substring(colon + 1);
            int portColon = hostPort.lastIndexOf(':');
            if (portColon < 0) {
                return null;
            }
            host = hostPort.substring(0, portColon);
            try {
                port = Integer.parseInt(hostPort.substring(portColon + 1));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        o.method = method;
        o.password = password;
        o.server = host;
        o.port = port;
        o.tag = name == null ? "" : name;

        if (query != null) {
            Map<String, String> params = parseQuery(query);
            String plugin = params.get("plugin");
            if (plugin != null && !plugin.isEmpty()) {
                String decodedPlugin = decode(plugin);
                String[] parts = decodedPlugin.split(";");
                String pluginName = parts[0];
                StringBuilder opts = new StringBuilder();
                for (int i = 1; i < parts.length; i++) {
                    if (opts.length() > 0) {
                        opts.append(';');
                    }
                    opts.append(parts[i]);
                }
                if (pluginName.contains("obfs")) {
                    o.plugin = "obfs-local";
                } else if (pluginName.contains("v2ray")) {
                    o.plugin = "v2ray-plugin";
                } else {
                    o.plugin = pluginName;
                }
                o.pluginOpts = opts.toString();
            }
        }
        return o;
    }

    private static Outbound parseShadowsocksR(String link) {
        String payload = link.substring("ssr://".length());
        int hash = payload.indexOf('#');
        if (hash >= 0) {
            payload = payload.substring(0, hash);
        }
        String decoded = B64.decodeToString(payload.trim());
        if (decoded == null || decoded.indexOf(':') < 0) {
            return null;
        }
        String base = decoded;
        String paramsPart = null;
        int slash = decoded.indexOf("/?");
        if (slash >= 0) {
            paramsPart = decoded.substring(slash + 2);
            base = decoded.substring(0, slash);
        }
        String[] parts = base.split(":");
        if (parts.length < 6) {
            return null;
        }
        Outbound o = new Outbound();
        o.type = "shadowsocksr";
        o.warn = "ShadowsocksR \u0443\u0434\u0430\u043b\u0451\u043d \u0438\u0437 sing-box 1.6 \u2014 \u043b\u043e\u043a\u0430\u0446\u0438\u044f \u043d\u0435 \u0431\u0443\u0434\u0435\u0442 \u0438\u0441\u043f\u043e\u043b\u044c\u0437\u043e\u0432\u0430\u043d\u0430";
        o.rawLink = link;
        o.server = parts[0];
        try {
            o.port = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        o.ssrProtocol = parts[2];
        o.method = parts[3];
        o.ssrObfs = parts[4];
        o.password = B64.decodeToString(parts[5]);
        if (paramsPart != null) {
            Map<String, String> params = parseQuery(paramsPart);
            if (params.containsKey("obfsparam")) {
                o.ssrObfsParam = B64.decodeToString(params.get("obfsparam"));
            }
            if (params.containsKey("protoparam")) {
                o.ssrProtocolParam = B64.decodeToString(params.get("protoparam"));
            }
            if (params.containsKey("remarks")) {
                o.tag = B64.decodeToString(params.get("remarks"));
            }
        }
        if (o.tag == null) {
            o.tag = "";
        }
        return o;
    }

    // ------------------------------------------------------------------ hysteria

    private static Outbound parseHysteria2(String link) {
        Uri uri = Uri.parse(link);
        Outbound o = new Outbound();
        o.type = "hysteria2";
        o.rawLink = link;
        o.server = uri.host;
        o.port = uri.port > 0 ? uri.port : 443;
        o.password = uri.user != null ? uri.user : uri.password;
        if (o.password != null && uri.password != null && uri.user != null) {
            o.password = uri.user + ":" + uri.password;
        }
        o.tag = uri.name();
        o.tls = true;
        o.sni = firstNonNull(uri.param("sni", null), uri.param("peer", null));
        o.insecure = uri.boolParam("insecure") || uri.boolParam("allowInsecure");
        String alpn = uri.param("alpn", null);
        if (alpn != null && !alpn.isEmpty()) {
            o.alpn = alpn.split(",");
        }
        o.obfsType = uri.param("obfs", null);
        o.obfsPassword = uri.param("obfs-password", uri.param("obfs_password", null));
        o.upMbps = uri.intParam("up", uri.intParam("upmbps", 0));
        o.downMbps = uri.intParam("down", uri.intParam("downmbps", 0));
        o.portHoppingRange = firstNonNull(uri.param("mport", null), uri.param("ports", null));
        return o;
    }

    private static Outbound parseHysteria(String link) {
        Uri uri = Uri.parse(link);
        Outbound o = new Outbound();
        o.type = "hysteria";
        o.rawLink = link;
        o.server = uri.host;
        o.port = uri.port > 0 ? uri.port : 443;
        o.password = firstNonNull(uri.param("auth", null), uri.user, uri.param("auth_str", null));
        o.tag = uri.name();
        o.tls = true;
        o.sni = firstNonNull(uri.param("peer", null), uri.param("sni", null));
        o.insecure = uri.boolParam("insecure") || uri.boolParam("allowInsecure");
        String alpn = uri.param("alpn", null);
        if (alpn != null && !alpn.isEmpty()) {
            o.alpn = alpn.split(",");
        }
        o.obfsType = uri.param("obfs", null);
        o.upMbps = uri.intParam("upmbps", uri.intParam("up", 0));
        o.downMbps = uri.intParam("downmbps", uri.intParam("down", 0));
        return o;
    }

    private static Outbound parseTuic(String link) {
        Uri uri = Uri.parse(link);
        Outbound o = new Outbound();
        o.type = "tuic";
        o.rawLink = link;
        o.server = uri.host;
        o.port = uri.port > 0 ? uri.port : 443;
        String user = uri.user;
        String password = uri.password;
        if (password == null && user != null && user.indexOf(':') > 0) {
            int colon = user.indexOf(':');
            password = user.substring(colon + 1);
            user = user.substring(0, colon);
        }
        o.uuid = user;
        o.password = password;
        o.tag = uri.name();
        o.tls = true;
        o.sni = firstNonNull(uri.param("sni", null), uri.param("peer", null));
        o.insecure = uri.boolParam("allow_insecure") || uri.boolParam("insecure") || uri.boolParam("allowInsecure");
        o.congestionControl = uri.param("congestion_control", uri.param("congestion", null));
        o.udpRelayMode = uri.param("udp_relay_mode", uri.param("udpRelayMode", null));
        String alpn = uri.param("alpn", null);
        if (alpn != null && !alpn.isEmpty()) {
            o.alpn = alpn.split(",");
        }
        return o;
    }

    // ------------------------------------------------------------------ URI helper

    static final class Uri {
        String scheme;
        String user;
        String password;
        String host;
        int port;
        String path;
        String fragment;
        final Map<String, String> params = new LinkedHashMap<>();

        String name() {
            return fragment == null ? "" : fragment.trim();
        }

        String param(String key, String fallback) {
            String value = params.get(key);
            return value == null || value.isEmpty() ? fallback : value;
        }

        int intParam(String key, int fallback) {
            String value = params.get(key);
            if (value == null || value.isEmpty()) {
                return fallback;
            }
            try {
                return (int) Double.parseDouble(value);
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        boolean boolParam(String key) {
            String value = params.get(key);
            if (value == null) {
                return false;
            }
            String lower = value.toLowerCase(Locale.ROOT);
            return lower.isEmpty() || "1".equals(lower) || "true".equals(lower) || "yes".equals(lower);
        }

        static Uri parse(String link) {
            Uri uri = new Uri();
            int schemeIndex = link.indexOf("://");
            uri.scheme = schemeIndex > 0 ? link.substring(0, schemeIndex) : "";
            String rest = schemeIndex > 0 ? link.substring(schemeIndex + 3) : link;

            int hash = rest.indexOf('#');
            if (hash >= 0) {
                uri.fragment = decode(rest.substring(hash + 1));
                rest = rest.substring(0, hash);
            }
            int question = rest.indexOf('?');
            if (question >= 0) {
                uri.params.putAll(parseQuery(rest.substring(question + 1)));
                rest = rest.substring(0, question);
            }
            int at = rest.lastIndexOf('@');
            String hostPort = rest;
            if (at >= 0) {
                String userInfo = rest.substring(0, at);
                hostPort = rest.substring(at + 1);
                int colon = userInfo.indexOf(':');
                if (colon >= 0) {
                    uri.user = decode(userInfo.substring(0, colon));
                    uri.password = decode(userInfo.substring(colon + 1));
                } else {
                    uri.user = decode(userInfo);
                }
            }
            int slash = hostPort.indexOf('/');
            if (slash >= 0) {
                uri.path = hostPort.substring(slash + 1);
                hostPort = hostPort.substring(0, slash);
            }
            if (hostPort.startsWith("[")) {
                int end = hostPort.indexOf(']');
                if (end > 0) {
                    uri.host = hostPort.substring(1, end);
                    String tail = hostPort.substring(end + 1);
                    if (tail.startsWith(":")) {
                        uri.port = safePort(tail.substring(1));
                    }
                    return uri;
                }
            }
            int portColon = hostPort.lastIndexOf(':');
            if (portColon > 0) {
                uri.host = hostPort.substring(0, portColon);
                uri.port = safePort(hostPort.substring(portColon + 1));
            } else {
                uri.host = hostPort;
            }
            return uri;
        }

        private static int safePort(String value) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new LinkedHashMap<>();
        if (query == null) {
            return map;
        }
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            map.put(decode(key), decode(value));
        }
        return map;
    }

    private static int[] splitHostPort(String hostPort) {
        int colon = hostPort.lastIndexOf(':');
        if (colon < 0) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(hostPort.substring(colon + 1)), colon};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Percent decoding that keeps '+' intact (proxy links treat it literally). */
    static String decode(String value) {
        if (value == null || value.indexOf('%') < 0) {
            return value;
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length()) {
                try {
                    int code = Integer.parseInt(value.substring(i + 1, i + 3), 16);
                    out.write(code);
                    i += 2;
                    continue;
                } catch (NumberFormatException ignored) {
                }
            }
            byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
            out.write(bytes, 0, bytes.length);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }
}

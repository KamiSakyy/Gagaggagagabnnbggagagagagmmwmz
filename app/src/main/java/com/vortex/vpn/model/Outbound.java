package com.vortex.vpn.model;

import com.vortex.vpn.cfg.Json;

/**
 * Protocol-agnostic description of a single proxy server.
 *
 * Pure Java on purpose: the same object is used by the Android app and by the
 * CI validation harness that feeds generated configs to {@code sing-box check}.
 */
public class Outbound {

    // ---- protocol identity -------------------------------------------------
    /** sing-box outbound type: vless, vmess, trojan, shadowsocks, ... */
    public String type = "vless";
    /** Display name (also used as the sing-box outbound tag). */
    public String tag = "";
    public String server = "";
    public int port;

    // ---- credentials -------------------------------------------------------
    public String uuid;
    public String password;
    public String username;
    public String method;          // shadowsocks / shadowsocksr cipher
    public String plugin;          // shadowsocks plugin (obfs-local, v2ray-plugin)
    public String pluginOpts;
    public String flow;            // vless flow (xtls-rprx-vision)
    public String security = "auto"; // vmess security
    public int alterId;
    public String packetEncoding;  // xudp / packetaddr

    // ---- shadowsocksr ------------------------------------------------------
    public String ssrProtocol;
    public String ssrProtocolParam;
    public String ssrObfs;
    public String ssrObfsParam;

    // ---- shadowtls / snell -------------------------------------------------
    public int shadowtlsVersion = 3;
    public String shadowtlsPassword;
    public String snellPsk;
    public int snellVersion = 4;
    public String snellObfsMode;
    public String snellObfsHost;

    // ---- TLS ---------------------------------------------------------------
    public boolean tls;
    public boolean insecure;
    public String sni;
    public String[] alpn;
    public String clientFingerprint;   // utls fingerprint (chrome, firefox, safari, random...)
    public String realityPublicKey;
    public String realityShortId;
    public boolean fragment;
    public boolean recordFragment;
    public String echConfig;

    // ---- transport ---------------------------------------------------------
    /** tcp (empty), ws, grpc, http, httpupgrade, quic */
    public String network = "tcp";
    public String wsPath;
    public String wsHost;
    public String grpcServiceName;
    public String httpPath;
    public String httpHost;
    public int wsMaxEarlyData;
    public String wsEarlyDataHeader;
    public String httpMethod;

    // ---- QUIC based protocols ---------------------------------------------
    public int upMbps;
    public int downMbps;
    public String obfsType;         // hysteria2 salamander
    public String obfsPassword;
    public String congestionControl; // bbr / cubic / new_reno
    public String udpRelayMode;      // tuic native / quic
    public boolean disableSni;       // tuic/anytls helpers
    public String portHoppingRange;  // hysteria2 server_ports

    // ---- wireguard ---------------------------------------------------------
    public String wgPrivateKey;
    public String wgPeerPublicKey;
    public String wgPreSharedKey;
    public String wgLocalAddress;      // 10.0.0.2/32
    public String wgAllowedIps = "0.0.0.0/0,::/0";
    public String[] wgReserved;
    public int wgMtu = 1408;
    public int wgKeepAlive;

    // ---- ssh / naive -------------------------------------------------------
    public String sshPrivateKey;
    public String sshHostKey;

    // ---- metadata ----------------------------------------------------------
    public String rawLink;           // original share link (for export / sharing)
    public String sourceId;          // subscription id
    public String country;           // detected location / flag
    public String sourceType = "link";
    /** Optional warning shown in the UI (e.g. unsupported transport downgraded to tcp). */
    public String warn;

    public String displayName() {
        return tag == null || tag.isEmpty() ? (server + ":" + port) : tag;
    }

    public String protocolName() {
        if ("shadowsocks".equals(type)) {
            return method == null ? "Shadowsocks" : "SS " + method;
        }
        if ("shadowsocksr".equals(type)) {
            return "SSR";
        }
        if ("hysteria2".equals(type)) {
            return "Hysteria2";
        }
        if ("vless".equals(type) && realityPublicKey != null) {
            return "VLESS Reality";
        }
        if ("vmess".equals(type)) {
            return "VMess";
        }
        return type == null ? "?" : Character.toUpperCase(type.charAt(0)) + type.substring(1);
    }

    /** sing-box outbound object of this server (type/server/port + protocol options). */
    public Json.Obj toJson() {
        Json.Obj o = Json.obj();
        o.put("type", type);
        o.put("tag", safeTag());
        if (!isEndpoint()) {
            o.put("server", server);
            o.put("server_port", port);
        }
        switch (type == null ? "" : type) {
            case "vless":
                o.put("uuid", nullToEmpty(uuid));
                o.putIf(flow != null && !flow.isEmpty(), "flow", flow);
                o.putIf(packetEncoding != null, "packet_encoding", packetEncoding);
                break;
            case "vmess":
                o.put("uuid", nullToEmpty(uuid));
                o.put("security", security == null || security.isEmpty() ? "auto" : security);
                o.putIf(alterId > 0, "alter_id", (long) alterId);
                break;
            case "trojan":
                o.put("password", nullToEmpty(password));
                break;
            case "shadowsocks":
                o.put("method", nullToEmpty(method));
                if (is2022()) {
                    o.put("password", nullToEmpty(password));
                } else {
                    o.put("password", nullToEmpty(password));
                }
                break;
            case "shadowsocksr":
                o.put("method", nullToEmpty(method));
                o.put("password", nullToEmpty(password));
                o.putIf(ssrProtocol != null, "protocol", ssrProtocol);
                o.putIf(ssrProtocolParam != null, "protocol_param", ssrProtocolParam);
                o.putIf(ssrObfs != null, "obfs", ssrObfs);
                o.putIf(ssrObfsParam != null, "obfs_param", ssrObfsParam);
                break;
            case "shadowtls":
                o.put("version", (long) (shadowtlsVersion <= 0 ? 3 : shadowtlsVersion));
                o.put("password", nullToEmpty(shadowtlsPassword != null ? shadowtlsPassword : password));
                break;
            case "snell":
                o.put("version", (long) (snellVersion <= 0 ? 4 : snellVersion));
                o.put("psk", nullToEmpty(snellPsk != null ? snellPsk : password));
                o.putIf(snellObfsMode != null, "obfs_mode", snellObfsMode);
                o.putIf(snellObfsHost != null, "obfs_host", snellObfsHost);
                break;
            case "hysteria2":
                o.put("password", nullToEmpty(password));
                o.putIf(upMbps > 0, "up_mbps", (long) upMbps);
                o.putIf(downMbps > 0, "down_mbps", (long) downMbps);
                if (obfsType != null && !obfsType.isEmpty()) {
                    Json.Obj obfs = Json.obj().put("type", obfsType).put("password", nullToEmpty(obfsPassword));
                    o.put("obfs", obfs);
                }
                break;
            case "hysteria":
                o.put("auth_str", nullToEmpty(password));
                o.putIf(upMbps > 0, "up_mbps", (long) upMbps);
                o.putIf(downMbps > 0, "down_mbps", (long) downMbps);
                o.putIf(obfsType != null && !obfsType.isEmpty(), "obfs", obfsType);
                break;
            case "tuic":
                o.putIf(uuid != null, "uuid", uuid);
                o.putIf(password != null, "password", password);
                o.putIf(congestionControl != null, "congestion_control", congestionControl);
                o.putIf(udpRelayMode != null, "udp_relay_mode", udpRelayMode);
                break;
            case "anytls":
                o.put("password", nullToEmpty(password));
                break;
            case "naive":
                o.putIf(username != null, "username", username);
                o.putIf(password != null, "password", password);
                break;
            case "ssh":
                o.putIf(username != null, "user", username);
                o.putIf(password != null, "password", password);
                o.putIf(sshPrivateKey != null, "private_key", sshPrivateKey);
                o.putIf(sshHostKey != null, "host_key", sshHostKey);
                break;
            case "socks":
                o.putIf(username != null, "username", username);
                o.putIf(password != null, "password", password);
                break;
            case "http":
                o.putIf(username != null, "username", username);
                o.putIf(password != null, "password", password);
                o.putIf(tls, "tls", Json.obj().put("enabled", true)
                        .putIf(sni != null, "server_name", sni)
                        .putIf(insecure, "insecure", true));
                break;
            case "wireguard":
                o.put("private_key", nullToEmpty(wgPrivateKey));
                o.putIf(wgLocalAddress != null, "address", wgLocalAddress);
                o.putIf(wgMtu > 0, "mtu", (long) wgMtu);
                Json.Obj peer = Json.obj()
                        .put("address", server)
                        .put("port", (long) port)
                        .put("public_key", nullToEmpty(wgPeerPublicKey));
                if (wgPreSharedKey != null && !wgPreSharedKey.isEmpty()) {
                    peer.put("pre_shared_key", wgPreSharedKey);
                }
                if (wgAllowedIps != null && !wgAllowedIps.isEmpty()) {
                    peer.put("allowed_ips", wgAllowedIps.split(","));
                }
                if (wgKeepAlive > 0) {
                    peer.put("persistent_keepalive_interval", (long) wgKeepAlive);
                }
                if (wgReserved != null && wgReserved.length == 3) {
                    Json.Arr reserved = Json.arr();
                    for (String r : wgReserved) {
                        try {
                            reserved.add(Long.parseLong(r.trim()));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    if (!reserved.isEmpty()) {
                        peer.put("reserved", reserved);
                    }
                }
                o.put("peers", Json.arr().add(peer));
                break;
            default:
                break;
        }

        if (needsTls() && tls) {
            o.put("tls", tlsObject());
        }
        if (!"wireguard".equals(type) && network != null && !network.isEmpty() && !"tcp".equals(network)) {
            Json.Obj transport = transportObject();
            if (transport != null) {
                o.put("transport", transport);
            }
        }
        return o;
    }

    private boolean isEndpoint() {
        return "wireguard".equals(type);
    }

    private boolean is2022() {
        return method != null && (method.startsWith("2022-blake3") || method.contains("blake3"));
    }

    private boolean needsTls() {
        switch (type == null ? "" : type) {
            case "vless":
            case "vmess":
            case "trojan":
            case "hysteria2":
            case "hysteria":
            case "tuic":
            case "anytls":
            case "naive":
            case "shadowtls":
                return true;
            default:
                return false;
        }
    }

    public Json.Obj tlsObject() {
        Json.Obj tls = Json.obj();
        tls.put("enabled", true);
        // gRPC and hysteria2 always use TLS, the rest only when requested.
        switch (type == null ? "" : type) {
            case "hysteria2":
            case "hysteria":
            case "tuic":
            case "anytls":
            case "naive":
            case "shadowtls":
                tls.put("enabled", true);
                break;
            default:
                tls.put("enabled", true);
        }
        String serverName = sni;
        if ((serverName == null || serverName.isEmpty()) && !isIp(server)) {
            serverName = server;
        }
        if (serverName != null && !serverName.isEmpty()) {
            tls.put("server_name", serverName);
        }
        if (insecure) {
            tls.put("insecure", true);
        }
        if (alpn != null && alpn.length > 0) {
            tls.put("alpn", alpn);
        }
        if (clientFingerprint != null && !clientFingerprint.isEmpty()) {
            tls.put("utls", Json.obj().put("enabled", true).put("fingerprint", clientFingerprint));
        }
        if (realityPublicKey != null && !realityPublicKey.isEmpty()) {
            Json.Obj reality = Json.obj()
                    .put("enabled", true)
                    .put("public_key", realityPublicKey)
                    .putIf(realityShortId != null && !realityShortId.isEmpty(), "short_id", realityShortId);
            tls.put("reality", reality);
        }
        if (fragment) {
            tls.put("fragment", true);
        }
        if (recordFragment) {
            tls.put("record_fragment", true);
        }
        if (echConfig != null && !echConfig.isEmpty()) {
            tls.put("ech", Json.obj().put("enabled", true).put("config", echConfig.split(",")));
        }
        return tls;
    }

    private Json.Obj transportObject() {
        String net = network == null ? "" : network;
        switch (net) {
            case "ws": {
                Json.Obj ws = Json.obj().put("type", "ws");
                ws.putIf(wsPath != null && !wsPath.isEmpty(), "path", wsPath);
                if (wsHost != null && !wsHost.isEmpty()) {
                    ws.put("headers", Json.obj().put("Host", wsHost));
                }
                if (wsMaxEarlyData > 0) {
                    ws.put("max_early_data", (long) wsMaxEarlyData);
                    ws.putIf(wsEarlyDataHeader != null && !wsEarlyDataHeader.isEmpty(),
                            "early_data_header_name", wsEarlyDataHeader);
                }
                return ws;
            }
            case "grpc": {
                Json.Obj grpc = Json.obj().put("type", "grpc");
                grpc.putIf(grpcServiceName != null && !grpcServiceName.isEmpty(), "service_name", grpcServiceName);
                return grpc;
            }
            case "http":
            case "h2": {
                Json.Obj http = Json.obj().put("type", "http");
                if (httpHost != null && !httpHost.isEmpty()) {
                    http.put("host", httpHost.split(","));
                }
                http.putIf(httpPath != null && !httpPath.isEmpty(), "path", httpPath);
                http.putIf(httpMethod != null && !httpMethod.isEmpty(), "method", httpMethod);
                return http;
            }
            case "httpupgrade": {
                Json.Obj upgrade = Json.obj().put("type", "httpupgrade");
                upgrade.putIf(httpHost != null && !httpHost.isEmpty(), "host", httpHost);
                upgrade.putIf(httpPath != null && !httpPath.isEmpty(), "path", httpPath);
                return upgrade;
            }
            default:
                return null;
        }
    }

    public String safeTag() {
        if (tag == null || tag.trim().isEmpty()) {
            return server + ":" + port;
        }
        return tag.trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static boolean isIp(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        if (value.indexOf(':') >= 0) {
            return true;
        }
        boolean dot = false;
        for (char c : value.toCharArray()) {
            if (c == '.') {
                dot = true;
            } else if (!Character.isDigit(c)) {
                return false;
            }
        }
        return dot;
    }

    // ------------------------------------------------------------------ storage

    /** Serializes the outbound into the compact JSON kept in the local database. */
    public String toStorageJson() {
        com.vortex.vpn.cfg.Json.Obj o = com.vortex.vpn.cfg.Json.obj();
        o.put("type", type).put("tag", tag).put("server", server).put("port", (long) port)
                .put("uuid", uuid).put("password", password).put("username", username)
                .put("method", method).put("plugin", plugin).put("pluginOpts", pluginOpts)
                .put("flow", flow).put("security", security).put("alterId", (long) alterId)
                .put("packetEncoding", packetEncoding)
                .put("ssrProtocol", ssrProtocol).put("ssrProtocolParam", ssrProtocolParam)
                .put("ssrObfs", ssrObfs).put("ssrObfsParam", ssrObfsParam)
                .put("shadowtlsVersion", (long) shadowtlsVersion).put("shadowtlsPassword", shadowtlsPassword)
                .put("snellPsk", snellPsk).put("snellVersion", (long) snellVersion)
                .put("snellObfsMode", snellObfsMode).put("snellObfsHost", snellObfsHost)
                .put("tls", tls).put("insecure", insecure).put("sni", sni)
                .put("clientFingerprint", clientFingerprint)
                .put("realityPublicKey", realityPublicKey).put("realityShortId", realityShortId)
                .put("fragment", fragment).put("recordFragment", recordFragment).put("echConfig", echConfig)
                .put("network", network).put("wsPath", wsPath).put("wsHost", wsHost)
                .put("grpcServiceName", grpcServiceName).put("httpPath", httpPath).put("httpHost", httpHost)
                .put("wsMaxEarlyData", (long) wsMaxEarlyData).put("wsEarlyDataHeader", wsEarlyDataHeader)
                .put("httpMethod", httpMethod)
                .put("upMbps", (long) upMbps).put("downMbps", (long) downMbps)
                .put("obfsType", obfsType).put("obfsPassword", obfsPassword)
                .put("congestionControl", congestionControl).put("udpRelayMode", udpRelayMode)
                .put("portHoppingRange", portHoppingRange)
                .put("wgPrivateKey", wgPrivateKey).put("wgPeerPublicKey", wgPeerPublicKey)
                .put("wgPreSharedKey", wgPreSharedKey).put("wgLocalAddress", wgLocalAddress)
                .put("wgAllowedIps", wgAllowedIps).put("wgMtu", (long) wgMtu).put("wgKeepAlive", (long) wgKeepAlive)
                .put("sshPrivateKey", sshPrivateKey).put("sshHostKey", sshHostKey)
                .put("rawLink", rawLink).put("sourceId", sourceId).put("country", country)
                .put("sourceType", sourceType).put("warn", warn);
        if (alpn != null && alpn.length > 0) {
            o.put("alpn", alpn);
        }
        if (wgReserved != null && wgReserved.length > 0) {
            o.put("wgReserved", wgReserved);
        }
        return o.toString();
    }

    /** Restores an outbound previously written by {@link #toStorageJson()}. */
    public static Outbound fromStorageJson(String json) {
        java.util.Map<String, Object> m;
        try {
            m = com.vortex.vpn.cfg.JsonReader.parseObject(json);
        } catch (Exception e) {
            return null;
        }
        Outbound o = new Outbound();
        o.type = com.vortex.vpn.cfg.JsonReader.string(m, "type", "vless");
        o.tag = value(m, "tag");
        o.server = value(m, "server");
        o.port = com.vortex.vpn.cfg.JsonReader.integer(m, "port", 443);
        o.uuid = value(m, "uuid");
        o.password = value(m, "password");
        o.username = value(m, "username");
        o.method = value(m, "method");
        o.plugin = value(m, "plugin");
        o.pluginOpts = value(m, "pluginOpts");
        o.flow = value(m, "flow");
        o.security = value(m, "security");
        o.alterId = com.vortex.vpn.cfg.JsonReader.integer(m, "alterId", 0);
        o.packetEncoding = value(m, "packetEncoding");
        o.ssrProtocol = value(m, "ssrProtocol");
        o.ssrProtocolParam = value(m, "ssrProtocolParam");
        o.ssrObfs = value(m, "ssrObfs");
        o.ssrObfsParam = value(m, "ssrObfsParam");
        o.shadowtlsVersion = com.vortex.vpn.cfg.JsonReader.integer(m, "shadowtlsVersion", 3);
        o.shadowtlsPassword = value(m, "shadowtlsPassword");
        o.snellPsk = value(m, "snellPsk");
        o.snellVersion = com.vortex.vpn.cfg.JsonReader.integer(m, "snellVersion", 4);
        o.snellObfsMode = value(m, "snellObfsMode");
        o.snellObfsHost = value(m, "snellObfsHost");
        o.tls = com.vortex.vpn.cfg.JsonReader.bool(m, "tls", false);
        o.insecure = com.vortex.vpn.cfg.JsonReader.bool(m, "insecure", false);
        o.sni = value(m, "sni");
        o.clientFingerprint = value(m, "clientFingerprint");
        o.realityPublicKey = value(m, "realityPublicKey");
        o.realityShortId = value(m, "realityShortId");
        o.fragment = com.vortex.vpn.cfg.JsonReader.bool(m, "fragment", false);
        o.recordFragment = com.vortex.vpn.cfg.JsonReader.bool(m, "recordFragment", false);
        o.echConfig = value(m, "echConfig");
        o.network = value(m, "network");
        o.wsPath = value(m, "wsPath");
        o.wsHost = value(m, "wsHost");
        o.grpcServiceName = value(m, "grpcServiceName");
        o.httpPath = value(m, "httpPath");
        o.httpHost = value(m, "httpHost");
        o.wsMaxEarlyData = com.vortex.vpn.cfg.JsonReader.integer(m, "wsMaxEarlyData", 0);
        o.wsEarlyDataHeader = value(m, "wsEarlyDataHeader");
        o.httpMethod = value(m, "httpMethod");
        o.upMbps = com.vortex.vpn.cfg.JsonReader.integer(m, "upMbps", 0);
        o.downMbps = com.vortex.vpn.cfg.JsonReader.integer(m, "downMbps", 0);
        o.obfsType = value(m, "obfsType");
        o.obfsPassword = value(m, "obfsPassword");
        o.congestionControl = value(m, "congestionControl");
        o.udpRelayMode = value(m, "udpRelayMode");
        o.portHoppingRange = value(m, "portHoppingRange");
        o.wgPrivateKey = value(m, "wgPrivateKey");
        o.wgPeerPublicKey = value(m, "wgPeerPublicKey");
        o.wgPreSharedKey = value(m, "wgPreSharedKey");
        o.wgLocalAddress = value(m, "wgLocalAddress");
        o.wgAllowedIps = com.vortex.vpn.cfg.JsonReader.string(m, "wgAllowedIps", "0.0.0.0/0,::/0");
        o.wgMtu = com.vortex.vpn.cfg.JsonReader.integer(m, "wgMtu", 1408);
        o.wgKeepAlive = com.vortex.vpn.cfg.JsonReader.integer(m, "wgKeepAlive", 0);
        o.sshPrivateKey = value(m, "sshPrivateKey");
        o.sshHostKey = value(m, "sshHostKey");
        o.rawLink = value(m, "rawLink");
        o.sourceId = value(m, "sourceId");
        o.country = value(m, "country");
        o.sourceType = com.vortex.vpn.cfg.JsonReader.string(m, "sourceType", "link");
        o.warn = value(m, "warn");
        String alpn = value(m, "alpn");
        if (alpn != null && !alpn.isEmpty()) {
            o.alpn = alpn.split(",");
        }
        String reserved = value(m, "wgReserved");
        if (reserved != null && !reserved.isEmpty()) {
            o.wgReserved = reserved.split(",");
        }
        if (o.country == null || o.country.isEmpty()) {
            o.country = com.vortex.vpn.sub.Geo.countryCode(o.tag);
        }
        return o;
    }

    private static String value(java.util.Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v);
        return s.isEmpty() || "null".equals(s) ? null : s;
    }
}

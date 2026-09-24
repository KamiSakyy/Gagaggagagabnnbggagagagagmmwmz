package com.vortex.vpn.model;

/** A stored server: {@link Outbound} plus local database metadata. */
public class Server extends Outbound {

    public long id;
    public long subId;
    /** Latency in ms, 0 = unknown, -1 = unreachable. */
    public int ping;
    public long pingTime;
    public boolean favorite;
    public int sort;
    /** Transient flag used by the location list (never persisted). */
    public boolean selected;

    public static Server from(Outbound outbound) {
        Server server = new Server();
        server.copyFrom(outbound);
        return server;
    }

    public void copyFrom(Outbound o) {
        type = o.type;
        tag = o.tag;
        server = o.server;
        port = o.port;
        uuid = o.uuid;
        password = o.password;
        username = o.username;
        method = o.method;
        plugin = o.plugin;
        pluginOpts = o.pluginOpts;
        flow = o.flow;
        security = o.security;
        alterId = o.alterId;
        packetEncoding = o.packetEncoding;
        ssrProtocol = o.ssrProtocol;
        ssrProtocolParam = o.ssrProtocolParam;
        ssrObfs = o.ssrObfs;
        ssrObfsParam = o.ssrObfsParam;
        shadowtlsVersion = o.shadowtlsVersion;
        shadowtlsPassword = o.shadowtlsPassword;
        snellPsk = o.snellPsk;
        snellVersion = o.snellVersion;
        snellObfsMode = o.snellObfsMode;
        snellObfsHost = o.snellObfsHost;
        tls = o.tls;
        insecure = o.insecure;
        sni = o.sni;
        alpn = o.alpn;
        clientFingerprint = o.clientFingerprint;
        realityPublicKey = o.realityPublicKey;
        realityShortId = o.realityShortId;
        fragment = o.fragment;
        recordFragment = o.recordFragment;
        echConfig = o.echConfig;
        network = o.network;
        wsPath = o.wsPath;
        wsHost = o.wsHost;
        grpcServiceName = o.grpcServiceName;
        httpPath = o.httpPath;
        httpHost = o.httpHost;
        wsMaxEarlyData = o.wsMaxEarlyData;
        wsEarlyDataHeader = o.wsEarlyDataHeader;
        httpMethod = o.httpMethod;
        upMbps = o.upMbps;
        downMbps = o.downMbps;
        obfsType = o.obfsType;
        obfsPassword = o.obfsPassword;
        congestionControl = o.congestionControl;
        udpRelayMode = o.udpRelayMode;
        portHoppingRange = o.portHoppingRange;
        wgPrivateKey = o.wgPrivateKey;
        wgPeerPublicKey = o.wgPeerPublicKey;
        wgPreSharedKey = o.wgPreSharedKey;
        wgLocalAddress = o.wgLocalAddress;
        wgAllowedIps = o.wgAllowedIps;
        wgReserved = o.wgReserved;
        wgMtu = o.wgMtu;
        wgKeepAlive = o.wgKeepAlive;
        sshPrivateKey = o.sshPrivateKey;
        sshHostKey = o.sshHostKey;
        rawLink = o.rawLink;
        sourceId = o.sourceId;
        country = o.country;
        sourceType = o.sourceType;
        warn = o.warn;
    }
}

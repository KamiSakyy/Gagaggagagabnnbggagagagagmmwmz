package com.vortex.vpn.cfg;

import com.vortex.vpn.model.Outbound;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds a complete sing-box configuration (1.14 schema) for the Android TUN client.
 *
 * Pure Java: CI runs every generated sample through {@code sing-box check} to make sure the
 * schema stays valid.
 */
public final class ConfigBuilder {

    public static final String TAG_PROXY = "proxy";
    public static final String TAG_AUTO = "auto";
    public static final String TAG_DIRECT = "direct";
    public static final String TAG_DNS_DIRECT = "dns-direct";
    public static final String TAG_DNS_REMOTE = "dns-remote";
    public static final String TAG_FAKEIP = "dns-fakeip";

    private ConfigBuilder() {
    }

    public static String build(ConfigSettings s, List<Outbound> servers) {
        List<Outbound> list = new ArrayList<>();
        if (servers != null) {
            for (Outbound server : servers) {
                if (server.isSupported()) {
                    list.add(server);
                }
            }
        }
        boolean hasProxies = !list.isEmpty();
        Json.Obj root = Json.obj();
        root.put("log", log(s));
        root.put("dns", dns(s, hasProxies));
        root.put("inbounds", inbounds(s));
        root.put("outbounds", outbounds(s, list));
        if (hasWireGuard(list)) {
            root.put("endpoints", endpoints(list));
        }
        root.put("route", route(s, hasProxies));
        root.put("experimental", experimental(s));
        return root.toString();
    }

    /** Config with no servers at all - TUN stays up but everything goes direct. */
    public static String buildDirectOnly(ConfigSettings s) {
        return build(s, new ArrayList<Outbound>());
    }

    // ------------------------------------------------------------------ log
    private static Json.Obj log(ConfigSettings s) {
        return Json.obj()
                .put("level", s.logLevel == null || s.logLevel.isEmpty() ? "info" : s.logLevel)
                .put("timestamp", true);
    }

    // ------------------------------------------------------------------ dns
    private static Json.Obj dns(ConfigSettings s, boolean hasProxies) {
        Json.Arr servers = Json.arr();

        // Local resolver: used to bootstrap the proxy server host name, never proxied.
        servers.add(Json.obj()
                .put("type", "udp")
                .put("tag", TAG_DNS_DIRECT)
                .put("server", host(s.directDns))
                .putIf(port(s.directDns) > 0, "server_port", (long) port(s.directDns))
                .put("detour", TAG_DIRECT));

        // Remote resolver: protected by the tunnel, keeps DNS away from the ISP.
        if (!hasProxies) {
            // Nothing to protect the remote resolver with - stay direct.
        } else if (s.remoteDnsDoh) {
            servers.add(Json.obj()
                    .put("type", "https")
                    .put("tag", TAG_DNS_REMOTE)
                    .put("server", host(s.remoteDns))
                    .putIf(port(s.remoteDns) > 0, "server_port", (long) port(s.remoteDns))
                    .putIf(s.remoteDnsPath != null && !s.remoteDnsPath.isEmpty(), "path", s.remoteDnsPath)
                    .put("tls", Json.obj()
                            .put("enabled", true)
                            .putIf(s.remoteDnsServerName != null && !s.remoteDnsServerName.isEmpty(),
                                    "server_name", s.remoteDnsServerName))
                    .put("detour", TAG_PROXY));
        } else {
            servers.add(Json.obj()
                    .put("type", "udp")
                    .put("tag", TAG_DNS_REMOTE)
                    .put("server", host(s.remoteDns))
                    .putIf(port(s.remoteDns) > 0, "server_port", (long) port(s.remoteDns))
                    .put("detour", TAG_PROXY));
        }

        if (s.fakeIp && hasProxies) {
            Json.Obj fake = Json.obj()
                    .put("type", "fakeip")
                    .put("tag", TAG_FAKEIP)
                    .put("inet4_range", "198.18.0.0/15");
            if (s.ipv6) {
                fake.put("inet6_range", "fc00::/18");
            }
            servers.add(fake);
        }

        Json.Arr rules = Json.arr();
        if (!s.directDomains.isEmpty()) {
            rules.add(Json.obj()
                    .put("domain_suffix", s.directDomains.toArray(new String[0]))
                    .put("server", TAG_DNS_DIRECT));
        }
        if (s.fakeIp && hasProxies) {
            rules.add(Json.obj()
                    .put("query_type", new String[]{"A", "AAAA"})
                    .put("server", TAG_FAKEIP));
        }

        Json.Obj dns = Json.obj()
                .put("servers", servers)
                .put("final", hasProxies ? TAG_DNS_REMOTE : TAG_DNS_DIRECT)
                .put("strategy", s.preferIpv4 ? "prefer_ipv4" : "prefer_ipv6")
                .put("timeout", "5s");
        if (s.dnsCache) {
            dns.put("disable_cache", false);
        } else {
            dns.put("disable_cache", true);
            dns.put("disable_expire", true);
        }
        if (!rules.isEmpty()) {
            dns.put("rules", rules);
        }
        return dns;
    }

    // ------------------------------------------------------------------ inbounds
    private static Json.Arr inbounds(ConfigSettings s) {
        Json.Arr address = Json.arr().add(s.tunAddress);
        if (s.ipv6 && s.tunAddress6 != null && !s.tunAddress6.isEmpty()) {
            address.add(s.tunAddress6);
        }

        Json.Obj tun = Json.obj()
                .put("type", "tun")
                .put("tag", "tun-in")
                .put("mtu", (long) s.mtu)
                .put("address", address)
                .put("auto_route", s.autoRoute)
                .put("strict_route", s.strictRoute)
                .put("dns_mode", "hijack")
                .put("udp_timeout", "5m");
        if (s.stack != null && !s.stack.isEmpty()) {
            tun.put("stack", s.stack);
        }

        return Json.arr().add(tun);
    }

    // ------------------------------------------------------------------ outbounds

    /** The naive outbound rejects the TLS fragment option outright. */
    private static boolean supportsTlsFragment(String type) {
        return type != null && !"naive".equals(type);
    }

    private static Json.Arr outbounds(ConfigSettings s, List<Outbound> servers) {
        Json.Arr result = Json.arr();

        Set<String> used = new LinkedHashSet<>();
        used.add(TAG_PROXY);
        used.add(TAG_AUTO);
        used.add(TAG_DIRECT);

        List<String> tags = new ArrayList<>();
        List<Json.Obj> proxies = new ArrayList<>();
        for (Outbound server : servers) {
            String tag = uniqueTag(server, used);
            used.add(tag);
            server.tag = tag;
            tags.add(tag);
            if ("wireguard".equals(server.type)) {
                // wireguard is emitted through the endpoints section instead
                continue;
            }
            Json.Obj outbound = server.toJson();
            if (s.mux && supportsMultiplex(server.type)) {
                Json.Obj mux = Json.obj()
                        .put("enabled", true)
                        .put("protocol", s.muxProtocol == null ? "h2mux" : s.muxProtocol);
                if (s.muxMaxStreams > 0) {
                    mux.put("max_streams", (long) s.muxMaxStreams);
                }
                outbound.put("multiplex", mux);
            }
            if (s.tlsFragment && supportsTlsFragment(server.type) && outbound.has("tls")) {
                Json.Obj tls = outbound.object("tls");
                if (tls != null) {
                    tls.put("fragment", true);
                    if (s.recordFragment) {
                        tls.put("record_fragment", true);
                    }
                }
            }
            proxies.add(outbound);
        }

        if (!tags.isEmpty()) {
            String selected = s.selectedTag;
            if (selected == null || !tags.contains(selected)) {
                selected = tags.get(0);
            }
            if (s.autoSelect) {
                // "proxy" follows the fastest location chosen by the urltest group
                result.add(Json.obj()
                        .put("type", "selector")
                        .put("tag", TAG_PROXY)
                        .put("outbounds", new String[]{TAG_AUTO})
                        .put("default", TAG_AUTO)
                        .put("interrupt_exist_connections", false));
            } else {
                result.add(Json.obj()
                        .put("type", "selector")
                        .put("tag", TAG_PROXY)
                        .put("outbounds", tags.toArray(new String[0]))
                        .put("default", selected)
                        .put("interrupt_exist_connections", false));
            }
            result.add(Json.obj()
                    .put("type", "urltest")
                    .put("tag", TAG_AUTO)
                    .put("outbounds", tags.toArray(new String[0]))
                    .put("url", s.urlTestUrl)
                    .put("interval", s.urlTestInterval)
                    .put("tolerance", (long) s.urlTestTolerance)
                    .put("idle_timeout", "30m")
                    .put("interrupt_exist_connections", false));
        }

        for (Json.Obj proxy : proxies) {
            result.add(proxy);
        }

        // sing-box 1.12+ refuses a detour that points at an "empty" direct outbound
        // ("detour to an empty direct outbound makes no sense"), and both the local DNS server
        // and the private-network rules detour here. Giving the outbound the same resolver the
        // route already uses makes it non-empty without changing behaviour.
        result.add(Json.obj()
                .put("type", "direct")
                .put("tag", TAG_DIRECT)
                .put("domain_resolver", Json.obj().put("server", TAG_DNS_DIRECT)));
        return result;
    }

    private static boolean supportsMultiplex(String type) {
        if (type == null) {
            return false;
        }
        switch (type) {
            case "vless":
            case "vmess":
            case "trojan":
            case "shadowsocks":
                return true;
            default:
                return false;
        }
    }

    private static String uniqueTag(Outbound server, Set<String> used) {
        String base = server.safeTag();
        if (base.length() > 64) {
            base = base.substring(0, 64);
        }
        String tag = base;
        int attempt = 2;
        while (used.contains(tag)) {
            tag = base + " #" + attempt;
            attempt++;
        }
        if (tag.trim().isEmpty()) {
            tag = "server";
        }
        return tag;
    }

    private static boolean hasWireGuard(List<Outbound> servers) {
        for (Outbound server : servers) {
            if ("wireguard".equals(server.type)) {
                return true;
            }
        }
        return false;
    }

    /** WireGuard is an endpoint (not an outbound) since sing-box 1.11. */
    private static Json.Arr endpoints(List<Outbound> servers) {
        Json.Arr result = Json.arr();
        for (Outbound server : servers) {
            if (!"wireguard".equals(server.type)) {
                continue;
            }
            result.add(server.toJson());
        }
        return result;
    }

    // ------------------------------------------------------------------ route
    private static Json.Obj route(ConfigSettings s, boolean hasProxies) {
        Json.Arr rules = Json.arr();

        // sing-box 1.13+ performs sniffing through a rule action instead of inbound fields.
        if (s.sniff) {
            Json.Obj sniff = Json.obj()
                    .put("action", "sniff")
                    .put("timeout", "300ms");
            rules.add(sniff);
        }

        if (s.mode != ConfigSettings.MODE_GLOBAL) {
            rules.add(Json.obj()
                    .put("ip_is_private", true)
                    .put("outbound", TAG_DIRECT));
            rules.add(Json.obj()
                    .put("domain_suffix", new String[]{
                            ".lan", ".local", ".localdomain", ".home", ".internal", ".localhost", ".home.arpa",
                            "localhost"
                    })
                    .put("outbound", TAG_DIRECT));
        }

        if (!s.blockDomains.isEmpty()) {
            rules.add(Json.obj()
                    .put("domain_suffix", s.blockDomains.toArray(new String[0]))
                    .put("action", "reject"));
        }

        if (s.mode != ConfigSettings.MODE_GLOBAL && !s.directDomains.isEmpty()) {
            rules.add(Json.obj()
                    .put("domain_suffix", s.directDomains.toArray(new String[0]))
                    .put("outbound", TAG_DIRECT));
        }

        // Local network discovery traffic must not be dragged into the tunnel.
        rules.add(Json.obj()
                .put("ip_cidr", new String[]{"0.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16"})
                .put("outbound", TAG_DIRECT));

        return Json.obj()
                .put("rules", rules)
                .put("final", hasProxies ? TAG_PROXY : TAG_DIRECT)
                .put("auto_detect_interface", true)
                .put("default_domain_resolver", Json.obj().put("server", TAG_DNS_DIRECT));
    }

    private static Json.Obj experimental(ConfigSettings s) {
        return Json.obj()
                .put("cache_file", Json.obj()
                        .put("enabled", true)
                        .put("store_fakeip", s.fakeIp));
    }

    // ------------------------------------------------------------------ helpers
    static String host(String address) {
        if (address == null || address.isEmpty()) {
            return "1.1.1.1";
        }
        int index = address.lastIndexOf(':');
        if (index > 0 && address.indexOf('[') < 0 && address.indexOf(':') == index) {
            return address.substring(0, index);
        }
        return address;
    }

    static int port(String address) {
        if (address == null) {
            return 0;
        }
        int index = address.lastIndexOf(':');
        if (index > 0 && address.indexOf('[') < 0 && address.indexOf(':') == index) {
            try {
                return Integer.parseInt(address.substring(index + 1));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }
}

package com.vortex.vpn.cfg;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Plain settings object passed to {@link ConfigBuilder}.
 *
 * Pure Java (no android imports) so CI can build configs on a plain JVM and validate
 * them with the real sing-box binary.
 */
public class ConfigSettings {

    // ---- routing modes -----------------------------------------------------
    public static final int MODE_GLOBAL = 0;   // everything through the proxy
    public static final int MODE_SMART = 1;    // LAN / custom domains direct, rest proxied
    public static final int MODE_BYPASS = 2;   // only the selected apps are proxied

    // ---- tun ---------------------------------------------------------------
    public String tunAddress = "172.19.0.1/30";
    public String tunAddress6 = "fdfe:dcba:9876::1/126";
    public int mtu = 9000;
    public String stack = "mixed";
    public boolean autoRoute = true;
    public boolean strictRoute = false;
    public boolean ipv6 = true;
    public boolean allowBypass = false;
    public boolean sniff = true;
    public boolean sniffOverrideDestination = false;

    // ---- dns ---------------------------------------------------------------
    public String directDns = "1.1.1.1";
    public String remoteDns = "1.1.1.1";
    public boolean remoteDnsDoh = true;
    public String remoteDnsServerName = "cloudflare-dns.com";
    public String remoteDnsPath = "/dns-query";
    public boolean preferIpv4 = true;
    public boolean fakeIp = false;
    public boolean dnsCache = true;

    // ---- logs --------------------------------------------------------------
    public String logLevel = "info";

    // ---- routing -----------------------------------------------------------
    public int mode = MODE_SMART;
    public final Set<String> directDomains = new LinkedHashSet<>();
    public final Set<String> blockDomains = new LinkedHashSet<>();
    public String urlTestUrl = "http://cp.cloudflare.com/generate_204";
    /** 10 minutes: probing costs the user's own traffic, so it must stay rare. */
    public String urlTestInterval = "10m";
    public int urlTestTolerance = 50;

    // ---- transport hardening ----------------------------------------------
    public boolean tlsFragment = false;
    public boolean recordFragment = false;

    // ---- multiplex ---------------------------------------------------------
    public boolean mux = false;
    public String muxProtocol = "h2mux";
    public int muxMaxStreams = 0;

    // ---- per-app proxy (applied through OverrideOptions as well) -----------
    public boolean perAppEnabled = false;
    public boolean perAppInclude = true;
    public final List<String> perAppPackages = new ArrayList<>();

    // ---- selection ---------------------------------------------------------
    /** Tag of the outbound that owns the traffic when no explicit selection is made. */
    public String selectedTag = "";
    /** When true the "auto" URL-test group is preferred over the manual selection. */
    public boolean autoSelect = false;

    public ConfigSettings copy() {
        ConfigSettings c = new ConfigSettings();
        c.tunAddress = tunAddress;
        c.tunAddress6 = tunAddress6;
        c.mtu = mtu;
        c.stack = stack;
        c.autoRoute = autoRoute;
        c.strictRoute = strictRoute;
        c.ipv6 = ipv6;
        c.allowBypass = allowBypass;
        c.sniff = sniff;
        c.sniffOverrideDestination = sniffOverrideDestination;
        c.directDns = directDns;
        c.remoteDns = remoteDns;
        c.remoteDnsDoh = remoteDnsDoh;
        c.remoteDnsServerName = remoteDnsServerName;
        c.remoteDnsPath = remoteDnsPath;
        c.preferIpv4 = preferIpv4;
        c.fakeIp = fakeIp;
        c.dnsCache = dnsCache;
        c.logLevel = logLevel;
        c.mode = mode;
        c.directDomains.addAll(directDomains);
        c.blockDomains.addAll(blockDomains);
        c.urlTestUrl = urlTestUrl;
        c.urlTestInterval = urlTestInterval;
        c.urlTestTolerance = urlTestTolerance;
        c.tlsFragment = tlsFragment;
        c.recordFragment = recordFragment;
        c.mux = mux;
        c.muxProtocol = muxProtocol;
        c.muxMaxStreams = muxMaxStreams;
        c.perAppEnabled = perAppEnabled;
        c.perAppInclude = perAppInclude;
        c.perAppPackages.addAll(perAppPackages);
        c.selectedTag = selectedTag;
        c.autoSelect = autoSelect;
        return c;
    }
}

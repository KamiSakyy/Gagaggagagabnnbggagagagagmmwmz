package com.vortex.vpn;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.vortex.vpn.cfg.ConfigBuilder;
import com.vortex.vpn.cfg.ConfigSettings;
import com.vortex.vpn.cfg.JsonReader;
import com.vortex.vpn.cfg.SampleServers;
import com.vortex.vpn.model.Outbound;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Guards the sing-box rules that are only enforced when the tunnel actually starts.
 *
 * <p>{@code sing-box check} accepts a configuration that {@code sing-box run} then rejects - for
 * example sing-box 1.12+ refuses any detour that points at an "empty" direct outbound
 * ({@code detour to an empty direct outbound makes no sense}), which is exactly what made the
 * connect button fail on a real phone. Every case the app can build is verified here.</p>
 */
public class ConfigSanityTest {

    /** Dialer fields that make a direct outbound non-empty for sing-box. */
    private static final List<String> DIALER_FIELDS = Arrays.asList(
            "domain_resolver", "detour", "bind_interface", "inet4_bind_address", "inet6_bind_address",
            "routing_mark", "reuse_addr", "connect_timeout", "tcp_fast_open", "tcp_multipath",
            "udp_fragment", "domain_strategy", "fallback_delay");

    @Test
    public void everyGeneratedConfigurationCanActuallyStart() {
        for (Map.Entry<String, String> config : configurations().entrySet()) {
            String name = config.getKey();
            Map<String, Object> root = JsonReader.parseObject(config.getValue());
            assertNotNull(name + ": the configuration is not a JSON object", root);

            Set<String> outboundTags = new HashSet<>();
            for (Object value : list(root.get("outbounds"))) {
                outboundTags.add(String.valueOf(map(value).get("tag")));
            }
            for (Object value : list(root.get("endpoints"))) {
                outboundTags.add(String.valueOf(map(value).get("tag")));
            }

            // 1. a detour must never point at a direct outbound that carries no options
            for (Object value : list(root.get("outbounds"))) {
                Map<String, Object> outbound = map(value);
                if (!"direct".equals(outbound.get("type"))) {
                    continue;
                }
                boolean configured = false;
                for (String field : DIALER_FIELDS) {
                    if (outbound.get(field) != null) {
                        configured = true;
                    }
                }
                assertTrue(name + " / 'detour to an empty direct outbound makes no sense': "
                        + "the direct outbound has no options, sing-box 1.14 refuses to start",
                        configured);
            }

            // 2. every detour of every DNS server must resolve to a real outbound
            Map<String, Object> dns = map(root.get("dns"));
            Set<String> dnsTags = new HashSet<>();
            for (Object value : list(dns.get("servers"))) {
                Map<String, Object> server = map(value);
                dnsTags.add(String.valueOf(server.get("tag")));
                Object detour = server.get("detour");
                if (detour != null) {
                    assertTrue(name + ": the DNS server " + server.get("tag")
                            + " detours to the unknown outbound '" + detour + "'",
                            outboundTags.contains(String.valueOf(detour)));
                }
            }

            // 3. every route rule and the final outbound must exist as well
            Map<String, Object> route = map(root.get("route"));
            Object finalOutbound = route.get("final");
            if (finalOutbound != null) {
                assertTrue(name + ": route.final points at the unknown outbound " + finalOutbound,
                        outboundTags.contains(String.valueOf(finalOutbound)));
            }
            for (Object value : list(route.get("rules"))) {
                Map<String, Object> rule = map(value);
                Object outbound = rule.get("outbound");
                if (outbound != null) {
                    assertTrue(name + ": a route rule points at the unknown outbound " + outbound,
                            outboundTags.contains(String.valueOf(outbound)));
                }
            }

            // 4. the default resolver must reference an existing DNS server
            Map<String, Object> resolver = map(route.get("default_domain_resolver"));
            if (resolver != null && resolver.get("server") != null) {
                assertTrue(name + ": default_domain_resolver points at the unknown DNS server "
                                + resolver.get("server"),
                        dnsTags.contains(String.valueOf(resolver.get("server"))));
            }
        }
    }

    /**
     * Probing every location costs the user's own traffic, so it must only happen when automatic
     * selection is switched on - and rarely.
     */
    @Test
    public void nothingProbesTheLocationsInTheBackgroundUnlessAsked() {
        List<Outbound> servers = SampleServers.all();

        ConfigSettings manual = new ConfigSettings();
        manual.autoSelect = false;
        String withoutProbing = ConfigBuilder.build(manual, servers);
        assertFalse("the manual configuration probes the locations in the background",
                withoutProbing.contains("\"urltest\""));

        ConfigSettings auto = new ConfigSettings();
        auto.autoSelect = true;
        auto.urlTestInterval = "10m";
        String withProbing = ConfigBuilder.build(auto, servers);
        assertTrue("automatic selection needs the url test group",
                withProbing.contains("\"urltest\""));
        assertTrue("the probing interval is too aggressive: " + withProbing.contains("3m"),
                withProbing.contains("\"interval\":\"10m\""));
    }

    @Test
    public void theConfigurationDoesNotGrowSurprises() {
        // the tunnel must never be able to fall through to "no outbound at all"
        for (Map.Entry<String, String> config : configurations().entrySet()) {
            Map<String, Object> root = JsonReader.parseObject(config.getValue());
            assertFalse(config.getKey() + ": the configuration has no outbounds",
                    list(root.get("outbounds")).isEmpty());
        }
    }

    /** Every configuration the app can produce, as JSON text. */
    private static java.util.LinkedHashMap<String, String> configurations() {
        java.util.LinkedHashMap<String, String> result = new java.util.LinkedHashMap<>();
        List<Outbound> servers = SampleServers.all();
        for (int mode = ConfigSettings.MODE_GLOBAL; mode <= ConfigSettings.MODE_BYPASS; mode++) {
            for (String stack : Arrays.asList("mixed", "gvisor", "system")) {
                ConfigSettings settings = new ConfigSettings();
                settings.mode = mode;
                settings.stack = stack;
                result.put("mode" + mode + "-" + stack, ConfigBuilder.build(settings, servers));
            }
        }

        ConfigSettings hardened = new ConfigSettings();
        hardened.fakeIp = true;
        hardened.mux = true;
        hardened.tlsFragment = true;
        hardened.recordFragment = true;
        hardened.remoteDnsDoh = true;
        hardened.directDomains.addAll(Arrays.asList("example.com", "domain:ru", "geoip:ru"));
        hardened.blockDomains.addAll(Arrays.asList("ads.example.org", "regexp:.*\\.tracker\\.net"));
        result.put("hardened", ConfigBuilder.build(hardened, servers));

        ConfigSettings minimal = new ConfigSettings();
        minimal.ipv6 = false;
        minimal.remoteDnsDoh = false;
        minimal.dnsCache = false;
        result.put("minimal", ConfigBuilder.build(minimal, servers));

        ConfigSettings empty = new ConfigSettings();
        result.put("direct-only", ConfigBuilder.buildDirectOnly(empty));

        ConfigSettings perApp = new ConfigSettings();
        perApp.perAppEnabled = true;
        perApp.perAppPackages.add("com.example.app");
        result.put("per-app", ConfigBuilder.build(perApp, servers));

        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new java.util.LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value instanceof List ? (List<Object>) value : new ArrayList<>();
    }
}

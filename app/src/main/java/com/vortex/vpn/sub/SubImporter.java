package com.vortex.vpn.sub;

import com.vortex.vpn.cfg.JsonReader;
import com.vortex.vpn.model.Outbound;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Subscription content sniffing + parsing:
 * plain share links, base64 encoded link lists, Clash YAML and full sing-box JSON configs.
 */
public final class SubImporter {

    public static final String KIND_LINKS = "links";
    public static final String KIND_CLASH = "clash";
    public static final String KIND_CONFIG = "config";

    private static final Pattern LINK_PATTERN = Pattern.compile(
            "(?:vless|vmess|trojan-go|trojan|ssr|ss|shadowtls|snell|hysteria2|hy2|hysteria|tuic|anytls|naive\\+https|naive|socks5|socks|sk5|wireguard|wg|ssh)://[^\\s\"'<>\\\\]+",
            Pattern.CASE_INSENSITIVE);

    public static final class Result {
        public final List<Outbound> servers = new ArrayList<>();
        public String kind = KIND_LINKS;
        public String rawConfig;
        public int skipped;

        public boolean isConfig() {
            return KIND_CONFIG.equals(kind);
        }
    }

    private SubImporter() {
    }

    public static Result parse(String content) {
        Result result = new Result();
        if (content == null) {
            return result;
        }
        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            return result;
        }

        if (looksLikeSingBoxConfig(trimmed)) {
            result.kind = KIND_CONFIG;
            result.rawConfig = trimmed;
            return result;
        }

        if (looksLikeClash(trimmed)) {
            result.kind = KIND_CLASH;
            List<Outbound> servers = ClashParser.parse(trimmed);
            if (!servers.isEmpty()) {
                result.servers.addAll(servers);
                return result;
            }
            result.kind = KIND_LINKS;
        }

        // Plain links or base64 wrapped links.
        List<String> links = extractLinks(trimmed);
        if (links.isEmpty() && B64.looksBase64(trimmed.replace("\n", "").replace("\r", ""))) {
            String decoded = B64.decodeToString(trimmed.replace("\n", "").replace("\r", "").replace(" ", ""));
            links = extractLinks(decoded);
        }
        if (links.isEmpty()) {
            String decoded = B64.decodeToString(trimmed);
            links = extractLinks(decoded);
        }

        for (String link : links) {
            Outbound outbound = LinkParser.parse(link);
            if (outbound == null) {
                result.skipped++;
                continue;
            }
            if (outbound.country == null || outbound.country.isEmpty()) {
                outbound.country = Geo.countryCode(outbound.tag);
            }
            if (outbound.tag == null || outbound.tag.trim().isEmpty()) {
                String cc = outbound.country == null ? "" : outbound.country;
                outbound.tag = (cc.isEmpty() ? "" : Geo.flag(cc) + " ") + outbound.server + ":" + outbound.port;
            }
            result.servers.add(outbound);
        }
        result.kind = KIND_LINKS;
        return result;
    }

    private static boolean looksLikeSingBoxConfig(String content) {
        if (!content.startsWith("{")) {
            return false;
        }
        try {
            Map<String, Object> map = JsonReader.parseObject(content);
            return map.containsKey("outbounds") || map.containsKey("inbounds") || map.containsKey("route");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean looksLikeClash(String content) {
        String lower = content.toLowerCase(Locale.ROOT);
        if (lower.contains("proxies:") && (lower.contains("proxy-groups:") || lower.contains("rules:")
                || lower.contains("mixed-port:") || lower.contains("port:") || lower.contains("type: "))) {
            return true;
        }
        return lower.startsWith("proxies:");
    }

    public static List<String> extractLinks(String content) {
        List<String> links = new ArrayList<>();
        if (content == null) {
            return links;
        }
        Matcher matcher = LINK_PATTERN.matcher(content);
        while (matcher.find()) {
            String link = matcher.group().trim();
            // Trailing punctuation that is not part of the link.
            while (!link.isEmpty()) {
                char last = link.charAt(link.length() - 1);
                if (last == ',' || last == ';' || last == ')' || last == ']' || last == '}') {
                    link = link.substring(0, link.length() - 1);
                } else {
                    break;
                }
            }
            if (link.length() > 12) {
                links.add(link);
            }
        }
        return links;
    }

    /** Removes duplicates inside one payload (same protocol + endpoint + secret). */
    public static List<Outbound> dedupe(List<Outbound> servers) {
        List<Outbound> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Outbound server : servers) {
            String key = fingerprint(server);
            if (seen.add(key)) {
                result.add(server);
            }
        }
        return result;
    }

    public static String fingerprint(Outbound o) {
        String secret = o.uuid != null ? o.uuid : (o.password != null ? o.password : (o.wgPeerPublicKey != null ? o.wgPeerPublicKey : ""));
        String key = (o.type == null ? "" : o.type) + "|" + (o.server == null ? "" : o.server.toLowerCase(Locale.ROOT))
                + "|" + o.port + "|" + secret;
        return key;
    }

    /** Groups servers by detected country, keeping the original order. */
    public static Map<String, List<Outbound>> groupByCountry(List<Outbound> servers) {
        Map<String, List<Outbound>> groups = new LinkedHashMap<>();
        for (Outbound server : servers) {
            String key = server.country == null || server.country.isEmpty() ? "??" : server.country;
            List<Outbound> list = groups.get(key);
            if (list == null) {
                list = new ArrayList<>();
                groups.put(key, list);
            }
            list.add(server);
        }
        return groups;
    }
}

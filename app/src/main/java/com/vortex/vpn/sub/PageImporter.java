package com.vortex.vpn.sub;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the real subscription URL behind a provider's landing page.
 *
 * <p>Many providers hand out a human page (HTML) with a "copy subscription" button instead of the
 * raw list of servers. The page is not a subscription, so the client has to dig the machine
 * readable link out of it: links, data attributes, JavaScript strings, deep links
 * ({@code v2rayng://}, {@code hiddify://}, {@code sing-box://}, ...) and finally the well known
 * panel paths built from the token that is already in the page address
 * ({@code /sub/TOKEN} is what Marzban, 3x-ui, Remnawave and most bots use).</p>
 *
 * <p>Pure Java: no Android or network code here, so it is covered by unit tests and by CI.</p>
 */
public final class PageImporter {

    /** Deep links that wrap a subscription URL. */
    private static final String[] IMPORT_SCHEMES = {
            "v2rayng", "v2ray", "hiddify", "sing-box", "singbox", "clash", "clashmeta", "clash-verge",
            "stash", "shadowrocket", "incy", "streisand", "nekoray", "throne", "koala", "karing",
            "happ", "v2box", "sfa", "sfi", "sft", "sub", "sub-link",
    };

    /** Path shapes that panels use for the machine readable profile. */
    private static final String[] SUB_PATHS = {
            "/sub/%s", "/api/sub/%s", "/api/v1/sub/%s", "/api/v1/client/subscribe?token=%s",
            "/api/client/subscribe?token=%s", "/subscribe/%s", "/sub/link/%s", "/link/%s",
            "/sub/%s/base64", "/sub/%s/clash", "/sub/%s/singbox", "/%s/sub", "/s/%s",
            "/sub?token=%s", "/api/sub?token=%s", "/clash/%s", "/singbox/%s",
    };

    private static final Pattern URL_PATTERN =
            Pattern.compile("https?://[^\\s\"'<>()\\\\]{4,400}", Pattern.CASE_INSENSITIVE);
    private static final Pattern UUID_OR_TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9_-])([A-Za-z0-9_-]{8,64})(?![A-Za-z0-9_-])");

    private PageImporter() {
    }

    /** One candidate address plus the reason it was suggested (shown in the UI/log). */
    public static final class Candidate {
        public final String url;
        public final String reason;

        Candidate(String url, String reason) {
            this.url = url;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return url + " (" + reason + ")";
        }
    }

    /** Cheap check: is this a web page rather than a subscription payload? */
    public static boolean looksLikeHtml(String content) {
        if (content == null) {
            return false;
        }
        String head = content.substring(0, Math.min(content.length(), 2048)).toLowerCase(Locale.ROOT);
        return head.contains("<!doctype html") || head.contains("<html") || head.contains("<head")
                || head.contains("<body") || head.contains("<meta ") || head.contains("<script")
                || head.contains("<div") || head.contains("<button") || head.contains("<a href");
    }

    /** Subscription URLs hidden in deep links, e.g. {@code v2rayng://install-config?url=...}. */
    public static List<String> deepLinks(String content) {
        List<String> result = new ArrayList<>();
        if (content == null) {
            return result;
        }
        for (String scheme : IMPORT_SCHEMES) {
            Pattern pattern = Pattern.compile(scheme + "://[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(content);
            while (matcher.find()) {
                String inner = unwrap(matcher.group());
                if (inner != null && !result.contains(inner)) {
                    result.add(inner);
                }
            }
        }
        return result;
    }

    /** Pulls the http(s) URL out of an import deep link. */
    static String unwrap(String deepLink) {
        if (deepLink == null) {
            return null;
        }
        String link = deepLink.trim();
        int query = link.indexOf('?');
        if (query > 0) {
            for (String pair : link.substring(query + 1).split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = pair.substring(0, eq).toLowerCase(Locale.ROOT);
                String value = pair.substring(eq + 1);
                if ("url".equals(key) || "link".equals(key) || "sub".equals(key)
                        || "profile".equals(key) || "config".equals(key) || "path".equals(key)) {
                    String decoded = urlDecode(value);
                    if (decoded.startsWith("http")) {
                        return decoded;
                    }
                }
            }
        }
        // v2rayng://install-sub?<url>  /  hiddify://import/https://example.com/sub
        String rest = link.substring(link.indexOf("://") + 3);
        int slash = rest.indexOf('/');
        String tail = slash >= 0 ? rest.substring(slash + 1) : "";
        String decoded = urlDecode(tail);
        if (decoded.startsWith("http")) {
            return decoded;
        }
        int http = link.indexOf("http", link.indexOf("://"));
        if (http > 0) {
            return link.substring(http);
        }
        return null;
    }

    private static String urlDecode(String value) {
        String result = value.replace("%3A", ":").replace("%3a", ":")
                .replace("%2F", "/").replace("%2f", "/")
                .replace("%3F", "?").replace("%3f", "?")
                .replace("%3D", "=").replace("%3d", "=")
                .replace("%26", "&");
        return result.startsWith("//") ? "https:" + result : result;
    }

    /**
     * Candidate subscription addresses for a pasted address + the page body, best first.
     * The order is what the importer tries: deep links, then links found in the page,
     * then panel paths derived from the token of the pasted address.
     */
    public static List<Candidate> candidates(String pastedUrl, String pageBody) {
        List<Candidate> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String body = pageBody == null ? "" : pageBody;

        for (String inner : deepLinks(body)) {
            add(result, seen, inner, "ссылка из deep-link");
        }

        String host = host(pastedUrl);
        List<String> found = new ArrayList<>();
        if (!body.isEmpty()) {
            Matcher matcher = URL_PATTERN.matcher(body);
            while (matcher.find()) {
                String url = clean(matcher.group());
                if (url != null) {
                    found.add(url);
                }
            }
        }
        // Same host first: another page of the same provider is usually the machine readable one.
        List<String> ordered = new ArrayList<>();
        for (String url : found) {
            if (host != null && host.equalsIgnoreCase(host(url))) {
                ordered.add(url);
            }
        }
        for (String url : found) {
            if (!ordered.contains(url)) {
                ordered.add(url);
            }
        }
        for (String url : ordered) {
            add(result, seen, url, "ссылка со страницы");
        }

        // Panel paths derived from the token that is already in the pasted address.
        String base = origin(pastedUrl);
        String token = token(pastedUrl);
        if (base != null && token != null) {
            for (String shape : SUB_PATHS) {
                add(result, seen, base + String.format(Locale.ROOT, shape, token), "адрес панели подписки");
            }
        }
        // Long tokens found on the page may be the profile id instead of the page id.
        if (base != null && !body.isEmpty()) {
            Matcher matcher = UUID_OR_TOKEN.matcher(stripTags(body));
            int added = 0;
            while (matcher.find() && added < 6) {
                String candidate = matcher.group(1);
                if (candidate.equals(token) || candidate.length() < 12) {
                    continue;
                }
                add(result, seen, base + "/sub/" + candidate, "токен со страницы");
                added++;
            }
        }
        return result;
    }

    /** Panel paths for the token of the given address, without touching the network. */
    public static List<String> panelPaths(String pastedUrl) {
        List<String> result = new ArrayList<>();
        String base = origin(pastedUrl);
        String token = token(pastedUrl);
        if (base == null || token == null) {
            return result;
        }
        for (String shape : SUB_PATHS) {
            result.add(base + String.format(Locale.ROOT, shape, token));
        }
        return result;
    }

    private static void add(List<Candidate> list, Set<String> seen, String url, String reason) {
        if (url == null) {
            return;
        }
        String trimmed = url.trim();
        if (trimmed.length() < 12 || !trimmed.startsWith("http")) {
            return;
        }
        if (seen.add(trimmed)) {
            list.add(new Candidate(trimmed, reason));
        }
    }

    private static String clean(String raw) {
        String url = raw.trim();
        while (!url.isEmpty()) {
            char last = url.charAt(url.length() - 1);
            if (last == ',' || last == ';' || last == ')' || last == ']' || last == '}'
                    || last == '.' || last == '\\' || last == '"' || last == '\'') {
                url = url.substring(0, url.length() - 1);
            } else {
                break;
            }
        }
        // HTML entities are common in attribute values.
        url = url.replace("&amp;", "&").replace("&#38;", "&").replace("\\/", "/");
        return url;
    }

    /** Removes tags so that token scanning does not pick up markup. */
    private static String stripTags(String html) {
        return html.replaceAll("<[^>]{0,400}>", " ").replaceAll("[^A-Za-z0-9_\\-./:?=&%]", " ");
    }

    public static String origin(String url) {
        try {
            java.net.URL parsed = new java.net.URL(url);
            String protocol = parsed.getProtocol();
            String host = parsed.getHost();
            if (host == null || host.isEmpty()) {
                return null;
            }
            int port = parsed.getPort();
            return protocol + "://" + host + (port > 0 ? ":" + port : "");
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String host(String url) {
        try {
            return new java.net.URL(url).getHost();
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Last meaningful path segment: the profile token in most panels. */
    public static String token(String url) {
        try {
            String path = new java.net.URL(url).getPath();
            if (path == null || path.isEmpty()) {
                return null;
            }
            String[] parts = path.split("/");
            for (int i = parts.length - 1; i >= 0; i--) {
                String part = parts[i].trim();
                if (part.isEmpty() || "sub".equalsIgnoreCase(part) || "index.html".equalsIgnoreCase(part)
                        || "api".equalsIgnoreCase(part)) {
                    continue;
                }
                return part;
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}

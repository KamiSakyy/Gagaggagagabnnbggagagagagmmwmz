package com.vortex.vpn.sub;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Subscription downloader built on {@link HttpURLConnection} - no extra dependencies,
 * small APK, follows redirects and reports the provider's response headers
 * (traffic counters, update interval, web page).
 */
public final class SubFetcher {

    public static final String DEFAULT_USER_AGENT =
            "VortexVPN/1.0 (Android) sing-box/1.14";

    public static final class Response {
        public int code;
        public String body = "";
        public String finalUrl;
        public final Map<String, String> headers = new LinkedHashMap<>();
        public String error;

        public boolean isOk() {
            return code >= 200 && code < 300 && error == null;
        }
    }

    private SubFetcher() {
    }

    public static Response fetch(String url) {
        return fetch(url, DEFAULT_USER_AGENT, null, null);
    }

    public static Response fetch(String url, String userAgent, String hwid, Map<String, String> extraHeaders) {
        Response response = new Response();
        HttpURLConnection connection = null;
        try {
            String current = url;
            int redirects = 0;
            while (true) {
                if (redirects++ > 6) {
                    response.error = "too many redirects";
                    return response;
                }
                connection = (HttpURLConnection) new URL(current).openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(25000);
                connection.setRequestProperty("User-Agent", userAgent == null ? DEFAULT_USER_AGENT : userAgent);
                connection.setRequestProperty("Accept", "*/*");
                connection.setRequestProperty("Accept-Encoding", "gzip");
                connection.setRequestProperty("Cache-Control", "no-cache");
                if (hwid != null && !hwid.isEmpty()) {
                    connection.setRequestProperty("x-hwid", hwid);
                    connection.setRequestProperty("x-device-id", hwid);
                }
                if (extraHeaders != null) {
                    for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
                        if (header.getKey() == null || header.getKey().isEmpty()) {
                            continue;
                        }
                        connection.setRequestProperty(header.getKey(), header.getValue() == null ? "" : header.getValue());
                    }
                }
                int code = connection.getResponseCode();
                response.code = code;
                response.finalUrl = connection.getURL().toString();
                response.headers.clear();
                for (Map.Entry<String, java.util.List<String>> entry : connection.getHeaderFields().entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isEmpty()) {
                        continue;
                    }
                    response.headers.put(entry.getKey().toLowerCase(java.util.Locale.ROOT), entry.getValue().get(0));
                }
                if (code == HttpURLConnection.HTTP_MOVED_PERM
                        || code == HttpURLConnection.HTTP_MOVED_TEMP
                        || code == HttpURLConnection.HTTP_SEE_OTHER
                        || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    connection.disconnect();
                    if (location == null || location.isEmpty()) {
                        response.error = "redirect without location";
                        return response;
                    }
                    current = location.startsWith("http") ? location : new URL(new URL(current), location).toString();
                    continue;
                }
                InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
                if (stream == null) {
                    response.body = "";
                    return response;
                }
                String encoding = connection.getContentEncoding();
                if (encoding != null && encoding.toLowerCase(java.util.Locale.ROOT).contains("gzip")) {
                    stream = new GZIPInputStream(stream);
                }
                response.body = readAll(stream);
                return response;
            }
        } catch (Exception e) {
            response.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            return response;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(16384);
            byte[] chunk = new byte[8192];
            int read;
            int total = 0;
            while ((read = stream.read(chunk)) > 0) {
                total += read;
                if (total > 16 * 1024 * 1024) {
                    break;
                }
                buffer.write(chunk, 0, read);
            }
            String text = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == 0xFEFF) {
                text = text.substring(1);
            }
            return text;
        } finally {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
    }
}

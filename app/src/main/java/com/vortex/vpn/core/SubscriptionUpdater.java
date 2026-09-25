package com.vortex.vpn.core;

import android.content.Context;
import android.text.TextUtils;

import com.vortex.vpn.Prefs;
import com.vortex.vpn.db.Repo;
import com.vortex.vpn.model.Outbound;
import com.vortex.vpn.model.Server;
import com.vortex.vpn.model.Subscription;
import com.vortex.vpn.sub.B64;
import com.vortex.vpn.sub.SubFetcher;
import com.vortex.vpn.sub.SubImporter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Downloads, parses and stores subscriptions (and one-off share links). */
public final class SubscriptionUpdater {

    public static final class Result {
        public boolean ok;
        public String message = "";
        public int imported;
        public String kind = SubImporter.KIND_LINKS;
    }

    private SubscriptionUpdater() {
    }

    /** Refreshes one subscription (network access - call from a worker thread). */
    public static Result refresh(Context context, long subscriptionId) {
        Result result = new Result();
        Subscription subscription = Repo.subscription(context, subscriptionId);
        if (subscription == null) {
            result.message = "подписка не найдена";
            return result;
        }
        if (TextUtils.isEmpty(subscription.url)) {
            result.message = "у подписки нет ссылки";
            return result;
        }
        String userAgent = Prefs.userAgent();
        SubFetcher.Response response = SubFetcher.fetch(subscription.url,
                TextUtils.isEmpty(userAgent) ? SubFetcher.DEFAULT_USER_AGENT : userAgent,
                Prefs.hwid(), null);
        if (!response.isOk()) {
            subscription.lastError = response.error == null
                    ? ("HTTP " + response.code) : response.error;
            Repo.updateSubscription(context, subscription);
            result.message = subscription.lastError;
            Bridge.appendLog("E", "подписка «" + subscription.name + "»: " + result.message);
            return result;
        }
        subscription.lastError = "";
        subscription.lastUpdate = System.currentTimeMillis();
        applyHeaders(subscription, response.headers);
        Result parsed = store(context, subscription, response.body);
        result.ok = parsed.ok;
        result.message = parsed.message;
        result.imported = parsed.imported;
        result.kind = parsed.kind;
        return result;
    }

    /** Imports raw content (subscription body or a list of share links) into a profile. */
    public static Result store(Context context, Subscription subscription, String content) {
        Result result = new Result();
        SubImporter.Result parsed = SubImporter.parse(content);
        subscription.kind = parsed.kind;
        result.kind = parsed.kind;

        if (parsed.isConfig()) {
            subscription.rawConfig = parsed.rawConfig;
            Repo.updateSubscription(context, subscription);
            Repo.replaceSubscriptionServers(context, subscription.id, new ArrayList<Server>());
            result.ok = true;
            result.message = "импортирован конфиг sing-box";
            Bridge.appendLog("I", "«" + subscription.name + "»: " + result.message);
            return result;
        }

        subscription.rawConfig = null;
        List<Outbound> unique = SubImporter.dedupe(parsed.servers);
        List<Server> servers = new ArrayList<>();
        for (Outbound outbound : unique) {
            serverFrom(outbound, subscription);
            servers.add((Server) outbound);
        }
        Repo.updateSubscription(context, subscription);
        Repo.replaceSubscriptionServers(context, subscription.id, servers);

        int usable = 0;
        for (Server server : servers) {
            if (server.isSupported()) {
                usable++;
            }
        }
        result.ok = usable > 0;
        result.imported = usable;
        result.message = servers.isEmpty()
                ? "не найдено ни одной локации"
                : (usable == servers.size()
                        ? ("локаций: " + servers.size())
                        : ("локаций: " + usable + " (не поддерживается: " + (servers.size() - usable) + ")"));
        Bridge.appendLog(result.ok ? "I" : "W", "«" + subscription.name + "»: " + result.message
                + (parsed.skipped > 0 ? " (пропущено: " + parsed.skipped + ")" : ""));

        // Make sure something is selected after the first import.
        if (!servers.isEmpty() && Prefs.selectedFp().isEmpty()) {
            selectFirst(context, servers.get(0));
        }
        return result;
    }

    private static void serverFrom(Outbound outbound, Subscription subscription) {
        Server server = (Server) outbound;
        server.subId = subscription.id;
        server.sourceType = subscription.kind;
        server.sourceId = String.valueOf(subscription.id);
        if (server.country == null || server.country.isEmpty()) {
            server.country = com.vortex.vpn.sub.Geo.countryCode(server.tag);
        }
    }

    private static void selectFirst(Context context, Server server) {
        String fingerprint = SubImporter.fingerprint(server);
        Prefs.setSelectedFp(fingerprint);
        Server stored = Repo.serverByFingerprint(context, fingerprint);
        Prefs.setSelectedServerId(stored == null ? server.id : stored.id);
    }

    private static void applyHeaders(Subscription subscription, Map<String, String> headers) {
        if (headers == null) {
            return;
        }
        String userInfo = headers.get("subscription-userinfo");
        if (userInfo != null) {
            long upload = 0;
            long download = 0;
            long total = 0;
            long expire = 0;
            for (String part : userInfo.split(";")) {
                String[] pair = part.trim().split("=");
                if (pair.length != 2) {
                    continue;
                }
                String key = pair[0].trim().toLowerCase(Locale.ROOT);
                long value;
                try {
                    value = Long.parseLong(pair[1].trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                switch (key) {
                    case "upload":
                        upload = value;
                        break;
                    case "download":
                        download = value;
                        break;
                    case "total":
                        total = value;
                        break;
                    case "expire":
                        expire = value;
                        break;
                    default:
                        break;
                }
            }
            subscription.trafficUsed = upload + download;
            subscription.trafficTotal = total;
            subscription.expire = expire;
        }
        String interval = headers.get("profile-update-interval");
        if (interval != null) {
            try {
                int hours = (int) Double.parseDouble(interval.trim());
                if (hours > 0) {
                    subscription.updateIntervalHours = hours;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        String webPage = headers.get("profile-web-page-url");
        if (webPage != null) {
            subscription.webPage = webPage;
        }
    }

    /** Adds a subscription or a single share link pasted by the user. */
    public static long addFromInput(Context context, String input, String title) {
        String trimmed = input == null ? "" : input.trim();
        if (trimmed.isEmpty()) {
            return -1;
        }
        Subscription subscription = new Subscription();
        subscription.name = TextUtils.isEmpty(title) ? defaultName(trimmed) : title;
        subscription.updateIntervalHours = Prefs.getInt(Prefs.KEY_SUB_UPDATE_INTERVAL, 12);
        subscription.autoUpdate = Prefs.getBoolean(Prefs.KEY_SUB_AUTO_UPDATE, true);
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("http")) {
            subscription.url = trimmed;
            subscription.kind = SubImporter.KIND_LINKS;
            subscription.lastUpdate = 0;
        } else {
            subscription.url = "";
            subscription.kind = SubImporter.KIND_LINKS;
            subscription.lastUpdate = System.currentTimeMillis();
        }
        long id = Repo.insertSubscription(context, subscription);
        subscription.id = id;
        if (!TextUtils.isEmpty(subscription.url)) {
            Result result = refresh(context, id);
            if (!result.ok) {
                // keep the profile so the user can retry later
                Bridge.appendLog("W", "профиль добавлен, но загрузка не удалась: " + result.message);
            }
        } else {
            String body = trimmed;
            if (!body.contains("://") && B64.looksBase64(body.replace("\n", "").replace("\r", ""))) {
                body = B64.decodeToString(body);
            }
            store(context, subscription, body);
        }
        return id;
    }

    private static String defaultName(String input) {
        if (input.toLowerCase(Locale.ROOT).startsWith("http")) {
            try {
                java.net.URL url = new java.net.URL(input);
                String host = url.getHost();
                if (host != null && !host.isEmpty()) {
                    return host;
                }
            } catch (Exception ignored) {
            }
            return "Подписка";
        }
        return "Ссылка " + new java.text.SimpleDateFormat("dd.MM HH:mm", Locale.US).format(new java.util.Date());
    }
}

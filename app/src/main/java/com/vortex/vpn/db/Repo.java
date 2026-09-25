package com.vortex.vpn.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.vortex.vpn.model.Outbound;
import com.vortex.vpn.model.Server;
import com.vortex.vpn.model.Subscription;
import com.vortex.vpn.sub.SubImporter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Data access layer for subscriptions and servers. */
public final class Repo {

    private Repo() {
    }

    // ------------------------------------------------------------------ servers

    public static List<Server> servers(Context context) {
        List<Server> result = new ArrayList<>();
        SQLiteDatabase db = Db.get(context).getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.rawQuery(
                    "SELECT id, sub_id, name, country, protocol, payload, fp, ping, ping_time, favorite, sort "
                            + "FROM servers ORDER BY favorite DESC, sort ASC, id ASC", null);
            while (cursor.moveToNext()) {
                Server server = readServer(cursor);
                if (server != null) {
                    result.add(server);
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return result;
    }

    public static Server server(Context context, long id) {
        SQLiteDatabase db = Db.get(context).getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT id, sub_id, name, country, protocol, payload, fp, ping, ping_time, favorite, sort "
                    + "FROM servers WHERE id = ?", new String[]{String.valueOf(id)});
            if (cursor.moveToNext()) {
                return readServer(cursor);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    public static Server serverByFingerprint(Context context, String fingerprint) {
        if (fingerprint == null || fingerprint.isEmpty()) {
            return null;
        }
        SQLiteDatabase db = Db.get(context).getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT id, sub_id, name, country, protocol, payload, fp, ping, ping_time, favorite, sort "
                    + "FROM servers WHERE fp = ? LIMIT 1", new String[]{fingerprint});
            if (cursor.moveToNext()) {
                return readServer(cursor);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    public static int countServers(Context context) {
        SQLiteDatabase db = Db.get(context).getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT COUNT(*) FROM servers", null);
            if (cursor.moveToNext()) {
                return cursor.getInt(0);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return 0;
    }

    private static Server readServer(Cursor cursor) {
        String payload = cursor.getString(5);
        Outbound outbound = Outbound.fromStorageJson(payload);
        if (outbound == null) {
            return null;
        }
        Server server = Server.from(outbound);
        server.id = cursor.getLong(0);
        server.subId = cursor.getLong(1);
        if (server.tag == null || server.tag.isEmpty()) {
            server.tag = cursor.getString(2);
        }
        if (server.country == null || server.country.isEmpty()) {
            server.country = cursor.getString(3);
        }
        server.ping = cursor.getInt(7);
        server.pingTime = cursor.getLong(8);
        server.favorite = cursor.getInt(9) == 1;
        server.sort = cursor.getInt(10);
        return server;
    }

    public static long insertServer(Context context, long subId, Server server, int sort) {
        ContentValues values = new ContentValues();
        values.put("sub_id", subId);
        values.put("name", server.displayName());
        values.put("country", server.country == null ? "" : server.country);
        values.put("protocol", server.type);
        values.put("payload", server.toStorageJson());
        values.put("fp", SubImporter.fingerprint(server));
        values.put("ping", server.ping);
        values.put("favorite", server.favorite ? 1 : 0);
        values.put("sort", sort);
        return Db.get(context).getWritableDatabase().insert("servers", null, values);
    }

    public static void updateServer(Context context, long id, Server server) {
        ContentValues values = new ContentValues();
        values.put("name", server.displayName());
        values.put("country", server.country == null ? "" : server.country);
        values.put("protocol", server.type);
        values.put("payload", server.toStorageJson());
        values.put("fp", SubImporter.fingerprint(server));
        Db.get(context).getWritableDatabase().update("servers", values, "id = ?",
                new String[]{String.valueOf(id)});
    }

    /**
     * Replaces the servers of one subscription while keeping favourites (matched by
     * fingerprint) and the per-server latency values that are still valid.
     */
    public static void replaceSubscriptionServers(Context context, long subId, List<Server> servers) {
        SQLiteDatabase db = Db.get(context).getWritableDatabase();
        Map<String, int[]> previous = new HashMap<>();
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT fp, favorite, ping FROM servers WHERE sub_id = ?",
                    new String[]{String.valueOf(subId)});
            while (cursor.moveToNext()) {
                previous.put(cursor.getString(0), new int[]{cursor.getInt(1), cursor.getInt(2)});
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        db.beginTransaction();
        try {
            db.delete("servers", "sub_id = ?", new String[]{String.valueOf(subId)});
            int sort = 0;
            for (Server server : servers) {
                String fp = SubImporter.fingerprint(server);
                int[] old = previous.get(fp);
                if (old != null) {
                    server.favorite = old[0] == 1;
                    server.ping = old[1];
                }
                insertServer(context, subId, server, sort++);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public static void deleteSubscriptionServers(Context context, long subId) {
        Db.get(context).getWritableDatabase().delete("servers", "sub_id = ?", new String[]{String.valueOf(subId)});
    }

    public static void deleteServer(Context context, long id) {
        Db.get(context).getWritableDatabase().delete("servers", "id = ?", new String[]{String.valueOf(id)});
    }

    public static void setFavorite(Context context, long id, boolean favorite) {
        ContentValues values = new ContentValues();
        values.put("favorite", favorite ? 1 : 0);
        Db.get(context).getWritableDatabase().update("servers", values, "id = ?", new String[]{String.valueOf(id)});
    }

    public static void updatePing(Context context, long id, int ping) {
        ContentValues values = new ContentValues();
        values.put("ping", ping);
        values.put("ping_time", System.currentTimeMillis());
        Db.get(context).getWritableDatabase().update("servers", values, "id = ?", new String[]{String.valueOf(id)});
    }

    public static void clearPings(Context context) {
        ContentValues values = new ContentValues();
        values.put("ping", 0);
        Db.get(context).getWritableDatabase().update("servers", values, null, null);
    }

    // ------------------------------------------------------------------ subscriptions

    public static List<Subscription> subscriptions(Context context) {
        List<Subscription> result = new ArrayList<>();
        SQLiteDatabase db = Db.get(context).getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT id, name, url, enabled, kind, raw_config, user_agent, last_update, "
                    + "update_interval, auto_update, traffic_used, traffic_total, expire, web_page, last_error "
                    + "FROM subscriptions ORDER BY id ASC", null);
            Map<Long, Integer> counts = new HashMap<>();
            while (cursor.moveToNext()) {
                Subscription sub = new Subscription();
                sub.id = cursor.getLong(0);
                sub.name = cursor.getString(1);
                sub.url = cursor.getString(2);
                sub.enabled = cursor.getInt(3) == 1;
                sub.kind = cursor.getString(4);
                sub.rawConfig = cursor.getString(5);
                sub.userAgent = cursor.getString(6);
                sub.lastUpdate = cursor.getLong(7);
                sub.updateIntervalHours = cursor.getInt(8);
                sub.autoUpdate = cursor.getInt(9) == 1;
                sub.trafficUsed = cursor.getLong(10);
                sub.trafficTotal = cursor.getLong(11);
                sub.expire = cursor.getLong(12);
                sub.webPage = cursor.getString(13);
                sub.lastError = cursor.getString(14);
                result.add(sub);
            }
            cursor.close();
            cursor = null;
            cursor = db.rawQuery("SELECT sub_id, COUNT(*) FROM servers GROUP BY sub_id", null);
            while (cursor.moveToNext()) {
                counts.put(cursor.getLong(0), cursor.getInt(1));
            }
            for (Subscription sub : result) {
                Integer count = counts.get(sub.id);
                sub.serverCount = count == null ? 0 : count;
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return result;
    }

    public static Subscription subscription(Context context, long id) {
        for (Subscription sub : subscriptions(context)) {
            if (sub.id == id) {
                return sub;
            }
        }
        return null;
    }

    public static long insertSubscription(Context context, Subscription sub) {
        ContentValues values = new ContentValues();
        values.put("name", sub.name);
        values.put("url", sub.url);
        values.put("enabled", sub.enabled ? 1 : 0);
        values.put("kind", sub.kind);
        values.put("raw_config", sub.rawConfig);
        values.put("user_agent", sub.userAgent);
        values.put("last_update", sub.lastUpdate);
        values.put("update_interval", sub.updateIntervalHours);
        values.put("auto_update", sub.autoUpdate ? 1 : 0);
        values.put("traffic_used", sub.trafficUsed);
        values.put("traffic_total", sub.trafficTotal);
        values.put("expire", sub.expire);
        values.put("web_page", sub.webPage);
        values.put("last_error", sub.lastError);
        return Db.get(context).getWritableDatabase().insert("subscriptions", null, values);
    }

    public static void updateSubscription(Context context, Subscription sub) {
        ContentValues values = new ContentValues();
        values.put("name", sub.name);
        values.put("url", sub.url);
        values.put("enabled", sub.enabled ? 1 : 0);
        values.put("kind", sub.kind);
        values.put("raw_config", sub.rawConfig);
        values.put("user_agent", sub.userAgent);
        values.put("last_update", sub.lastUpdate);
        values.put("update_interval", sub.updateIntervalHours);
        values.put("auto_update", sub.autoUpdate ? 1 : 0);
        values.put("traffic_used", sub.trafficUsed);
        values.put("traffic_total", sub.trafficTotal);
        values.put("expire", sub.expire);
        values.put("web_page", sub.webPage);
        values.put("last_error", sub.lastError);
        Db.get(context).getWritableDatabase().update("subscriptions", values, "id = ?",
                new String[]{String.valueOf(sub.id)});
    }

    public static void deleteSubscription(Context context, long id) {
        SQLiteDatabase db = Db.get(context).getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("servers", "sub_id = ?", new String[]{String.valueOf(id)});
            db.delete("subscriptions", "id = ?", new String[]{String.valueOf(id)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }
}

package com.vortex.vpn.db;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/** SQLite schema for subscriptions and servers. */
public class Db extends SQLiteOpenHelper {

    private static final String NAME = "vortex.db";
    private static final int VERSION = 1;
    private static Db instance;

    private Db(Context context) {
        super(context, NAME, null, VERSION);
    }

    public static synchronized Db get(Context context) {
        if (instance == null) {
            instance = new Db(context.getApplicationContext());
        }
        return instance;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE subscriptions ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name TEXT NOT NULL DEFAULT '',"
                + "url TEXT NOT NULL DEFAULT '',"
                + "enabled INTEGER NOT NULL DEFAULT 1,"
                + "kind TEXT NOT NULL DEFAULT 'links',"
                + "raw_config TEXT,"
                + "user_agent TEXT,"
                + "last_update INTEGER NOT NULL DEFAULT 0,"
                + "update_interval INTEGER NOT NULL DEFAULT 12,"
                + "auto_update INTEGER NOT NULL DEFAULT 1,"
                + "traffic_used INTEGER NOT NULL DEFAULT 0,"
                + "traffic_total INTEGER NOT NULL DEFAULT 0,"
                + "expire INTEGER NOT NULL DEFAULT 0,"
                + "web_page TEXT NOT NULL DEFAULT '',"
                + "last_error TEXT NOT NULL DEFAULT '')");

        db.execSQL("CREATE TABLE servers ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "sub_id INTEGER NOT NULL DEFAULT 0,"
                + "name TEXT NOT NULL DEFAULT '',"
                + "country TEXT NOT NULL DEFAULT '',"
                + "protocol TEXT NOT NULL DEFAULT '',"
                + "payload TEXT NOT NULL,"
                + "fp TEXT NOT NULL DEFAULT '',"
                + "ping INTEGER NOT NULL DEFAULT 0,"
                + "ping_time INTEGER NOT NULL DEFAULT 0,"
                + "favorite INTEGER NOT NULL DEFAULT 0,"
                + "sort INTEGER NOT NULL DEFAULT 0)");

        db.execSQL("CREATE INDEX idx_servers_sub ON servers(sub_id)");
        db.execSQL("CREATE INDEX idx_servers_fp ON servers(fp)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS servers");
        db.execSQL("DROP TABLE IF EXISTS subscriptions");
        onCreate(db);
    }
}

package com.vortex.vpn;

import android.content.Context;
import android.content.SharedPreferences;

import com.vortex.vpn.cfg.ConfigSettings;

import java.util.LinkedHashSet;
import java.util.Set;

/** Typed wrapper around the shared preferences (all client settings live here). */
public final class Prefs {

    private static final String NAME = "vortex";

    public static final String KEY_SERVER_ID = "server_id";
    public static final String KEY_MODE = "route_mode";
    public static final String KEY_STACK = "stack";
    public static final String KEY_MTU = "mtu";
    public static final String KEY_IPV6 = "ipv6";
    public static final String KEY_DNS_DIRECT = "dns_direct";
    public static final String KEY_DNS_REMOTE = "dns_remote";
    public static final String KEY_DNS_DOH = "dns_doh";
    public static final String KEY_DNS_DOH_SNI = "dns_doh_sni";
    public static final String KEY_FAKEIP = "fakeip";
    public static final String KEY_DNS_CACHE = "dns_cache";
    public static final String KEY_LOG_LEVEL = "log_level";
    public static final String KEY_SNIFF = "sniff";
    public static final String KEY_ALLOW_BYPASS = "allow_bypass";
    public static final String KEY_STRICT_ROUTE = "strict_route";
    public static final String KEY_DIRECT_DOMAINS = "direct_domains";
    public static final String KEY_BLOCK_DOMAINS = "block_domains";
    public static final String KEY_PER_APP = "per_app";
    public static final String KEY_PER_APP_INCLUDE = "per_app_include";
    public static final String KEY_PER_APP_LIST = "per_app_list";
    public static final String KEY_TLS_FRAGMENT = "tls_fragment";
    public static final String KEY_RECORD_FRAGMENT = "record_fragment";
    public static final String KEY_MUX = "mux";
    public static final String KEY_AUTO_START = "auto_start";
    public static final String KEY_AUTO_CONNECT = "auto_connect";
    public static final String KEY_HWID = "hwid";
    public static final String KEY_USER_AGENT = "user_agent";
    public static final String KEY_SUB_AUTO_UPDATE = "sub_auto_update";
    public static final String KEY_SUB_UPDATE_INTERVAL = "sub_update_interval";
    public static final String KEY_URL_TEST_URL = "url_test_url";
    public static final String KEY_AUTO_SELECT = "auto_select";
    public static final String KEY_SHOW_LOGS = "show_logs";
    public static final String KEY_SERVER_FP = "server_fp";
    public static final String KEY_RAW_SUB_ID = "raw_sub_id";
    public static final String KEY_ACTIVE_TAG = "active_tag";

    private static SharedPreferences prefs;

    private Prefs() {
    }

    public static void init(Context context) {
        if (prefs == null) {
            prefs = context.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
        }
        if (getString(KEY_HWID, "").isEmpty()) {
            String id = java.util.UUID.randomUUID().toString().replace("-", "");
            setString(KEY_HWID, id.substring(0, 16));
        }
    }

    private static SharedPreferences sp() {
        return prefs;
    }

    public static String getString(String key, String fallback) {
        return sp().getString(key, fallback);
    }

    public static void setString(String key, String value) {
        sp().edit().putString(key, value).apply();
    }

    public static int getInt(String key, int fallback) {
        return sp().getInt(key, fallback);
    }

    public static void setInt(String key, int value) {
        sp().edit().putInt(key, value).apply();
    }

    public static long getLong(String key, long fallback) {
        return sp().getLong(key, fallback);
    }

    public static void setLong(String key, long value) {
        sp().edit().putLong(key, value).apply();
    }

    public static boolean getBoolean(String key, boolean fallback) {
        return sp().getBoolean(key, fallback);
    }

    public static void setBoolean(String key, boolean value) {
        sp().edit().putBoolean(key, value).apply();
    }

    public static Set<String> getStringSet(String key) {
        Set<String> stored = sp().getStringSet(key, null);
        return stored == null ? new LinkedHashSet<String>() : new LinkedHashSet<>(stored);
    }

    public static void setStringSet(String key, Set<String> value) {
        sp().edit().putStringSet(key, new LinkedHashSet<>(value)).apply();
    }

    // ------------------------------------------------------------------ typed helpers

    public static long selectedServerId() {
        return getLong(KEY_SERVER_ID, -1L);
    }

    public static void setSelectedServerId(long id) {
        setLong(KEY_SERVER_ID, id);
    }

    /** Fingerprint (type|server|port|secret) of the active server. */
    public static String selectedFp() {
        return getString(KEY_SERVER_FP, "");
    }

    public static void setSelectedFp(String fingerprint) {
        setString(KEY_SERVER_FP, fingerprint == null ? "" : fingerprint);
    }

    /** Profile id whose raw sing-box configuration is used directly, or 0. */
    public static long rawSubId() {
        return getLong(KEY_RAW_SUB_ID, 0);
    }

    public static void setRawSubId(long id) {
        setLong(KEY_RAW_SUB_ID, id);
    }

    public static int routeMode() {
        return getInt(KEY_MODE, ConfigSettings.MODE_SMART);
    }

    public static String stack() {
        return getString(KEY_STACK, "mixed");
    }

    public static int mtu() {
        return getInt(KEY_MTU, 9000);
    }

    public static boolean ipv6() {
        return getBoolean(KEY_IPV6, true);
    }

    public static boolean fakeIp() {
        return getBoolean(KEY_FAKEIP, false);
    }

    public static boolean perAppEnabled() {
        return getBoolean(KEY_PER_APP, false);
    }

    public static boolean perAppInclude() {
        return getBoolean(KEY_PER_APP_INCLUDE, true);
    }

    public static Set<String> perAppList() {
        return getStringSet(KEY_PER_APP_LIST);
    }

    public static boolean autoSelect() {
        return getBoolean(KEY_AUTO_SELECT, false);
    }

    public static String hwid() {
        return getString(KEY_HWID, "");
    }

    public static String userAgent() {
        return getString(KEY_USER_AGENT, "");
    }

    public static void setDirectDomains(Set<String> domains) {
        setStringSet(KEY_DIRECT_DOMAINS, domains);
    }

    public static void setBlockDomains(Set<String> domains) {
        setStringSet(KEY_BLOCK_DOMAINS, domains);
    }

    /** Converts the stored preferences into the engine-independent settings object. */
    public static ConfigSettings toConfigSettings() {
        ConfigSettings s = new ConfigSettings();
        s.mode = routeMode();
        s.stack = stack();
        s.mtu = mtu();
        s.ipv6 = ipv6();
        s.fakeIp = fakeIp();
        s.dnsCache = getBoolean(KEY_DNS_CACHE, true);
        s.directDns = getString(KEY_DNS_DIRECT, "223.5.5.5");
        s.remoteDns = getString(KEY_DNS_REMOTE, "1.1.1.1");
        s.remoteDnsDoh = getBoolean(KEY_DNS_DOH, true);
        s.remoteDnsServerName = getString(KEY_DNS_DOH_SNI, "cloudflare-dns.com");
        s.logLevel = getString(KEY_LOG_LEVEL, "info");
        s.sniff = getBoolean(KEY_SNIFF, true);
        s.allowBypass = getBoolean(KEY_ALLOW_BYPASS, false);
        s.strictRoute = getBoolean(KEY_STRICT_ROUTE, false);
        s.tlsFragment = getBoolean(KEY_TLS_FRAGMENT, false);
        s.recordFragment = getBoolean(KEY_RECORD_FRAGMENT, false);
        s.mux = getBoolean(KEY_MUX, false);
        s.autoSelect = autoSelect();
        s.urlTestUrl = getString(KEY_URL_TEST_URL, "http://cp.cloudflare.com/generate_204");
        s.directDomains.addAll(getStringSet(KEY_DIRECT_DOMAINS));
        s.blockDomains.addAll(getStringSet(KEY_BLOCK_DOMAINS));
        s.perAppEnabled = perAppEnabled();
        s.perAppInclude = perAppInclude();
        s.perAppPackages.addAll(perAppList());
        return s;
    }
}

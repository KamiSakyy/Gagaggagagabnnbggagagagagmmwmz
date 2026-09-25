package com.vortex.vpn.model;

/** A subscription / profile row. */
public class Subscription {

    public long id;
    public String name = "";
    public String url = "";
    public boolean enabled = true;
    /** links | clash | config */
    public String kind = "links";
    public String rawConfig;
    public String userAgent;
    public long lastUpdate;
    public int updateIntervalHours = 12;
    public boolean autoUpdate = true;
    public long trafficUsed;
    public long trafficTotal;
    public long expire;
    public String webPage = "";
    public String lastError = "";
    /** Number of locations of this profile (filled when the list is loaded). */
    public int serverCount;

    public boolean isConfig() {
        return "config".equals(kind);
    }
}

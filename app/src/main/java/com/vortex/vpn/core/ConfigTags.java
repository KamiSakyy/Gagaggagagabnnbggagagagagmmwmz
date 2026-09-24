package com.vortex.vpn.core;

import com.vortex.vpn.cfg.ConfigBuilder;

/** Well known outbound tags used by the generated configuration. */
public final class ConfigTags {

    public static final String PROXY = ConfigBuilder.TAG_PROXY;
    public static final String AUTO = ConfigBuilder.TAG_AUTO;
    public static final String DIRECT = ConfigBuilder.TAG_DIRECT;

    private ConfigTags() {
    }

    public static boolean isGroup(String tag) {
        return PROXY.equals(tag) || AUTO.equals(tag) || DIRECT.equals(tag);
    }
}

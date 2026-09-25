package com.vortex.vpn.core;

import com.vortex.vpn.R;

import java.util.Locale;

/**
 * Turns the raw engine failure into something a user can act on.
 *
 * <p>The engine reports everything in English and in its own terms ("start or reload service: start
 * dns/udp[...]: detour to an empty direct outbound makes no sense"). Showing that on the dashboard
 * tells the user nothing. Here the message is classified into a short Russian title plus a hint,
 * while the original text stays available as technical details.</p>
 */
public final class EngineError {

    private EngineError() {
    }

    /** Short headline for the error card. */
    public static int titleRes(String raw) {
        String message = normalize(raw);
        if (message.isEmpty()) {
            return R.string.error_unknown_title;
        }
        if (contains(message, "detour to an empty", "empty direct outbound", "start or reload service",
                "missing outbound", "unknown outbound", "outbound not found")) {
            return R.string.error_engine_title;
        }
        if (contains(message, "permission", "not prepared", "denied")) {
            return R.string.error_permission_title;
        }
        if (contains(message, "no servers", "no outbound", "empty configuration", "outbounds")) {
            return R.string.error_no_locations_title;
        }
        if (contains(message, "timeout", "timed out", "deadline exceeded", "i/o timeout",
                "no route to host", "network is unreachable")) {
            return R.string.error_timeout_title;
        }
        if (contains(message, "connection refused", "connection reset", "eof", "broken pipe",
                "handshake", "tls:", "unexpected")) {
            return R.string.error_refused_title;
        }
        if (contains(message, "authentication", "invalid user", "wrong password", "unauthorized",
                "invalid password", "auth")) {
            return R.string.error_auth_title;
        }
        if (contains(message, "certificate", "x509", "server name")) {
            return R.string.error_certificate_title;
        }
        if (contains(message, "dns", "resolver", "lookup", "no such host")) {
            return R.string.error_dns_title;
        }
        if (contains(message, "не поддерживается")) {
            return R.string.error_unsupported_title;
        }
        return R.string.error_unknown_title;
    }

    /** Sentence that tells the user what to do about it. */
    public static int hintRes(String raw) {
        String message = normalize(raw);
        if (contains(message, "detour to an empty", "empty direct outbound", "start or reload service",
                "missing outbound", "unknown outbound", "outbound not found")) {
            return R.string.error_engine_hint;
        }
        if (contains(message, "permission", "not prepared", "denied")) {
            return R.string.error_permission_hint;
        }
        if (contains(message, "no servers", "no outbound", "empty configuration", "outbounds")) {
            return R.string.error_no_locations_hint;
        }
        if (contains(message, "timeout", "timed out", "deadline exceeded", "i/o timeout",
                "no route to host", "network is unreachable")) {
            return R.string.error_timeout_hint;
        }
        if (contains(message, "authentication", "invalid user", "wrong password", "unauthorized",
                "invalid password", "auth")) {
            return R.string.error_auth_hint;
        }
        if (contains(message, "certificate", "x509", "server name")) {
            return R.string.error_certificate_hint;
        }
        if (contains(message, "dns", "resolver", "lookup", "no such host")) {
            return R.string.error_dns_hint;
        }
        return R.string.error_unknown_hint;
    }

    /** True when the failure is worth showing with the "engine rejected the configuration" text. */
    public static boolean isEngineConfigurationProblem(String raw) {
        String message = normalize(raw);
        return contains(message, "detour to an empty", "empty direct outbound",
                "start or reload service", "check config", "decode config");
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.toLowerCase(Locale.ROOT);
    }

    private static boolean contains(String message, String... needles) {
        for (String needle : needles) {
            if (message.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}

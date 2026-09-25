package com.vortex.vpn.core;

/**
 * Fallback traffic counter: reads the bytes that went through the tunnel interface.
 *
 * <p>The engine reports its own counters through the command protocol, but it only does so while
 * its traffic manager is up. If that ever fails, the dashboard would show nothing at all, so the
 * kernel numbers for the tun device are used instead of pretending there is no traffic.</p>
 *
 * <p>The parsing is a plain function so it can be unit tested without a real {@code /proc}.</p>
 */
public final class TrafficProbe {

    private TrafficProbe() {
    }

    /** Reads the tunnel counters, or {@code null} when they are not available. */
    public static long[] tunTotals() {
        String name = tunInterfaceName();
        if (name == null) {
            return null;
        }
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/net/dev"), 512);
            try {
                StringBuilder content = new StringBuilder();
                String line;
                int lines = 0;
                while ((line = reader.readLine()) != null && lines++ < 64) {
                    content.append(line).append('\n');
                }
                return parse(content.toString(), name);
            } finally {
                reader.close();
            }
        } catch (Throwable error) {
            return null;
        }
    }

    /** The tunnel interface, as created by the engine inside the VPN service. */
    public static String tunInterfaceName() {
        try {
            String[] names = new java.io.File("/sys/class/net").list();
            if (names == null) {
                return null;
            }
            for (String name : names) {
                if (name != null && name.startsWith("tun")) {
                    return name;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Parses {@code /proc/net/dev} content.
     *
     * @return {@code long[]{uplink, downlink}} in bytes, or {@code null} when the interface is
     * not listed
     */
    public static long[] parse(String content, String interfaceName) {
        if (content == null || interfaceName == null) {
            return null;
        }
        for (String rawLine : content.split("\n")) {
            String line = rawLine.trim();
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            if (!line.substring(0, colon).trim().equals(interfaceName)) {
                continue;
            }
            String[] numbers = line.substring(colon + 1).trim().split("\\s+");
            if (numbers.length < 9) {
                return null;
            }
            try {
                long received = Long.parseLong(numbers[0]);
                long sent = Long.parseLong(numbers[8]);
                return new long[]{sent, received};
            } catch (NumberFormatException error) {
                return null;
            }
        }
        return null;
    }
}

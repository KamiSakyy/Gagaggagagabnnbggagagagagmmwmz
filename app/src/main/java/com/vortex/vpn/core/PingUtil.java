package com.vortex.vpn.core;

import com.vortex.vpn.model.Server;

import java.net.InetSocketAddress;
import java.net.Socket;

/** Lightweight TCP handshake latency probe (used when the tunnel is down). */
public final class PingUtil {

    private PingUtil() {
    }

    /** Returns the latency in ms, or -1 when unreachable. */
    public static int tcpPing(Server server, int timeoutMs) {
        if (server == null || server.server == null || server.server.isEmpty()) {
            return -1;
        }
        Socket socket = new Socket();
        long start = System.currentTimeMillis();
        try {
            socket.connect(new InetSocketAddress(server.server, server.port), timeoutMs);
            return (int) (System.currentTimeMillis() - start);
        } catch (Throwable t) {
            return -1;
        } finally {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }
}

package com.vortex.vpn.core;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Log;

import io.nekohasekai.libbox.InterfaceUpdateListener;

/**
 * Watches the underlying (non VPN) default network and reports it to the engine so that
 * outbound sockets are bound to the real interface instead of the tunnel.
 */
public class ConnMonitor {

    private static final String TAG = "Vortex/Net";

    private final ConnectivityManager connectivity;
    private NetworkCallbackImpl callback;
    private volatile InterfaceUpdateListener listener;
    private volatile Network lastNetwork;

    public ConnMonitor(Context context) {
        connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    public void setListener(InterfaceUpdateListener listener) {
        this.listener = listener;
        if (listener == null) {
            stop();
            return;
        }
        start();
        deliver(lastNetwork);
    }

    private void start() {
        if (connectivity == null || callback != null) {
            return;
        }
        try {
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build();
            callback = new NetworkCallbackImpl();
            connectivity.registerNetworkCallback(request, callback);
        } catch (Throwable t) {
            Log.w(TAG, "registerNetworkCallback", t);
        }
    }

    public void stop() {
        try {
            if (connectivity != null && callback != null) {
                connectivity.unregisterNetworkCallback(callback);
            }
        } catch (Throwable ignored) {
        }
        callback = null;
    }

    private void deliver(Network network) {
        InterfaceUpdateListener target = listener;
        if (target == null) {
            return;
        }
        if (network == null) {
            target.updateDefaultInterface("", -1, false, false);
            return;
        }
        try {
            LinkProperties properties = connectivity.getLinkProperties(network);
            if (properties == null || properties.getInterfaceName() == null) {
                return;
            }
            String name = properties.getInterfaceName();
            int index;
            try {
                java.net.NetworkInterface networkInterface = java.net.NetworkInterface.getByName(name);
                index = networkInterface == null ? -1 : networkInterface.getIndex();
            } catch (Throwable t) {
                index = -1;
            }
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
            boolean expensive = capabilities != null
                    && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
            boolean constrained = capabilities != null
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) == false;
            target.updateDefaultInterface(name, index, expensive, constrained);
        } catch (Throwable t) {
            Log.w(TAG, "deliver", t);
        }
    }

    private class NetworkCallbackImpl extends ConnectivityManager.NetworkCallback {
        @Override
        public void onAvailable(Network network) {
            lastNetwork = network;
            deliver(network);
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
            lastNetwork = network;
            deliver(network);
        }

        @Override
        public void onLost(Network network) {
            if (lastNetwork != null && lastNetwork.equals(network)) {
                lastNetwork = null;
                deliver(null);
            }
        }
    }
}

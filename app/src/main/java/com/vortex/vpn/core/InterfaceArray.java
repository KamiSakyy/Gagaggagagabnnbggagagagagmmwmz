package com.vortex.vpn.core;

import java.util.ArrayList;
import java.util.List;

import io.nekohasekai.libbox.NetworkInterface;
import io.nekohasekai.libbox.NetworkInterfaceIterator;

/** Iterator over the interface list handed to the engine. */
public class InterfaceArray implements NetworkInterfaceIterator {

    private final List<NetworkInterface> values;
    private int index;

    public InterfaceArray(List<NetworkInterface> values) {
        this.values = values == null ? new ArrayList<NetworkInterface>() : values;
    }

    @Override
    public boolean hasNext() {
        return index < values.size();
    }

    @Override
    public NetworkInterface next() {
        return values.get(index++);
    }
}

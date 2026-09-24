package com.vortex.vpn.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import io.nekohasekai.libbox.StringIterator;

/** Java implementation of the gobind {@code StringIterator} callback interface. */
public class StringArray implements StringIterator {

    private final List<String> values;
    private int index;

    public StringArray(Collection<String> values) {
        this.values = values == null ? new ArrayList<String>() : new ArrayList<>(values);
    }

    @Override
    public boolean hasNext() {
        return index < values.size();
    }

    @Override
    public int len() {
        return values.size();
    }

    @Override
    public String next() {
        if (index >= values.size()) {
            return "";
        }
        return values.get(index++);
    }
}

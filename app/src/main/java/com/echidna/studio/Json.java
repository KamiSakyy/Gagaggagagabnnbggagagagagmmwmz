package com.echidna.studio;

import java.util.ArrayList;
import java.util.List;

/**
 * A three line JSON writer.
 *
 * <p>It exists to keep the self test report machine readable for the CI job without pulling a JSON
 * library into the APK (the framework already ships its own parser, but only for reading).</p>
 */
public final class Json {
    private final List<String> parts = new ArrayList<String>();

    public static Json object() {
        return new Json();
    }

    public Json put(String key, boolean value) {
        return raw(key, value ? "true" : "false");
    }

    public Json put(String key, Number value) {
        return raw(key, String.valueOf(value));
    }

    public Json put(String key, String value) {
        return raw(key, quote(value));
    }

    private Json raw(String key, String value) {
        parts.add(quote(key) + ":" + value);
        return this;
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        final StringBuilder builder = new StringBuilder(value.length() + 2);
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '"':
                    builder.append("\\\"");
                    break;
                case '\\':
                    builder.append("\\\\");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                    break;
            }
        }
        builder.append('"');
        return builder.toString();
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder("{");
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(parts.get(i));
        }
        return builder.append('}').toString();
    }
}

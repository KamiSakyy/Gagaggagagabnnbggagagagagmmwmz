package com.vortex.vpn.cfg;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal, dependency-free JSON writer.
 *
 * Deliberately pure Java (no android.* / org.json) so the configuration builder can be
 * compiled and validated on a plain JVM (CI runs the generated configs through
 * {@code sing-box check}).
 */
public final class Json {

    private Json() {
    }

    public interface Value {
        void write(StringBuilder sb);
    }

    public static Obj obj() {
        return new Obj();
    }

    public static Arr arr() {
        return new Arr();
    }

    public static final class Obj implements Value {
        private final List<String> keys = new ArrayList<>();
        private final List<Value> values = new ArrayList<>();

        public Obj put(String key, Value value) {
            if (value == null) {
                return this;
            }
            int index = keys.indexOf(key);
            if (index >= 0) {
                values.set(index, value);
                return this;
            }
            keys.add(key);
            values.add(value);
            return this;
        }

        public Obj put(String key, String value) {
            return value == null ? this : put(key, new Str(value));
        }

        public Obj put(String key, boolean value) {
            return put(key, new Raw(value ? "true" : "false"));
        }

        public Obj put(String key, long value) {
            return put(key, new Raw(Long.toString(value)));
        }

        public Obj put(String key, String... values) {
            if (values == null || values.length == 0) {
                return this;
            }
            Arr arr = arr();
            for (String v : values) {
                arr.add(v);
            }
            return put(key, arr);
        }

        public Obj putIf(boolean condition, String key, Value value) {
            return condition ? put(key, value) : this;
        }

        public Obj putIf(boolean condition, String key, String value) {
            return condition && value != null ? put(key, value) : this;
        }

        public Obj putIf(boolean condition, String key, long value) {
            return condition ? put(key, value) : this;
        }

        public Obj putIf(boolean condition, String key, boolean value) {
            return condition ? put(key, value) : this;
        }

        public boolean isEmpty() {
            return keys.isEmpty();
        }

        public boolean has(String key) {
            return keys.contains(key);
        }

        public Obj object(String key) {
            int index = keys.indexOf(key);
            if (index < 0) {
                return null;
            }
            Value value = values.get(index);
            return value instanceof Obj ? (Obj) value : null;
        }

        public void remove(String key) {
            int index = keys.indexOf(key);
            if (index >= 0) {
                keys.remove(index);
                values.remove(index);
            }
        }

        @Override
        public void write(StringBuilder sb) {
            sb.append('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeString(sb, keys.get(i));
                sb.append(':');
                values.get(i).write(sb);
            }
            sb.append('}');
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(256);
            write(sb);
            return sb.toString();
        }
    }

    public static final class Arr implements Value {
        private final List<Value> values = new ArrayList<>();

        public Arr add(Value value) {
            if (value != null) {
                values.add(value);
            }
            return this;
        }

        public Arr add(String value) {
            return value == null ? this : add(new Str(value));
        }

        public Arr add(long value) {
            return add(new Raw(Long.toString(value)));
        }

        public Arr add(boolean value) {
            return add(new Raw(value ? "true" : "false"));
        }

        public Arr addAll(String... values) {
            if (values != null) {
                for (String v : values) {
                    add(v);
                }
            }
            return this;
        }

        public boolean isEmpty() {
            return values.isEmpty();
        }

        public int size() {
            return values.size();
        }

        @Override
        public void write(StringBuilder sb) {
            sb.append('[');
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                values.get(i).write(sb);
            }
            sb.append(']');
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            write(sb);
            return sb.toString();
        }
    }

    private static final class Str implements Value {
        private final String value;

        Str(String value) {
            this.value = value;
        }

        @Override
        public void write(StringBuilder sb) {
            writeString(sb, value);
        }
    }

    private static final class Raw implements Value {
        private final String raw;

        Raw(String raw) {
            this.raw = raw;
        }

        @Override
        public void write(StringBuilder sb) {
            sb.append(raw);
        }
    }

    static void writeString(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    /** Pretty print (2-space indentation) - used for the in-app config viewer. */
    public static String pretty(String compact) {
        StringBuilder out = new StringBuilder(compact.length() + 256);
        int indent = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < compact.length(); i++) {
            char c = compact.charAt(i);
            if (inString) {
                out.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"':
                    inString = true;
                    out.append(c);
                    break;
                case '{':
                case '[':
                    out.append(c);
                    if (i + 1 < compact.length() && (compact.charAt(i + 1) == '}' || compact.charAt(i + 1) == ']')) {
                        break;
                    }
                    indent++;
                    out.append('\n');
                    appendIndent(out, indent);
                    break;
                case '}':
                case ']':
                    indent--;
                    out.append('\n');
                    appendIndent(out, indent);
                    out.append(c);
                    break;
                case ',':
                    out.append(c);
                    out.append('\n');
                    appendIndent(out, indent);
                    break;
                case ':':
                    out.append(": ");
                    break;
                default:
                    out.append(c);
            }
        }
        return out.toString();
    }

    private static void appendIndent(StringBuilder sb, int indent) {
        for (int i = 0; i < indent; i++) {
            sb.append("  ");
        }
    }
}

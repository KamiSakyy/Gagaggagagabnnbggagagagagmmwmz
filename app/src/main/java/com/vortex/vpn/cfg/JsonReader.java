package com.vortex.vpn.cfg;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny recursive-descent JSON reader (pure Java).
 *
 * Returns {@link Map}, {@link List}, {@link String}, {@link Double}, {@link Boolean} or null.
 */
public final class JsonReader {

    private final String text;
    private int pos;

    private JsonReader(String text) {
        this.text = text;
    }

    public static Object parse(String text) {
        JsonReader reader = new JsonReader(text);
        reader.skipWhitespace();
        Object value = reader.readValue();
        reader.skipWhitespace();
        return value;
    }

    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (value instanceof Map) {
            //noinspection unchecked
            return (Map<String, Object>) value;
        }
        throw new IllegalArgumentException("not a JSON object");
    }

    public static String string(Map<String, Object> map, String key, String fallback) {
        Object value = map == null ? null : map.get(key);
        if (value == null) {
            return fallback;
        }
        String s = String.valueOf(value);
        if (s.endsWith(".0")) {
            s = s.substring(0, s.length() - 2);
        }
        return s;
    }

    public static int integer(Map<String, Object> map, String key, int fallback) {
        Object value = map == null ? null : map.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return (int) Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static boolean bool(Map<String, Object> map, String key, boolean fallback) {
        Object value = map == null ? null : map.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        String s = String.valueOf(value).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
    }

    // ------------------------------------------------------------------ parser

    private Object readValue() {
        if (pos >= text.length()) {
            throw new IllegalArgumentException("unexpected end of JSON");
        }
        char c = text.charAt(pos);
        switch (c) {
            case '{':
                return readObject();
            case '[':
                return readArray();
            case '"':
                return readString();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                return readNumber();
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // {
        skipWhitespace();
        if (pos < text.length() && text.charAt(pos) == '}') {
            pos++;
            return map;
        }
        while (pos < text.length()) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            expectChar(':');
            skipWhitespace();
            map.put(key, readValue());
            skipWhitespace();
            char c = text.charAt(pos);
            if (c == ',') {
                pos++;
            } else if (c == '}') {
                pos++;
                break;
            } else {
                throw new IllegalArgumentException("expected , or } at " + pos);
            }
        }
        return map;
    }

    private List<Object> readArray() {
        List<Object> list = new ArrayList<>();
        pos++; // [
        skipWhitespace();
        if (pos < text.length() && text.charAt(pos) == ']') {
            pos++;
            return list;
        }
        while (pos < text.length()) {
            skipWhitespace();
            list.add(readValue());
            skipWhitespace();
            char c = text.charAt(pos);
            if (c == ',') {
                pos++;
            } else if (c == ']') {
                pos++;
                break;
            } else {
                throw new IllegalArgumentException("expected , or ] at " + pos);
            }
        }
        return list;
    }

    private String readString() {
        expectChar('"');
        StringBuilder sb = new StringBuilder();
        while (pos < text.length()) {
            char c = text.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                char esc = text.charAt(pos++);
                switch (esc) {
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '/':
                        sb.append('/');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default:
                        sb.append(esc);
                }
            } else {
                sb.append(c);
            }
        }
        throw new IllegalArgumentException("unterminated string");
    }

    private Object readNumber() {
        int start = pos;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || (c >= '0' && c <= '9')) {
                pos++;
            } else {
                break;
            }
        }
        String raw = text.substring(start, pos);
        try {
            return Double.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void expect(String literal) {
        if (!text.startsWith(literal, pos)) {
            throw new IllegalArgumentException("expected " + literal + " at " + pos);
        }
        pos += literal.length();
    }

    private void expectChar(char expected) {
        if (pos >= text.length() || text.charAt(pos) != expected) {
            throw new IllegalArgumentException("expected " + expected + " at " + pos);
        }
        pos++;
    }

    private void skipWhitespace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }
}

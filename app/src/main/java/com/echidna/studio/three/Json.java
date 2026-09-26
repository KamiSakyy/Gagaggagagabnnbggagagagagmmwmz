package com.echidna.studio.three;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny JSON reader used by the 3D side of the app.
 *
 * <p>The 3D models are glTF, whose header is JSON. Android ships a JSON parser, but it is part of the
 * platform: the unit tests of this project run on a plain JVM, where that class does not exist, and a
 * 3D loader that cannot be tested is a 3D loader that breaks on the phone of the user. This parser is
 * therefore plain Java, about 150 lines, and completely covered by tests.</p>
 *
 * <p>Values are read through two thin wrappers, {@link Obj} and {@link Arr}, which mirror the calls
 * the loader would make on Apple's or Android's JSON classes.</p>
 */
public final class Json {

    /** Thrown when the text is not JSON at all. */
    public static final class JsonError extends RuntimeException {
        public JsonError(String message) {
            super(message);
        }
    }

    private final String text;
    private int position;

    private Json(String text) {
        this.text = text;
    }

    /** Parses a whole document. Throws {@link JsonError} on garbage. */
    public static Object parse(String text) {
        if (text == null) {
            throw new JsonError("пустой JSON");
        }
        final Json json = new Json(text);
        json.skipWhitespace();
        final Object value = json.readValue();
        json.skipWhitespace();
        if (json.position < json.text.length()) {
            throw new JsonError("лишние символы после JSON на позиции " + json.position);
        }
        return value;
    }

    /** Parses a document that must be an object. */
    public static Obj parseObject(String text) {
        final Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new JsonError("ожидался объект JSON");
        }
        return new Obj(asMap(value));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object value) {
        return (List<Object>) value;
    }

    static boolean isMissing(Object value) {
        return value == null;
    }

    // ------------------------------------------------------------------ разбор

    private Object readValue() {
        if (position >= text.length()) {
            throw new JsonError("JSON оборвался");
        }
        final char c = text.charAt(position);
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
        final Map<String, Object> map = new LinkedHashMap<String, Object>();
        position++;                     // {
        skipWhitespace();
        if (peek() == '}') {
            position++;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw new JsonError("имя поля должно быть строкой, позиция " + position);
            }
            final String key = readString();
            skipWhitespace();
            if (peek() != ':') {
                throw new JsonError("после имени поля нужно ':', позиция " + position);
            }
            position++;
            skipWhitespace();
            map.put(key, readValue());
            skipWhitespace();
            final char c = peek();
            if (c == ',') {
                position++;
                continue;
            }
            if (c == '}') {
                position++;
                return map;
            }
            throw new JsonError("ожидалась ',' или '}', позиция " + position);
        }
    }

    private List<Object> readArray() {
        final List<Object> list = new ArrayList<Object>();
        position++;                     // [
        skipWhitespace();
        if (peek() == ']') {
            position++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(readValue());
            skipWhitespace();
            final char c = peek();
            if (c == ',') {
                position++;
                continue;
            }
            if (c == ']') {
                position++;
                return list;
            }
            throw new JsonError("ожидалась ',' или ']', позиция " + position);
        }
    }

    private String readString() {
        final StringBuilder out = new StringBuilder(32);
        position++;                     // opening quote
        while (true) {
            if (position >= text.length()) {
                throw new JsonError("строка не закрыта");
            }
            final char c = text.charAt(position++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (position >= text.length()) {
                throw new JsonError("строка не закрыта после '\\'");
            }
            final char escape = text.charAt(position++);
            switch (escape) {
                case '"':
                    out.append('"');
                    break;
                case '\\':
                    out.append('\\');
                    break;
                case '/':
                    out.append('/');
                    break;
                case 'b':
                    out.append('\b');
                    break;
                case 'f':
                    out.append('\f');
                    break;
                case 'n':
                    out.append('\n');
                    break;
                case 'r':
                    out.append('\r');
                    break;
                case 't':
                    out.append('\t');
                    break;
                case 'u':
                    if (position + 4 > text.length()) {
                        throw new JsonError("недописанный \\u");
                    }
                    out.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                    position += 4;
                    break;
                default:
                    throw new JsonError("неизвестная escape-последовательность: \\" + escape);
            }
        }
    }

    private Double readNumber() {
        final int start = position;
        while (position < text.length()) {
            final char c = text.charAt(position);
            if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E'
                    || (c >= '0' && c <= '9')) {
                position++;
            } else {
                break;
            }
        }
        if (start == position) {
            throw new JsonError("ожидалось значение, позиция " + position);
        }
        try {
            return Double.valueOf(text.substring(start, position));
        } catch (NumberFormatException e) {
            throw new JsonError("не число: " + text.substring(start, position));
        }
    }

    private void expect(String literal) {
        if (!text.startsWith(literal, position)) {
            throw new JsonError("ожидалось " + literal + " на позиции " + position);
        }
        position += literal.length();
    }

    private char peek() {
        if (position >= text.length()) {
            throw new JsonError("JSON оборвался");
        }
        return text.charAt(position);
    }

    private void skipWhitespace() {
        while (position < text.length()) {
            final char c = text.charAt(position);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                position++;
            } else {
                break;
            }
        }
    }

    // ------------------------------------------------------------- обёртки

    /** An object: field lookups never throw, a missing field gives the default. */
    public static final class Obj {
        private final Map<String, Object> map;

        Obj(Map<String, Object> map) {
            this.map = map;
        }

        public boolean has(String key) {
            return map.containsKey(key) && map.get(key) != null;
        }

        public Iterator<String> keys() {
            return map.keySet().iterator();
        }

        public Object get(String key) {
            return map.get(key);
        }

        public Obj optObject(String key) {
            final Object value = map.get(key);
            return value instanceof Map ? new Obj(asMap(value)) : null;
        }

        public Arr optArray(String key) {
            final Object value = map.get(key);
            return value instanceof List ? new Arr(asList(value)) : null;
        }

        public String optString(String key, String fallback) {
            final Object value = map.get(key);
            return value instanceof String ? (String) value : fallback;
        }

        public int optInt(String key, int fallback) {
            final Object value = map.get(key);
            return value instanceof Number ? ((Number) value).intValue() : fallback;
        }

        public double optDouble(String key, double fallback) {
            final Object value = map.get(key);
            return value instanceof Number ? ((Number) value).doubleValue() : fallback;
        }

        public boolean optBoolean(String key, boolean fallback) {
            final Object value = map.get(key);
            return value instanceof Boolean ? (Boolean) value : fallback;
        }

        public int getInt(String key) {
            final Object value = map.get(key);
            if (!(value instanceof Number)) {
                throw new JsonError("поле " + key + " не число");
            }
            return ((Number) value).intValue();
        }

        public String getString(String key) {
            final Object value = map.get(key);
            if (!(value instanceof String)) {
                throw new JsonError("поле " + key + " не строка");
            }
            return (String) value;
        }

        public Obj getObject(String key) {
            final Object value = map.get(key);
            if (!(value instanceof Map)) {
                throw new JsonError("поле " + key + " не объект");
            }
            return new Obj(asMap(value));
        }

        public Arr getArray(String key) {
            final Object value = map.get(key);
            if (!(value instanceof List)) {
                throw new JsonError("поле " + key + " не массив");
            }
            return new Arr(asList(value));
        }

        public int size() {
            return map.size();
        }

        @Override
        public String toString() {
            return "Obj" + map.keySet();
        }
    }

    /** An array; index lookups outside the bounds give the default. */
    public static final class Arr {
        private final List<Object> list;

        Arr(List<Object> list) {
            this.list = list;
        }

        public int length() {
            return list.size();
        }

        public Object opt(int index) {
            return index >= 0 && index < list.size() ? list.get(index) : null;
        }

        public Obj optObject(int index) {
            final Object value = opt(index);
            return value instanceof Map ? new Obj(asMap(value)) : null;
        }

        public Arr optArray(int index) {
            final Object value = opt(index);
            return value instanceof List ? new Arr(asList(value)) : null;
        }

        public String optString(int index, String fallback) {
            final Object value = opt(index);
            return value instanceof String ? (String) value : fallback;
        }

        public double optDouble(int index, double fallback) {
            final Object value = opt(index);
            return value instanceof Number ? ((Number) value).doubleValue() : fallback;
        }

        public int optInt(int index, int fallback) {
            final Object value = opt(index);
            return value instanceof Number ? ((Number) value).intValue() : fallback;
        }

        public Object get(int index) {
            if (index < 0 || index >= list.size()) {
                throw new JsonError("индекс " + index + " вне массива из " + list.size());
            }
            return list.get(index);
        }

        public int getInt(int index) {
            final Object value = get(index);
            if (!(value instanceof Number)) {
                throw new JsonError("элемент " + index + " не число");
            }
            return ((Number) value).intValue();
        }

        public double getDouble(int index) {
            final Object value = get(index);
            if (!(value instanceof Number)) {
                throw new JsonError("элемент " + index + " не число");
            }
            return ((Number) value).doubleValue();
        }

        public String getString(int index) {
            final Object value = get(index);
            if (!(value instanceof String)) {
                throw new JsonError("элемент " + index + " не строка");
            }
            return (String) value;
        }

        public Obj getObject(int index) {
            final Object value = get(index);
            if (!(value instanceof Map)) {
                throw new JsonError("элемент " + index + " не объект");
            }
            return new Obj(asMap(value));
        }

        public List<Object> values() {
            return Collections.unmodifiableList(list);
        }

        @Override
        public String toString() {
            return "Arr[" + list.size() + "]";
        }
    }
}

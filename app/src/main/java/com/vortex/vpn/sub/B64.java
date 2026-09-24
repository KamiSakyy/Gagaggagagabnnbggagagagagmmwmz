package com.vortex.vpn.sub;

import java.nio.charset.StandardCharsets;

/** Dependency free Base64 (standard + URL safe, padded or not) - pure Java. */
public final class B64 {

    private static final String STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    private static final String URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    private B64() {
    }

    public static String encode(byte[] data) {
        return encode(data, false);
    }

    public static String encode(byte[] data, boolean urlSafe) {
        String alphabet = urlSafe ? URL : STD;
        StringBuilder sb = new StringBuilder((data.length + 2) / 3 * 4);
        for (int i = 0; i < data.length; i += 3) {
            int b0 = data[i] & 0xFF;
            int b1 = i + 1 < data.length ? data[i + 1] & 0xFF : -1;
            int b2 = i + 2 < data.length ? data[i + 2] & 0xFF : -1;
            sb.append(alphabet.charAt(b0 >> 2));
            sb.append(alphabet.charAt(((b0 & 0x03) << 4) | (b1 < 0 ? 0 : b1 >> 4)));
            if (b1 < 0) {
                sb.append('=');
            } else {
                sb.append(alphabet.charAt(((b1 & 0x0F) << 2) | (b2 < 0 ? 0 : b2 >> 6)));
            }
            if (b2 < 0) {
                sb.append('=');
            } else {
                sb.append(alphabet.charAt(b2 & 0x3F));
            }
        }
        return sb.toString();
    }

    public static byte[] decode(String value) {
        if (value == null) {
            return new byte[0];
        }
        String cleaned = value.trim().replace("\n", "").replace("\r", "").replace(" ", "");
        int pad = 0;
        while (cleaned.endsWith("=")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
            pad++;
        }
        int length = cleaned.length();
        int bytes = length * 3 / 4;
        byte[] out = new byte[bytes];
        int acc = 0;
        int bits = 0;
        int index = 0;
        for (int i = 0; i < length; i++) {
            int v = map(cleaned.charAt(i));
            if (v < 0) {
                continue;
            }
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                if (index < out.length) {
                    out[index++] = (byte) ((acc >> bits) & 0xFF);
                }
            }
        }
        if (index == out.length) {
            return out;
        }
        byte[] exact = new byte[index];
        System.arraycopy(out, 0, exact, 0, index);
        return exact;
    }

    public static String decodeToString(String value) {
        return new String(decode(value), StandardCharsets.UTF_8);
    }

    private static int map(char c) {
        if (c >= 'A' && c <= 'Z') {
            return c - 'A';
        }
        if (c >= 'a' && c <= 'z') {
            return c - 'a' + 26;
        }
        if (c >= '0' && c <= '9') {
            return c - '0' + 52;
        }
        if (c == '+' || c == '-') {
            return 62;
        }
        if (c == '/' || c == '_') {
            return 63;
        }
        return -1;
    }

    /** True when the value looks like base64 text (used to sniff subscription payloads). */
    public static boolean looksBase64(String value) {
        if (value == null || value.length() < 16) {
            return false;
        }
        int allowed = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '+' || c == '/' || c == '=' || c == '-' || c == '_'
                    || c == '\n' || c == '\r') {
                allowed++;
            }
        }
        return allowed == value.length();
    }
}

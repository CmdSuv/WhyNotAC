package dev.whynotac.storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Zero-dependency RFC 8259 recursive-descent JSON parser with bounded nesting depth ({@link #MAX_DEPTH} = 32)
 * to guard against stack-overflow DoS, full Unicode escape handling (4-hex-digit escapes and surrogate pairs),
 * scientific-notation numbers, and field-order-independent object lookup.
 */
public final class MinimalJson {

    public static final int MAX_DEPTH = 32;

    private final String input;
    private final int length;
    private int pos;

    private MinimalJson(String input) {
        if (input == null) {
            throw new IllegalArgumentException("JSON input must not be null");
        }
        this.input = input;
        this.length = input.length();
    }

    /** Parses any valid RFC 8259 JSON value ({@code Map}, {@code List}, {@code String}, {@code Number}, {@code Boolean}, or {@code null}). */
    public static Object parse(String json) {
        MinimalJson parser = new MinimalJson(json);
        parser.skipWhitespace();
        Object value = parser.parseValue(0);
        parser.skipWhitespace();
        if (parser.pos != parser.length) {
            throw new IllegalArgumentException("Unexpected trailing character at index " + parser.pos);
        }
        return value;
    }

    /** Parses a JSON object ({@code {...}}) into an insertion-ordered {@link Map}. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object val = parse(json);
        if (!(val instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Expected JSON object");
        }
        return (Map<String, Object>) val;
    }

    private Object parseValue(int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("JSON nesting exceeds maximum depth " + MAX_DEPTH);
        }
        skipWhitespace();
        if (pos >= length) {
            throw new IllegalArgumentException("Unexpected end of JSON input");
        }
        char c = input.charAt(pos);
        return switch (c) {
            case '{' -> parseObjectInternal(depth + 1);
            case '[' -> parseArrayInternal(depth + 1);
            case '"' -> parseString();
            case 't' -> parseLiteral("true", Boolean.TRUE);
            case 'f' -> parseLiteral("false", Boolean.FALSE);
            case 'n' -> parseLiteral("null", null);
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) {
                    yield parseNumber();
                }
                throw new IllegalArgumentException("Unexpected character '" + c + "' at index " + pos);
            }
        };
    }

    private Map<String, Object> parseObjectInternal(int depth) {
        pos++; // '{'
        Map<String, Object> map = new LinkedHashMap<>();
        skipWhitespace();
        if (pos < length && input.charAt(pos) == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (pos >= length || input.charAt(pos) != '"') {
                throw new IllegalArgumentException("Expected object key string at index " + pos);
            }
            String key = parseString();
            skipWhitespace();
            if (pos >= length || input.charAt(pos) != ':') {
                throw new IllegalArgumentException("Expected ':' after key at index " + pos);
            }
            pos++; // ':'
            Object value = parseValue(depth);
            map.put(key, value);
            skipWhitespace();
            if (pos >= length) {
                throw new IllegalArgumentException("Unterminated JSON object");
            }
            char next = input.charAt(pos++);
            if (next == '}') {
                return map;
            }
            if (next != ',') {
                throw new IllegalArgumentException("Expected ',' or '}' in object at index " + (pos - 1));
            }
        }
    }

    private List<Object> parseArrayInternal(int depth) {
        pos++; // '['
        List<Object> list = new ArrayList<>();
        skipWhitespace();
        if (pos < length && input.charAt(pos) == ']') {
            pos++;
            return list;
        }
        while (true) {
            list.add(parseValue(depth));
            skipWhitespace();
            if (pos >= length) {
                throw new IllegalArgumentException("Unterminated JSON array");
            }
            char next = input.charAt(pos++);
            if (next == ']') {
                return list;
            }
            if (next != ',') {
                throw new IllegalArgumentException("Expected ',' or ']' in array at index " + (pos - 1));
            }
        }
    }

    private String parseString() {
        pos++; // '"'
        StringBuilder sb = new StringBuilder();
        while (pos < length) {
            char c = input.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c < 0x20) {
                throw new IllegalArgumentException("Unescaped control character in string at index " + (pos - 1));
            }
            if (c == '\\') {
                if (pos >= length) {
                    throw new IllegalArgumentException("Unterminated escape sequence");
                }
                char esc = input.charAt(pos++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > length) {
                            throw new IllegalArgumentException("Truncated \\u escape at index " + pos);
                        }
                        int code = parseHex4(pos);
                        pos += 4;
                        sb.append((char) code);
                    }
                    default -> throw new IllegalArgumentException("Invalid escape '\\" + esc + "' at index " + (pos - 1));
                }
            } else {
                sb.append(c);
            }
        }
        throw new IllegalArgumentException("Unterminated JSON string");
    }

    private int parseHex4(int start) {
        int val = 0;
        for (int i = 0; i < 4; i++) {
            char h = input.charAt(start + i);
            int d = Character.digit(h, 16);
            if (d < 0) {
                throw new IllegalArgumentException("Invalid hex digit '" + h + "' in \\u escape at index " + (start + i));
            }
            val = (val << 4) | d;
        }
        return val;
    }

    private Number parseNumber() {
        int start = pos;
        if (input.charAt(pos) == '-') {
            pos++;
        }
        if (pos >= length) {
            throw new IllegalArgumentException("Incomplete number at index " + start);
        }
        if (input.charAt(pos) == '0') {
            pos++;
        } else if (input.charAt(pos) >= '1' && input.charAt(pos) <= '9') {
            while (pos < length && input.charAt(pos) >= '0' && input.charAt(pos) <= '9') {
                pos++;
            }
        } else {
            throw new IllegalArgumentException("Invalid number at index " + start);
        }
        boolean floating = false;
        if (pos < length && input.charAt(pos) == '.') {
            floating = true;
            pos++;
            int fracStart = pos;
            while (pos < length && input.charAt(pos) >= '0' && input.charAt(pos) <= '9') {
                pos++;
            }
            if (pos == fracStart) {
                throw new IllegalArgumentException("Missing fractional digits in number at index " + start);
            }
        }
        if (pos < length && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
            floating = true;
            pos++;
            if (pos < length && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) {
                pos++;
            }
            int expStart = pos;
            while (pos < length && input.charAt(pos) >= '0' && input.charAt(pos) <= '9') {
                pos++;
            }
            if (pos == expStart) {
                throw new IllegalArgumentException("Missing exponent digits in number at index " + start);
            }
        }
        String token = input.substring(start, pos);
        if (!floating) {
            try {
                return Long.parseLong(token);
            } catch (NumberFormatException ignored) {
                // Fall through to double for integers exceeding Long range
            }
        }
        double d = Double.parseDouble(token);
        if (!Double.isFinite(d)) {
            throw new IllegalArgumentException("Non-finite JSON number at index " + start);
        }
        return d;
    }

    private Object parseLiteral(String expected, Object value) {
        if (!input.regionMatches(pos, expected, 0, expected.length())) {
            throw new IllegalArgumentException("Expected '" + expected + "' at index " + pos);
        }
        pos += expected.length();
        return value;
    }

    private void skipWhitespace() {
        while (pos < length) {
            char c = input.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    // --- Typed accessors & escaping ---

    public static String getString(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        return v instanceof String s ? s : null;
    }

    public static String requireString(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        if (!(v instanceof String s)) {
            throw new IllegalArgumentException("missing string field " + key);
        }
        return s;
    }

    public static long getLong(Map<String, Object> obj, String key, long defaultValue) {
        Object v = obj.get(key);
        return v instanceof Number n ? n.longValue() : defaultValue;
    }

    public static long requireLong(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        if (!(v instanceof Number n)) {
            throw new IllegalArgumentException("missing numeric field " + key);
        }
        return n.longValue();
    }

    public static double getDouble(Map<String, Object> obj, String key, double defaultValue) {
        Object v = obj.get(key);
        return v instanceof Number n ? n.doubleValue() : defaultValue;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getObject(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> getArray(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        return v instanceof List<?> l ? (List<Object>) l : null;
    }

    public static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}

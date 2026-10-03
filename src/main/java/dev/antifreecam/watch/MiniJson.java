package dev.antifreecam.watch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MiniJson {
    private final String s;
    private int p;

    private MiniJson(String s) { this.s = s == null ? "" : s; }

    public static Object parse(String json) {
        MiniJson parser = new MiniJson(json);
        Object value = parser.value();
        parser.ws();
        if (parser.p != parser.s.length()) throw new IllegalArgumentException("Trailing JSON data");
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(String json) {
        Object value = parse(json);
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("JSON object expected");
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> array(String json) {
        Object value = parse(json);
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("JSON array expected");
        return (List<Object>) list;
    }

    private Object value() {
        ws();
        if (p >= s.length()) throw error("Unexpected end");
        char c = s.charAt(p);
        return switch (c) {
            case '{' -> objectValue();
            case '[' -> arrayValue();
            case '"' -> stringValue();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> numberValue();
        };
    }

    private Map<String, Object> objectValue() {
        Map<String, Object> out = new LinkedHashMap<>();
        p++;
        ws();
        if (take('}')) return out;
        while (true) {
            ws();
            if (p >= s.length() || s.charAt(p) != '"') throw error("Object key expected");
            String key = stringValue();
            ws();
            if (!take(':')) throw error("':' expected");
            out.put(key, value());
            ws();
            if (take('}')) return out;
            if (!take(',')) throw error("',' expected");
        }
    }

    private List<Object> arrayValue() {
        List<Object> out = new ArrayList<>();
        p++;
        ws();
        if (take(']')) return out;
        while (true) {
            out.add(value());
            ws();
            if (take(']')) return out;
            if (!take(',')) throw error("',' expected");
        }
    }

    private String stringValue() {
        if (!take('"')) throw error("String expected");
        StringBuilder out = new StringBuilder();
        while (p < s.length()) {
            char c = s.charAt(p++);
            if (c == '"') return out.toString();
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (p >= s.length()) throw error("Bad escape");
            char e = s.charAt(p++);
            switch (e) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (p + 4 > s.length()) throw error("Bad unicode escape");
                    int code = Integer.parseInt(s.substring(p, p + 4), 16);
                    p += 4;
                    out.append((char) code);
                }
                default -> throw error("Unknown escape: " + e);
            }
        }
        throw error("Unclosed string");
    }

    private Object numberValue() {
        int start = p;
        if (s.charAt(p) == '-') p++;
        while (p < s.length() && Character.isDigit(s.charAt(p))) p++;
        boolean decimal = false;
        if (p < s.length() && s.charAt(p) == '.') {
            decimal = true; p++;
            while (p < s.length() && Character.isDigit(s.charAt(p))) p++;
        }
        if (p < s.length() && (s.charAt(p) == 'e' || s.charAt(p) == 'E')) {
            decimal = true; p++;
            if (p < s.length() && (s.charAt(p) == '+' || s.charAt(p) == '-')) p++;
            while (p < s.length() && Character.isDigit(s.charAt(p))) p++;
        }
        String raw = s.substring(start, p);
        try {
            if (decimal) return Double.parseDouble(raw);
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw error("Bad number: " + raw);
        }
    }

    private Object literal(String literal, Object value) {
        if (!s.startsWith(literal, p)) throw error("Expected " + literal);
        p += literal.length();
        return value;
    }

    private void ws() {
        while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++;
    }

    private boolean take(char c) {
        if (p < s.length() && s.charAt(p) == c) { p++; return true; }
        return false;
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at " + p);
    }

    private MiniJson() { throw new AssertionError(); }
}

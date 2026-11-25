package org.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试桩（**只给 tools/ 下的桌面测试用，不打进 APK**）：实现 SttClient 用到的那几个方法。
 * 目的不是做一个完整 JSON 库，而是让真实的 SttClient 能在桌面 JDK17 上跑起来被验证。
 */
public class JSONObject {

    final Map<String, Object> map = new LinkedHashMap<>();

    public JSONObject() {
    }

    public JSONObject(String src) throws JSONException {
        int[] pos = {0};
        Object v = parseValue(src, pos);
        if (!(v instanceof JSONObject)) {
            throw new JSONException("not an object: " + src);
        }
        map.putAll(((JSONObject) v).map);
        skipWs(src, pos);
        if (pos[0] != src.length()) {
            throw new JSONException("trailing data at " + pos[0]);
        }
    }

    // ---------------------------------------------------------------- SttClient 用到的 API

    public boolean has(String k) {
        return map.containsKey(k);
    }

    public boolean isNull(String k) {
        return !map.containsKey(k) || map.get(k) == null;
    }

    public Object opt(String k) {
        return map.get(k);
    }

    public String optString(String k, String def) {
        Object v = map.get(k);
        if (v == null) {
            return def;
        }
        if (v instanceof String) {
            return (String) v;
        }
        if (v instanceof Boolean || v instanceof Number) {
            return String.valueOf(v);
        }
        return def;
    }

    public boolean optBoolean(String k, boolean def) {
        Object v = map.get(k);
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof String) {
            String s = ((String) v).trim();
            if ("true".equalsIgnoreCase(s)) return true;
            if ("false".equalsIgnoreCase(s)) return false;
        }
        return def;
    }

    public double optDouble(String k, double def) {
        Object v = map.get(k);
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        if (v instanceof String) {
            try {
                return Double.parseDouble(((String) v).trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    public int optInt(String k, int def) {
        Object v = map.get(k);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v instanceof String) {
            try {
                return Integer.parseInt(((String) v).trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    public JSONArray optJSONArray(String k) {
        Object v = map.get(k);
        return v instanceof JSONArray ? (JSONArray) v : null;
    }

    public JSONObject optJSONObject(String k) {
        Object v = map.get(k);
        return v instanceof JSONObject ? (JSONObject) v : null;
    }

    @Override
    public String toString() {
        return "JSONObject" + map;
    }

    // ---------------------------------------------------------------- 极简解析器

    static void skipWs(String s, int[] pos) {
        while (pos[0] < s.length() && Character.isWhitespace(s.charAt(pos[0]))) {
            pos[0]++;
        }
    }

    static Object parseValue(String s, int[] pos) throws JSONException {
        skipWs(s, pos);
        if (pos[0] >= s.length()) {
            throw new JSONException("unexpected end of input");
        }
        char c = s.charAt(pos[0]);
        if (c == '{') {
            return parseObject(s, pos);
        }
        if (c == '[') {
            return parseArray(s, pos);
        }
        if (c == '"') {
            return parseString(s, pos);
        }
        if (s.startsWith("true", pos[0])) {
            pos[0] += 4;
            return Boolean.TRUE;
        }
        if (s.startsWith("false", pos[0])) {
            pos[0] += 5;
            return Boolean.FALSE;
        }
        if (s.startsWith("null", pos[0])) {
            pos[0] += 4;
            return null;
        }
        return parseNumber(s, pos);
    }

    private static JSONObject parseObject(String s, int[] pos) throws JSONException {
        JSONObject o = new JSONObject();
        pos[0]++; // {
        skipWs(s, pos);
        if (pos[0] < s.length() && s.charAt(pos[0]) == '}') {
            pos[0]++;
            return o;
        }
        while (true) {
            skipWs(s, pos);
            String key = parseString(s, pos);
            skipWs(s, pos);
            if (pos[0] >= s.length() || s.charAt(pos[0]) != ':') {
                throw new JSONException("expected ':' at " + pos[0]);
            }
            pos[0]++;
            Object v = parseValue(s, pos);
            o.map.put(key, v);
            skipWs(s, pos);
            if (pos[0] >= s.length()) {
                throw new JSONException("unterminated object");
            }
            char c = s.charAt(pos[0]++);
            if (c == '}') {
                return o;
            }
            if (c != ',') {
                throw new JSONException("expected ',' or '}' at " + (pos[0] - 1));
            }
        }
    }

    private static JSONArray parseArray(String s, int[] pos) throws JSONException {
        JSONArray a = new JSONArray();
        pos[0]++; // [
        skipWs(s, pos);
        if (pos[0] < s.length() && s.charAt(pos[0]) == ']') {
            pos[0]++;
            return a;
        }
        while (true) {
            Object v = parseValue(s, pos);
            a.items.add(v);
            skipWs(s, pos);
            if (pos[0] >= s.length()) {
                throw new JSONException("unterminated array");
            }
            char c = s.charAt(pos[0]++);
            if (c == ']') {
                return a;
            }
            if (c != ',') {
                throw new JSONException("expected ',' or ']' at " + (pos[0] - 1));
            }
        }
    }

    private static String parseString(String s, int[] pos) throws JSONException {
        if (pos[0] >= s.length() || s.charAt(pos[0]) != '"') {
            throw new JSONException("expected string at " + pos[0]);
        }
        pos[0]++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos[0] >= s.length()) {
                throw new JSONException("unterminated string");
            }
            char c = s.charAt(pos[0]++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos[0] >= s.length()) {
                throw new JSONException("bad escape");
            }
            char e = s.charAt(pos[0]++);
            switch (e) {
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case '/': sb.append('/'); break;
                case '\\': sb.append('\\'); break;
                case '"': sb.append('"'); break;
                case 'u':
                    if (pos[0] + 4 > s.length()) {
                        throw new JSONException("bad \\u escape");
                    }
                    sb.append((char) Integer.parseInt(s.substring(pos[0], pos[0] + 4), 16));
                    pos[0] += 4;
                    break;
                default:
                    throw new JSONException("bad escape \\" + e);
            }
        }
    }

    private static Object parseNumber(String s, int[] pos) throws JSONException {
        int start = pos[0];
        while (pos[0] < s.length()
                && "+-0123456789.eE".indexOf(s.charAt(pos[0])) >= 0) {
            pos[0]++;
        }
        if (start == pos[0]) {
            throw new JSONException("unexpected char '" + s.charAt(start) + "' at " + start);
        }
        String t = s.substring(start, pos[0]);
        try {
            if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) {
                return Long.valueOf(t);
            }
            return Double.valueOf(t);
        } catch (NumberFormatException e) {
            throw new JSONException("bad number: " + t);
        }
    }
}

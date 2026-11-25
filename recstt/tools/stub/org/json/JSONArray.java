package org.json;

import java.util.ArrayList;
import java.util.List;

/** 测试桩：解析 "[{...},{...}]" 这种扁平数组，够 SttClient 的 segments 用。 */
public class JSONArray {
    final List<Object> items = new ArrayList<>();

    public JSONArray(String src) throws JSONException {
        int[] pos = {0};
        Object v = JSONObject.parseValue(src, pos);
        if (!(v instanceof List)) {
            throw new JSONException("not an array: " + src);
        }
        @SuppressWarnings("unchecked")
        List<Object> l = (List<Object>) v;
        items.addAll(l);
        JSONObject.skipWs(src, pos);
        if (pos[0] != src.length()) {
            throw new JSONException("trailing data at " + pos[0]);
        }
    }

    public JSONArray() {
    }

    public int length() {
        return items.size();
    }

    public Object opt(int i) {
        return i < 0 || i >= items.size() ? null : items.get(i);
    }

    public JSONObject optJSONObject(int i) {
        Object v = opt(i);
        return v instanceof JSONObject ? (JSONObject) v : null;
    }
}

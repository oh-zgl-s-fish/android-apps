package org.json;

/** 测试桩：只为让 SttClient 在桌面 JDK 上编译/运行，不参与 APK。 */
public class JSONException extends Exception {
    private static final long serialVersionUID = 1L;

    public JSONException(String message) {
        super(message);
    }

    public JSONException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.zglinus.recstt.ui;

import com.zglinus.recstt.model.Seg;

import java.util.List;
import java.util.Locale;

/**
 * 把分段列表写成 SRT 字幕文本。
 *
 * <p>纯静态工具：只依赖 {@link Seg} 与 java.util，不碰 android.*、不碰 Activity，
 * 这样桌面端也能直接跑单元测试（和 tpl/TplEngine 同一个待遇）。
 *
 * <p>输出与 SRT 规范对齐：序号从 1 递增、`HH:MM:SS,mmm --> HH:MM:SS,mmm`、
 * 正文可多行、每条之间空一行、结尾留一个换行。
 *
 * <p>几处刻意的取舍（宁可有据可查，也不静默丢数据）：
 * <ul>
 *   <li>null 分段直接跳过；</li>
 *   <li>空文本（null / 全空白）也跳过 —— 空的字幕条在播放器里是“闪一下”，不是信息；</li>
 *   <li>endMs 缺失或 &lt;= startMs 时补成 start+1s（不补成 0，避免时长 0 的条目被杀软/播放器吞掉）；</li>
 *   <li>startMs 为负按 0 处理（时间戳不能回退到负数）。</li>
 * </ul>
 */
public final class Srt {

    /** 缺 endMs 时给的默认句长（毫秒）。 */
    private static final int FALLBACK_DUR_MS = 1000;

    private Srt() {
    }

    /**
     * 分段列表 → SRT 文本。
     *
     * @param segs 分段（null 视为空列表）
     * @return SRT 文本；没有有效分段时返回空字符串
     */
    public static String build(List<Seg> segs) {
        if (segs == null || segs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(segs.size() * 80);
        int no = 0;
        for (int i = 0; i < segs.size(); i++) {
            Seg s = segs.get(i);
            if (s == null) {
                continue;
            }
            String text = normalizeText(s.text);
            if (text.isEmpty()) {
                continue;
            }
            long start = Math.max(0L, (long) s.startMs);
            long end = (long) s.endMs;
            if (end <= start) {
                end = start + FALLBACK_DUR_MS;
            }
            no++;
            sb.append(no).append('\n');
            sb.append(stamp(start)).append(" --> ").append(stamp(end)).append('\n');
            sb.append(text).append('\n');
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 毫秒 → `HH:MM:SS,mmm`（SRT 用小逗号当小数分隔符，这点别改成点）。 */
    public static String stamp(long ms) {
        long v = Math.max(0L, ms);
        long h = v / 3600000L;
        long m = (v % 3600000L) / 60000L;
        long s = (v % 60000L) / 1000L;
        long mm = v % 1000L;
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, mm);
    }

    /** 统一换行、去首尾空白；空文本返回 ""。 */
    private static String normalizeText(String raw) {
        if (raw == null) {
            return "";
        }
        String t = raw.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (t.isEmpty()) {
            return "";
        }
        // 只把连续空行压成一个，正常的句内换行保留
        StringBuilder sb = new StringBuilder(t.length());
        boolean prevBlank = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\n') {
                if (prevBlank) {
                    continue;
                }
                prevBlank = true;
            } else {
                prevBlank = false;
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }
}

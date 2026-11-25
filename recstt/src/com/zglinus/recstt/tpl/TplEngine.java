package com.zglinus.recstt.tpl;

import com.zglinus.recstt.model.Tpl;

import java.nio.charset.StandardCharsets;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 文件名 → 元数据（姓名 / 号码 / 开始时间）。
 *
 * <p><b>纯 Java，禁止 import android.*</b>：这个类的逻辑要能在桌面上用
 * {@code tools/TplTest} 直接跑，所以不许碰任何 Android API，也不许碰文件系统。
 *
 * <p>解析顺序：模板按 priority 升序，首个正则命中即停；全都不命中才走兜底
 * （在文件名里找 14 位时间戳，timeSource="fallback_ts"）。连时间都找不到时
 * timeSource="mtime"、startMs=0 —— 本类拿不到 mtime，由 Scanner 用文件
 * mtime 回填；Scanner 也拿不到时才算 timeSource="none"。
 */
public final class TplEngine {

    /** 兜底模板的伪 id（没有模板命中时写进 Hit.tplId / Rec.templateId）。 */
    public static final String TPL_FALLBACK = "fallback_ts";

    // 14 位时间戳，两边不许再连着数字（避免把 15/16 位串切一半）
    private static final Pattern TS14 = Pattern.compile("(?<!\\d)(\\d{14})(?!\\d)");

    // 时间落在 2000-01-01 .. 2100-01-01 之间才认，挡掉 00000000000000 这类垃圾
    private static final long MIN_SANE_MS = 946684800000L;
    private static final long MAX_SANE_MS = 4102444800000L;

    private static final String[] TS_FORMATS = {
            "yyyyMMddHHmmss",
            "yyyyMMddHHmm",
            "yyyyMMdd",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HHmmss",
            "yyyy-MM-dd_HHmmss",
            "yyyy-MM-dd-HHmmss",
            "yyyy-MM-dd HH-mm-ss",
            "yyyy-MM-dd_HH-mm-ss",
            "yyyyMMdd_HHmmss",
            "yyyyMMdd-HHmmss",
            "yyyy/M/d H:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy年MM月dd日HHmmss",
            "yyyy年MM月dd日 HH:mm:ss",
    };

    private static final ConcurrentHashMap<String, Pattern> CACHE = new ConcurrentHashMap<String, Pattern>();
    private static final int CACHE_MAX = 512;

    private TplEngine() {
    }

    /** 解析结果。matched 只代表“有模板命中”；兜底命中时 matched=false 且 tplId=fallback_ts。 */
    public static final class Hit {
        public boolean matched;
        public String tplId;
        public String tplName;
        public String name;
        public String phone;
        public String phoneKind;
        public String timeSource;
        public String error;
        /** 0 表示没取到。 */
        public long startMs;
    }

    // ---------------------------------------------------------------- 内置模板

    /** 4 条内置模板：miui_call 10 / wx_voice 20 / recorder_named 30 / generic_ts 90。 */
    public static List<Tpl> builtin() {
        List<Tpl> out = new ArrayList<Tpl>(4);
        out.add(make("miui_call", "MIUI 通话录音（姓名@号码_时间）", 10,
                "{姓名}@{号码}_{时间}", "yyyyMMddHHmmss"));
        out.add(make("wx_voice", "微信语音（微信语音_时间）", 20,
                "微信语音_{时间}", "yyyyMMddHHmmss"));
        out.add(make("recorder_named", "录音机（名称_时间）", 30,
                "{名称}_{时间}", "yyyyMMddHHmmss"));
        out.add(make("generic_ts", "通用（纯时间戳）", 90,
                "{时间}", "yyyyMMddHHmmss"));
        return out;
    }

    private static Tpl make(String id, String name, int priority, String format, String timeFmt) {
        Tpl t = new Tpl();
        t.id = id;
        t.name = name;
        t.format = format;
        t.pattern = formatToPattern(format);
        t.timeFmt = timeFmt;
        t.scope = "audio";
        t.builtin = true;
        t.enabled = true;
        t.priority = priority;
        return t;
    }

    // ---------------------------------------------------------------- 人话 <-> 正则

    /**
     * 人话格式串 → 正则。
     *
     * <pre>
     * "{姓名}@{号码}_{时间}" -> "^(?:(?&lt;name&gt;.+?)@)?(?&lt;phone&gt;[0-9][0-9 \-]{2,24}?)_(?&lt;ts&gt;\\d{14})$"
     * </pre>
     *
     * 支持 {姓名}{名称}{号码}{时间}{时长}；其余字符按字面量转义。
     */
    public static String formatToPattern(String format) {
        if (format == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder("^");
        int i = 0;
        while (i < format.length()) {
            char c = format.charAt(i);
            if (c != '{') {
                appendLiteral(sb, c);
                i++;
                continue;
            }
            int close = format.indexOf('}', i);
            if (close < 0) {
                appendLiteral(sb, c);
                i++;
                continue;
            }
            String token = format.substring(i + 1, close).trim();
            String rest = format.substring(close + 1);
            if (token.equals("姓名") || token.equals("名称")) {
                String inner = token.equals("姓名") ? ".+?" : "[^_]+?";
                if (rest.startsWith("@")) {
                    // MIUI：姓名后面的 @ 一起做可选组
                    sb.append("(?:(?<name>").append(inner).append(")@)?");
                    i = close + 2;
                } else {
                    sb.append("(?<name>").append(inner).append(")");
                    i = close + 1;
                }
            } else if (token.equals("号码")) {
                sb.append("(?<phone>[0-9][0-9 \\-]{2,24}?)");
                i = close + 1;
            } else if (token.equals("时间")) {
                sb.append("(?<ts>\\d{14})");
                i = close + 1;
            } else if (token.equals("时长")) {
                sb.append("(?<dur>\\d{1,7})");
                i = close + 1;
            } else {
                // 不认识的占位符：原样当字面量，绝不吞
                for (int k = i; k <= close; k++) {
                    appendLiteral(sb, format.charAt(k));
                }
                i = close + 1;
            }
        }
        sb.append('$');
        return sb.toString();
    }

    /**
     * 正则 → 人话格式串（能反推就反推，反推不出就原样返回，不抛异常）。
     *
     * <p>覆盖 {@link #formatToPattern} 生成的全部形状；遇到手写的高级正则
     * （非贪婪组里塞了别的元字符、分支、量词等）直接返回原名。
     */
    public static String humanize(String pattern) {
        if (pattern == null || pattern.trim().isEmpty()) {
            return pattern;
        }
        String p = pattern.trim();
        if (p.startsWith("^")) {
            p = p.substring(1);
        }
        if (p.endsWith("$") && !p.endsWith("\\$")) {
            p = p.substring(0, p.length() - 1);
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < p.length()) {
            char c = p.charAt(i);
            if (c == '\\') {
                if (i + 1 >= p.length()) {
                    return pattern;
                }
                out.append(p.charAt(i + 1));
                i += 2;
                continue;
            }
            // 可选包： (?:(?<name>.+?)@)?
            if (p.startsWith("(?:(?<name>", i)) {
                int end = p.indexOf(")@)?", i);
                if (end < 0) {
                    return pattern;
                }
                String inner = p.substring(i + 11, end);
                String tok = nameToken(inner);
                if (tok == null) {
                    return pattern;
                }
                out.append(tok).append('@');
                i = end + 4;
                continue;
            }
            int groupAt = groupHead(p, i);
            if (groupAt > 0) {
                // 取组名
                int nameStart = i + groupAt;
                int nameEnd = p.indexOf('>', nameStart);
                if (nameEnd < 0) {
                    return pattern;
                }
                String gname = p.substring(nameStart, nameEnd);
                int innerStart = nameEnd + 1;
                int innerEnd = findGroupEnd(p, innerStart);
                if (innerEnd < 0) {
                    return pattern;
                }
                String inner = p.substring(innerStart, innerEnd);
                String tok = tokenFor(gname, inner);
                if (tok == null) {
                    return pattern;
                }
                out.append(tok);
                i = innerEnd + 1;
                continue;
            }
            if (isMeta(c)) {
                return pattern; // 手工正则，反推不出
            }
            out.append(c);
            i++;
        }
        String s = out.toString();
        return s.isEmpty() ? pattern : s;
    }

    private static int groupHead(String p, int i) {
        if (p.startsWith("(?<", i)) {
            return 3; // "(?<" 后是组名
        }
        if (p.startsWith("(?P<", i)) {
            return 4; // Python 风格
        }
        return -1;
    }

    /** 从 innerStart 找到命名组的 ')'，跳过字符类里的 ')'。 */
    private static int findGroupEnd(String p, int innerStart) {
        boolean inClass = false;
        int i = innerStart;
        while (i < p.length()) {
            char c = p.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '[') {
                inClass = true;
            } else if (c == ']') {
                inClass = false;
            } else if (c == ')' && !inClass) {
                return i;
            } else if (c == '(' && !inClass) {
                return -1; // 嵌套组，认不出
            }
            i++;
        }
        return -1;
    }

    private static String nameToken(String inner) {
        if (".+?".equals(inner) || ".+".equals(inner) || "(.+?)".equals(inner)) {
            return "{姓名}";
        }
        if ("[^_]+?".equals(inner) || "[^_]+".equals(inner) || "([^_]+?)".equals(inner)) {
            return "{名称}";
        }
        return null;
    }

    private static String tokenFor(String gname, String inner) {
        if ("name".equals(gname)) {
            return nameToken(inner);
        }
        if ("phone".equals(gname)) {
            return inner.startsWith("[0-9]") || inner.startsWith("\\d") ? "{号码}" : null;
        }
        if ("ts".equals(gname)) {
            return inner.startsWith("\\d") ? "{时间}" : null;
        }
        if ("dur".equals(gname)) {
            return inner.startsWith("\\d") ? "{时长}" : null;
        }
        return null;
    }

    private static boolean isMeta(char c) {
        return c == '.' || c == '*' || c == '+' || c == '?' || c == '(' || c == ')'
                || c == '[' || c == ']' || c == '{' || c == '}' || c == '|' || c == '^' || c == '$';
    }

    private static void appendLiteral(StringBuilder sb, char c) {
        if (isMeta(c) || c == '\\') {
            sb.append('\\');
        }
        sb.append(c);
    }

    // ---------------------------------------------------------------- 解析

    /** 按 priority 升序试模板，首个命中即停；全不中走兜底。 */
    public static Hit parse(String fileName, List<Tpl> tpls) {
        String stem = stemOf(fileName);
        String firstError = null;
        if (tpls != null && !tpls.isEmpty()) {
            for (Tpl t : sortByPriority(tpls)) {
                String pattern = resolvePattern(t);
                if (pattern == null) {
                    continue;
                }
                Hit h;
                try {
                    h = match(t, pattern, stem);
                } catch (PatternSyntaxException e) {
                    if (firstError == null) {
                        firstError = "模板 " + (t.id == null ? "?" : t.id) + " 正则无效：" + e.getDescription();
                    }
                    continue;
                }
                if (h != null) {
                    return h;
                }
            }
        }
        Hit h = fallback(stem);
        if (firstError != null) {
            h.error = firstError;
        }
        return h;
    }

    /** 试跑单个模板：不兜底、不看 enabled；正则无效时把原因写进 error。 */
    public static Hit parseWithTemplate(Tpl t, String fileName) {
        String stem = stemOf(fileName);
        Hit h = new Hit();
        h.timeSource = "none";
        if (t == null) {
            h.error = "模板为空";
            return h;
        }
        String pattern = resolvePattern(t);
        if (pattern == null) {
            h.error = "模板没有 pattern 或 format";
            return h;
        }
        try {
            Hit m = match(t, pattern, stem);
            if (m != null) {
                return m;
            }
            h.error = "未命中";
            if (t.id != null) {
                h.tplId = t.id;
            }
            if (t.name != null) {
                h.tplName = t.name;
            }
            return h;
        } catch (PatternSyntaxException e) {
            h.error = "正则无效：" + e.getDescription();
            if (t.id != null) {
                h.tplId = t.id;
            }
            return h;
        }
    }

    private static Hit match(Tpl t, String pattern, String stem) {
        Matcher m = compile(pattern).matcher(stem);
        if (!m.matches()) {
            return null;
        }
        Hit h = new Hit();
        h.matched = true;
        h.tplId = t.id;
        h.tplName = t.name;
        h.name = trimToNull(group(m, "name"));
        h.phone = normalizePhone(group(m, "phone"));
        h.phoneKind = phoneKind(h.phone);
        long ms = parseTs(group(m, "ts"), t.timeFmt);
        if (ms > 0) {
            h.startMs = ms;
            h.timeSource = "filename";
        } else {
            // 模板命中了但时间没解析出来：再看文件名里有没有 14 位时间戳
            Matcher f = TS14.matcher(stem);
            if (f.find()) {
                long x = parseTs(f.group(1), null);
                if (x > 0) {
                    h.startMs = x;
                    h.timeSource = TPL_FALLBACK;
                }
            }
            if (h.startMs == 0) {
                h.timeSource = "mtime"; // Scanner 用文件 mtime 回填
            }
        }
        return h;
    }

    private static Hit fallback(String stem) {
        Hit h = new Hit();
        h.matched = false;
        h.timeSource = "mtime";
        Matcher m = TS14.matcher(stem);
        if (!m.find()) {
            return h;
        }
        long ms = parseTs(m.group(1), null);
        if (ms <= 0) {
            return h;
        }
        h.startMs = ms;
        h.timeSource = TPL_FALLBACK;
        h.tplId = TPL_FALLBACK;
        h.tplName = "文件名 14 位时间戳（兜底）";

        // 顺手把时间戳抠掉，剩下的部分能当姓名/号码就当
        String rest = (stem.substring(0, m.start()) + stem.substring(m.end())).trim();
        rest = trimEdges(rest, " _-@\t");
        if (!rest.isEmpty()) {
            int at = rest.lastIndexOf('@');
            if (at >= 0) {
                h.name = trimToNull(rest.substring(0, at));
                h.phone = normalizePhone(rest.substring(at + 1));
            } else if (looksLikePhone(rest)) {
                h.phone = normalizePhone(rest);
            } else {
                h.name = trimToNull(rest);
            }
            h.phoneKind = phoneKind(h.phone);
        }
        return h;
    }

    private static String resolvePattern(Tpl t) {
        if (t == null) {
            return null;
        }
        if (t.pattern != null && !t.pattern.trim().isEmpty()) {
            return t.pattern;
        }
        if (t.format != null && !t.format.trim().isEmpty()) {
            return formatToPattern(t.format);
        }
        return null;
    }

    private static List<Tpl> sortByPriority(List<Tpl> tpls) {
        List<Tpl> copy = new ArrayList<Tpl>(tpls.size());
        for (Tpl t : tpls) {
            if (t != null) {
                copy.add(t);
            }
        }
        Collections.sort(copy, new Comparator<Tpl>() {
            @Override
            public int compare(Tpl a, Tpl b) {
                return Integer.compare(a.priority, b.priority);
            }
        });
        return copy;
    }

    private static Pattern compile(String regex) {
        Pattern p = CACHE.get(regex);
        if (p != null) {
            return p;
        }
        p = Pattern.compile(regex);
        if (CACHE.size() > CACHE_MAX) {
            CACHE.clear();
        }
        CACHE.put(regex, p);
        return p;
    }

    private static String group(Matcher m, String name) {
        try {
            return m.group(name);
        } catch (IllegalArgumentException e) {
            return null; // 该模板没有这个组
        }
    }

    // ---------------------------------------------------------------- 号码 / 时间

    /**
     * 号码归一：去空格 / - / +86 / 0086。
     *
     * @return 纯数字；没有数字返回 null
     */
    private static String normalizePhone(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            }
        }
        String d = sb.toString();
        if (d.startsWith("0086")) {
            d = d.substring(4);
        } else if (d.startsWith("86") && d.length() == 13) {
            d = d.substring(2);
        }
        return d.isEmpty() ? null : d;
    }

    /** mobile：11 位且 1 开头；landline：0 开头；short：<=6 位；其余 unknown。 */
    private static String phoneKind(String digits) {
        if (digits == null || digits.isEmpty()) {
            return null;
        }
        if (digits.length() == 11 && digits.charAt(0) == '1') {
            return "mobile";
        }
        if (digits.charAt(0) == '0') {
            return "landline";
        }
        if (digits.length() <= 6) {
            return "short";
        }
        return "unknown";
    }

    private static boolean looksLikePhone(String s) {
        if (s == null || s.isEmpty() || s.length() > 25) {
            return false;
        }
        int digits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                digits++;
            } else if (c != ' ' && c != '-' && c != '+') {
                return false;
            }
        }
        return digits >= 3;
    }

    /**
     * 时间串 → epoch ms。先按 timeFmt，失败再按内置候选格式，最后认 10/13 位
     * epoch 秒/毫秒；都不成返回 0。
     */
    private static long parseTs(String raw, String fmt) {
        if (raw == null) {
            return 0;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return 0;
        }
        long ms = 0;
        if (fmt != null && !fmt.trim().isEmpty()) {
            ms = parseWith(s, fmt.trim());
        }
        if (ms <= 0) {
            for (String f : TS_FORMATS) {
                ms = parseWith(s, f);
                if (ms > 0) {
                    break;
                }
            }
        }
        if (ms <= 0 && s.matches("\\d{13}")) {
            ms = Long.parseLong(s);
        }
        if (ms <= 0 && s.matches("\\d{10}")) {
            ms = Long.parseLong(s) * 1000L;
        }
        return (ms >= MIN_SANE_MS && ms <= MAX_SANE_MS) ? ms : 0;
    }

    private static long parseWith(String s, String fmt) {
        try {
            SimpleDateFormat f = new SimpleDateFormat(fmt, Locale.US);
            f.setLenient(false);
            ParsePosition pos = new ParsePosition(0);
            Date d = f.parse(s, pos);
            if (d == null || pos.getIndex() != s.length()) {
                return 0;
            }
            return d.getTime();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- 杂项

    private static final String[] AUDIO_EXT = {
            "m4a", "mp3", "wav", "amr", "ogg", "opus", "aac", "flac",
            "mp4", "3gp", "m4b", "wma", "caf", "aiff", "aif", "oga", "mka", "silk", "pcm",
    };

    /** 去掉目录和音频扩展名。 */
    private static String stemOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        String s = fileName.trim();
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) {
            s = s.substring(slash + 1);
        }
        int dot = s.lastIndexOf('.');
        if (dot > 0) {
            String ext = s.substring(dot + 1).toLowerCase(Locale.US);
            for (String e : AUDIO_EXT) {
                if (e.equals(ext)) {
                    s = s.substring(0, dot);
                    break;
                }
            }
        }
        return s;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String trimEdges(String s, String junk) {
        int a = 0;
        int b = s.length();
        while (a < b && junk.indexOf(s.charAt(a)) >= 0) {
            a++;
        }
        while (b > a && junk.indexOf(s.charAt(b - 1)) >= 0) {
            b--;
        }
        return s.substring(a, b);
    }

    /**
     * 稳定 64 位正数（FNV-1a），路径不变 id 就不变；用作 rec 主键。
     */
    public static long hashPath(String path) {
        String p = path == null ? "" : path;
        long h = 0xcbf29ce484222325L;
        for (byte b : p.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xffL);
            h *= 0x100000001b3L;
        }
        h &= 0x7fffffffffffffffL;
        return h == 0 ? 1L : h;
    }
}

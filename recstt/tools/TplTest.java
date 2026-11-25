import com.zglinus.recstt.model.Tpl;
import com.zglinus.recstt.tpl.TplEngine;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 桌面验收：拿真实的 434 个录音文件名跑 TplEngine.builtin()，打印命中率。
 *
 * <p>纯桌面 main，不依赖 android.jar：
 * <pre>
 *   javac -encoding UTF-8 -d /tmp/tplt \
 *       src/com/zglinus/recstt/model/Tpl.java \
 *       src/com/zglinus/recstt/tpl/TplEngine.java \
 *       tools/TplTest.java
 *   java -cp /tmp/tplt TplTest
 *   java -cp /tmp/tplt TplTest /path/to/names.txt
 * </pre>
 */
public final class TplTest {

    private static final String DEFAULT_NAMES = "proto/names.txt";

    /** 契约里逐字写死的那条正则。 */
    private static final String MIUI_EXPECTED =
            "^(?:(?<name>.+?)@)?(?<phone>[0-9][0-9 \\-]{2,24}?)_(?<ts>\\d{14})$";

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : DEFAULT_NAMES;
        List<String> names = readNames(path);
        if (names.isEmpty()) {
            System.err.println("读不到文件名（文件不存在或为空）: " + path);
            System.exit(2);
            return;
        }

        List<Tpl> tpls = TplEngine.builtin();
        System.out.println("=== recstt TplEngine 实测（纯桌面，不依赖 android.jar） ===");
        System.out.println("样本文件: " + path);
        System.out.println("内置模板: " + tpls.size() + " 条  "
                + tpls.get(0).id + "/" + tpls.get(0).priority + "  "
                + tpls.get(1).id + "/" + tpls.get(1).priority + "  "
                + tpls.get(2).id + "/" + tpls.get(2).priority + "  "
                + tpls.get(3).id + "/" + tpls.get(3).priority);
        selfCheck(tpls);
        int branchFails = branchCheck(tpls);

        Map<String, Integer> byTpl = new LinkedHashMap<String, Integer>();
        for (Tpl t : tpls) {
            byTpl.put(t.id, 0);
        }
        int total = names.size();
        int named = 0;
        int withPhone = 0;
        int withTime = 0;
        int fallback = 0;
        int missing = 0;
        int noTime = 0;
        Map<String, Integer> timeSrc = new LinkedHashMap<String, Integer>();
        Map<String, Integer> kinds = new LinkedHashMap<String, Integer>();
        List<String> missingSamples = new ArrayList<String>();
        List<String> fallbackSamples = new ArrayList<String>();
        List<String> rows = new ArrayList<String>();

        for (String name : names) {
            TplEngine.Hit h = TplEngine.parse(name, tpls);
            if (h.matched) {
                String id = h.tplId == null ? "?" : h.tplId;
                Integer n = byTpl.get(id);
                byTpl.put(id, n == null ? 1 : n + 1);
            } else if (TplEngine.TPL_FALLBACK.equals(h.tplId)) {
                fallback++;
                if (fallbackSamples.size() < 5) {
                    fallbackSamples.add(name);
                }
            } else {
                missing++;
                if (missingSamples.size() < 10) {
                    missingSamples.add(name);
                }
            }
            if (h.name != null && !h.name.isEmpty()) {
                named++;
            }
            if (h.phone != null && !h.phone.isEmpty()) {
                withPhone++;
                String k = h.phoneKind == null ? "null" : h.phoneKind;
                Integer n = kinds.get(k);
                kinds.put(k, n == null ? 1 : n + 1);
            }
            if (h.startMs > 0) {
                withTime++;
            } else {
                noTime++;
            }
            String ts = h.timeSource == null ? "null" : h.timeSource;
            Integer n = timeSrc.get(ts);
            timeSrc.put(ts, n == null ? 1 : n + 1);

            if (rows.size() < 8) {
                rows.add(pad(name, 44) + " " + pad(idOr(h), 15) + " " + pad(orDash(h.name), 12)
                        + " " + pad(orDash(h.phone), 13) + " " + pad(fmtTime(h.startMs), 19)
                        + " " + orDash(h.timeSource));
            }
        }

        System.out.println();
        System.out.println("总数: " + total);
        System.out.println();
        System.out.println("各模板命中数:");
        for (Map.Entry<String, Integer> e : byTpl.entrySet()) {
            System.out.println("  " + pad(e.getKey(), 16) + String.format(Locale.US, "%4d  (%5.1f%%)",
                    e.getValue(), pct(e.getValue(), total)));
        }
        System.out.println("  " + pad("fallback_ts 兜底", 16)
                + String.format(Locale.US, "%4d  (%5.1f%%)", fallback, pct(fallback, total)));
        int templateMiss = total - countMatched(byTpl) - fallback;
        System.out.println("  " + pad("模板未命中", 16)
                + String.format(Locale.US, "%4d  (%5.1f%%)", templateMiss, pct(templateMiss, total)));
        System.out.println();

        System.out.println("元数据覆盖:");
        System.out.println("  有姓名  " + String.format(Locale.US, "%4d  (%5.1f%%)", named, pct(named, total)));
        System.out.println("  有号码  " + String.format(Locale.US, "%4d  (%5.1f%%)", withPhone, pct(withPhone, total)));
        System.out.println("  有时间  " + String.format(Locale.US, "%4d  (%5.1f%%)", withTime, pct(withTime, total)));
        System.out.println("  没时间  " + String.format(Locale.US, "%4d  (%5.1f%%)", noTime, pct(noTime, total)));
        System.out.println("  timeSource: " + timeSrc);
        System.out.println("  号码类型:   " + kinds);
        System.out.println();

        System.out.println("未命中（模板、兜底都不中）: " + missing);
        if (missingSamples.isEmpty()) {
            System.out.println("  样例: 无");
        } else {
            for (String s : missingSamples) {
                System.out.println("  - " + s);
            }
        }
        System.out.println("兜底命中（fallback_ts）: " + fallback);
        for (String s : fallbackSamples) {
            System.out.println("  - " + s);
        }
        System.out.println();

        System.out.println("解析示例（前 8 条）:");
        System.out.println("  " + pad("文件名", 44) + " " + pad("模板", 15) + " "
                + pad("姓名", 12) + " " + pad("号码", 13) + " " + pad("开始时间", 19) + " timeSource");
        for (String r : rows) {
            System.out.println("  " + r);
        }

        boolean ok = missing == 0 && branchFails == 0;
        System.out.println();
        System.out.println(ok ? "结果: PASS" : "结果: 有未命中，见上");
        if (!ok) {
            System.exit(1);
        }
    }

    // TplTest 里没有“已有命中数”的现成累加器，这里从 map 求和
    private static int countMatched(Map<String, Integer> byTpl) {
        int s = 0;
        for (Integer v : byTpl.values()) {
            s += v == null ? 0 : v;
        }
        return s;
    }

    // ---------------------------------------------------------------- 自检

    private static void selfCheck(List<Tpl> tpls) {
        boolean ok = true;

        Tpl miui = tpls.get(0);
        boolean exact = MIUI_EXPECTED.equals(miui.pattern);
        System.out.println("[自检] miui_call 正则与契约逐字一致              " + (exact ? "✓" : "✗ " + miui.pattern));
        ok &= exact;

        int sameCount = 0;
        for (Tpl t : tpls) {
            String expected = TplEngine.formatToPattern(t.format);
            if (expected != null && expected.equals(t.pattern)) {
                sameCount++;
            }
        }
        System.out.println("[自检] formatToPattern(format) == pattern      "
                + (sameCount == tpls.size() ? "✓ (" + sameCount + "/" + tpls.size() + ")"
                : "✗ (" + sameCount + "/" + tpls.size() + ")"));
        ok &= sameCount == tpls.size();

        int round = 0;
        StringBuilder human = new StringBuilder();
        for (Tpl t : tpls) {
            String h = TplEngine.humanize(t.pattern);
            if (t.format.equals(h)) {
                round++;
            }
            if (human.length() > 0) {
                human.append(" | ");
            }
            human.append(h == null ? "null" : h);
        }
        System.out.println("[自检] humanize 反推人话                      "
                + (round == tpls.size() ? "✓ (" + round + "/" + tpls.size() + ") → " + human
                : "✗ (" + round + "/" + tpls.size() + ") → " + human));
        ok &= round == tpls.size();

        String weird = "^[0-9]{3,}$";
        String back = TplEngine.humanize(weird);
        boolean same = weird.equals(back);
        System.out.println("[自检] humanize(高级正则) 原样返回不抛异常      " + (same ? "✓" : "✗ " + back));
        ok &= same;

        boolean nullSafe = TplEngine.humanize(null) == null && "".equals(TplEngine.humanize(""));
        System.out.println("[自检] humanize(null/空串) 不抛异常            " + (nullSafe ? "✓" : "✗"));
        ok &= nullSafe;

        System.out.println("[自检] 汇总                                   " + (ok ? "全部通过 ✓" : "存在失败 ✗"));
    }

    /**
     * 分支自检：434 条真实样本清一色是 miui_call 形状，光跑它们证明不了另外三条
     * 模板和兜底路径。这里用合成文件名把每条分支各踩一遍（不计入上面统计）。
     */
    private static int branchCheck(List<Tpl> tpls) {
        System.out.println();
        System.out.println("— 分支自检（合成文件名，不占上面 434 条统计）—");
        int fail = 0;
        fail += expect(tpls, "微信语音_20260101120000.m4a", "wx_voice", null, null, null, "filename", true);
        fail += expect(tpls, "家庭聚会_20260203141516.m4a", "recorder_named", "家庭聚会", null, null, "filename", true);
        fail += expect(tpls, "20260304161718.m4a", "generic_ts", null, null, null, "filename", true);
        fail += expect(tpls, "会议录音-20260305143000.m4a", TplEngine.TPL_FALLBACK,
                "会议录音", null, null, "fallback_ts", false);
        fail += expect(tpls, "voice_memo.m4a", null, null, null, null, "mtime", false);
        // 带 +86 / 0086 / 空格 / 横线的号码都要归一
        fail += expect(tpls, "8613800138000_20260101120000.m4a", "miui_call",
                null, "13800138000", "mobile", "filename", true);
        fail += expect(tpls, "李四@0086 139 0013 9000_20260101120000.m4a", "miui_call",
                "李四", "13900139000", "mobile", "filename", true);
        fail += expect(tpls, "赵六@138-0013-8001_20260101120000.m4a", "miui_call",
                "赵六", "13800138001", "mobile", "filename", true);
        fail += expect(tpls, "王五@0755 1234 5678_20260101120000.m4a", "miui_call",
                "王五", "075512345678", "landline", "filename", true);
        fail += expect(tpls, "客服@400 112 6921_20260101120000.m4a", "miui_call",
                "客服", "4001126921", "unknown", "filename", true);
        fail += expect(tpls, "值班@95066_20260101120000.m4a", "miui_call",
                "值班", "95066", "short", "filename", true);
        // 模板命中但 20261301 这个时间非法 → 不能瞎编时间，退回 mtime
        fail += expect(tpls, "张三@13800138000_20261301120000.m4a", "miui_call",
                "张三", "13800138000", "mobile", "mtime", true);

        // 字面量必须转义：a+b(1) 不能当正则元字符
        String fmt = "a+b(1)_{时间}";
        Tpl custom = new Tpl();
        custom.id = "custom";
        custom.name = "转义检查";
        custom.format = fmt;
        custom.pattern = TplEngine.formatToPattern(fmt);
        custom.timeFmt = "yyyyMMddHHmmss";
        custom.enabled = true;
        custom.priority = 1;
        List<Tpl> only = new ArrayList<Tpl>();
        only.add(custom);
        fail += expect(only, "a+b(1)_20260101120000.m4a", "custom", null, null, null, "filename", true);
        Tpl plus = new Tpl();
        plus.id = "plus";
        plus.format = "{号码}_{时间}";
        plus.pattern = "^(?<phone>\\+?[0-9][0-9 \\-]{2,24}?)_(?<ts>\\d{14})$";
        plus.timeFmt = "yyyyMMddHHmmss";
        plus.enabled = true;
        plus.priority = 1;
        List<Tpl> plusOnly = new ArrayList<Tpl>();
        plusOnly.add(plus);
        fail += expect(plusOnly, "+86 138 0013 8000_20260101120000.m4a", "plus",
                null, "13800138000", "mobile", "filename", true);
        // 转义生效：aab(1) 不该被 custom 命中（随后由兜底接管时间戳）
        fail += expect(only, "aab(1)_20260101120000.m4a", TplEngine.TPL_FALLBACK,
                "aab(1)", null, null, "fallback_ts", false);
        boolean round = fmt.equals(TplEngine.humanize(custom.pattern));
        System.out.println("  " + (round ? "✓" : "✗") + " humanize 转义串往返: " + fmt + " -> "
                + TplEngine.humanize(custom.pattern));
        if (!round) {
            fail++;
        }
        return fail;
    }

    private static int expect(List<Tpl> tpls, String file, String wantTpl, String wantName,
                              String wantPhone, String wantKind, String wantTimeSrc, boolean wantMatched) {
        TplEngine.Hit h = TplEngine.parse(file, tpls);
        boolean ok = eq(wantTpl, h.tplId) && eq(wantName, h.name) && eq(wantPhone, h.phone)
                && eq(wantKind, h.phoneKind) && eq(wantTimeSrc, h.timeSource)
                && h.matched == wantMatched;
        String got = "tpl=" + h.tplId + " matched=" + h.matched
                + " name=" + h.name + " phone=" + h.phone + "/" + h.phoneKind
                + " time=" + h.timeSource + "@" + fmtTime(h.startMs);
        System.out.println("  " + (ok ? "✓" : "✗") + " " + pad(file, 46) + got);
        return ok ? 0 : 1;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    // ---------------------------------------------------------------- 工具

    private static List<String> readNames(String path) {
        List<String> out = new ArrayList<String>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                String s = line.trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        } catch (Exception e) {
            System.err.println("读取失败: " + e);
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Exception ignore) {
                    // ignore
                }
            }
        }
        return out;
    }

    private static String idOr(TplEngine.Hit h) {
        if (h.tplId != null) {
            return h.tplId;
        }
        return h.matched ? "?" : "无";
    }

    private static String orDash(String s) {
        return s == null || s.isEmpty() ? "-" : s;
    }

    private static String fmtTime(long ms) {
        if (ms <= 0) {
            return "-";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(ms));
    }

    private static double pct(int n, int total) {
        return total == 0 ? 0d : (n * 100d / total);
    }

    /** 中文算两个显示宽度，凑个整齐的表格。 */
    private static String pad(String s, int width) {
        if (s == null) {
            s = "";
        }
        int w = displayWidth(s);
        StringBuilder sb = new StringBuilder(s);
        while (w < width) {
            sb.append(' ');
            w++;
        }
        return sb.toString();
    }

    private static int displayWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            w += (c >= 0x2E80) ? 2 : 1;
        }
        return w;
    }
}

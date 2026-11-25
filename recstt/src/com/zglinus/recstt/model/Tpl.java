package com.zglinus.recstt.model;

/**
 * 一条文件名解析模板。
 *
 * <p>{@link #format} 是给人看/给人填的格式串（如 {@code {姓名}@{号码}_{时间}}），
 * {@link #pattern} 是运行时真正用的正则，可由
 * {@code TplEngine.formatToPattern(format)} 生成。
 */
public final class Tpl {

    public String id;
    public String name;

    /** 人话格式串，如 {姓名}@{号码}_{时间}。 */
    public String format;

    /** Java 正则，含 (?<name>/(?<phone>/(?<ts>/(?<dur> 命名组。 */
    public String pattern;

    /** 时间格式，如 yyyyMMddHHmmss；空则按内置候选格式依次尝试。 */
    public String timeFmt;

    /** 适用范围（audio 表示录音文件）。 */
    public String scope;

    public boolean builtin;
    public boolean enabled;

    /** 越小越先试。 */
    public int priority;

    public Tpl() {
    }
}

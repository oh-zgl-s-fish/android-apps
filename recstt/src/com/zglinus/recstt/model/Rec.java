package com.zglinus.recstt.model;

/**
 * 一条录音记录的元数据 + 转写状态。
 *
 * <p>字段与 INTERFACES.md 逐字一致，不允许改名/改类型。
 * 时间语义：{@link #startMs} 是“录音开始”的 epoch 毫秒，0 表示未知
 * （Scanner 会用文件 mtime 回落，并把 {@link #timeSource} 改成 "mtime"）。
 */
public final class Rec {

    /** TplEngine.hashPath(path)，稳定正数，同时是 rec 表主键。 */
    public long id;

    public String path;
    public String fileName;
    public String displayName;
    public String phone;
    public String phoneKind; // mobile / landline / short / unknown

    /** 通话开始 epoch ms；0=未知。 */
    public long startMs;

    /** "filename" | "fallback_ts" | "mtime" | "none"。 */
    public String timeSource;

    /** 时长，-1=未知。 */
    public long durMs = -1L;

    public long size;
    public long mtime;

    public String templateId;
    public String jobId;
    public String error;

    /** 0 未转写 1 排队 2 转写中 3 完成 4 失败。 */
    public int state;

    /** 0..100。 */
    public int progress;

    public Rec() {
    }
}

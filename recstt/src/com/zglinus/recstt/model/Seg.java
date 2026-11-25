package com.zglinus.recstt.model;

/** 一句转写文本，挂在某条录音下的第 idx 句。 */
public final class Seg {

    public long recId;
    public int idx;

    /** 句内起止毫秒（相对整条录音开头）。 */
    public int startMs;
    public int endMs;

    public String text;

    public Seg() {
    }
}

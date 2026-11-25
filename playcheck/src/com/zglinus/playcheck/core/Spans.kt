package com.zglinus.playcheck.core

object Spans {
    /** 合并重叠/相邻区间，保证不会重复计时。 */
    fun merge(spans: List<Span>): List<Span> {
        if (spans.size <= 1) return spans
        val sorted = spans.sortedBy { it.start }
        val out = ArrayList<Span>(sorted.size)
        var cur = sorted.first()
        for (s in sorted.drop(1)) {
            cur = if (s.start <= cur.end) Span(cur.start, maxOf(cur.end, s.end)) else {
                out.add(cur); s
            }
        }
        out.add(cur)
        return out
    }

    fun total(spans: List<Span>): Long = merge(spans).sumOf { it.duration }
}

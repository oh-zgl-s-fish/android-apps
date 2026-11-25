package com.zglinus.installment.ui

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.BulletSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue

/** 极简 Markdown：标题 / 粗体 / 行内代码 / 列表 / 代码块 / 分隔线，够聊天用 */
object Md {

    fun render(text: String, fg: Int, codeBg: Int, accent: Int): CharSequence {
        val out = SpannableStringBuilder()
        val lines = text.replace("\r\n", "\n").split('\n')
        var inCode = false
        lines.forEachIndexed { li, raw ->
            val line = raw.trimEnd()
            if (line.trimStart().startsWith("```")) { inCode = !inCode; return@forEachIndexed }
            if (inCode) {
                val start = out.length
                out.append(line).append('\n')
                out.setSpan(
                    TypefaceSpan("monospace"), start, out.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                out.setSpan(
                    BackgroundColorSpan(codeBg), start, out.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                out.setSpan(
                    RelativeSizeSpan(0.94f), start, out.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                return@forEachIndexed
            }
            when {
                line.startsWith("### ") -> appendHeading(out, line.removePrefix("### "), 1.06f, fg)
                line.startsWith("## ") -> appendHeading(out, line.removePrefix("## "), 1.12f, fg)
                line.startsWith("# ") -> appendHeading(out, line.removePrefix("# "), 1.2f, fg)
                line.trim() == "---" || line.trim() == "***" -> out.append("────────────").append('\n')
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                    val start = out.length
                    out.append("  •  ")
                    inline(out, line.trimStart().substring(2), codeBg, accent)
                    out.append('\n')
                    out.setSpan(BulletSpan(0), start, start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                line.trimStart().matches(Regex("^\\d+\\.\\s.*")) -> {
                    out.append("  ")
                    inline(out, line.trimStart(), codeBg, accent)
                    out.append('\n')
                }
                else -> { inline(out, line, codeBg, accent); out.append('\n') }
            }
            if (li == lines.lastIndex && out.isNotEmpty()) { /* keep */ }
        }
        return out.trimEnd()
    }

    private fun appendHeading(out: SpannableStringBuilder, text: String, scale: Float, fg: Int) {
        val start = out.length
        out.append(text).append('\n')
        out.setSpan(StyleSpan(Typeface.BOLD), start, out.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(RelativeSizeSpan(scale), start, out.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(ForegroundColorSpan(fg), start, out.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** 行内：**粗体**、`代码`、$数字 高亮 */
    private fun inline(out: SpannableStringBuilder, line: String, codeBg: Int, accent: Int) {
        val bold = Regex("\\*\\*(.+?)\\*\\*")
        val code = Regex("`([^`]+)`")
        var i = 0
        val marks = mutableListOf<IntArray>() // [start,end,type]
        bold.findAll(line).forEach { marks += intArrayOf(it.range.first, it.range.last, 0) }
        code.findAll(line).forEach { marks += intArrayOf(it.range.first, it.range.last, 1) }
        val sorted = marks.sortedBy { it[0] }
        for (m in sorted) {
            if (m[0] < i) continue
            out.append(line, i, m[0])
            var content = line.substring(m[0], m[1] + 1)
            if (m[2] == 0) content = content.removePrefix("**").removeSuffix("**")
            else content = content.removePrefix("`").removeSuffix("`")
            val s = out.length
            out.append(content)
            val e = out.length
            if (m[2] == 0) out.setSpan(StyleSpan(Typeface.BOLD), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            else {
                out.setSpan(TypefaceSpan("monospace"), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(BackgroundColorSpan(codeBg), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(ForegroundColorSpan(accent), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            i = m[1] + 1
        }
        out.append(line, i, line.length)
    }
}

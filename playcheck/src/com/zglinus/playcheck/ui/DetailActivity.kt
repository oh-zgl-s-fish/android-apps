package com.zglinus.playcheck.ui

import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.zglinus.playcheck.R
import com.zglinus.playcheck.core.*
import com.zglinus.playcheck.data.Store
import com.zglinus.playcheck.engine.Aggregator
import com.zglinus.playcheck.engine.KeepAlive
import com.zglinus.playcheck.engine.Notifier
import com.zglinus.playcheck.engine.Schedule
import com.zglinus.playcheck.platform.AndroidUsage

/** 单个游戏的二级判定页；包名由 Intent 传入。 */
class DetailActivity : BaseActivity() {

    override fun buildScreen(): View = screenDetailPkg()

    private fun screenDetailPkg(): View {
        val pkg = intent.getStringExtra("pkg") ?: return missing("没有传包名进来", "")
        return screenDetail(pkg)
    }

// ---------------- 详情 ----------------

    private fun screenDetail(pkg: String): LinearLayout {
        // 二级页是新 Activity，verdicts 要等后台结算回来才有；首帧 onResume 必定是空的。
        // 原来这里直接 finish()，于是详情页“点一下就没了”——必须先区分「还没结算」和「真的没这个包」。
        val v = verdicts.firstOrNull { it.game.pkg == pkg } ?: return missing(
            if (!dataReady) "正在结算今日记录…" else "这个游戏不在监控清单里",
            when {
                !dataReady -> "首次进入要读一次使用记录，读完会自动刷新；期间点返回不会丢数据。"
                settleError != null -> "结算失败：$settleError —— 先查「使用情况访问」权限是否已授权。"
                else -> "包名 $pkg"
            }
        )
        val box = column()
        box.addView(headerRow("DETAIL", "今日判定", false, back = true))
        box.addView(gameInfoCard(v, pkg))

        val hero = card(glass = true)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(label("前台累计时长", 12f, T.ON_VAR), LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(chipText(if (v.done) "✓ 已打卡" else "进行中", if (v.done) T.PRIMARY_C else T.WARN_C, if (v.done) T.ON_PRIMARY_C else T.ON_WARN_C))
        hero.addView(top)
        hero.addView(label(Fmt.mmss(v.totalMs), 38f, T.ON_SURFACE, true))
        hero.addView(label("门槛 ${Fmt.mmss(v.thresholdsMs)} · 超出 ${Fmt.mmss((v.totalMs - v.thresholdsMs).coerceAtLeast(0))}", 11f, T.ON_VAR))
        hero.addView(com.google.android.material.progressindicator.LinearProgressIndicator(this).apply {
            max = 100
            progress = if (v.thresholdsMs == 0L) 0 else ((v.totalMs * 100) / v.thresholdsMs).toInt().coerceIn(0, 100)
            trackCornerRadius = dp(4f)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10f) }
        })
        box.addView(hero)

        val gd = dayMap[pkg]
        val spans = gd?.spans ?: emptyList()
        val ev = card()
        ev.addView(label("判定依据", 13f, T.ON_SURFACE, true))
        ev.addView(evidenceRow(v.totalMs > 0, "今天打开过", "首次前台 ${v.firstOpen?.let { Fmt.hhmm(it) } ?: "--:--"} · 共 ${spans.size} 段"))
        val reached = v.totalMs >= v.thresholdsMs
        ev.addView(evidenceRow(
            reached, "前台累计 ${if (reached) "≥" else "<"} 门槛",
            "${Fmt.minutes(v.totalMs)} ${if (reached) "≥" else "<"} ${v.thresholdsMs / 60_000L} 分"
        ))
        ev.addView(evidenceRow(false, "手动补卡", gd?.manualAt?.let { "已补于 ${Fmt.hhmm(it)}" } ?: "未使用", neutral = true))
        box.addView(ev)

        val rec = card()
        rec.addView(label("今日打开记录 · ${spans.size} 段", 13f, T.ON_SURFACE, true))
        rec.addView(DayHeatmap(this).apply {
            levels = heatLevels(spans)
        }, LinearLayout.LayoutParams(MATCH, WRAP))

        spans.take(3).forEach { s ->
            rec.addView(label("${Fmt.hhmm(s.start)} – ${Fmt.hhmm(s.end)}   ${Fmt.mmss(s.duration)}", 11f, T.ON_VAR))
        }
        box.addView(rec)

        val nowMs = System.currentTimeMillis()
        val estR = com.zglinus.playcheck.engine.Estimates.one(this, Store.rules(), nowMs, pkg)
        val sampleDay = com.zglinus.playcheck.engine.Estimates
            .recent(this, Store.rules(), nowMs, pkg, 1)
            .firstOrNull()
        val est = estR.ms
        val week = card()
        week.addView(label("近 7 天 · 门槛 ${v.thresholdsMs / 60_000L} 分", 13f, T.ON_SURFACE, true))
        week.addView(label(
            when {
                estR.samples == 0 -> "预估 --:-- · 还没有任何使用记录"
                estR.samples == 1 -> "上次 ${Fmt.duration(est)} · 样本来自 ${sampleDay?.first ?: "—"}（那天共 ${Fmt.duration(est)}）"
                else -> "预估 ${Fmt.duration(est)} · ${estR.samples} 个样本（0.8×中位 + 0.2×均值）· 最近 ${sampleDay?.first ?: "—"}"
            },
            11f, T.ON_VAR
        ).apply { setPadding(0, dp(3f), 0, dp(6f)) })
        week.addView(WeekBars(this).apply {
            labels = listOf("三", "四", "五", "六", "日", "一", "二", "预估")
            values = last7Ms(pkg) + listOf(est)
            todayIndex = 6
            grayFrom = 7   // 第 8 根是预估，设计上固定灰色
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        box.addView(week)

        if (!v.done && Store.rules().allowManual) {
            box.addView(MaterialButton(this).apply {
                text = "手动完成今天"
                setOnClickListener { confirmManual(v) }
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12f) })
        }
        return box
    }

    /** 当天区间 → 144 格（24 小时 × 6 个 10 分钟档），按覆盖比例给 0~3 级。 */
    private fun heatLevels(spans: List<Span>): IntArray {
        val out = IntArray(144)
        // 自然日 00:00 起点：热力图的列就是钟点，4 点落在第 4 列
        val c = java.util.Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis()
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        val ws = c.timeInMillis
        val bucket = 10 * 60_000L
        for (b in 0 until 144) {
            val from = ws + b * bucket
            val to = from + bucket
            val overlap = spans.sumOf { maxOf(0L, minOf(it.end, to) - maxOf(it.start, from)) }
            out[b] = when {
                overlap <= 0L -> 0
                overlap >= bucket * 8 / 10 -> 3
                overlap >= bucket * 4 / 10 -> 2
                else -> 1
            }
        }
        return out
    }

    private fun evidenceRow(ok: Boolean, k: String, v: String, neutral: Boolean = false): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8f), 0, dp(2f))
        }
        row.addView(label(if (neutral) "·" else if (ok) "✓" else "×", 13f, if (neutral) T.ON_VAR else T.PRIMARY, true)
            .apply { gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(dp(18f), WRAP) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8f), 0, 0, 0) }
        col.addView(label(k, 13f, T.ON_SURFACE))
        col.addView(label(v, 11f, T.ON_VAR))
        row.addView(col)
        return row
    }

    /**
     * 游戏信息：压成三行 —— ①图标+名称+包名+连续打卡 ②版本/安装包/数据/缓存 ③安装/更新/SDK/UID。
     * 数据来源 PackageManager + StorageStatsManager（复用已有 PACKAGE_USAGE_STATS 权限），拿不到显示 ——。
     */
    private fun gameInfoCard(v: Verdict, pkg: String): View {
        val c = card()   // 和其它卡同一套毛玻璃；上一版写成 card(glass = false) 所以“玻璃丢了”
        val ai = runCatching { packageManager.getApplicationInfo(pkg, 0) }.getOrNull()
        val pi = runCatching { packageManager.getPackageInfo(pkg, 0) }.getOrNull()
        val stats = runCatching {
            getSystemService(android.app.usage.StorageStatsManager::class.java)
                ?.queryStatsForPackage(android.os.storage.StorageManager.UUID_DEFAULT, pkg, android.os.Process.myUserHandle())
        }.getOrNull()
        val streak = runCatching {
            Store.gameStreak(pkg, System.currentTimeMillis(), Store.rules().refreshHour)
        }.getOrDefault(0)
        val apkBytes = ai?.sourceDir?.let { java.io.File(it).length() } ?: 0L

        // ① 图标 + 名称/包名 + 连续打卡
        val idRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        idRow.addView(avatar(v.game, 40f))
        val idCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10f), 0, dp(8f), 0)
        }
        // 名字 + 版本同一行：版本定宽 96dp，太长就末尾省略，不挤包名那一行
        val ver = pi?.versionName?.let { if (pi.longVersionCode > 0) "$it (${pi.longVersionCode})" else it } ?: "—"
        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nameRow.addView(label(v.game.label.ifEmpty { pkg }, 14f, T.ON_SURFACE, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        // 版本按实际宽度排（只给 maxWidth 兜底）：名字拿剩下的真实宽度去截断，
        // 不写死 96dp —— 定宽会让短版本在名字后面留一段空白，名字少截一截。
        nameRow.addView(label(ver, 10f, T.ON_VAR).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = dp(110f)
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6f) })
        idCol.addView(nameRow)
        idCol.addView(label(pkg, 10f, T.ON_VAR).apply {
            setTextIsSelectable(true)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        idRow.addView(idCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        idRow.addView(chipText("🔥 连续 $streak 天", T.PRIMARY_C, T.ON_PRIMARY_C))
        c.addView(idRow)

        // ② 安装包 · 数据 · 缓存（版本已挪到名字后面）
        c.addView(infoLine(listOf(
            "安装包 ${if (apkBytes > 0) sizeText(apkBytes) else "—"}",
            "数据 ${stats?.let { sizeText(it.dataBytes) } ?: "—"}",
            "缓存 ${stats?.let { sizeText(it.cacheBytes) } ?: "—"}"
        )))

        // ③ 安装 · 更新 · SDK · UID
        c.addView(infoLine(listOf(
            "安装 ${pi?.firstInstallTime?.let { dateText(it) } ?: "—"}",
            "更新 ${pi?.lastUpdateTime?.let { dateText(it) } ?: "—"}",
            "SDK ${ai?.let { "${it.minSdkVersion}/${it.targetSdkVersion}" } ?: "—"}",
            "UID ${ai?.uid?.toString() ?: "—"}"
        )))
        return c
    }

    /** 一行「a · b · c」：11sp 单行，放不下就省略，不折行（折了就破“三行”）。 */
    private fun infoLine(parts: List<String>): TextView = label(parts.joinToString("  ·  "), 10.5f, T.ON_VAR).apply {
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(0, dp(4f), 0, 0)
        setTextIsSelectable(true)
    }

    private fun sizeText(b: Long): String = when {
        b <= 0L -> "—"
        b >= 1L shl 30 -> String.format(java.util.Locale.US, "%.2f GB", b / 1073741824.0)
        b >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0)
        else -> "${b / 1024} KB"
    }

    private fun dateText(t: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(t))

    private fun last7Ms(pkg: String): List<Long> {
        val rules = Store.rules()
        val now = System.currentTimeMillis()
        return (6 downTo 0).map { back ->
            val key = DayClock.dayKey(now - back * 86_400_000L, rules.refreshHour)
            Store.day(key)[pkg]?.let { Spans.total(it.spans) } ?: 0L
        }
    }

    /** 打不开时的可见状态页：既不静默关掉，也不假装有数据。 */
    private fun missing(title: String, detail: String): LinearLayout {
        val box = column()
        box.addView(headerRow("DETAIL", "今日判定", false, back = true))
        val c = card(glass = false)
        c.addView(label(title, 14f, T.ON_SURFACE, true))
        if (detail.isNotEmpty()) {
            c.addView(label(detail, 11f, T.ON_VAR).apply { setPadding(0, dp(5f), 0, 0) })
        }
        box.addView(c)
        return box
    }

}

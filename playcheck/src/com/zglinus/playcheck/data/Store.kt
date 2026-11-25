package com.zglinus.playcheck.data

import android.content.Context
import android.content.SharedPreferences
import com.zglinus.playcheck.core.*
import org.json.JSONArray
import org.json.JSONObject

/** 外观配置。wallpaper 为空表示不启用壁纸（这时不产生任何模糊层）。 */
data class Appearance(
    val wallpaperUri: String? = null,
    val glass: Boolean = true,
    val tintAlpha: Int = 16,      // 玻璃填充，百分比
    val darken: Int = 18,         // 壁纸压暗，百分比
    val blurRadius: Int = 24,     // dp
    val iconShape: String = "round"
)

/**
 * 单例存储：规则、游戏清单、外观、按天记录。
 * 只依赖 core，不依赖 UI；将来换 Room 只改这一层。
 */
object Store {
    private const val FILE = "playcheck"
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (!this::sp.isInitialized) sp = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    // ---------- 规则 ----------
    fun rules(): Rules = Rules(
        thresholdMin = sp.getInt("threshold", 15),
        refreshHour = sp.getInt("refreshHour", 4),
        allowManual = sp.getBoolean("allowManual", true),
        onlyForeground = sp.getBoolean("onlyForeground", true)
    )

    fun saveRules(r: Rules) {
        sp.edit().putInt("threshold", r.thresholdMin)
            .putInt("refreshHour", r.refreshHour)
            .putBoolean("allowManual", r.allowManual)
            .putBoolean("onlyForeground", r.onlyForeground)
            .apply()
    }

    // ---------- 游戏清单 ----------
    fun games(): List<Game> {
        val raw = sp.getString("games", null) ?: return defaults()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Game(o.getString("pkg"), o.optString("label"), o.optBoolean("enabled", true))
            }
        }.getOrElse { defaults() }
    }

    fun saveGames(list: List<Game>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("pkg", it.pkg).put("label", it.label).put("enabled", it.enabled))
        }
        sp.edit().putString("games", arr.toString()).apply()
    }

    private fun defaults() = listOf(
        Game("com.miHoYo.Yuanshen", "原神"),
        Game("com.miHoYo.hkrpg", "崩坏：星穹铁道"),
        Game("com.hypergryph.arknights", "明日方舟")
    )

    // ---------- 外观 ----------
    fun appearance(): Appearance = Appearance(
        wallpaperUri = sp.getString("wallpaper", null),
        glass = sp.getBoolean("glass", true),
        tintAlpha = sp.getInt("tintAlpha", 16),
        darken = sp.getInt("darken", 18),
        blurRadius = sp.getInt("blurRadius", 24),
        iconShape = sp.getString("iconShape", "round") ?: "round"
    )

    fun saveAppearance(a: Appearance) {
        sp.edit().putString("wallpaper", a.wallpaperUri)
            .putBoolean("glass", a.glass)
            .putInt("tintAlpha", a.tintAlpha)
            .putInt("darken", a.darken)
            .putInt("blurRadius", a.blurRadius)
            .putString("iconShape", a.iconShape)
            .apply()
    }

    // ---------- 按天记录 ----------
    private fun dayKeyOf(day: String, pkg: String) = "d:$day:$pkg"

    fun day(day: String): Map<String, GameDay> {
        val out = HashMap<String, GameDay>()
        val prefix = "d:$day:"
        for ((k, v) in sp.all) {
            if (k is String && k.startsWith(prefix) && v is String) {
                val pkg = k.removePrefix(prefix)
                out[pkg] = parse(v, pkg)
            }
        }
        return out
    }

    fun saveDay(day: String, dayData: GameDay) {
        sp.edit().putString(dayKeyOf(day, dayData.pkg), toJson(dayData)).apply()
    }

    fun markManual(day: String, pkg: String, at: Long) {
        val cur = day(day)[pkg] ?: GameDay(pkg)
        saveDay(day, cur.copy(manualAt = at))
    }

    fun keepAlive(): Boolean = sp.getBoolean("keepAlive", true)
    fun keepAlive(v: Boolean) { sp.edit().putBoolean("keepAlive", v).apply() }

    /** 自定义图标：包名 -> 图片 URI（只存引用，不复制文件）。 */
    fun iconUri(pkg: String): String? = runCatching {
        val raw = sp.getString("icons", null) ?: return null
        val o = JSONObject(raw)
        if (o.has(pkg)) o.getString(pkg) else null
    }.getOrNull()

    fun saveIcon(pkg: String, uri: String?) {
        val o = runCatching { JSONObject(sp.getString("icons", null) ?: "{}") }.getOrElse { JSONObject() }
        if (uri == null) o.remove(pkg) else o.put(pkg, uri)
        sp.edit().putString("icons", o.toString()).apply()
    }

    fun lastSettle(): Long = sp.getLong("lastSettle", 0L)
    fun lastSettle(v: Long) = sp.edit().putLong("lastSettle", v).commit()

    fun lastAlertDay(): String = sp.getString("lastAlertDay", "") ?: ""
    fun lastAlertDay(v: String) { sp.edit().putString("lastAlertDay", v).apply() }

    fun streak(now: Long, refreshHour: Int): Int {
        // 连续天数：从昨天往前数，遇到“一个都没达标”就停；今天达标则从今天算起。
        var n = 0
        var t = now
        val games = games()
        val rules = rules()
        repeat(60) {
            val key = DayClock.dayKey(t, refreshHour)
            val days = day(key)
            val verdicts = Judge.judgeAll(days, games, rules)
            val anyDone = verdicts.any { it.done }
            if (anyDone) n++ else if (n > 0 || key != DayClock.dayKey(now, refreshHour)) return n
            t -= 24L * 3600_000L
        }
        return n
    }

    /** 单个游戏的连续打卡天数：今天达标就从今天算，否则从昨天往前数，断一天就停。 */
    fun gameStreak(pkg: String, now: Long, refreshHour: Int): Int {
        var n = 0
        var t = now
        val rules = rules()
        val g = games().firstOrNull { it.pkg == pkg } ?: Game(pkg)
        repeat(120) {
            val key = DayClock.dayKey(t, refreshHour)
            val done = Judge.judge(day(key)[pkg] ?: GameDay(pkg), g, rules).done
            if (done) n++ else if (n > 0 || key != DayClock.dayKey(now, refreshHour)) return n
            t -= 24L * 3600_000L
        }
        return n
    }

    private fun toJson(d: GameDay): String {
        val spans = JSONArray()
        d.spans.forEach { s -> spans.put(JSONArray().put(s.start).put(s.end)) }
        return JSONObject().put("sp", spans).put("m", d.manualAt ?: JSONObject.NULL)
            .put("t", d.totalMs).toString()
    }

    private fun parse(raw: String, pkg: String): GameDay = runCatching {
        val o = JSONObject(raw)
        val arr = o.optJSONArray("sp") ?: JSONArray()
        val spans = (0 until arr.length()).map { i ->
            val p = arr.getJSONArray(i); Span(p.getLong(0), p.getLong(1))
        }
        val m = if (o.isNull("m")) null else o.getLong("m")
        val t = if (o.has("t")) o.getLong("t") else -1L
        GameDay(pkg, Spans.merge(spans), m, t)
    }.getOrElse { GameDay(pkg) }
}

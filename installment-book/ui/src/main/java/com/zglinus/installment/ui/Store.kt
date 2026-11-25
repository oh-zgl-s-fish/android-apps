package com.zglinus.installment.ui

import android.content.Context
import com.zglinus.installment.core.Card
import com.zglinus.installment.core.IncomeItem
import com.zglinus.installment.core.Chat
import com.zglinus.installment.core.ChatMsg
import com.zglinus.installment.core.McpServer
import com.zglinus.installment.core.Savings
import com.zglinus.installment.core.Method
import com.zglinus.installment.core.Plan
import com.zglinus.installment.core.Repo
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** 本地持久化：所有改动落盘，重启后原样恢复 */
object Store {

    private const val FILE = "installment"
    private const val KEY = "state_v1"
    var autoPay: Boolean = true
        private set

    var llmBaseUrl: String = ""
        private set
    var llmModel: String = ""
        private set
    var llmApiKey: String = ""
        private set

    val chats = mutableListOf<Chat>()
    var currentChatId: Long = 0L
        private set

    var lockEnabled: Boolean = false
        private set
    var lockPin: String = ""
        private set
    var lockBio: Boolean = false
        private set

    var mcpEnabled: Boolean = false
        private set
    var mcpPort: Int = 8765
        private set
    var mcpToken: String = ""
        private set
    var mcpLocalOnly: Boolean = false
        private set

    fun currentChat(): Chat {
        chats.firstOrNull { it.id == currentChatId }?.let { return it }
        val c = Chat(System.currentTimeMillis(), "新对话")
        chats += c
        currentChatId = c.id
        return c
    }

    fun newChat(ctx: android.content.Context): Chat {
        val c = Chat(System.currentTimeMillis(), "新对话")
        chats.add(0, c)
        currentChatId = c.id
        save(ctx)
        return c
    }

    fun selectChat(ctx: android.content.Context, id: Long) {
        currentChatId = id
        save(ctx)
    }

    /** 导出：整份存档（与内部格式一致，可再导回） */
    fun exportJson(): String {
        val root = JSONObject()
        root.put("app", "installment")
        root.put("version", 1)
        root.put("exportedAt", java.time.LocalDateTime.now().toString())
        root.put("income", JSONArray().apply {
            Repo.income.forEach {
                put(JSONObject().put("name", it.name).put("amount", it.amount)
                    .put("hint", it.hint).put("savings", it.savingsRef))
            }
        })
        root.put("fixedExpense", Repo.fixedExpense)
        root.put("autoPay", autoPay)
        root.put("cards", JSONArray().apply {
            Repo.cards.forEach {
                put(JSONObject().put("bank", it.bank).put("tail", it.tail)
                    .put("statementDay", it.statementDay).put("dueDay", it.dueDay))
            }
        })
        root.put("savings", JSONArray().apply {
            Repo.savings.forEach { put(JSONObject().put("name", it.name).put("balance", it.balance)) }
        })
        root.put("plans", JSONArray().apply { Repo.active.forEach { put(planJson(it)) } })
        root.put("ended", JSONArray().apply { Repo.ended.forEach { put(planJson(it)) } })
        return root.toString(2)
    }

    /** 逐期明细：本金 / 利息 / 金额 / 是否已还 / 到期日 */
    private fun itemsOf(p: Plan): JSONArray = JSONArray().apply {
        val ds = p.dues
        for (i in 0 until p.periods) {
            val amount = p.amounts.getOrNull(i)
            val interest = p.interests.getOrNull(i) ?: p.periodSplit(i).second
            val total = amount ?: (p.periodSplit(i).first + interest)
            put(JSONObject().apply {
                put("i", i + 1)
                put("due", ds.getOrNull(i)?.toString() ?: "")
                put("principal", "%.2f".format(total - interest).toDouble())
                put("interest", "%.2f".format(interest).toDouble())
                put("amount", "%.2f".format(total).toDouble())
                put("paid", i < p.paidPeriods)
            })
        }
    }

    private fun planJson(p: Plan): JSONObject = JSONObject().apply {
        put("title", p.title); put("note", p.note); put("bank", p.bank); put("tail", p.tail)
        put("principal", p.principal); put("periods", p.periods)
        put("paidPeriods", p.paidPeriods); put("perInterest", p.perInterest)
        put("method", p.method.name); put("startDate", p.startDate.toString())
        put("statementDay", p.statementDay); put("dueDay", p.dueDay)
        put("amounts", JSONArray().apply { p.amounts.forEach { put(it) } })
        put("items", itemsOf(p))
    }

    /** 导入：校验后整份替换，返回摘要或抛异常（错误可见） */
    fun importJson(text: String): String {
        val root = JSONObject(text)
        val plans = root.optJSONArray("plans") ?: throw IllegalArgumentException("缺少 plans 数组")
        val income = root.optJSONArray("income") ?: JSONArray()
        val cardsArr = root.optJSONArray("cards") ?: JSONArray()
        val savingsArr = root.optJSONArray("savings") ?: JSONArray()

        val newActive = mutableListOf<Plan>()
        var id = 1L
        for (i in 0 until plans.length()) {
            val o = plans.getJSONObject(i)
            newActive += Plan(
                id = id++,
                title = o.optString("title"),
                note = o.optString("note"),
                bank = o.optString("bank", "银行"),
                tail = o.optString("tail", "0000"),
                principal = o.optDouble("principal", 0.0),
                periods = o.optInt("periods", 12).coerceAtLeast(1),
                paidPeriods = o.optInt("paidPeriods", 0).coerceIn(0, o.optInt("periods", 12)),
                perInterest = o.optDouble("perInterest", 0.0),
                method = runCatching { Method.valueOf(o.optString("method", "FLAT_RATE")) }
                    .getOrDefault(Method.FLAT_RATE),
                startDate = runCatching { java.time.LocalDate.parse(o.optString("startDate")) }
                    .getOrDefault(java.time.LocalDate.now()),
                statementDay = o.optInt("statementDay", 25),
                dueDay = o.optInt("dueDay", 10),
        amounts = o.optJSONArray("amounts")?.let { a ->
            (0 until a.length()).map { a.optDouble(it) }
        } ?: emptyList()
            ).let { base ->
                // 逐期明细优先：带每期金额 + 已还标记
                val items = base.itemsFromJson(o)
                if (items == null) base
                else base.copy(amounts = items.first, interests = items.second, paidPeriods = items.third)
            }
        }
        if (newActive.isEmpty()) throw IllegalArgumentException("plans 是空的")

        Repo.active.clear(); Repo.ended.clear(); Repo.cards.clear(); Repo.savings.clear()
        Repo.income.clear()
        Repo.active += newActive
        Repo.nextIdAfter(id)
        root.optJSONArray("ended")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                Repo.ended += Plan(
                    id = id++, title = o.optString("title"), bank = o.optString("bank", "银行"),
                    tail = o.optString("tail", "0000"), principal = o.optDouble("principal", 0.0),
                    periods = o.optInt("periods", 12).coerceAtLeast(1),
                    paidPeriods = o.optInt("periods", 12),
                    perInterest = o.optDouble("perInterest", 0.0),
                    method = runCatching { Method.valueOf(o.optString("method", "FLAT_RATE")) }
                        .getOrDefault(Method.FLAT_RATE),
                    startDate = runCatching { java.time.LocalDate.parse(o.optString("startDate")) }
                        .getOrDefault(java.time.LocalDate.now()),
                    statementDay = o.optInt("statementDay", 25), dueDay = o.optInt("dueDay", 10)
                )
            }
        }
        if (income.length() > 0) {
            for (i in 0 until income.length()) {
                val o = income.getJSONObject(i)
                Repo.income += IncomeItem(
                    o.optString("name", "收入"), o.optDouble("amount", 0.0),
                    o.optString("hint", "每月"), o.optString("savings", "")
                )
            }
        } else {
            Repo.income += IncomeItem("工资", 0.0, "每月", "")
        }
        Repo.fixedExpense = root.optDouble("fixedExpense", Repo.fixedExpense)
        for (i in 0 until cardsArr.length()) {
            val o = cardsArr.getJSONObject(i)
            Repo.cards += Card(
                o.optString("bank", "银行"), o.optString("tail", "0000"),
                o.optInt("statementDay", 25), o.optInt("dueDay", 10)
            )
        }
        for (i in 0 until savingsArr.length()) {
            val o = savingsArr.getJSONObject(i)
            Repo.savings += Savings(o.optString("name", "储蓄"), o.optDouble("balance", 0.0))
        }
        // 没给卡就从计划里补
        (Repo.active + Repo.ended).forEach { Repo.ensureCard(it.bank, it.tail, it.statementDay, it.dueDay) }
        val firstSavings = Repo.savings.firstOrNull()?.name ?: ""
        for (i in Repo.income.indices) {
            if (Repo.income[i].savingsRef.isBlank()) {
                Repo.income[i] = Repo.income[i].copy(savingsRef = firstSavings)
            }
        }
        return "导入 ${Repo.active.size} 条在还、${Repo.ended.size} 条已结束、${Repo.cards.size} 张卡"
    }

    fun setLock(ctx: android.content.Context, enabled: Boolean, pin: String, bio: Boolean) {
        lockEnabled = enabled
        lockPin = if (enabled) pin else ""
        lockBio = enabled && bio
        save(ctx)
    }

    fun setMcp(
        ctx: android.content.Context, enabled: Boolean, port: Int, token: String,
        localOnly: Boolean = mcpLocalOnly
    ) {
        mcpEnabled = enabled; mcpPort = port; mcpToken = token; mcpLocalOnly = localOnly
        if (enabled) McpHost.start(port, token, localOnly) else McpHost.stop()
        save(ctx)
    }

    fun setLlm(ctx: Context, baseUrl: String, model: String, apiKey: String) {
        llmBaseUrl = baseUrl.trim(); llmModel = model.trim(); llmApiKey = apiKey.trim()
        save(ctx)
    }

    fun load(ctx: Context) {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
        if (raw.isNullOrBlank()) {
            Repo.reset()
            Repo.seed()
            save(ctx)
            return
        }
        runCatching {
            val root = JSONObject(raw)
            Repo.reset()
            Repo.fixedExpense = root.optDouble("fixedExpense", 2988.0)
            autoPay = root.optBoolean("autoPay", true)
            llmBaseUrl = root.optString("llmBaseUrl")
            llmModel = root.optString("llmModel")
            llmApiKey = root.optString("llmApiKey")
            root.optJSONArray("income")?.let { arr ->
                Repo.income.clear()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    Repo.income += IncomeItem(
                        o.getString("n"), o.getDouble("a"), o.optString("h"), o.optString("s")
                    )
                }
            }
            root.optJSONArray("active")?.let { arr ->
                for (i in 0 until arr.length()) Repo.active += plan(arr.getJSONObject(i))
            }
            root.optJSONArray("ended")?.let { arr ->
                for (i in 0 until arr.length()) Repo.ended += plan(arr.getJSONObject(i))
            }
            // 关键：重启后 nextId 会回到 1，必须推到现有最大 id 之后，
            // 否则新建计划拿到重复 id，点进去会跳到别的计划
            (Repo.active + Repo.ended).maxOfOrNull { it.id }?.let { Repo.nextIdAfter(it) }

            // 旧版本已经写进去的重复 id 要当场修掉：重复的那条换一个新 id 并回写盘，
            // 否则 find(id) 永远命中更早那条，点新计划会跳进旧计划
            run {
                val seen = mutableSetOf<Long>()
                var maxId = (Repo.active + Repo.ended).maxOfOrNull { it.id } ?: 0L
                var changed = false
                fun dedupe(list: MutableList<Plan>) {
                    for (i in list.indices) {
                        val pl = list[i]
                        if (seen.add(pl.id)) continue
                        maxId++
                        list[i] = pl.copy(id = maxId)
                        seen.add(maxId)
                        changed = true
                    }
                }
                dedupe(Repo.active)
                dedupe(Repo.ended)
                if (changed) {
                    Repo.nextIdAfter(maxId)
                    android.util.Log.w("Store", "修复重复计划 id，已重排到 $maxId")
                    save(ctx)
                }
            }
            root.optJSONArray("cards")?.let { arr ->
                Repo.cards.clear()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    Repo.cards += Card(o.getString("bank"), o.getString("tail"), o.getInt("stmt"), o.getInt("due"))
                }
            }
            root.optJSONArray("chats")?.let { arr ->
                chats.clear()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val msgs = mutableListOf<ChatMsg>()
                    o.optJSONArray("msgs")?.let { ma ->
                        for (k in 0 until ma.length()) {
                            val m = ma.getJSONObject(k)
                            msgs += ChatMsg(m.optString("r", "user"), m.optString("t"), m.optString("img").ifBlank { null })
                        }
                    }
                    chats += Chat(o.optLong("id"), o.optString("title"), msgs)
                }
            }
            currentChatId = root.optLong("currentChatId", chats.firstOrNull()?.id ?: 0L)
            lockEnabled = root.optBoolean("lockEnabled", false)
            lockPin = root.optString("lockPin")
            lockBio = root.optBoolean("lockBio", false)
            mcpEnabled = root.optBoolean("mcpEnabled", false)
            mcpPort = root.optInt("mcpPort", 8765)
            mcpToken = root.optString("mcpToken")
            mcpLocalOnly = root.optBoolean("mcpLocalOnly", false)
            root.optJSONArray("savings")?.let { arr ->
                Repo.savings.clear()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    Repo.savings += Savings(o.getString("name"), o.getDouble("balance"))
                }
            }
            // 迁移：老存档没有 cards / savings
            if (Repo.cards.isEmpty()) {
                (Repo.active + Repo.ended).forEach { p ->
                    Repo.ensureCard(p.bank, p.tail, p.statementDay, p.dueDay)
                }
            }
            if (Repo.savings.isEmpty()) {
                Repo.savings += Savings("招行储蓄卡", 12480.0)
                Repo.savings += Savings("零钱", 1860.0)
            }
            // 收入账户还没挂储蓄账户的，挂到第一个储蓄账户
            val first = Repo.savings.firstOrNull()?.name
            if (first != null) {
                for (i in Repo.income.indices) {
                    if (Repo.income[i].savingsRef.isBlank()) {
                        Repo.income[i] = Repo.income[i].copy(savingsRef = first)
                    }
                }
            }
            // 补齐后立刻回写，避免每次启动都重新推导
            save(ctx)
        }.onFailure {
            // 存档坏了就回种子，不静默留空
            Repo.reset(); Repo.seed()
        }
    }

    fun save(ctx: Context) {
        val root = JSONObject()
        root.put("fixedExpense", Repo.fixedExpense)
        root.put("autoPay", autoPay)
        root.put("llmBaseUrl", llmBaseUrl)
        root.put("llmModel", llmModel)
        root.put("llmApiKey", llmApiKey)
        root.put("income", JSONArray().apply {
            Repo.income.forEach {
                put(
                    JSONObject().put("n", it.name).put("a", it.amount)
                        .put("h", it.hint).put("s", it.savingsRef)
                )
            }
        })
        root.put("active", JSONArray().apply { Repo.active.forEach { put(json(it)) } })
        root.put("ended", JSONArray().apply { Repo.ended.forEach { put(json(it)) } })
        root.put("cards", JSONArray().apply {
            Repo.cards.forEach {
                put(JSONObject().put("bank", it.bank).put("tail", it.tail)
                    .put("stmt", it.statementDay).put("due", it.dueDay))
            }
        })
        root.put("currentChatId", currentChatId)
        root.put("chats", JSONArray().apply {
            chats.forEach { c ->
                put(JSONObject().put("id", c.id).put("title", c.title).put("msgs", JSONArray().apply {
                    c.msgs.forEach { m ->
                        put(JSONObject().put("r", m.role).put("t", m.text).apply {
                            m.image?.let { put("img", it) }
                        })
                    }
                }))
            }
        })
        root.put("lockEnabled", lockEnabled)
        root.put("lockPin", lockPin)
        root.put("lockBio", lockBio)
        root.put("mcpEnabled", mcpEnabled)
        root.put("mcpPort", mcpPort)
        root.put("mcpToken", mcpToken)
        root.put("mcpLocalOnly", mcpLocalOnly)
        root.put("savings", JSONArray().apply {
            Repo.savings.forEach {
                put(JSONObject().put("name", it.name).put("balance", it.balance))
            }
        })
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, root.toString()).apply()
    }

    fun setFixedExpense(ctx: Context, v: Double) {
        Repo.fixedExpense = v
        save(ctx)
    }

    fun setAutoPay(ctx: Context, on: Boolean) {
        autoPay = on
        save(ctx)
    }

    private fun json(p: Plan) = JSONObject().apply {
        put("id", p.id); put("title", p.title); put("note", p.note); put("bank", p.bank); put("tail", p.tail)
        put("principal", p.principal); put("periods", p.periods)
        put("paid", p.paidPeriods); put("perInterest", p.perInterest)
        put("method", p.method.name); put("start", p.startDate.toString())
        put("stmt", p.statementDay); put("due", p.dueDay)
        put("items", itemsOf(p))
    }

    /** 从 JSON 的 items 还原逐期金额/利息/已还期数；没有 items 返回 null */
    private fun Plan.itemsFromJson(o: JSONObject): Triple<List<Double>, List<Double>, Int>? {
        val items = o.optJSONArray("items") ?: return null
        if (items.length() == 0) return null
        val amts = (0 until items.length()).map { items.getJSONObject(it).optDouble("amount", 0.0) }
        val ints = (0 until items.length()).map { items.getJSONObject(it).optDouble("interest", 0.0) }
        val paid = (0 until items.length()).count { items.getJSONObject(it).optBoolean("paid") }
        return Triple(amts, ints, paid)
    }

    private fun plan(o: JSONObject) = Plan(
        id = o.getLong("id"),
        title = o.optString("title"),
        note = o.optString("note"),
        bank = o.getString("bank"),
        tail = o.getString("tail"),
        principal = o.getDouble("principal"),
        periods = o.getInt("periods"),
        paidPeriods = o.getInt("paid"),
        perInterest = o.getDouble("perInterest"),
        method = runCatching { Method.valueOf(o.getString("method")) }.getOrDefault(Method.FLAT_RATE),
        startDate = runCatching { LocalDate.parse(o.getString("start")) }.getOrDefault(LocalDate.now()),
        statementDay = o.optInt("stmt", 25),
        dueDay = o.optInt("due", 10)
    ).let { base ->
        val items = o.optJSONArray("items") ?: return@let base
        if (items.length() == 0) return@let base
        val amts = (0 until items.length()).map { items.getJSONObject(it).optDouble("amount", 0.0) }
        val ints = (0 until items.length()).map { items.getJSONObject(it).optDouble("interest", 0.0) }
        val paid = (0 until items.length()).count { items.getJSONObject(it).optBoolean("paid") }
        base.copy(amounts = amts, interests = ints, paidPeriods = paid)
    }
}

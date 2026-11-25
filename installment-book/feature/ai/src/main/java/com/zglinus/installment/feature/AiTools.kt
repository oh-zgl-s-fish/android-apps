package com.zglinus.installment.feature

import com.zglinus.installment.core.*
import com.zglinus.installment.ui.Ui
import org.json.JSONObject
import java.time.LocalDate

/**
 * AI 的工具集：只读 + 写（写操作必须先确认）。
 * 约定：模型在回答里输出 ```json {"tool":"...","args":{...}}```，App 解析执行后把结果回喂。
 */
object AiTools {

    val spec = """
你可以调用工具读写分期数据。需要时在回答末尾输出一个 ```json 代码块：
```json
{"tool":"list_plans","args":{}}
```
可用工具：
- list_plans {}  列出所有在还/已结束分期
- summary {}     预算与负债总览
- add_plan {"title","bank","tail","principal","periods","perInterest","startDate":"YYYY-MM-DD"?,"method"?}
- update_plan {"bank","tail","title"?,"principal"?,"periods"?,"paidPeriods"?,"perInterest"?}
- delete_plan {"bank","tail","title"?}
写入类工具会由用户确认后才执行。一次只输出一个 json 块。
    """.trimIndent()

    data class Action(val tool: String, val args: JSONObject) {
        val isWrite get() = tool == "add_plan" || tool == "update_plan" || tool == "delete_plan"
    }

    fun parse(reply: String): Action? {
        val start = reply.indexOf("```json")
        if (start < 0) return null
        val body = reply.substring(start + 7)
        val end = body.indexOf("```")
        if (end < 0) return null
        return runCatching {
            val o = JSONObject(body.substring(0, end).trim())
            Action(o.getString("tool"), o.optJSONObject("args") ?: JSONObject())
        }.getOrNull()
    }

    /** 描述一个写操作，用于确认弹窗 */
    fun describe(a: Action): String = when (a.tool) {
        "add_plan" -> "新建分期：${a.args.optString("bank")} ${a.args.optString("tail")} · 本金 ${a.args.optDouble("principal")} · ${a.args.optInt("periods")} 期 · 每期利息 ${a.args.optDouble("perInterest")}"
        "update_plan" -> "修改分期：${a.args.optString("bank")} ${a.args.optString("tail")} ${a.args.optString("title")} → 本金 ${a.args.opt("principal") ?: "不变"} · 期数 ${a.args.opt("periods") ?: "不变"} · 已还 ${a.args.opt("paidPeriods") ?: "不变"} · 每期利息 ${a.args.opt("perInterest") ?: "不变"}"
        "delete_plan" -> "删除分期：${a.args.optString("bank")} ${a.args.optString("tail")} ${a.args.optString("title")}（真删，不可撤销）"
        else -> a.tool
    }

    /** 执行工具，返回给模型看的结果文本 */
    fun run(a: Action): String = runCatching {
        when (a.tool) {
            "list_plans" -> listPlans()
            "summary" -> summary()
            "add_plan" -> addPlan(a.args)
            "update_plan" -> updatePlan(a.args)
            "delete_plan" -> deletePlan(a.args)
            else -> "未知工具 ${a.tool}"
        }
    }.getOrElse { "工具 ${a.tool} 执行失败：${it.message}" }

    private fun listPlans(): String {
        if (Repo.active.isEmpty() && Repo.ended.isEmpty()) return "没有任何分期"
        val sb = StringBuilder()
        Repo.active.forEach {
            sb.appendLine("在还：${it.title.ifBlank { it.bank + " " + it.tail }} | 本金 ${it.principal} | ${it.periods} 期已还 ${it.paidPeriods} | 每期利息 ${it.perInterest} | 每期应还 ${"%.2f".format(it.perPay)}")
        }
        Repo.ended.forEach {
            sb.appendLine("已结束：${it.title.ifBlank { it.bank + " " + it.tail }} | 本金 ${it.principal} | ${it.periods} 期")
        }
        return sb.toString()
    }

    private fun summary(): String = """
收入 ${"%.2f".format(Repo.incomeTotal)}，还款 ${"%.2f".format(Repo.repay)}，固定支出 ${"%.2f".format(Repo.fixedExpense)}，可支配 ${"%.2f".format(Repo.disposable)}
待还 ${"%.2f".format(Repo.remainingTotal)}，已付利息 ${"%.2f".format(Repo.paidInterestTotal)}，利息合计 ${"%.2f".format(Repo.interestAllTime)}
    """.trimIndent()

    private fun addPlan(a: JSONObject): String {
        val periods = a.optInt("periods", 12).coerceAtLeast(1)
        val plan = Plan(
            id = Repo.newId(),
            title = a.optString("title"),
            bank = a.optString("bank", Repo.cards.firstOrNull()?.bank ?: "银行"),
            tail = a.optString("tail", Repo.cards.firstOrNull()?.tail ?: "0000"),
            principal = a.optDouble("principal", 0.0),
            periods = periods,
            paidPeriods = a.optInt("paidPeriods", 0),
            perInterest = a.optDouble("perInterest", 0.0),
            method = runCatching { Method.valueOf(a.optString("method", "FLAT_RATE")) }.getOrDefault(Method.FLAT_RATE),
            startDate = runCatching { LocalDate.parse(a.optString("startDate")) }.getOrDefault(LocalDate.now()),
            statementDay = Repo.cards.firstOrNull { it.bank == a.optString("bank") }?.statementDay ?: 25,
            dueDay = Repo.cards.firstOrNull { it.bank == a.optString("bank") }?.dueDay ?: 10
        )
        Repo.add(plan)
        return "已新建：${plan.bank} ${plan.tail} 本金 ${plan.principal} ${plan.periods} 期"
    }

    private fun updatePlan(a: JSONObject): String {
        val bank = a.optString("bank"); val tail = a.optString("tail"); val title = a.optString("title")
        val idx = Repo.active.indexOfFirst {
            it.bank == bank && it.tail == tail && (title.isBlank() || it.title == title)
        }
        if (idx < 0) return "没找到这条分期（$bank $tail $title）"
        val p = Repo.active[idx]
        val np = p.copy(
            title = if (a.has("title") && title.isNotBlank()) title else p.title,
            principal = if (a.has("principal")) a.optDouble("principal") else p.principal,
            periods = if (a.has("periods")) a.optInt("periods") else p.periods,
            paidPeriods = if (a.has("paidPeriods")) a.optInt("paidPeriods").coerceIn(0, p.periods) else p.paidPeriods,
            perInterest = if (a.has("perInterest")) a.optDouble("perInterest") else p.perInterest
        )
        Repo.active[idx] = np
        return "已修改：${np.title.ifBlank { np.bank + " " + np.tail }} → 本金 ${np.principal} ${np.periods} 期 已还 ${np.paidPeriods} 每期利息 ${np.perInterest}"
    }

    private fun deletePlan(a: JSONObject): String {
        val bank = a.optString("bank"); val tail = a.optString("tail"); val title = a.optString("title")
        val idx = Repo.active.indexOfFirst {
            it.bank == bank && it.tail == tail && (title.isBlank() || it.title == title)
        }
        if (idx < 0) return "没找到这条分期"
        val removed = Repo.active.removeAt(idx)
        return "已删除：${removed.title.ifBlank { removed.bank + " " + removed.tail }}"
    }
}

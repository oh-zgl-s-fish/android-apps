package com.zglinus.installment.ui

/**
 * 跨 feature 的导航契约：feature 之间不互相依赖，只认这个接口。
 * 实现放在 :app（唯一知道所有 feature 的地方）。
 */
interface Nav {
    fun openDashboard()
    fun openPlans()
    fun openNewPlan()
    fun openSchedule(id: Long)
    fun openPlanDetail(id: Long)
    fun openMonthly()
    fun openBills()
    fun openAi()
    fun openAccounts()
    fun openProfile()
}

object NavHolder {
    lateinit var nav: Nav
}

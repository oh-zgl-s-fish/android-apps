package com.zglinus.installment

import android.app.Application
import android.content.Intent
import com.google.android.material.color.DynamicColors
import com.zglinus.installment.feature.AccountsActivity
import com.zglinus.installment.feature.AiActivity
import com.zglinus.installment.feature.BillsActivity
import com.zglinus.installment.feature.DashboardActivity
import com.zglinus.installment.feature.MonthlyActivity
import com.zglinus.installment.feature.NewPlanActivity
import com.zglinus.installment.feature.PlanDetailActivity
import com.zglinus.installment.feature.PlansActivity
import com.zglinus.installment.feature.ProfileActivity
import com.zglinus.installment.feature.ScheduleActivity
import com.zglinus.installment.ui.Nav
import com.zglinus.installment.ui.NavHolder
import com.zglinus.installment.ui.Store

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // Monet：Android 12+ 跟随壁纸；更低版本回落主题种子色
        DynamicColors.applyToActivitiesIfAvailable(this)
        // 读本地存档（首启用种子数据）
        Store.load(this)
        com.zglinus.installment.ui.LockState.unlocked = false
        // 锁屏即上锁：灭屏广播一到就把解锁状态作废，亮屏回到前台会重新要密码
        val screenFilter = android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF)
        val screenReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                if (android.content.Intent.ACTION_SCREEN_OFF == intent?.action) {
                    com.zglinus.installment.ui.LockState.unlocked = false
                    android.util.Log.i("AppLock", "screen off → locked")
                }
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, screenFilter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, screenFilter)
        }
        if (Store.mcpEnabled) {
            com.zglinus.installment.ui.McpHost.start(Store.mcpPort, Store.mcpToken, Store.mcpLocalOnly)
        }

        NavHolder.nav = object : Nav {
            override fun openDashboard() = launchTab(DashboardActivity::class.java)
            override fun openPlans() = launchTab(PlansActivity::class.java)
            override fun openAccounts() = launchTab(AccountsActivity::class.java)
            override fun openProfile() = launchTab(ProfileActivity::class.java)
            override fun openNewPlan() = launch(NewPlanActivity::class.java)
            override fun openAi() = launchTab(AiActivity::class.java)
            override fun openMonthly() = launch(MonthlyActivity::class.java)
            override fun openBills() = launch(BillsActivity::class.java)

            override fun openPlanDetail(id: Long) {
                startActivity(
                    Intent(this@App, PlanDetailActivity::class.java)
                        .putExtra("id", id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }

            override fun openSchedule(id: Long) {
                startActivity(
                    Intent(this@App, ScheduleActivity::class.java)
                        .putExtra("id", id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    private fun launch(cls: Class<*>) {
        startActivity(Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** tab 页：复用已有实例，不再叠加 */
    private fun launchTab(cls: Class<*>) {
        startActivity(
            Intent(this, cls).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
    }
}

package com.zglinus.playcheck.platform

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager

/**
 * 锁屏状态的两套语义（官方文档原文）：
 *  - isKeyguardLocked()   = 锁屏“正在显示”，即使被来电/紧急拨号盖住也算，且不区分是否需要认证
 *  - isDeviceLocked()     = 设备“已锁定”，即需要 PIN/图案/密码/生物识别才能回到应用；滑动解锁那种不算
 * 打卡只关心第二种：需要认证 = 人不在游戏里，不能再计时。
 */
object Keyguard {
    fun isShowing(ctx: Context): Boolean = runCatching {
        (ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
    }.getOrDefault(false)

    fun isDeviceLocked(ctx: Context): Boolean = runCatching {
        (ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked
    }.getOrDefault(false)

    /** 屏幕是否处于可交互状态（息屏/AOD 为 false）。 */
    fun isInteractive(ctx: Context): Boolean = runCatching {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
    }.getOrDefault(true)

    /** 现在这一秒该不该计时：屏幕亮着且没被锁。 */
    fun canCountNow(ctx: Context): Boolean = isInteractive(ctx) && !isDeviceLocked(ctx)
}

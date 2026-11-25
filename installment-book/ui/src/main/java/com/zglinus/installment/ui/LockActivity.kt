package com.zglinus.installment.ui

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

/** 应用解锁：密码 + 生物识别（可选） */
object LockState {
    @Volatile var unlocked = false
}

class LockActivity : PageActivity() {

    override val navIndex = 0
    override val isTabPage = false

    private lateinit var pinBox: EditText
    private lateinit var tip: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        header("已加锁", "输入密码解锁")
        findViewById<View>(R.id.nav).visibility = View.GONE
        findViewById<View>(R.id.fab).visibility = View.GONE

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(40), dp(28), 0)
        }

        tip = Ui.text(this, "4 位数字密码", 11f, Ui.onVar).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        }
        col.addView(tip)

        pinBox = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = Gravity.CENTER
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(16f).toFloat()
                setColor(Ui.colorOf(this@LockActivity, Ui.container))
            }
        }
        col.addView(pinBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val unlock = com.google.android.material.button.MaterialButton(this).apply {
            text = "解锁"; isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48f)
            ).apply { topMargin = dp(14) }
            setOnClickListener { tryPin() }
        }
        col.addView(unlock)

        if (Store.lockBio) {
            col.addView(com.google.android.material.button.MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = "用指纹 / 面容"; isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(48f)
                ).apply { topMargin = dp(10) }
                setOnClickListener { bioPrompt() }
            })
        }
        add(col)

        if (Store.lockBio) bioPrompt()
    }

    private fun tryPin() {
        val entered = pinBox.text.toString().trim()
        if (entered == Store.lockPin) {
            LockState.unlocked = true
            finish()
        } else {
            tip.text = "密码不对"
            tip.setTextColor(Ui.colorOf(this, Ui.error))
            pinBox.setText("")
        }
    }

    private fun bioPrompt() {
        val mgr = BiometricManager.from(this)
        val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (mgr.canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
            tip.text = "这台设备没有可用的指纹/面容，用密码"
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    LockState.unlocked = true
                    finish()
                }
                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    tip.text = "生物识别不可用，用密码"
                }
            }
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("解锁分期")
                .setAllowedAuthenticators(allowed)
                .build()
        )
    }
}

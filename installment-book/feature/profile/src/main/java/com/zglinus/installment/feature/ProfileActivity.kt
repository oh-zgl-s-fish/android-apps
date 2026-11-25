package com.zglinus.installment.feature

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zglinus.installment.core.Repo
import com.zglinus.installment.ui.*

/** 我的：AI 模型 + 对外只读查询服务（MCP） */
class ProfileActivity : PageActivity() {

    override val navIndex = 4

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume(); render()
    }

    private fun render() {
        content.removeAllViews()
        header("我的", "AI 模型 · 对外只读服务")

        add(sectionLabel("AI 模型"))
        add(listItem(R.drawable.ic_ai, "Base URL", Store.llmBaseUrl.ifBlank { "未配置" }) { editText("Base URL", Store.llmBaseUrl) { v ->
            Store.setLlm(this, v, Store.llmModel, Store.llmApiKey); render()
        } })
        add(md3Divider())
        add(listItem(R.drawable.ic_ai, "模型", Store.llmModel.ifBlank { "未配置" }) { editText("模型", Store.llmModel) { v ->
            Store.setLlm(this, Store.llmBaseUrl, v, Store.llmApiKey); render()
        } })
        add(md3Divider())
        add(listItem(R.drawable.ic_ai, "API Key", mask(Store.llmApiKey).ifBlank { "未配置" }) { editText("API Key", Store.llmApiKey) { v ->
            Store.setLlm(this, Store.llmBaseUrl, Store.llmModel, v); render()
        } })

        // 对外只读服务
        add(sectionLabel("预算"))
        add(
            listItem(
                R.drawable.ic_income, "固定支出", Ui.money(Repo.fixedExpense)
            ) {
                val et = android.widget.EditText(this@ProfileActivity).apply {
                    setText("%.2f".format(Repo.fixedExpense))
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    setPadding(dp(20f).toInt(), dp(12f).toInt(), dp(20f).toInt(), dp(12f).toInt())
                }
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this@ProfileActivity)
                    .setTitle("固定支出")
                    .setView(et)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("保存") { _, _ ->
                        val v = et.text.toString().trim().toDoubleOrNull()
                        if (v != null && v >= 0) {
                            Store.setFixedExpense(this@ProfileActivity, v)
                            render()
                        } else {
                            Toast.makeText(this@ProfileActivity, "请输入数字", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .show()
            }
        )
        add(listItem(R.drawable.ic_income, "收入合计", Ui.money(Repo.incomeTotal), trailing = null))

        add(sectionLabel("对外只读服务（MCP）"))
        val ip = localIp()
        // 开关一旦打开就确保服务在跑（绑 0.0.0.0，局域网可连）
        if (Store.mcpEnabled) {
            com.zglinus.installment.ui.McpHost.ensureRunning(Store.mcpPort, Store.mcpToken, Store.mcpLocalOnly)
        }
        val listenText = when {
            !Store.mcpEnabled -> "关闭时不监听任何端口"
            com.zglinus.installment.ui.McpHost.running && !Store.mcpLocalOnly -> "http://$ip:${Store.mcpPort}/mcp"
            com.zglinus.installment.ui.McpHost.running -> "http://127.0.0.1:${Store.mcpPort}/mcp（仅本机）"
            else -> "启动失败：${com.zglinus.installment.ui.McpHost.lastError ?: "未知"}"
        }
        add(
            listItem(
                R.drawable.ic_storage, "开启服务",
                listenText,
                trailing = SwitchCompat(this).apply {
                    isChecked = Store.mcpEnabled
                    setOnCheckedChangeListener { _, on ->
                        // 打开时强绑 0.0.0.0，保证局域网 IP 能连；否则只会在回环上
                        Store.setMcp(this@ProfileActivity, on, Store.mcpPort, Store.mcpToken, Store.mcpLocalOnly)
                        if (on) {
                            McpHost.ensureRunning(Store.mcpPort, Store.mcpToken, Store.mcpLocalOnly)
                            val shown = if (Store.mcpLocalOnly) "127.0.0.1" else localIp()
                            val msg = if (McpHost.running)
                                "已监听 http://$shown:${Store.mcpPort}/mcp"
                            else "启动失败：${McpHost.lastError ?: "端口被占用"}"
                            Toast.makeText(this@ProfileActivity, msg, Toast.LENGTH_LONG).show()
                        }
                        render()
                    }
                }
            )
        )
        add(md3Divider())
        add(listItem(R.drawable.ic_card, "端口", "${Store.mcpPort}") { editPort() })
        add(md3Divider())
        add(listItem(R.drawable.ic_ai, "Token", Store.mcpToken.ifBlank { "未设置 · 谁都能查" }) { editToken() })
        add(md3Divider())
        add(
            listItem(
                R.drawable.ic_storage, "仅本机监听",
                if (Store.mcpLocalOnly) "只绑 127.0.0.1，外部连不上" else "绑 0.0.0.0，同网段都能连",
                trailing = SwitchCompat(this).apply {
                    isChecked = Store.mcpLocalOnly
                    setOnCheckedChangeListener { _, on ->
                        Store.setMcp(this@ProfileActivity, Store.mcpEnabled, Store.mcpPort, Store.mcpToken, on)
                        Toast.makeText(
                            this@ProfileActivity,
                            if (on) "已改为只监听 127.0.0.1" else "已改为监听所有网卡",
                            Toast.LENGTH_SHORT
                        ).show()
                        render()
                    }
                }
            )
        )
        add(md3Divider())
        add(listItem(R.drawable.ic_income, "工具", "summary · plans · upcoming", trailing = Ui.text(this, "只读", 12f, Ui.onVar)))

        // 数据
        add(sectionLabel("数据"))
        add(listItem(R.drawable.ic_income, "导出 JSON", "整份存档，可再导回") { exportFile() })
        add(md3Divider())
        add(listItem(R.drawable.ic_storage, "导入 JSON", "覆盖现有数据，先备份") { importFile() })

        // 加锁
        add(sectionLabel("加锁"))
        add(
            listItem(
                R.drawable.ic_storage, "应用锁",
                if (Store.lockEnabled) "密码 + ${if (Store.lockBio) "生物识别" else "仅密码"}" else "关闭",
                trailing = SwitchCompat(this).apply {
                    isChecked = Store.lockEnabled
                    setOnCheckedChangeListener { _, on ->
                        if (on) setPin { pin ->
                            Store.setLock(this@ProfileActivity, true, pin, Store.lockBio)
                            com.zglinus.installment.ui.LockState.unlocked = true
                            render()
                        } else {
                            isChecked = false
                        }
                    }
                }
            )
        )
        add(md3Divider())
        add(
            listItem(
                R.drawable.ic_ai, "生物识别",
                if (Store.lockEnabled) "指纹 / 面容（可选）" else "先开应用锁",
                trailing = SwitchCompat(this).apply {
                    isChecked = Store.lockBio && Store.lockEnabled
                    isEnabled = Store.lockEnabled
                    setOnCheckedChangeListener { _, on ->
                        Store.setLock(this@ProfileActivity, true, Store.lockPin, on)
                        render()
                    }
                }
            )
        )
        add(md3Divider())
        add(listItem(R.drawable.ic_card, "修改密码", if (Store.lockEnabled) "4 位数字" else "未设置") {
            if (!Store.lockEnabled) {
                Toast.makeText(this, "先开启应用锁", Toast.LENGTH_SHORT).show()
                return@listItem
            }
            setPin { pin ->
                Store.setLock(this, true, pin, Store.lockBio)
                Toast.makeText(this, "密码已更新", Toast.LENGTH_SHORT).show()
                render()
            }
        })

        val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        add(
            Ui.text(this, "分期 v$ver · com.zglinus.installment", 10f, Ui.onVar).apply {
                gravity = android.view.Gravity.CENTER
                setPadding(0, dp(28), 0, dp(8))
            }
        )
    }

    // ---------------- 对话框 ----------------

    private fun dialogBox(children: List<View>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(6), dp(20), 0)
        children.forEach { addView(it) }
    }

    private fun labeled(label: String, initial: String, numeric: Boolean = false): EditText {
        val et = EditText(this).apply {
            setText(initial)
            setSingleLine()
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
        }
        return et
    }

    private fun editText(title: String, initial: String, onSave: (String) -> Unit) {
        val et = labeled(title, initial)
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(dialogBox(listOf(et)))
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ -> onSave(et.text.toString().trim()) }
            .show()
    }

    private fun editPort() {
        val et = labeled("端口", Store.mcpPort.toString(), numeric = true)
        MaterialAlertDialogBuilder(this)
            .setTitle("监听端口（1024–65535）")
            .setView(dialogBox(listOf(et)))
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val p = et.text.toString().toIntOrNull()
                if (p == null || p !in 1024..65535) {
                    Toast.makeText(this, "端口要在 1024–65535", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                Store.setMcp(this, Store.mcpEnabled, p, Store.mcpToken)
                render()
            }
            .show()
    }

    private fun editToken() {
        val et = labeled("Token", Store.mcpToken)
        MaterialAlertDialogBuilder(this)
            .setTitle("访问 Token（留空 = 不校验）")
            .setView(dialogBox(listOf(et)))
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                Store.setMcp(this, Store.mcpEnabled, Store.mcpPort, et.text.toString().trim())
                render()
            }
            .show()
    }

    /** 设置 4 位数字密码 */
    private fun setPin(onSet: (String) -> Unit) {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setSingleLine()
            hint = "4 位数字"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("设置解锁密码")
            .setView(dialogBox(listOf(et)))
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                val pin = et.text.toString().trim()
                if (pin.length != 4 || pin.toIntOrNull() == null) {
                    Toast.makeText(this, "要 4 位数字", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                onSet(pin)
            }
            .show()
    }

    // ---------------- 导入导出 ----------------

    private val createDoc = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(Store.exportJson().toByteArray())
            }
        }.onSuccess {
            Toast.makeText(this, "已导出", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "导出失败：${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private val openDoc = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val text = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "读不到文件", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("导入会覆盖现有数据")
            .setMessage("当前 ${Repo.active.size} 条在还、${Repo.ended.size} 条已结束会被替换。继续？")
            .setNegativeButton("取消", null)
            .setPositiveButton("导入") { _, _ ->
                runCatching { Store.importJson(text) }
                    .onSuccess { msg ->
                        Store.save(this)
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        render()
                    }
                    .onFailure { e ->
                        Toast.makeText(this, "导入失败：${e.message}", Toast.LENGTH_LONG).show()
                    }
            }
            .show()
    }

    private fun exportFile() {
        val stamp = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        createDoc.launch("installment-$stamp.json")
    }

    private fun importFile() {
        openDoc.launch(arrayOf("application/json", "text/plain", "*/*"))
    }

    // ---------------- 工具 ----------------

    private fun mask(key: String) = if (key.length <= 8) key else key.take(4) + "…" + key.takeLast(4)

    private fun localIp(): String = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }?.hostAddress
    }.getOrNull() ?: "手机IP"
}

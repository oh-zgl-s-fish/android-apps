package com.zglinus.installment.feature

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zglinus.installment.core.Chat
import com.zglinus.installment.core.ChatMsg
import com.zglinus.installment.core.Repo
import com.zglinus.installment.ui.*

/**
 * AI 助手。结构照 Signal 的会话页：
 * 一列 = 会话头 + RecyclerView(weight=1) + 输入栏；键盘靠容器的 IME insets padding 压缩列表，
 * 滚动交给 RecyclerView.scrollToPosition，不自己做 ScrollView 高度/位移算术。
 */
class AiActivity : PageActivity() {

    override val navIndex = 3
    private lateinit var input: EditText
    private lateinit var titleView: TextView
    private lateinit var chat: Chat
    private lateinit var list: RecyclerView
    private lateinit var adapter: MsgAdapter
    /** 暂存的图：等用户发消息时一起带上 */
    private var pendingB64: String? = null       // 原图 base64，发给模型
    private var pendingThumb: String? = null     // 缩略图 base64，存进聊天记录
    private lateinit var imgChip: android.widget.FrameLayout
    private lateinit var imgThumb: android.widget.ImageView
    private lateinit var imgLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        header("AI 助手", configHint())
        chat = Store.currentChat()
        // Android 15+ 强制 edge-to-edge：窗口不随键盘缩放，靠读 IME insets 自己压
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        // 1) 本页不用 PageActivity 的滚动容器
        findViewById<View>(R.id.scroll).visibility = View.GONE
        val pageRoot = findViewById<View>(R.id.page_root) as android.widget.FrameLayout
        val inner = pageRoot.getChildAt(0) as LinearLayout
        val nav = findViewById<View>(R.id.nav)

        // 2) 会话列插在头部之后、底栏之前，吃满剩余高度
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inner.addView(column, 1, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val bar = Ui.row(this).apply { setPadding(dp(4), 0, dp(4), dp(8)) }
        titleView = Ui.text(this, chat.title, 13f, Ui.onSurface, bold = true)
        bar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Ui.text(this, "切换对话", 11f, Ui.primary, bold = true).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(Ui.colorOf(this@AiActivity, Ui.primaryContainer))
            }
            setOnClickListener { switchChat() }
        })
        column.addView(bar)

        adapter = MsgAdapter()
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@AiActivity).apply { stackFromEnd = true }
            adapter = this@AiActivity.adapter
            setPadding(dp(16f).toInt(), dp(4f).toInt(), dp(16f).toInt(), dp(4f).toInt())
            clipToPadding = false
            itemAnimator = null
        }
        column.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val bar2 = Ui.row(this).apply { setPadding(dp(16f).toInt(), dp(6f).toInt(), dp(16f).toInt(), dp(8f).toInt()) }
        // 图片：圆形，暂存后圆里显示缩略图；再点一次重新选图
        imgThumb = ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        imgLabel = Ui.text(this, "图片", 10f, Ui.onPrimaryContainer, bold = true).apply {
            gravity = Gravity.CENTER
        }
        imgChip = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Ui.colorOf(this@AiActivity, Ui.primaryContainer))
            }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, v.width, v.height)
                }
            }
            addView(imgThumb, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            addView(imgLabel, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER })
            setOnClickListener { pickImage() }
        }
        bar2.addView(imgChip, LinearLayout.LayoutParams(dp(42f), dp(42f)).apply { rightMargin = dp(8) })
        input = EditText(this).apply {
            hint = "问点什么…"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Ui.colorOf(this@AiActivity, Ui.onSurface))
            setHintTextColor(Ui.colorOf(this@AiActivity, Ui.onVar))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Ui.colorOf(this@AiActivity, Ui.container))
            }
        }
        bar2.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar2.addView(Ui.text(this, "↑", 17f, Ui.onPrimary, bold = true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Ui.colorOf(this@AiActivity, Ui.primary))
            }
            setOnClickListener { send(input.text.toString()) }
        }, LinearLayout.LayoutParams(dp(42f), dp(42f)).apply { leftMargin = dp(8) })
        column.addView(bar2)

        // 3) insets：IME 高度加到底部 padding，列表(weight=1)被压缩，输入栏落在键盘上沿
        var lastIme = -1
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(pageRoot) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            v.setPadding(bars.left, bars.top, bars.right, 0)
            val navH = dp(68f).toInt() + bars.bottom
            nav.layoutParams = nav.layoutParams.apply { height = navH }
            nav.setPadding(nav.paddingLeft, nav.paddingTop, nav.paddingRight, bars.bottom)
            if (ime != lastIme) {
                lastIme = ime
                // 底栏被平移出屏幕后仍占着布局高度，所以内边距要减掉它的高度，
                // 否则输入栏会离键盘空出一截
                inner.setPadding(
                    inner.paddingLeft, inner.paddingTop, inner.paddingRight,
                    (ime - navH).coerceAtLeast(0)
                )
                nav.translationY = if (ime > 0) navH.toFloat() else 0f
                if (ime > 0) scrollDown()
            }
            insets
        }

        renderMessages()
    }

    private fun configHint(): String =
        if (Store.llmBaseUrl.isBlank() || Store.llmApiKey.isBlank()) "未配置模型 · 去「我的」"
        else Store.llmModel.ifBlank { "deepseek-chat" }

    private fun switchChat() {
        val names = Store.chats.map { "${it.title}${if (it.id == chat.id) "（当前）" else ""}" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("对话")
            .setItems(names) { _, which ->
                chat = Store.chats[which]
                Store.selectChat(this, chat.id)
                titleView.text = chat.title
                renderMessages()
            }
            .setPositiveButton("新建对话") { _, _ ->
                chat = Store.newChat(this)
                titleView.text = chat.title
                renderMessages()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun renderMessages() {
        val items = if (chat.msgs.isEmpty())
            listOf(ChatMsg("assistant", "你好，我可以按你本地的分期数据回答。改前设的 LLM 配置后就能真聊。"))
        else chat.msgs
        adapter.submit(items)
        scrollDown()
    }

    private fun appendMsg(role: String, text: String, image: String? = null) {
        chat.msgs += ChatMsg(role, text, image)
        adapter.add(ChatMsg(role, text, image))
        scrollDown()
    }

    private val pickImg = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            pendingB64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            pendingMime = contentResolver.getType(uri) ?: "image/jpeg"
            // 缩略图：最长边 480，存进聊天记录用
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val scale = 480f / maxOf(bmp.width, bmp.height).coerceAtLeast(1)
            val th = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(
                bmp, (bmp.width * scale).toInt().coerceAtLeast(1),
                (bmp.height * scale).toInt().coerceAtLeast(1), true
            ) else bmp
            val bos = java.io.ByteArrayOutputStream()
            th.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, bos)
            pendingThumb = android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
            showPendingThumb(th)
        }.onFailure { appendMsg("assistant", "读图失败：${it.message}") }
    }

    private var pendingMime = "image/jpeg"

    private fun pickImage() = pickImg.launch("image/*")

    /** 暂存图显示在圆里（点一下可重选） */
    private fun showPendingThumb(bmp: android.graphics.Bitmap) {
        imgThumb.setImageBitmap(bmp)
        imgThumb.visibility = View.VISIBLE
        imgLabel.visibility = View.GONE
    }

    private fun clearPending() {
        pendingB64 = null
        pendingThumb = null
        imgThumb.setImageDrawable(null)
        imgThumb.visibility = View.GONE
        imgLabel.visibility = View.VISIBLE
    }

    private fun scrollDown() {
        listOf(0L, 50L, 150L).forEach { d ->
            list.postDelayed({
                if (adapter.itemCount > 0) list.scrollToPosition(adapter.itemCount - 1)
            }, d)
        }
    }

    private fun send(text: String) {
        if (text.isBlank() && pendingB64 == null) return
        val imageB64 = pendingB64
        val imageMime = pendingMime
        val thumb = pendingThumb
        appendMsg("user", text.ifBlank { "（图片）" }, thumb)
        clearPending()
        if (chat.title == "新对话") chat.title = text.take(12)
        titleView.text = chat.title
        input.setText("")

        if (Store.llmBaseUrl.isBlank() || Store.llmApiKey.isBlank()) {
            appendMsg("assistant", "还没配置模型：去「我的」填 Base URL、模型、API Key。")
            return
        }

        val thinking = ChatMsg("assistant", "思考中…")
        adapter.add(thinking)
        scrollDown()
        Thread {
            var reply = runCatching {
                Llm.complete(
                    Store.llmBaseUrl, Store.llmApiKey, Store.llmModel, system(), text,
                    imageB64, imageMime
                )
            }.getOrElse { "请求失败：${it.message ?: it.javaClass.simpleName}" }

            var round = 0
            while (round < 3) {
                val action = AiTools.parse(reply) ?: break
                if (action.isWrite) {
                    runOnUiThread {
                        adapter.remove(thinking)
                        MaterialAlertDialogBuilder(this)
                            .setTitle("AI 要改数据")
                            .setMessage(AiTools.describe(action))
                            .setNegativeButton("不要") { _, _ ->
                                Thread {
                                    val r2 = runCatching {
                                        Llm.complete(
                                            Store.llmBaseUrl, Store.llmApiKey, Store.llmModel,
                                            system(), "用户拒绝了这次操作：" + AiTools.describe(action)
                                        )
                                    }.getOrDefault("好的，我不改。")
                                    runOnUiThread { appendMsg("assistant", r2); Store.save(this) }
                                }.start()
                            }
                            .setPositiveButton("执行") { _, _ ->
                                val result = AiTools.run(action)
                                Store.save(this)
                                appendMsg("assistant", "已执行：$result")
                                Thread {
                                    val r3 = runCatching {
                                        Llm.complete(
                                            Store.llmBaseUrl, Store.llmApiKey, Store.llmModel,
                                            system(), "工具结果：$result。用一句话确认，别重复数字。"
                                        )
                                    }.getOrDefault("完成。")
                                    runOnUiThread { appendMsg("assistant", r3); Store.save(this) }
                                }.start()
                            }
                            .show()
                    }
                    return@Thread
                }
                val result = AiTools.run(action)
                reply = runCatching {
                    Llm.complete(
                        Store.llmBaseUrl, Store.llmApiKey, Store.llmModel, system(),
                        "工具结果（${action.tool}）：\n$result\n请据此回答之前的问题。"
                    )
                }.getOrDefault(result)
                round++
            }

            runOnUiThread {
                adapter.remove(thinking)
                appendMsg("assistant", reply)
                Store.save(this)
            }
        }.start()
    }

    /** 消息项：无状态视图，由 adapter 复用（Signal 的 MessageView 简化版） */
    private inner class MsgAdapter : RecyclerView.Adapter<MsgAdapter.VH>() {
        private val data = mutableListOf<ChatMsg>()

        fun submit(items: List<ChatMsg>) {
            data.clear(); data.addAll(items); notifyDataSetChanged()
        }

        fun add(msg: ChatMsg) {
            data += msg
            notifyItemInserted(data.size - 1)
        }

        fun remove(msg: ChatMsg) {
            val i = data.indexOfFirst { it === msg }
            if (i >= 0) { data.removeAt(i); notifyItemRemoved(i) }
        }

        override fun getItemCount() = data.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val wrap = LinearLayout(this@AiActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8f).toInt() }
            }
            val col = LinearLayout(this@AiActivity).apply { orientation = LinearLayout.VERTICAL }
            val iv = android.widget.ImageView(this@AiActivity).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                visibility = View.GONE
                val r = dp(12f).toFloat()
                background = GradientDrawable().apply {
                    cornerRadii = floatArrayOf(r, r, r, r, r, r, r, r)
                    setColor(Ui.colorOf(this@AiActivity, Ui.container))
                }
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, v.width, v.height, r)
                    }
                }
            }
            val tv = Ui.text(this@AiActivity, "", 12.5f, Ui.onSurface).apply {
                setLineSpacing(dp(3f).toFloat(), 1.05f)
                setPadding(dp(14f).toInt(), dp(11f).toInt(), dp(14f).toInt(), dp(11f).toInt())
                setTextIsSelectable(true)
            }
            col.addView(iv, LinearLayout.LayoutParams(dp(150f), dp(150f)).apply { bottomMargin = dp(6f).toInt() })
            col.addView(tv, LinearLayout.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.78).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            wrap.addView(col)
            return VH(wrap, tv, iv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val m = data[position]
            val mine = m.role == "user"
            holder.wrap.gravity = if (mine) Gravity.END else Gravity.START
            val bg = Ui.colorOf(this@AiActivity, if (mine) Ui.primary else Ui.container)
            val fg = Ui.colorOf(this@AiActivity, if (mine) Ui.onPrimary else Ui.onSurface)
            val img = m.image
            if (img.isNullOrBlank()) {
                holder.iv.visibility = View.GONE
            } else {
                val bytes = android.util.Base64.decode(img, android.util.Base64.NO_WRAP)
                holder.iv.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                holder.iv.visibility = View.VISIBLE
            }
            holder.tv.visibility = if (m.text.isBlank()) View.GONE else View.VISIBLE
            holder.tv.setTextColor(fg)
            holder.tv.text = if (mine) m.text else Md.render(
                m.text, fg,
                Ui.colorOf(this@AiActivity, Ui.container),
                Ui.colorOf(this@AiActivity, Ui.primary)
            )
            holder.tv.background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(
                    dp(if (mine) 16f else 4f).toFloat(), dp(if (mine) 16f else 4f).toFloat(),
                    dp(16f).toFloat(), dp(16f).toFloat(),
                    dp(16f).toFloat(), dp(16f).toFloat(),
                    dp(if (mine) 4f else 16f).toFloat(), dp(if (mine) 4f else 16f).toFloat()
                )
                setColor(bg)
            }
        }

        inner class VH(val wrap: LinearLayout, val tv: TextView, val iv: android.widget.ImageView) :
            RecyclerView.ViewHolder(wrap)
    }

    private fun system(): String {
        val active = Repo.active.joinToString("；") {
            "${it.bank}${it.tail} 本金${it.principal} 剩余本金${it.perPrincipal * it.remainPeriods} 期数${it.periods} 已还${it.paidPeriods} 每期${it.perPay} 年化${"%.2f".format(it.apr * 100)}%"
        }.ifBlank { "无" }
        val next = Repo.upcoming(1).firstOrNull()
        return AiTools.spec + """

你是信用卡分期助手。只根据下面这份本地数据回答，不要编造数字；算不出来就直说算不出来。
可支配预算 = 收入 − 还款 − 固定支出
收入合计 ${"%.2f".format(Repo.incomeTotal)}，还款 ${"%.2f".format(Repo.repay)}，固定支出 ${"%.2f".format(Repo.fixedExpense)}，本月可支配 ${"%.2f".format(Repo.disposable)}
待还合计 ${"%.2f".format(Repo.remainingTotal)}，已付利息 ${"%.2f".format(Repo.paidInterestTotal)}，利息合计 ${"%.2f".format(Repo.interestAllTime)}
下一次还款：${next?.let { "${it.first} ${it.second} ${"%.2f".format(it.third)}" } ?: "无"}
在还计划：$active
回答简短，用中文，数字保留两位小数。

遇到「逐期还款明细」或账单里能读出的分期（有每期金额/期数/本金/利息）时：
- 先查本地是否已有对应计划（同银行、同尾号、同本金或同期数）
- 没有对应汇总就直接用 add_plan 建一条，把明细里的数字原样写进去：principal 用本金，periods 用期数，perInterest 用（总利息/期数），method 按明细形态判断（每期利息相同=等本等息；每期总额相同=等额本息；利息递减=等额本金；无利息=免息）
- 不要只做文字汇总却不落库；建完用一句话说明建了什么
- 已有对应计划就不要重复建，改为提示用户可以 update_plan 修正
        """.trimIndent()
    }
}

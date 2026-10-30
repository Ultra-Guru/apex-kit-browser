package com.apex.browser.engine

import android.os.Handler
import android.os.Looper
import android.webkit.JsPromptResult
import android.webkit.JsResult

/*
 * DialogArbiter —— JS 弹窗仲裁器（v2.0.0，Playwright `page.on('dialog')` +
 * `dialog.accept()/dismiss()` 的 WebView 对标面）。
 *
 * v1.x 行为是**全部自动确认**（alert 关掉、confirm 确认、prompt 回默认值）：
 * 保守不卡死，但 Agent 对 `confirm('确定删除吗？')` 没有任何否决权 ——
 * 「确认离开页面？」「确定提交订单？」这类语义分叉点全被替用户点了「好」。
 *
 * 三模式（[Mode]，默认 [Mode.AUTO_CONFIRM] 保持旧行为零漂移）：
 * - AUTO_CONFIRM：全确认（旧行为）；
 * - AUTO_DISMISS：全拒绝（confirm=false / prompt 取消）——「离开页面？」
 *   点「留下」才是多数自动化想要的语义；
 * - MANUAL：入队等待 [respond]（accept / dismiss / promptText）——
 *   **15s 超时兜底自动确认**：页面 JS 是挂起态，永不 respond 会把标签页
 *   永久卡死，超时兜底保证「最坏回到旧行为」而非「最坏死锁」。
 *
 * 线程模型：WebChromeClient 回调在主线程 → 弹窗入队主线程；[respond]
 * 可从任意线程调用（post 到主线程执行）；[pendingDialogs] / [pendingNotice]
 * 只读快照经 synchronized（读侧无锁竞争压力）。
 */
class DialogArbiter internal constructor(private val tracer: BrowserTracer) {

    /** 弹窗处置模式。 */
    enum class Mode {
        /** 全确认（v1.x 行为，默认）。 */
        AUTO_CONFIRM,
        /** 全拒绝：confirm 返回 false、prompt 取消。 */
        AUTO_DISMISS,
        /** 入队等待 respond（15s 超时自动确认）。 */
        MANUAL,
    }

    /** 待处理弹窗的只读视图（respond 用 id 定位）。 */
    data class DialogInfo(
        val id: Int,
        /** alert / confirm / prompt。 */
        val type: String,
        val message: String,
        /** prompt 的默认输入值（其余类型为 null）。 */
        val defaultValue: String?,
    )

    private class Pending(
        val id: Int,
        val type: String,
        val message: String,
        val defaultValue: String?,
        val jsResult: JsResult?,
        val promptResult: JsPromptResult?,
    ) {
        var resolved = false
    }

    @Volatile
    var mode: Mode = Mode.AUTO_CONFIRM

    private val main = Handler(Looper.getMainLooper())
    private val pending = ArrayList<Pending>()
    private var seq = 0

    /** MANUAL 模式的超时兜底：到点未 respond 自动确认（页面不得永久挂起）。 */
    private val timeoutMs: Long = 15_000

    /* ───────────── WebChromeClient 回调侧（主线程） ───────────── */

    /** alert 仲裁：返回 true = 已接管（WebChromeClient 契约）。 */
    fun alert(message: String?, result: JsResult?): Boolean {
        result ?: return true
        return when (mode) {
            Mode.AUTO_CONFIRM, Mode.AUTO_DISMISS -> {
                result.confirm()
                traceAuto("alert", message, confirmed = true)
                true
            }
            Mode.MANUAL -> enqueue("alert", message, null, result, null)
        }
    }

    /** confirm 仲裁（AUTO_DISMISS 走 cancel —— JS 收到 false）。 */
    fun confirm(url: String?, message: String?, result: JsResult?): Boolean {
        result ?: return true
        return when (mode) {
            Mode.AUTO_CONFIRM -> {
                result.confirm()
                traceAuto("confirm", message, confirmed = true)
                true
            }
            Mode.AUTO_DISMISS -> {
                result.cancel()
                traceAuto("confirm", message, confirmed = false)
                true
            }
            Mode.MANUAL -> enqueue("confirm", message, null, result, null)
        }
    }

    /** prompt 仲裁（dismiss = 取消；accept 可带输入文本，缺省回默认值）。 */
    fun prompt(url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
        result ?: return true
        return when (mode) {
            Mode.AUTO_CONFIRM -> {
                result.confirm(defaultValue ?: "")
                traceAuto("prompt", message, confirmed = true)
                true
            }
            Mode.AUTO_DISMISS -> {
                result.cancel()
                traceAuto("prompt", message, confirmed = false)
                true
            }
            Mode.MANUAL -> enqueue("prompt", message, defaultValue, result, result)
        }
    }

    /* ───────────── Agent 控制侧（任意线程） ───────────── */

    /**
     * 响应待处理弹窗。accept=false 即拒绝（confirm→false / prompt→取消）；
     * prompt 可带 [promptText]（缺省用弹窗默认值）。返回 false = id 不存在
     * 或已响应（含超时兜底已自动确认的）。
     */
    fun respond(id: Int, accept: Boolean, promptText: String? = null): Boolean {
        val runnable = Runnable {
            val p = synchronized(pending) { pending.firstOrNull { it.id == id } } ?: return@Runnable
            resolve(p, accept, promptText)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) runnable.run() else main.post(runnable)
        // 同步语义：无法等主线程结果（respond 可能在非挂起上下文调用），
        // 返回「请求已派发」前先做存在性预检 —— 幂等性由 resolved 标记保证。
        return synchronized(pending) { pending.any { it.id == id && !it.resolved } }
    }

    /** 待处理弹窗快照（MANUAL 模式下 Agent 轮询 / 模型决策的输入面）。 */
    fun pendingDialogs(): List<DialogInfo> = synchronized(pending) {
        pending.filter { !it.resolved }.map { DialogInfo(it.id, it.type, it.message, it.defaultValue) }
    }

    /**
     * 快照融合通知（v1.3.0「消费即清」契约的扩展位）：MANUAL 模式有弹窗
     * 挂起时，让下一次 snapshot 显式看到「有 N 个弹窗等你决策」。
     */
    fun pendingNotice(): String? {
        val n = synchronized(pending) { pending.count { !it.resolved } }
        return if (n == 0) null else "dialog_pending: 有 $n 个 JS 弹窗等待响应（respondDialog 处理，15 秒后自动确认）"
    }

    /** 清空队列并确认全部挂起弹窗（会话收尾 / 引擎释放路径的防泄漏兜底）。 */
    fun resolveAll() {
        val snapshot = synchronized(pending) { pending.toList() }
        if (snapshot.isEmpty()) return
        main.post { snapshot.forEach { if (!it.resolved) resolve(it, true, null) } }
    }

    /* ───────────── 内部 ───────────── */

    private fun enqueue(
        type: String,
        message: String?,
        defaultValue: String?,
        jsResult: JsResult?,
        promptResult: JsPromptResult?,
    ): Boolean {
        val p = Pending(++seq, type, message ?: "", defaultValue, jsResult, promptResult)
        synchronized(pending) { pending.add(p) }
        main.postDelayed({
            if (!p.resolved) {
                resolve(p, true, null)
                tracer.record(
                    "system.dialog_timeout_confirmed", "id=${p.id} type=${p.type}",
                    "MANUAL 弹窗 15s 未响应，已自动确认（防死锁兜底）：${p.message.take(80)}", 0, null, "-",
                )
            }
        }, timeoutMs)
        return true
    }

    private fun resolve(p: Pending, accept: Boolean, promptText: String?) {
        if (p.resolved) return
        p.resolved = true
        synchronized(pending) { pending.removeAll { it.id == p.id } }
        when (p.type) {
            "alert" -> p.jsResult?.confirm()
            "confirm" -> if (accept) p.jsResult?.confirm() else p.jsResult?.cancel()
            "prompt" -> {
                val pr = p.promptResult ?: p.jsResult
                if (accept) (pr as? JsPromptResult)?.confirm(promptText ?: p.defaultValue ?: "")
                else pr?.cancel()
            }
        }
        tracer.record(
            "system.dialog_responded", "id=${p.id} type=${p.type} accept=$accept",
            "弹窗已响应（${if (accept) "接受" else "拒绝"}）：${p.message.take(80)}", 0, null, "-",
        )
    }

    private fun traceAuto(type: String, message: String?, confirmed: Boolean) {
        tracer.record(
            "system.dialog_auto", "type=$type confirmed=$confirmed",
            "弹窗自动${if (confirmed) "确认" else "拒绝"}：${message ?: ""}", 0, null, "-",
        )
    }
}

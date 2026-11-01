package com.apex.browser.engine

import android.os.SystemClock
import android.webkit.WebView
import com.apex.browser.core.TaskScripts
import com.apex.browser.core.WaitCondition
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * v2.1.0 Tab 级事件等待中枢（评审 A2）：**合并探针 + 自适应节拍**。
 *
 * 设计取舍（如实声明）：仓库 WebView hardening 门禁**禁止 addJavascriptInterface**
 * （CVE-2012-6636 / CVE-2013-4710 防线，check_webview_hardening.py BRIDGE 规则），
 * 因此 MutationObserver 无法向 Kotlin 推送信号 —— v2.1.0 采用「每次 tick 一次
 * **合并求值**」：单次 evaluateJavascript 同时读 DOM 变更号（页内常驻 MutationObserver
 * 计数，供 dom_stable 与未来多订阅者共享）、条件命中、网络在途三件事，替代旧路径
 * N 个等待器各自固定 300ms 轮询 N 次求值。节拍自适应（前 1.2s 100ms 快轮覆盖
 * 「点击后内容秒到」，之后 250ms），netty_idle 由**静默窗口**判定（评审 P1-3），
 * 监控未注入时诚实返回 NOT_DETECTED 绝不假装支持。
 *
 * 推送式通道（JavascriptInterface / WebMessage）留待安全评审豁免后再接入，
 * 本类是唯一等待入口，届时只改节拍来源、条件语义不变。
 */
internal class TabEventHub(private val engine: BrowserEngine, val tabId: Int) {

    /** 等待结果。 */
    data class Outcome(
        val matched: Boolean,
        val detail: String,
        val elapsedMs: Long,
        /** network_idle 场景下监控未注入（NOT_DETECTED）——诚实上报，绝不假装支持。 */
        val notDetected: Boolean = false,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 等待条件成立。超时返回 matched=false（**不是失败**——页面可能仍在正常
     * 加载，调用方按需重试或取消；评审 P0 超时语义）。
     */
    suspend fun await(tab: BrowserEngine.Tab, condition: WaitCondition): Outcome {
        val start = SystemClock.uptimeMillis()
        val timeout = condition.timeoutMs.coerceIn(500, 60_000)
        val wv = tab.webView
        val hitJs = TaskScripts.conditionHitJs(condition.mode, condition.value, condition.quietWindowMs)
        val probeJs = TaskScripts.waitProbeJs(hitJs)
        // network_idle 静默窗口判定：idleSince 锚定「最近一次归零」时刻
        var idleSinceMs = -1L

        while (SystemClock.uptimeMillis() - start < timeout) {
            val probe = probeOnce(wv, probeJs)
            if (probe != null) {
                if (condition.mode == "network_idle") {
                    if (probe.net < 0) {
                        return Outcome(
                            false,
                            "网络监控未注入（NOT_DETECTED）——页面尚未发起 fetch/xhr 或钩子被导航重置",
                            elapsed(start), notDetected = true,
                        )
                    }
                    val now = SystemClock.uptimeMillis()
                    if (netBusy(probe.net)) {
                        idleSinceMs = -1L
                    } else {
                        if (idleSinceMs < 0) idleSinceMs = now
                        if (now - idleSinceMs >= condition.quietWindowMs) {
                            return Outcome(true, "network idle（静默 ${condition.quietWindowMs}ms）", elapsed(start))
                        }
                    }
                } else if (probe.hit) {
                    return Outcome(true, "matched ${condition.describe()}", elapsed(start))
                }
            }
            val remaining = timeout - (SystemClock.uptimeMillis() - start)
            if (remaining <= 0) break
            delay(pollIntervalFor(SystemClock.uptimeMillis() - start, SLOW_TICK_MS).coerceAtMost(remaining))
        }
        return Outcome(false, "timeout（${timeout}ms）等待 ${condition.describe()}", elapsed(start))
    }

    private class Probe(val seq: Long, val hit: Boolean, val net: Int)

    private suspend fun probeOnce(wv: WebView, probeJs: String): Probe? {
        val raw = runCatching { engine.evaluateJson(wv, probeJs) }.getOrNull() ?: return null
        return runCatching {
            val o = json.parseToJsonElement(raw).jsonObject
            Probe(
                seq = o["seq"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                hit = o["hit"]?.jsonPrimitive?.content == "true",
                net = o["net"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1,
            )
        }.getOrNull()
    }

    /** net>0 视为在途（计数短暂归零的间隙由静默窗口吸收）。 */
    private fun netBusy(net: Int): Boolean = net > 0

    private fun elapsed(start: Long): Long = SystemClock.uptimeMillis() - start

    companion object {
        /** 无信号推送（hardening 门禁禁用桥接）时的兜底节拍：250ms。 */
        private const val SLOW_TICK_MS = 250L
    }
}

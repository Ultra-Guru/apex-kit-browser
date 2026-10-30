package com.apex.browser.engine

import android.os.SystemClock
import android.webkit.WebView
import com.apex.browser.core.BrowserScript
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `waitForCondition(selector_count)` 的谓词支撑（v1.3.0，internal）。
 *
 * 独立成文件：谓词解析是纯字符串逻辑（可独立演进），且 BrowserEngine.kt 已贴近
 * 非注释行预算（1200，check_code_quality GATE 1）。对消费方不可见（internal）。
 */
internal class CountPredicate(val selector: String, val op: String, val n: Int)

/**
 * 解析 selector_count 的 value（尾部解析）：算子形态 `<选择器>>=10` 支持任意空白；
 * 纯数字形态（语义为 `>=`，「等到至少 N 个」是等待场景的主导用法）要求与选择器间
 * 有空白 —— 避免与 `.item2` 这类以数字结尾的选择器歧义。解析失败返回 null
 * （调用方据此给出可诊断错误而非静默超时）。
 */
internal fun parseCountPredicate(value: String): CountPredicate? {
    val v = value.trim()
    Regex("^(.*?)(>=|<=|==|>|<)\\s*(\\d+)$").find(v)?.let {
        val sel = it.groupValues[1].trim()
        if (sel.isNotEmpty()) return CountPredicate(sel, it.groupValues[2], it.groupValues[3].toInt())
    }
    Regex("^(.+?)\\s+(\\d+)$").find(v)?.let { return CountPredicate(it.groupValues[1].trim(), ">=", it.groupValues[2].toInt()) }
    return null
}

internal fun matchesCount(count: Int, p: CountPredicate): Boolean = when (p.op) {
    ">=" -> count >= p.n
    "<=" -> count <= p.n
    "==" -> count == p.n
    ">" -> count > p.n
    else -> count < p.n
}

/**
 * v1.8.0 提速：等待轮询的自适应间隔。
 *
 * 点击/提交后的异步内容多在数百毫秒内到达——固定 300ms 轮询会让「秒到」
 * 条件平均多等 ~150ms。前 [FAST_PHASE_MS] 内用 [FAST_POLL_MS]（100ms）
 * 快轮，之后回落调用方给定的 [pollMs]（长等待不放大开销：每次轮询是一次
 * 毫秒级 evaluateJavascript，快轮期的额外成本可忽略）。
 */
internal fun pollIntervalFor(elapsedMs: Long, pollMs: Long): Long =
    if (elapsedMs < FAST_PHASE_MS) minOf(pollMs, FAST_POLL_MS) else pollMs.coerceIn(100, 2000)

/** 快轮窗口：进入等待后的前 1.2s（覆盖「点击后内容秒到」主场景）。 */
internal const val FAST_PHASE_MS: Long = 1_200

/** 快轮间隔：100ms（与轮询循环允许的最小间隔一致）。 */
internal const val FAST_POLL_MS: Long = 100

/**
 * 数值型求值（v1.3.0，internal）：WebView 回传的 JSON 数字串（如 `"3"`）转 Int；
 * 非数值形状（null / 异常）返回 null。network_idle 与 selector_count 轮询的取值侧。
 */
internal suspend fun WebView.evaluateAsInt(js: String): Int? =
    suspendCancellableCoroutine { cont ->
        evaluateJavascript(js) { result -> cont.resume(result?.trim()?.toIntOrNull()) }
    }

/**
 * `dom_stable` 的安静阈值（v2.0.0）：距最后一次 DOM 变更 ≥ 此毫秒数才算
 * 「安静」；调用方可在 value 传 100..5000 覆盖（默认 500）。
 */
internal const val DOM_STABLE_QUIET_MS: Long = 500

/** dom_stable 探针 JSON 解析：{m, idle} → idle（异常形状返回 null）。 */
internal fun parseDomStableProbe(probe: String?): Long? {
    if (probe == null) return null
    return runCatching {
        val o = Json.parseToJsonElement(probe).jsonObject
        o["idle"]?.jsonPrimitive?.content?.toLong()
    }.getOrNull()
}

/**
 * `waitForCondition` 的轮询循环本体（v1.4.0 自 BrowserEngine.kt 迁入，纯代码
 * 搬移；GATE 1 预算纪律）。入口校验（模式白名单 / 谓词解析）留在引擎方法内，
 * 这里只做「轮询到命中或超时」：
 * - `selector` / `element_gone` / `text` / `text_gone`：同步 JS 谓词轮询；
 * - `network_idle`：在途请求数**连续 2 次**为 0 才算 idle（单次 0 可能只是
 *   两次请求的间隙）；
 * - `selector_count`：谓词命中数比较；
 * - `url`：注入 CURRENT_URL_JS 求值——SPA pushState/replaceState 即时反映到
 *   location.href，而 native `webView.url` 只在导航提交时更新。
 *
 * 求值器（evaluateBoolean / evaluateJson）由引擎以 lambda 注入：循环逻辑可
 * 独立演进，引擎私有成员可见性不外泄。
 *
 * v1.8.0 起轮询间隔自适应（[pollIntervalFor]）：进入等待的前 1.2s 以 100ms
 * 快轮（异步内容「秒到」场景不再多等整拍），之后回落调用方 pollMs。
 */
internal suspend fun waitConditionLoop(
    wv: WebView,
    mode: String,
    value: String,
    countPred: CountPredicate?,
    timeoutMs: Long,
    pollMs: Long,
    evaluateBoolean: suspend (WebView, String) -> Boolean,
    evaluateJson: suspend (WebView, String) -> String,
): BrowserEngine.WaitOutcome {
    val effectiveTimeout = timeoutMs.coerceIn(500, 60_000)
    val start = SystemClock.uptimeMillis()
    var idleStreak = 0
    while (SystemClock.uptimeMillis() - start < effectiveTimeout) {
        val hit = when (mode) {
            "selector" -> runCatching { evaluateBoolean(wv, BrowserScript.selectorPresentJs(value)) }.getOrDefault(false)
            "element_gone" -> !runCatching { evaluateBoolean(wv, BrowserScript.selectorPresentJs(value)) }.getOrDefault(true)
            "text" -> runCatching { evaluateBoolean(wv, BrowserScript.textContainsJs(value)) }.getOrDefault(false)
            "text_gone" -> !runCatching { evaluateBoolean(wv, BrowserScript.textContainsJs(value)) }.getOrDefault(true)
            "network_idle" -> {
                // 连续 2 次读到 0 才算 idle：单次 0 可能只是两次请求的间隙
                val pending = runCatching { wv.evaluateAsInt(BrowserScript.networkIdleJs()) }.getOrNull()
                if (pending == 0) idleStreak++ else idleStreak = 0
                idleStreak >= 2
            }
            "dom_stable" -> {
                // v2.0.0：DOM 稳定性（MutationObserver 探针，连续 2 个轮询周期
                // 安静才命中 —— 与 network_idle 同款防抖；value 可传安静毫秒数）
                val quiet = value.trim().toLongOrNull()?.coerceIn(100, 5_000) ?: DOM_STABLE_QUIET_MS
                val idle = parseDomStableProbe(
                    runCatching { evaluateJson(wv, BrowserScript.DOM_STABLE_JS) }.getOrNull(),
                )
                if (idle != null && idle >= quiet) idleStreak++ else idleStreak = 0
                idleStreak >= 2
            }
            "selector_count" -> {
                val p = countPred
                val count = if (p == null) null else runCatching { wv.evaluateAsInt(BrowserScript.selectorCountJs(p.selector)) }.getOrNull()
                count != null && p != null && matchesCount(count, p)
            }
            "url" -> runCatching { evaluateJson(wv, BrowserScript.CURRENT_URL_JS) }.getOrDefault("").contains(value, ignoreCase = true)
            else -> false // 未知模式已在引擎入口拒绝
        }
        if (hit) return BrowserEngine.WaitOutcome(true, "matched $mode", SystemClock.uptimeMillis() - start)
        delay(pollIntervalFor(SystemClock.uptimeMillis() - start, pollMs))
    }
    return BrowserEngine.WaitOutcome(
        false,
        "timeout (${effectiveTimeout}ms) waiting for $mode '$value'",
        SystemClock.uptimeMillis() - start,
    )
}

package com.apex.browser.engine

import com.apex.browser.core.BrowserScript
import com.apex.browser.core.DomDiff
import com.apex.browser.core.DomParser
import com.apex.browser.core.PageSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * v2.1.0 增量上下文引擎（评审 A5 / P1）：FOCUSED 子树快照与 DIFF 增量快照，
 * 把「反复向模型传全页」变成「只传必要结构化信息和变化部分」。
 *
 * 缓存纪律（不可妥协）：基准快照**按 docId 键控**——文档切换即失效
 * （[EngineRuntime.onPageCommitted] 强制 invalidate）。baseDocId 与当前文档不一致
 * 一律 [DiffResult.Stale]，绝不拿旧页当 DIFF 基准。
 *
 * FOCUSED 走 [BrowserScript.snapshotJs] 的 rootSelector 参数（JS 侧子树采集，
 * 语义哈希/ref 与全页快照同一套），DIFF 复用全页采集 + 纯 JVM [DomDiff]。
 */

/** DIFF 请求结果。 */
sealed interface DiffResult {
    /** 差分成功（[diff.render] 直接面向模型）。 */
    data class Ok(val diff: DomDiff.Diff, val currentDocId: String) : DiffResult

    /** 基准不可用（缓存缺失 / docId 不一致）——reason 指导模型先取 FULL。 */
    data class Stale(val reason: String, val currentDocId: String?) : DiffResult
}

/**
 * FOCUSED 快照：只采集 [rootSelector] 子树内的可交互元素。
 * 返回 null = 无激活标签页 / root 不存在（root 缺失时快照为空集，truncated 如实）。
 * dialogNotice 融合不适用（聚焦模式语义即「局部」）——调用方如需弹窗提示走 FULL。
 */
suspend fun BrowserEngine.snapshotFocused(
    rootSelector: String,
    tokenBudget: Int = 1600,
    strategy: DomParser.SnapshotStrategy = DomParser.SnapshotStrategy.INTERACTIVE_ONLY,
): PageSnapshot? = withContext(Dispatchers.Main) {
    val tab = activeTab() ?: return@withContext null
    val wv = tab.webView
    val raw = runCatching { evaluateJson(wv, BrowserScript.snapshotJs(strategy, rootSelector)) }
        .getOrNull() ?: return@withContext null
    val geo = parseGeometry(runCatching { evaluateJson(wv, BrowserScript.PAGE_GEOMETRY_JS) }.getOrNull())
    val docId = runtime.docIdOf(tab) ?: ""
    val snap = DomParser.parse(
        rawJson = raw, url = tab.url, title = tab.title,
        scrollY = geo.scrollY, scrollHeight = geo.scrollHeight, viewportHeight = geo.viewportH,
        tokenBudget = tokenBudget.coerceIn(400, 4000), strategy = strategy,
    ).copy(docId = docId)
    // 缓存子树元素（DIFF 基准按最近快照走——FOCUSED 也可作为后续 DIFF 的基准）
    runtime.snapshotCache.put(tab.id, docId, snap.interactiveElements)
    snap
}

/**
 * DIFF 快照：与 [baseDocId] 对应的缓存基准做纯 JVM 差分，只回变化部分。
 * 基准缺失 / baseDocId 与缓存或当前文档不一致 → [DiffResult.Stale]（模型先取 FULL）。
 */
suspend fun BrowserEngine.snapshotDiff(
    baseDocId: String,
    tokenBudget: Int = 1600,
    strategy: DomParser.SnapshotStrategy = DomParser.SnapshotStrategy.INTERACTIVE_ONLY,
): DiffResult = withContext(Dispatchers.Main) {
    val tab = activeTab()
        ?: return@withContext DiffResult.Stale("无激活标签页", null)
    val currentDocId = runtime.docIdOf(tab)
    val cached = runtime.snapshotCache.get(tab.id)
    when {
        cached == null -> DiffResult.Stale("本标签页尚无基准快照——先取一次 FULL 快照建立基准", currentDocId)
        cached.docId != baseDocId ->
            DiffResult.Stale("基准过期：缓存基准 ${cached.docId} ≠ 请求基准 $baseDocId（文档已切换）", currentDocId)
        currentDocId != baseDocId ->
            DiffResult.Stale("STALE_DOCUMENT: expected $baseDocId, current ${currentDocId ?: "none"} — 请重新快照", currentDocId)
        else -> {
            val raw = runCatching { evaluateJson(tab.webView, BrowserScript.snapshotJs(strategy)) }
                .getOrNull() ?: return@withContext DiffResult.Stale("采集失败（页面未就绪或脚本超时）", currentDocId)
            val geo = parseGeometry(runCatching { evaluateJson(tab.webView, BrowserScript.PAGE_GEOMETRY_JS) }.getOrNull())
            val current = DomParser.parse(
                rawJson = raw, url = tab.url, title = tab.title,
                scrollY = geo.scrollY, scrollHeight = geo.scrollHeight, viewportHeight = geo.viewportH,
                tokenBudget = Int.MAX_VALUE, strategy = strategy,
            )
            val diff = DomDiff.diff(cached.elements, current.interactiveElements, baseDocId, baseDocId)
            // 刷新缓存（基准前移）
            runtime.snapshotCache.put(tab.id, baseDocId, current.interactiveElements)
            DiffResult.Ok(diff, baseDocId)
        }
    }
}

private data class Geometry(val scrollY: Int, val scrollHeight: Int, val viewportH: Int)

private val geoJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun parseGeometry(raw: String?): Geometry {
    if (raw == null) return Geometry(0, 0, 0)
    return runCatching {
        val o = geoJson.parseToJsonElement(raw).jsonObject
        Geometry(
            scrollY = o["scrollY"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            scrollHeight = o["scrollHeight"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            viewportH = o["viewportH"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
        )
    }.getOrDefault(Geometry(0, 0, 0))
}

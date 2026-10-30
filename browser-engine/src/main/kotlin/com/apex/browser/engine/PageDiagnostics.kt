package com.apex.browser.engine

import com.apex.browser.core.BrowserScript
import com.apex.browser.core.FormReport
import com.apex.browser.core.FormReportParser
import com.apex.browser.core.FrameReport
import com.apex.browser.core.FrameReportParser
import com.apex.browser.core.LayoutAnalysis
import com.apex.browser.core.PerformanceReport
import com.apex.browser.core.PerformanceReportParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume

/*
 * PageDiagnostics —— 只读页面诊断四件套（v1.9.0；扩展函数形态，GATE 1
 * 文件预算纪律同 ScrollBoundary.kt）。
 *
 * 四个能力共享同一条执行缝合流（脚本 → evaluateJavascript 双层编码剥离 →
 * 纯 JVM 解析器），因此收敛在一个文件里而不是四个 ~30 行的样板副本：
 *
 * - [detectForms]：表单结构发现（ref 与快照同源，直连 batchExecute 填表）；
 * - [performanceMetrics]：Navigation/Resource Timing（慢在哪 / 拉了多少）；
 * - [frameList]：iframe 枚举（Agent 的嵌入框感知面）；
 * - [analyzeLayout]：真实渲染布局报告（自宿主 BrowserLayoutTools 下沉）。
 *
 * 四者全部**只读**：脚本不改 DOM、不发网络、无 Promise（等待语义归
 * Kotlin 侧超时）—— 与 [BrowserScript] 的注入边界契约一致。
 */

/**
 * 表单结构检测：返回页面全部 `<form>` + 孤儿字段的结构化报告
 * （字段 / 类型 / label / 选项 / required / 提交按钮，ref 与快照同源）。
 * null = 执行失败（无激活标签页 / 超时 / JS 异常 / 页面无 form 概念）。
 */
suspend fun BrowserEngine.detectForms(): FormReport? =
    diagnose(BrowserScript.formDetectJs(), FormReportParser::parse)

/**
 * 性能指标：Navigation Timing 里程碑 + Resource Timing 按类型聚合 +
 * 最慢资源 Top 8。null = 执行失败（无标签页 / 超时 / WebView 不支持
 * performance API）。
 */
suspend fun BrowserEngine.performanceMetrics(): PerformanceReport? =
    diagnose(BrowserScript.PERFORMANCE_METRICS_JS, PerformanceReportParser::parse)

/**
 * iframe / frame 枚举：嵌入框的 src / name / title / 尺寸 / sandbox /
 * 同源判定。null = 执行失败。
 */
suspend fun BrowserEngine.frameList(): FrameReport? =
    diagnose(BrowserScript.FRAME_LIST_JS, FrameReportParser::parse)

/**
 * 布局分析（自宿主 BrowserLayoutTools 下沉）：真实渲染几何报告文本 ——
 * 视口溢出 / landmark / flex/grid / 定位 / 层叠 / 可达性采样 / 暗色探测。
 * [maxElements] 扫描上限（50..4000，脚本侧钳制）。null = 执行失败
 * （页面未加载完成时先 navigate 带 wait_for）。
 */
suspend fun BrowserEngine.analyzeLayout(maxElements: Int = 1200): String? {
    val payload = diagnoseRaw(BrowserScript.layoutCollectJs(maxElements)) ?: return null
    return if (payload.isBlank() || payload == "null") null
    else LayoutAnalysis.formatReport(payload)
}

/* ───────────── 内部：共享执行缝合流 ───────────── */

private suspend fun <T> BrowserEngine.diagnose(script: String, parse: (String) -> T?): T? {
    val raw = diagnoseRaw(script) ?: return null
    return parse(raw)
}

private suspend fun BrowserEngine.diagnoseRaw(script: String): String? = withContext(Dispatchers.Main) {
    val wv = activeTab()?.webView ?: return@withContext null
    val raw = runCatching {
        withTimeout(10_000) {
            suspendCancellableCoroutine<String> { cont ->
                wv.evaluateJavascript(script) { cont.resume(it ?: "null") }
            }
        }
    }.getOrNull() ?: return@withContext null
    // evaluateJavascript 回传 JSON 字符串字面量（脚本以 stringify 收尾）→ 剥一层
    runCatching { Json.parseToJsonElement(raw).jsonPrimitive.content }.getOrNull()
}

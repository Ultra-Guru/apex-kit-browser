package com.apex.browser.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * PerformanceReport —— 性能指标的数据模型与解析（v1.9.0）。
 *
 * 数据流：
 *   [BrowserScript.PERFORMANCE_METRICS_JS]（页面侧采集 Navigation/Resource Timing）
 *     → JSON 文本（transport：navigation/resourceCount/buckets/slowest）
 *     → [PerformanceReportParser.parse]（本文件，纯 JVM）
 *     → [PerformanceReport]（宿主 BrowserPerformance 工具消费）
 *
 * 定位：回答 Agent 常见的三个性能问题——「这页为什么慢」（TTFB / 最慢资源）、
 * 「加载了多少东西」（按类型聚合的字节 / 数量）、「重定向了几跳」。
 * Playwright `performance` 基线的对等面；数值全为毫秒，由脚本侧 Math.round。
 *
 * 纯 JVM 纪律：解析在 `:browser-core`，engine 侧只执行脚本转交结果。
 */

/** 导航级关键里程碑（毫秒，相对 navigation start）。 */
data class NavigationTiming(
    val dns: Int = 0,
    val tcp: Int = 0,
    /** Time To First Byte（含重定向与请求发出；首屏等待的主要成分）。 */
    val ttfb: Int = 0,
    val domInteractive: Int = 0,
    val domContentLoaded: Int = 0,
    /** loadEventEnd；仍在加载时为 0（如实回传，调用方判 0 即「未完成」）。 */
    val loadEvent: Int = 0,
    val transferSize: Long = 0,
    val encodedBodySize: Long = 0,
    val decodedBodySize: Long = 0,
    /** navigate / reload / back_forward。 */
    val type: String = "",
    val redirectCount: Int = 0,
)

/** 按资源类型（initiatorType）聚合的桶。 */
data class ResourceBucket(
    val type: String,
    val count: Int = 0,
    /** 传输字节合计（transferSize 求和；0 = 缓存命中或跨源无 Timing-Allow-Origin）。 */
    val bytes: Long = 0,
    /** 该类型最慢单资源耗时（毫秒）。 */
    val maxDuration: Int = 0,
)

/** 最慢资源条目（Top 8）。 */
data class SlowResource(
    val url: String,
    val duration: Int,
    val bytes: Long = 0,
    val type: String = "",
)

/** 性能采集报告。 */
data class PerformanceReport(
    val navigation: NavigationTiming? = null,
    val resourceCount: Int = 0,
    val buckets: List<ResourceBucket> = emptyList(),
    val slowest: List<SlowResource> = emptyList(),
) {
    /** 全部传输字节合计（跨源资源可能报 0 —— 视为下界，如实解读）。 */
    val totalBytes: Long
        get() = buckets.sumOf { it.bytes }

    /** 面向模型的报告文本（宿主工具可直接作为返回值）。 */
    fun toAgentText(): String {
        val sb = StringBuilder()
        sb.append("## 页面性能报告\n")
        val nav = navigation
        if (nav != null) {
            sb.append("\n### 导航时序\n")
            sb.append("- TTFB ").append(nav.ttfb).append("ms")
                .append(" · DNS ").append(nav.dns).append("ms")
                .append(" · TCP ").append(nav.tcp).append("ms\n")
            sb.append("- DOM 可交互 ").append(nav.domInteractive).append("ms")
                .append(" · DOMContentLoaded ").append(nav.domContentLoaded).append("ms")
                .append(" · Load ").append(if (nav.loadEvent > 0) "${nav.loadEvent}ms" else "(未完成)")
                .append('\n')
            if (nav.redirectCount > 0) sb.append("- 重定向 ").append(nav.redirectCount).append(" 跳\n")
            if (nav.type.isNotBlank() && nav.type != "navigate") sb.append("- 导航类型: ").append(nav.type).append('\n')
        } else {
            sb.append("\n(无 Navigation Timing —— 旧 WebView 或已清 buffer)\n")
        }
        sb.append("\n### 资源（").append(resourceCount).append(" 个，传输 ~")
            .append(humanBytes(totalBytes)).append("）\n")
        for (b in buckets.sortedByDescending { it.bytes }) {
            sb.append("- ").append(b.type).append(" ×").append(b.count)
                .append(" · ").append(humanBytes(b.bytes))
                .append(" · 最慢 ").append(b.maxDuration).append("ms\n")
        }
        if (slowest.isNotEmpty()) {
            sb.append("\n### 最慢资源 Top ").append(slowest.size).append('\n')
            for (s in slowest) {
                sb.append("- ").append(s.duration).append("ms ")
                    .append(humanBytes(s.bytes)).append(" [").append(s.type).append("] ")
                    .append(s.url).append('\n')
            }
        }
        sb.append("\n提示：跨源资源字节数依赖 Timing-Allow-Origin，0 不等于「零成本」；")
        sb.append("慢资源可用 NetworkInterceptor 屏蔽重采对比。")
        return sb.toString().trimEnd()
    }

    private fun humanBytes(v: Long): String = when {
        v >= 1 shl 20 -> String.format(java.util.Locale.US, "%.1fMB", v / 1048576.0)
        v >= 1 shl 10 -> String.format(java.util.Locale.US, "%.1fKB", v / 1024.0)
        else -> "${v}B"
    }
}

/** 性能报告解析器（纯函数，防御性解析）。 */
object PerformanceReportParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 解析 [BrowserScript.PERFORMANCE_METRICS_JS] 回传的 JSON **内层**文本。
     * 空串 / "null" / 坏 JSON 一律 null。
     */
    fun parse(raw: String?): PerformanceReport? {
        val text = raw?.trim()?.removeSurrounding("\"")?.trim() ?: return null
        if (text.isEmpty() || text == "null") return null
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val nav = obj["navigation"]?.let {
            runCatching {
                val n = it.jsonObject
                NavigationTiming(
                    dns = n.intOr("dns"),
                    tcp = n.intOr("tcp"),
                    ttfb = n.intOr("ttfb"),
                    domInteractive = n.intOr("domInteractive"),
                    domContentLoaded = n.intOr("domContentLoaded"),
                    loadEvent = n.intOr("loadEvent"),
                    transferSize = n.longOr("transferSize"),
                    encodedBodySize = n.longOr("encodedBodySize"),
                    decodedBodySize = n.longOr("decodedBodySize"),
                    type = n.strOr("type"),
                    redirectCount = n.intOr("redirectCount"),
                )
            }.getOrNull()
        }
        val buckets = runCatching { obj["buckets"]?.jsonObject?.entries }.getOrNull()
            ?.map { (type, v) ->
                runCatching {
                    val b = v.jsonObject
                    ResourceBucket(
                        type = type,
                        count = b.intOr("count"),
                        bytes = b.longOr("bytes"),
                        maxDuration = b.intOr("maxDuration"),
                    )
                }.getOrNull()
            }?.filterNotNull() ?: emptyList()
        val slowest = runCatching { obj["slowest"]?.jsonArray }.getOrNull()
            ?.mapNotNull { el ->
                runCatching {
                    val s = el.jsonObject
                    SlowResource(
                        url = s.strOr("url"),
                        duration = s.intOr("duration"),
                        bytes = s.longOr("bytes"),
                        type = s.strOr("type"),
                    )
                }.getOrNull()
            } ?: emptyList()
        return PerformanceReport(
            navigation = nav,
            resourceCount = obj.intOr("resourceCount"),
            buckets = buckets,
            slowest = slowest,
        )
    }

    private fun JsonObject.strOr(key: String): String =
        runCatching { this[key]?.jsonPrimitive?.content ?: "" }.getOrDefault("")

    private fun JsonObject.intOr(key: String): Int =
        runCatching { this[key]?.jsonPrimitive?.content?.toDoubleOrNull()?.toInt() }.getOrNull() ?: 0

    private fun JsonObject.longOr(key: String): Long =
        runCatching { this[key]?.jsonPrimitive?.content?.toDoubleOrNull()?.toLong() }.getOrNull() ?: 0
}

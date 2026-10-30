package com.apex.browser.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * FrameReport —— iframe 枚举的数据模型与解析（v1.9.0）。
 *
 * 数据流：
 *   [BrowserScript.FRAME_LIST_JS]（页面侧枚举 iframe/frame）
 *     → JSON 文本（transport：total/truncated/frames[]）
 *     → [FrameReportParser.parse]（本文件，纯 JVM）
 *     → [FrameReport]（宿主 BrowserFrames 工具消费）
 *
 * 定位：Agent 的 iframe「感知面」。快照 / 表单检测都只覆盖顶层文档——
 * 嵌入框内的登录、支付、验证码控件对 Agent 完全不可见。本报告至少让
 * 模型**知道**框的存在与来源（同源可读、跨源只报元信息），把「静默
 * 找不到元素」转化为「显式知道要去哪个框」。框内操作是另一代工程。
 */

/** 单个嵌入框。 */
data class FrameInfo(
    val src: String = "",
    val name: String = "",
    val title: String = "",
    /** 视口矩形宽高（px，渲染实际尺寸；0×0 = 懒加载未挂或隐藏）。 */
    val width: Int = 0,
    val height: Int = 0,
    /** sandbox 属性原样（空串 = 未沙箱）。 */
    val sandbox: String = "",
    /** 同源判定（true 时页面侧 JS 可入内 —— 嵌码/表单预填的前提）。 */
    val sameOrigin: Boolean = false,
) {
    /** 模型可读的单行摘要。 */
    fun summaryLine(): String {
        val sb = StringBuilder()
        sb.append("- ").append(if (src.isBlank()) "(无 src)" else src)
        if (name.isNotBlank()) sb.append(" name=\"").append(name).append('"')
        if (title.isNotBlank()) sb.append(" 「").append(title).append("」")
        sb.append(" ").append(width).append("×").append(height)
        sb.append(if (sameOrigin) " [同源]" else " [跨源]")
        if (sandbox.isNotBlank()) sb.append(" sandbox=").append(sandbox)
        return sb.toString()
    }
}

/** iframe 枚举报告。 */
data class FrameReport(
    /** 页面框总数（frames 可能因 [BrowserScript.MAX_FRAMES] 截断）。 */
    val total: Int = 0,
    val truncated: Boolean = false,
    val frames: List<FrameInfo> = emptyList(),
) {
    /** 面向模型的报告文本。 */
    fun toAgentText(): String {
        if (frames.isEmpty()) {
            return "(页面无 iframe/frame —— 顶层文档即全部内容)"
        }
        val sb = StringBuilder()
        sb.append("## 嵌入框报告（").append(total).append(" 个")
        if (truncated) sb.append("，回传前 ").append(frames.size).append(" 个")
        sb.append("）\n")
        for (f in frames) sb.append(f.summaryLine()).append('\n')
        sb.append("\n提示：快照与表单检测只覆盖顶层文档；跨源框内元素不可定位，")
        sb.append("同源框内容需 executeJavaScript 直查 contentDocument。")
        return sb.toString().trimEnd()
    }
}

/** 帧报告解析器（纯函数，防御性解析）。 */
object FrameReportParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 解析 [BrowserScript.FRAME_LIST_JS] 回传的 JSON **内层**文本。 */
    fun parse(raw: String?): FrameReport? {
        val text = raw?.trim()?.removeSurrounding("\"")?.trim() ?: return null
        if (text.isEmpty() || text == "null") return null
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val frames = runCatching { obj["frames"]?.jsonArray }.getOrNull()
            ?.mapNotNull { el ->
                runCatching {
                    val f = el.jsonObject
                    FrameInfo(
                        src = f.strOr("src"),
                        name = f.strOr("name"),
                        title = f.strOr("title"),
                        width = f.intOr("width"),
                        height = f.intOr("height"),
                        sandbox = f.strOr("sandbox"),
                        sameOrigin = f.strOr("sameOrigin") == "true",
                    )
                }.getOrNull()
            } ?: emptyList()
        return FrameReport(
            total = obj.intOr("total"),
            truncated = obj.strOr("truncated") == "true",
            frames = frames,
        )
    }

    private fun JsonObject.strOr(key: String): String =
        runCatching { this[key]?.jsonPrimitive?.content ?: "" }.getOrDefault("")

    private fun JsonObject.intOr(key: String): Int =
        runCatching { this[key]?.jsonPrimitive?.content?.toIntOrNull() }.getOrNull() ?: 0
}

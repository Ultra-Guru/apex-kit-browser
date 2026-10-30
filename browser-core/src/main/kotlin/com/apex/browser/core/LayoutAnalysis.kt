package com.apex.browser.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * LayoutAnalysis —— 布局采集报告的格式化（v1.9.0，自宿主 BrowserLayoutTools 下沉）。
 *
 * 数据流：
 *   [BrowserScript.layoutCollectJs]（页面侧采集真实渲染几何）
 *     → JSON 文本（transport：viewport/landmarks/flex/grid/absPos/fixed/
 *       overflowEls/zIssues/noAltImgs/headings/trunc/smallTargets/
 *       lowContrast/colorScheme/maxDepth/textChars）
 *     → [LayoutAnalysis.formatReport]（本文件，纯 JVM）
 *     → 报告文本（宿主 BrowserAnalyzeLayout 工具直接作为返回值）
 *
 * 下沉动机：宿主自维护百行内联 JS + 格式化器的形态不可持续（库改脚本
 * 宿主不同步、消费方各自漂移）。脚本进 [BrowserScript.layoutCollectJs]，
 * 格式化进本文件，宿主一行调用拿报告 —— 与 [PageClassifier] 的下沉
 * 口径一致：策略与格式化都是纯逻辑，属于库。
 *
 * 与宿主版的契约对齐：采集脚本逐行为一致，报告段落顺序 / 判定文案 /
 * 截断标注（每类列表 8-20 条）保持同一形态，宿主切换实现零行为漂移。
 */

/** 布局报告格式化器（纯函数；解析坏数据返回明确的失败说明而非异常）。 */
object LayoutAnalysis {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 把 [BrowserScript.layoutCollectJs] 回传的 JSON **内层**文本格式化为
     * Agent 可读报告。解析失败时返回带原始片段的错误说明（调用方无需再包
     * try/catch）。
     */
    fun formatReport(payload: String): String {
        val obj = try {
            json.parseToJsonElement(payload).jsonObject
        } catch (e: Exception) {
            return "(采集结果解析失败：${e.message?.take(80) ?: e::class.simpleName}；原始片段: ${payload.take(120)})"
        }
        val sb = StringBuilder()
        val vp = obj["viewport"]?.jsonObject
        val vw = str(vp?.get("w"))
        val vh = str(vp?.get("h"))
        val scrollW = str(vp?.get("scrollW"))
        val overflowX = str(vp?.get("overflowX")) == "true"
        sb.appendLine("## 页面布局报告（真实渲染几何）")
        sb.appendLine()
        sb.appendLine("- 视口: ${vw}x${vh} · 文档宽: $scrollW" + if (overflowX) " · ⚠️ 存在横向溢出" else " · 无横向溢出")
        sb.appendLine("- 最大嵌套: ${str(obj["maxDepth"])} 层 · 正文 ${str(obj["textChars"])} 字符")
        sb.appendLine()

        fun rows(key: String, limitLabel: String, render: (JsonObject) -> String): List<String> {
            val arr = obj[key]?.jsonArray ?: return emptyList()
            val out = mutableListOf<String>()
            for (i in 0 until arr.jsonArray.size) {
                val row = arr.jsonArray[i] as? JsonObject ?: continue
                out.add(render(row))
            }
            return out.also { if (arr.jsonArray.size > it.size) it.add("…（$limitLabel 截断）") }
        }

        val landmarks = rows("landmarks", "12") {
            val r = it["rect"]?.jsonObject
            "- <${str(it["tag"])}> ${str(r?.get("w"))}x${str(r?.get("h"))} @(${str(r?.get("x"))},${str(r?.get("y"))}) “${str(it["text"])}”"
        }
        if (landmarks.isNotEmpty()) sb.appendLine("### 区块 (landmarks)\n${landmarks.joinToString("\n")}\n")

        val flex = rows("flex", "20") {
            "- ${str(it["sel"])} [${str(it["dir"])}] justify=${str(it["justify"])} align=${str(it["align"])} gap=${str(it["gap"])} ×${str(it["kids"])}子"
        }
        if (flex.isNotEmpty()) sb.appendLine("### flex 容器\n${flex.joinToString("\n")}\n")

        val grid = rows("grid", "12") {
            "- ${str(it["sel"])} cols=${str(it["cols"])} ×${str(it["kids"])}子"
        }
        if (grid.isNotEmpty()) sb.appendLine("### grid 容器\n${grid.joinToString("\n")}\n")

        val absPos = rows("absPos", "16") {
            val r = it["rect"]?.jsonObject
            val anc = if (str(it["positionedAncestor"]) == "true") "已定位祖先OK" else "⚠️无已定位祖先(以body为基准)"
            "- ${str(it["sel"])} ${str(r?.get("w"))}x${str(r?.get("h"))} — $anc"
        }
        if (absPos.isNotEmpty()) sb.appendLine("### absolute 定位\n${absPos.joinToString("\n")}\n")

        val fixed = rows("fixed", "12") {
            val r = it["rect"]?.jsonObject
            "- ${str(it["sel"])} @(${str(r?.get("x"))},${str(r?.get("y"))}) z=${str(it["z"])}"
        }
        if (fixed.isNotEmpty()) sb.appendLine("### fixed 定位\n${fixed.joinToString("\n")}\n")

        val overflow = rows("overflowEls", "12") {
            val r = it["rect"]?.jsonObject
            "- ${str(it["sel"])} x=${str(r?.get("x"))} w=${str(r?.get("w"))}（越出 ${vw}px 视口）"
        }
        if (overflow.isNotEmpty()) sb.appendLine("### 越出视口（横向滚动根因）\n${overflow.joinToString("\n")}\n")

        val zIssues = rows("zIssues", "10") { "- ${str(it["sel"])} z=${str(it["z"])} 配 static（不生效）" }
        if (zIssues.isNotEmpty()) sb.appendLine("### 层叠失效\n${zIssues.joinToString("\n")}\n")

        val noAlt = rows("noAltImgs", "8") { "- <img src=\"${str(it["src"])}\"> 缺 alt" }
        if (noAlt.isNotEmpty()) sb.appendLine("### 图片无 alt\n${noAlt.joinToString("\n")}\n")

        val headings = rows("headings", "20") { "h${str(it["level"])} “${str(it["text"])}”" }
        if (headings.isNotEmpty()) sb.appendLine("### 标题链: ${headings.joinToString(" → ")}\n")

        val trunc = rows("trunc", "12") {
            val sw = str(it["sw"]).toDoubleOrNull() ?: 0.0
            val cw = str(it["cw"]).toDoubleOrNull() ?: 0.0
            val axis = if (sw > cw + 1) "横向 scrollW ${str(it["sw"])} > 可视 ${str(it["cw"])}" else "纵向 scrollH ${str(it["sh"])} > 可视 ${str(it["ch"])}"
            "- ${str(it["sel"])} “${str(it["text"])}” — $axis（内容被裁切）"
        }
        if (trunc.isNotEmpty()) sb.appendLine("### 文字截断（内容被裁切）\n${trunc.joinToString("\n")}\n")

        val small = rows("smallTargets", "12") {
            "- ${str(it["sel"])} ${str(it["w"])}x${str(it["h"])}px（触屏建议 ≥44x44）"
        }
        if (small.isNotEmpty()) sb.appendLine("### 触屏点击目标过小（<44px）\n${small.joinToString("\n")}\n")

        val lowC = rows("lowContrast", "10") {
            "- ${str(it["sel"])} “${str(it["text"])}” 对比度 ${str(it["ratio"])}（需 ≥${str(it["need"])}）颜色 ${str(it["fg"])}"
        }
        if (lowC.isNotEmpty()) sb.appendLine("### 低对比度文字（WCAG）\n${lowC.joinToString("\n")}\n")

        val cs = obj["colorScheme"] as? JsonObject
        if (cs != null && cs.isNotEmpty()) {
            val prefersDark = str(cs["prefersDark"]) == "true"
            val darkBg = str(cs["darkBg"]) == "true"
            val meta = str(cs["meta"]).ifBlank { "-" }
            val bodyBg = str(cs["bodyBg"]).ifBlank { "-" }
            val verdict = when {
                prefersDark && darkBg -> "暗色适配 OK"
                prefersDark && !darkBg && meta == "-" -> "⚠️ 系统暗色但页面纯浅色（未适配，暗色下刺眼）"
                else -> if (meta != "-") "meta 声明 $meta（以系统为准切换）" else "仅浅色（未声明 color-scheme）"
            }
            sb.appendLine("### 暗色模式\n- 系统偏好: ${if (prefersDark) "dark" else "light"} · meta[color-scheme]: $meta · body 背景: $bodyBg —— $verdict\n")
        }

        if (sb.isBlank() || sb.length < 40) {
            sb.appendLine("(页面可见元素极少——可能内容未渲染，先 navigate 带 wait_for 重试)")
        }
        sb.appendLine("提示：交互元素定位用 snapshot；表单结构用 formDetect。")
        return sb.toString().trim()
    }

    private fun str(el: JsonElement?): String =
        try { el?.jsonPrimitive?.content ?: "-" } catch (e: Exception) { "-" }
}

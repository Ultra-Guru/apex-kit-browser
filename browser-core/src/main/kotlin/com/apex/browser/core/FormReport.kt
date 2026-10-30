package com.apex.browser.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * FormReport —— 表单结构检测的数据模型与解析（v1.9.0）。
 *
 * 数据流：
 *   [BrowserScript.formDetectJs]（页面侧采集，ref 与 snapshotJs 同源）
 *     → JSON 文本（transport：totalForms/forms[]/orphanCount/orphans[]）
 *     → [FormReportParser.parse]（本文件，纯 JVM）
 *     → [FormReport]（宿主 BrowserForm 工具消费 / 与 batchExecute 组合填表）
 *
 * 定位：把「发现表单 → 逐字段试探输入」的多轮模型往返压缩为一次采集：
 * 报告里每个字段带 [FormFieldDescriptor.ref]，Agent 据此直接构造
 * [BatchAction] 序列（input/select/click），一次 batchExecute 完成填提。
 *
 * 纯 JVM 纪律：解析在 `:browser-core`（与 [ReaderParser] 同款下沉），
 * engine 侧只做脚本执行与结果转交。
 */

/** select 下拉选项。 */
data class FormOption(
    val value: String,
    val text: String,
    val selected: Boolean = false,
)

/** 单个表单字段（input / select / textarea）。 */
data class FormFieldDescriptor(
    /** 与快照同源的语义哈希 ref（batchExecute 直接可用）；不可见字段为空串。 */
    val ref: String,
    val visible: Boolean = true,
    val tag: String,
    /** input 的 type（text/password/email/checkbox/radio/…）；select/textarea 用自身 tag。 */
    val type: String,
    val name: String = "",
    val id: String = "",
    val required: Boolean = false,
    val disabled: Boolean = false,
    val readOnly: Boolean = false,
    /** 关联 label 文本（labels[0] / 包裹 label / aria 语义，采集侧尽力）。 */
    val label: String = "",
    val placeholder: String = "",
    /** 有无当前值（password 只报有值，不回传内容 —— 安全语义见脚本 KDoc）。 */
    val hasValue: Boolean = false,
    /** 当前值预览（≤80 字符；password 恒空）。 */
    val valuePreview: String = "",
    /** checkbox / radio 的选中态；其余为 null。 */
    val checked: Boolean? = null,
    /** select 的总选项数（options 截断时模型据此知道「还有更多」）。 */
    val optionCount: Int = 0,
    val options: List<FormOption> = emptyList(),
) {
    /** 模型可读的单行摘要。 */
    fun summaryLine(): String {
        val sb = StringBuilder()
        sb.append("ref=").append(ref.ifBlank { "(不可操作)" })
        sb.append(" <").append(tag)
        if (type != tag) sb.append(" type=").append(type)
        sb.append(">")
        if (name.isNotBlank()) sb.append(" name=\"").append(name).append('"')
        if (id.isNotBlank()) sb.append(" id=\"").append(id).append('"')
        sb.append(" label=\"").append(label).append('"')
        if (placeholder.isNotBlank()) sb.append(" placeholder=\"").append(placeholder).append('"')
        if (required) sb.append(" [必填]")
        if (disabled) sb.append(" [禁用]")
        if (readOnly) sb.append(" [只读]")
        if (!visible) sb.append(" [不可见]")
        if (checked != null) sb.append(if (checked) " [已勾选]" else " [未勾选]")
        if (hasValue && type != "password") sb.append(" 当前值=\"").append(valuePreview).append('"')
        if (type == "password" && hasValue) sb.append(" [已有密码值]")
        return sb.toString()
    }
}

/** 单个表单（`<form>` 元素）。 */
data class FormDescriptor(
    /** 表单 action（相对 URL 原样回传，页面基址由模型自行拼接）。 */
    val action: String = "",
    val method: String = "get",
    val id: String = "",
    val name: String = "",
    val fields: List<FormFieldDescriptor> = emptyList(),
    /** 字段是否被 [BrowserScript.MAX_FIELDS_PER_FORM] 截断。 */
    val truncatedFields: Boolean = false,
    /** 可见提交按钮的 ref（≤3 个，最后一键提交用）。 */
    val submitRefs: List<String> = emptyList(),
) {
    /** 模型可读的单行表头摘要。 */
    fun headerLine(): String {
        val sb = StringBuilder()
        sb.append("表单 ").append(method.uppercase())
        if (action.isNotBlank()) sb.append(" → ").append(action)
        if (id.isNotBlank()) sb.append(" #").append(id)
        if (name.isNotBlank()) sb.append(" name=").append(name)
        if (truncatedFields) sb.append("（字段超上限已截断）")
        if (submitRefs.isNotEmpty()) sb.append(" 提交: ").append(submitRefs.joinToString(","))
        return sb.toString()
    }
}

/** 表单检测报告（全部表单 + 孤儿字段）。 */
data class FormReport(
    /** 页面 `<form>` 总数（forms 可能因 [BrowserScript.MAX_FORMS] 截断）。 */
    val totalForms: Int = 0,
    val forms: List<FormDescriptor> = emptyList(),
    val orphans: List<FormFieldDescriptor> = emptyList(),
) {
    val hasAnyField: Boolean
        get() = forms.any { it.fields.isNotEmpty() } || orphans.isNotEmpty()

    /**
     * 面向模型的完整报告文本（宿主工具可直接作为返回值）。
     * 结构对齐 [BatchOutcome.summaryLine] 的单行摘要风格。
     */
    fun toAgentText(): String {
        if (!hasAnyField && forms.isEmpty()) {
            return "(页面未检出表单字段 —— 若确认存在，可能整块在 iframe 内，用 frameList 确认)"
        }
        val sb = StringBuilder()
        sb.append("## 表单检测报告\n")
        sb.append("共 ").append(totalForms).append(" 个表单")
        if (totalForms > forms.size) sb.append("（回传前 ").append(forms.size).append(" 个）")
        sb.append(" + ").append(orphans.size).append(" 个孤儿字段（无 form 包裹）\n")
        for (form in forms) {
            sb.append("\n### ").append(form.headerLine()).append('\n')
            for (f in form.fields) sb.append("- ").append(f.summaryLine()).append('\n')
        }
        if (orphans.isNotEmpty()) {
            sb.append("\n### 孤儿字段（SPA 散字段）\n")
            for (f in orphans) sb.append("- ").append(f.summaryLine()).append('\n')
        }
        sb.append("\n提示：字段 ref 与快照同源，可直接构造 batchExecute（input/select/click）一次填提。")
        return sb.toString().trimEnd()
    }
}

/** 表单报告解析器（纯函数，防御性解析 —— 坏数据给默认值而非异常）。 */
object FormReportParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 解析 [BrowserScript.formDetectJs] 回传的 JSON **内层**文本
     * （调用方负责剥掉 evaluateJavascript 的 JSON 编码外壳）。
     * 空串 / "null" / 坏 JSON 一律返回 null（调用方按「采集失败」语义处理）。
     */
    fun parse(raw: String?): FormReport? {
        val text = raw?.trim()?.removeSurrounding("\"")?.trim() ?: return null
        if (text.isEmpty() || text == "null") return null
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val forms = obj["forms"]?.jsonArray?.mapNotNull { el ->
            runCatching { parseForm(el.jsonObject) }.getOrNull()
        } ?: emptyList()
        val orphans = obj["orphans"]?.jsonArray?.mapNotNull { el ->
            runCatching { parseField(el.jsonObject) }.getOrNull()
        } ?: emptyList()
        return FormReport(
            totalForms = obj.int("totalForms") ?: forms.size,
            forms = forms,
            orphans = orphans,
        )
    }

    private fun parseForm(obj: JsonObject): FormDescriptor = FormDescriptor(
        action = obj.str("action"),
        method = obj.str("method").ifBlank { "get" },
        id = obj.str("id"),
        name = obj.str("name"),
        fields = obj["fields"]?.jsonArray?.mapNotNull { el ->
            runCatching { parseField(el.jsonObject) }.getOrNull()
        } ?: emptyList(),
        truncatedFields = obj.bool("truncatedFields"),
        submitRefs = obj["submitRefs"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList(),
    )

    private fun parseField(obj: JsonObject): FormFieldDescriptor = FormFieldDescriptor(
        ref = obj.str("ref"),
        visible = obj.bool("visible", default = true),
        tag = obj.str("tag").ifBlank { "input" },
        type = obj.str("type").ifBlank { "text" },
        name = obj.str("name"),
        id = obj.str("id"),
        required = obj.bool("required"),
        disabled = obj.bool("disabled"),
        readOnly = obj.bool("readOnly"),
        label = obj.str("label"),
        placeholder = obj.str("placeholder"),
        hasValue = obj.bool("hasValue"),
        valuePreview = obj.str("valuePreview"),
        checked = obj["checked"]?.jsonPrimitive?.content?.let { it == "true" },
        optionCount = obj.int("optionCount") ?: 0,
        options = obj["options"]?.jsonArray?.mapNotNull { el ->
            runCatching {
                val o = el.jsonObject
                FormOption(
                    value = o.str("value"),
                    text = o.str("text"),
                    selected = o.bool("selected"),
                )
            }.getOrNull()
        } ?: emptyList(),
    )

    private fun JsonObject.str(key: String): String =
        runCatching { this[key]?.jsonPrimitive?.content ?: "" }.getOrDefault("")

    private fun JsonObject.int(key: String): Int? =
        runCatching { this[key]?.jsonPrimitive?.content?.toIntOrNull() }.getOrNull()

    private fun JsonObject.bool(key: String, default: Boolean = false): Boolean =
        runCatching { this[key]?.jsonPrimitive?.content?.toBooleanStrictOrNull() }.getOrNull() ?: default
}

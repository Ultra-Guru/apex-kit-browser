package com.apex.browser.engine

import android.webkit.WebView
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * v2.1.0 自 [BrowserEngine] 迁出的 JS 求值封装（GATE 1 文件预算；纯代码搬移，
 * 语义零变更——原为类内成员，现为同包扩展，类内调用点经隐式接收者解析不受影响）。
 */

/** 布尔求值：脚本回传 "true"/"false"（超时/异常路径由调用方处理）。 */
internal suspend fun BrowserEngine.evaluateBoolean(wv: WebView, js: String): Boolean =
    suspendCancellableCoroutine { cont ->
        wv.evaluateJavascript(js) { result -> cont.resume(result == "true") }
    }

/** JSON 求值：回调经 [unwrapWebViewJson] 剥壳后原样返回字符串。 */
internal suspend fun BrowserEngine.evaluateJson(wv: WebView, js: String): String =
    suspendCancellableCoroutine { cont ->
        wv.evaluateJavascript(js) { result ->
            cont.resume(unwrapWebViewJson(result ?: "null"))
        }
    }

/**
 * 剥掉 WebView.evaluateJavascript 的「JSON 编码回传」外壳（P0 修复，
 * 2026-10-07 诊断包：browser_input 三连「输入结果解析失败」+ browser_click
 * 「找不到 ref」+ 熔断器开启）。
 *
 * evaluateJavascript 的回调参数是脚本返回值的 **JSON 编码**：本库脚本
 * 以 `return JSON.stringify({...})` 结尾，回调拿到的其实是该字符串再编码
 * 一层（"{\"ok\":true}"）——直接 `parseToJsonElement(...).jsonObject`
 * 必抛 IllegalArgumentException。快照路径（snapshot / A11y fallback）早已
 * 用 `parseToJsonElement(wrapped).jsonPrimitive.content` 剥壳，但
 * inputText / queryCssRect / readElementAtPoint / fuzzyRelocate /
 * readQuickProbe / pageType / networkLog 全部漏了——即输入与点击在
 * 「元素确实存在」时也必然失败，模糊自愈与探针 diff 全部静默失效。
 *
 * 统一收敛到本函数：结果首尾是引号（字符串字面量编码）→ 剥一层再回；
 * 其余形状（布尔/数值/null/对象——脚本不以 stringify 结尾的场景）原样返回。
 */
internal fun BrowserEngine.unwrapWebViewJson(result: String): String {
    if (result.length < 2 || !result.startsWith("\"") || !result.endsWith("\"")) return result
    return runCatching {
        (unwrapJson.parseToJsonElement(result) as? JsonPrimitive)?.content ?: result
    }.getOrDefault(result)
}

/** 剥壳专用解析器（与引擎主 json 同配置：ignoreUnknownKeys + isLenient）。 */
private val unwrapJson = Json { ignoreUnknownKeys = true; isLenient = true }

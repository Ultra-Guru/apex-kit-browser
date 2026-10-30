package com.apex.browser.engine

import com.apex.browser.core.BrowserScript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume

/*
 * StorageAccess —— Web Storage 读写扩展（v1.9.0，Playwright `storage_state`
 * 对标的字面读写面；扩展函数形态，GATE 1 文件预算纪律同 ScrollBoundary.kt）。
 *
 * 能力：localStorage / sessionStorage 的 get / set / remove / keys / size /
 * clear。Agent 用它读登录态 token、改站点偏好、核对 A/B 分桶、注入测试
 * 状态 —— 此前这些只能靠 executeJavaScript 手拼，无转义无信封。
 *
 * 语义：
 * - 脚本侧统一 `{ok, ...}` 信封（[BrowserScript.storageJs]）：storage 被禁用 /
 *   QUOTA_EXCEEDED / 隐私模式全部转成 `ok=false + error`，不撕裂 JS；
 * - 读值超长按 [BrowserScript.MAX_STORAGE_VALUE_CHARS] 截断并如实标注
 *  （length / truncated —— 「截断如实上报」是库的既定契约）；
 * - 写入无大小预校验：QUOTA 由页面侧信封回传（预校验需要知道剩余配额，
 *   WebView 无此 API，假装知道不如如实失败）。
 */

/** 读取结果：[exists] = false 表示键不存在（非错误）；[error] 非空 = storage 不可用。 */
data class StorageRead(
    val exists: Boolean = false,
    val value: String = "",
    /** 原始值完整长度（截断前 —— 模型判断「这是不是被截了」的依据）。 */
    val length: Int = 0,
    val truncated: Boolean = false,
    val error: String? = null,
)

/** keys 枚举结果：[truncated] = 键数超 [BrowserScript.MAX_STORAGE_KEYS]。 */
data class StorageKeys(
    val keys: List<String> = emptyList(),
    val total: Int = 0,
    val truncated: Boolean = false,
)

/** 变更类操作（set/remove/clear）的结果：失败带页面侧 error 文案。 */
data class StorageMutation(
    val ok: Boolean,
    val error: String? = null,
)

/** 读取键值（[session] = true 用 sessionStorage）。null = 执行失败（无标签页/超时/JS 异常）。 */
suspend fun BrowserEngine.getStorageValue(key: String, session: Boolean = false): StorageRead? {
    val env = runStorageEnvelope(BrowserScript.storageJs(BrowserScript.StorageOp.GET, key = key, session = session)) ?: return null
    if (env.str("ok") != "true") return StorageRead(error = env.str("error").ifBlank { "storage unavailable" })
    if (env.str("exists") != "true") return StorageRead(exists = false)
    return StorageRead(
        exists = true,
        value = env.str("value"),
        length = env.int("length"),
        truncated = env.str("truncated") == "true",
    )
}

/** 写入键值。返回 [StorageMutation]（QUOTA / 禁用 → ok=false + error）。 */
suspend fun BrowserEngine.setStorageValue(key: String, value: String, session: Boolean = false): StorageMutation =
    mutation(BrowserScript.storageJs(BrowserScript.StorageOp.SET, key = key, value = value, session = session))

/** 删除键。 */
suspend fun BrowserEngine.removeStorageKey(key: String, session: Boolean = false): StorageMutation =
    mutation(BrowserScript.storageJs(BrowserScript.StorageOp.REMOVE, key = key, session = session))

/** 清空全部键（整 storage，非单键 —— 语义同浏览器「清除站点数据」的 storage 部分）。 */
suspend fun BrowserEngine.clearStorage(session: Boolean = false): StorageMutation =
    mutation(BrowserScript.storageJs(BrowserScript.StorageOp.CLEAR, session = session))

/** 枚举全部键（截断如实标注）。null = 执行失败。 */
suspend fun BrowserEngine.storageKeys(session: Boolean = false): StorageKeys? {
    val env = runStorageEnvelope(BrowserScript.storageJs(BrowserScript.StorageOp.KEYS, session = session)) ?: return null
    if (env.str("ok") != "true") return StorageKeys()
    val keys = runCatching { env["keys"]?.jsonArray }.getOrNull()
        ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: emptyList()
    return StorageKeys(
        keys = keys,
        total = env.int("total"),
        truncated = env.str("truncated") == "true",
    )
}

/** 键数量（O(1) 版本的 keys —— 只需要规模时用它）。null = 执行失败。 */
suspend fun BrowserEngine.storageSize(session: Boolean = false): Int? {
    val env = runStorageEnvelope(BrowserScript.storageJs(BrowserScript.StorageOp.SIZE, session = session)) ?: return null
    return if (env.str("ok") == "true") env.int("count") else null
}

/* ───────────── 内部：信封执行与解析 ───────────── */

// 扩展形态：mutation 内部要调同为 BrowserEngine 扩展的 runStorageEnvelope
//（无接收者的顶层函数解析不到扩展 —— CI 抓到过一次）。
private suspend fun BrowserEngine.mutation(script: String): StorageMutation {
    val env = runStorageEnvelope(script)
        ?: return StorageMutation(ok = false, error = "执行失败（无激活标签页或脚本超时）")
    return if (env.str("ok") == "true") StorageMutation(ok = true)
    else StorageMutation(ok = false, error = env.str("error").ifBlank { "storage unavailable" })
}

/** 执行脚本并解双层编码，返回信封对象；失败（无标签页/超时/坏 JSON）返回 null。 */
private suspend fun BrowserEngine.runStorageEnvelope(script: String): JsonObject? =
    withContext(Dispatchers.Main) {
        val wv = activeTab()?.webView ?: return@withContext null
        val raw = runCatching {
            withTimeout(6000) {
                suspendCancellableCoroutine<String> { cont ->
                    wv.evaluateJavascript(script) { cont.resume(it ?: "null") }
                }
            }
        }.getOrNull() ?: return@withContext null
        runCatching {
            val inner = Json.parseToJsonElement(raw).jsonPrimitive.content
            Json.parseToJsonElement(inner).jsonObject
        }.getOrNull()
    }

private fun JsonObject.str(key: String): String =
    runCatching { this[key]?.jsonPrimitive?.content ?: "" }.getOrDefault("")

private fun JsonObject.int(key: String): Int =
    runCatching { this[key]?.jsonPrimitive?.content?.toIntOrNull() }.getOrNull() ?: 0

package com.apex.browser.engine

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/*
 * CookieControl —— Cookie 写入/删除扩展（v1.9.0；扩展函数形态，GATE 1
 * 文件预算纪律同 StorageAccess.kt）。
 *
 * 引擎已有 [BrowserEngine.getCookies]（读），本文件补**写**与**删**：
 * - setCookie：恢复登录态 / 预置会话（配合外部取到的 Cookie 串）；
 * - removeCookie：单键删除（过期语义删除，CookieManager 无单键 API 的
 *   标准替代）；
 * - clearCookiesForDomain：域级清除（全局 removeAllCookies 已由
 *   EngineDisplayController.clearBrowsingData 提供，这里做的是「只清这
 *   一个站点」—— 重登单个站点不动其他站点的登录态）。
 *
 * 安全口径：
 * - name / value 拒绝 `;` 与控制字符（撕裂 Set-Cookie 头 = 注入面），
 *   拒绝时抛 [IllegalArgumentException]（宿主 SafeAgentTool 转错误文本）；
 * - 只允许 http/https URL（file:// 上设 Cookie 无意义且放大攻击面）；
 * - Secure 属性跟随目标 URL 的 scheme（https → Secure）。
 */

/** Set-Cookie 值里的非法字符（分号撕裂属性、控制字符撕裂 HTTP 头）。 */
private val ILLEGAL_COOKIE_CHARS = Regex("[;\\r\\n\\u0000-\\u001F\\u007F]")

/**
 * 写入 Cookie。默认 `Path=/`；[expiresEpochSeconds] 为 null 时写会话 Cookie
 * （WebView 退出即失效）。返回 false = CookieManager 不可用或 URL 非法。
 */
suspend fun BrowserEngine.setCookie(
    url: String,
    name: String,
    value: String,
    path: String = "/",
    expiresEpochSeconds: Long? = null,
): Boolean {
    require(name.isNotBlank()) { "cookie name 不能为空" }
    require(!ILLEGAL_COOKIE_CHARS.containsMatchIn(name)) { "cookie name 含非法字符（; 或控制字符）" }
    require(!ILLEGAL_COOKIE_CHARS.containsMatchIn(value)) { "cookie value 含非法字符（; 或控制字符）" }
    if (!url.startsWith("http://") && !url.startsWith("https://")) return false
    val sb = StringBuilder(name).append('=').append(value)
    sb.append("; Path=").append(path.ifBlank { "/" })
    if (url.startsWith("https://")) sb.append("; Secure")
    if (expiresEpochSeconds != null) {
        // RFC 1123（Set-Cookie Expires 的法定格式）
        val fmt = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)
        sb.append("; Expires=").append(fmt.format(Instant.ofEpochSecond(expiresEpochSeconds)))
    }
    return withContext(Dispatchers.Main) {
        val cm = runCatching { CookieManager.getInstance() }.getOrNull() ?: return@withContext false
        // setCookie 返回 void（平台类型）—— isSuccess 判定，避免 getOrDefault
        // 把 Result<Unit!> 与 Boolean 统一成 Any 的类型陷阱
        val ok = runCatching { cm.setCookie(url, sb.toString()) }.isSuccess
        if (ok) runCatching { cm.flush() }
        ok
    }
}

/**
 * 删除单个 Cookie（过期语义删除）。返回 false = CookieManager 不可用或 URL 非法。
 */
suspend fun BrowserEngine.removeCookie(url: String, name: String, path: String = "/"): Boolean =
    setCookie(url, name, "", path = path, expiresEpochSeconds = 0L)

/**
 * 清除某 URL 所属域的全部 Cookie（域级清除，不动其他站点）。返回删除的键数；
 * -1 = 执行失败（CookieManager 不可用）。
 */
suspend fun BrowserEngine.clearCookiesForDomain(url: String): Int {
    if (!url.startsWith("http://") && !url.startsWith("https://")) return -1
    return withContext(Dispatchers.Main) {
        val cm = runCatching { CookieManager.getInstance() }.getOrNull() ?: return@withContext -1
        val raw = runCatching { cm.getCookie(url) }.getOrNull() ?: return@withContext 0
        val names = raw.split(";").mapNotNull { seg ->
            val k = seg.substringBefore('=', "").trim()
            k.ifBlank { null }
        }.distinct()
        var removed = 0
        for (n in names) {
            if (runCatching { cm.setCookie(url, "$n=; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT") }.isSuccess) {
                removed++
            }
        }
        runCatching { cm.flush() }
        removed
    }
}

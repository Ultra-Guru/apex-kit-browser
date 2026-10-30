package com.apex.browser.engine

import java.util.ArrayDeque

/*
 * ConsoleCapture —— JS 控制台捕获（v2.0.0，Playwright `page.on('console')` /
 * `page.on('pageerror')` 的 WebView 对标面）。
 *
 * v1.x 的引擎对 console **零感知**：页面 JS 报错（未捕获异常、SDK 初始化
 * 失败、资源 404 的 console.error）全部直接进 logcat 蒸发 —— Agent 调试
 * 页面交互失败时，「页面自己说了什么」是完全的盲区。Chromium 会把未捕获
 * JS 异常以 ERROR 级 console 消息上报（"Uncaught TypeError: ..."），因此
 * 单一 onConsoleMessage 桥即同时覆盖 console.* 与页面错误两面。
 *
 * 语义：
 * - 环形缓冲 [CAPACITY] 条（400）：console.log 刷屏的页面不挤爆内存，
 *   旧条目自然淘汰；
 * - [recent] 三维过滤：级别下限（ERROR > WARNING > 其余）/ 子串 /
 *   条数 —— 「只看报错」「只看含 checkout 的日志」都是一次调用；
 * - [errorCount] 自上次 clear 的累计错误数：Agent 动作前后各读一次，
 *   差值即「该动作是否引发新 JS 错误」（动作后验证的补全面）。
 *
 * 线程模型：onConsoleMessage 主线程写；recent 任意线程读（synchronized）。
 */
class ConsoleCapture {

    /** 控制台条目（`level`：ERROR/WARNING/INFO/LOG/DEBUG/TIP，Chromium 原生枚举名）。 */
    data class Entry(
        val level: String,
        val message: String,
        /** 来源（sourceId，通常是脚本 URL；about: 的内联脚本为空串）。 */
        val source: String,
        val line: Int,
        val atMs: Long,
    ) {
        /** 单行渲染（供 Agent 直接消费）。 */
        fun summaryLine(): String {
            val tag = level.firstOrNull() ?: '?'
            val src = if (source.isBlank()) "" else " @${
                source.substringAfterLast('/').take(40)
            }${if (line > 0) ":$line" else ""}"
            return "[$tag] ${message.replace('\n', ' ').take(200)}$src"
        }
    }

    private val buffer = ArrayDeque<Entry>(CAPACITY)
    private var errorsSinceClear = 0

    /** 环形缓冲容量（刷屏页面内存防线）。 */
    val capacity: Int get() = CAPACITY

    /** WebChromeClient.onConsoleMessage 的接入点（主线程）。 */
    fun record(level: String, message: String?, source: String?, line: Int) {
        if (message == null) return
        synchronized(buffer) {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(
                Entry(
                    level = level.uppercase(),
                    message = message.take(2_000),
                    source = (source ?: "").take(300),
                    line = line,
                    atMs = System.currentTimeMillis(),
                ),
            )
            if (level.equals("ERROR", ignoreCase = true)) errorsSinceClear++
        }
    }

    /**
     * 最近条目。[minLevel] = "ERROR" 只看错误 / "WARNING" 错误+警告 /
     * null 全量；[contains] 子串过滤（忽略大小写）。
     */
    fun recent(
        limit: Int = 50,
        minLevel: String? = null,
        contains: String? = null,
    ): List<Entry> {
        val minOrd = minLevel?.let { levelRank(it) }
        val needle = contains?.lowercase()
        synchronized(buffer) {
            return buffer.toList()
                .asReversed()
                .filter { e ->
                    (minOrd == null || levelRank(e.level) >= minOrd) &&
                        (needle == null || e.message.lowercase().contains(needle))
                }
                .take(limit.coerceIn(1, CAPACITY))
        }
    }

    /** 自上次 [clear] 的 ERROR 级累计数（动作前后差值 = 是否引发新错误）。 */
    fun errorCount(): Int = synchronized(buffer) { errorsSinceClear }

    /** 清空缓冲与错误计数（新动作的「干净基线」）。 */
    fun clear() = synchronized(buffer) {
        buffer.clear()
        errorsSinceClear = 0
    }

    /** 条目数（不带过滤的规模面）。 */
    fun size(): Int = synchronized(buffer) { buffer.size }

    private fun levelRank(level: String): Int = when (level.uppercase()) {
        "ERROR" -> 4
        "WARNING" -> 3
        "INFO", "LOG" -> 2
        "DEBUG", "TIP" -> 1
        else -> 2
    }

    companion object {
        private const val CAPACITY: Int = 400
    }
}

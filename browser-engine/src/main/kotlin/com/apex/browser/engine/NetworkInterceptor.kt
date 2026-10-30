package com.apex.browser.engine

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.PatternSyntaxException

/*
 * NetworkInterceptor —— 请求屏蔽引擎（v1.9.0，Playwright `route().abort()` 的
 * WebView 对标面）。
 *
 * 能力：按 URL 规则（子串 / 正则）屏蔽子资源请求 —— 广告与跟踪器掐流量、
 * 图片/字体/媒体按需断供提速、可疑第三方域整体熔断。每条规则带独立命中
 * 计数（可观测性：屏蔽了多少、哪条规则在起作用）。
 *
 * ## 实现形态：装饰器包装，而非改引擎
 *
 * [BrowserEngine] 的 WebViewClient 是 createWebView 内的匿名对象（文件预算
 * 已顶满，不允许继续加方法）。本类不动引擎一行：读出 WebView 当前安装的
 * client，用 [InterceptingClient] 包装后回装 —— 拦截 [shouldInterceptRequest]
 * 之外的全部行为**逐方法转发**给原 client（引擎的 SSL 拒绝 / scheme 白名单 /
 * 崩溃恢复语义原样保留）。标签切换 / 崩溃重建产生新 WebView 时经
 * [BrowserEngine.ActiveTabListener] 自动重新包装（幂等：已包装的直接跳过）。
 *
 * ## 安全底线（不可配置，代码写死）
 *
 * - **主框架导航永不屏蔽**：`isForMainFrame` 的请求直通 —— 否则一条过宽的
 *   规则能把浏览器整体打瞎（导航返回空响应 = 白屏）。
 * - **非 GET 请求永不屏蔽**：POST 是表单提交 / 状态变更，屏蔽它等于静默
 *   吞掉用户操作的结果（页面侧显示成功、服务端什么都没发生）。
 * - 图片屏蔽回传 1×1 透明 GIF（而非空响应）：占位不撕裂布局。
 *
 * ## 已知取舍
 *
 * 只做「屏蔽」，不做「改写/重定向」：shouldInterceptRequest 无法把请求头
 * 修改后转发（需自建网络栈重新发起，破坏 Cookie/Referer/SPDY 复用语义）。
 * 屏蔽是覆盖广告/跟踪/提速 80% 场景的 20% 实现。
 */
class NetworkInterceptor private constructor(private val engine: BrowserEngine) {

    /** 屏蔽规则（id 稳定，供 removeBlockRule / stats 定位）。 */
    data class BlockRule(
        val id: String,
        val pattern: String,
        val isRegex: Boolean = false,
        val description: String = "",
    ) {
        // kotlin.text.Regex（java.util.regex 无 Regex 类型，是 Pattern）
        internal val regex: Regex? by lazy {
            if (isRegex) runCatching { pattern.toRegex(RegexOption.IGNORE_CASE) }.getOrNull() else null
        }

        internal fun matches(url: String): Boolean = when {
            isRegex -> regex?.containsMatchIn(url) == true
            else -> url.contains(pattern, ignoreCase = true)
        }
    }

    private val rules = CopyOnWriteArrayList<BlockRule>()
    private val counts = ConcurrentHashMap<String, AtomicInteger>()
    private val main = Handler(Looper.getMainLooper())
    private var seq = 0

    /** 已包装的 WebView（弱键：WebView 销毁后条目自动清理）。 */
    private val wrapped = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<WebView, Boolean>()
    )

    private val tabListener = object : BrowserEngine.ActiveTabListener {
        override fun onActiveTabChanged(webView: WebView?) {
            runOnMain { installOn(webView) }
        }
    }

    init {
        // 订阅活动标签变化：标签切换 / 新建 / 崩溃重建后新 WebView 自动补包装。
        engine.addActiveTabListener(tabListener)
        runOnMain { installOn(engine.activeWebView()) }
    }

    /* ───────────── 规则管理 ───────────── */

    /**
     * 添加屏蔽规则。[pattern] 为 URL 子串（大小写不敏感）或正则
     * （[isRegex] = true，INVALID_REGEX 立即抛 [PatternSyntaxException] ——
     * fail fast，规则进列表时就知道坏，不是页面打不开才发现）。
     * 返回带稳定 id 的规则（removeBlockRule / stats 用）。
     */
    @Synchronized
    fun addBlockRule(pattern: String, isRegex: Boolean = false, description: String = ""): BlockRule {
        if (pattern.isBlank()) throw IllegalArgumentException("pattern 不能为空")
        if (isRegex) {
            // 编译校验 fail fast（结果丢弃，BlockRule.regex 持久持有编译态）
            java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE)
        }
        val rule = BlockRule(id = "blk_${++seq}", pattern = pattern, isRegex = isRegex, description = description)
        rules.add(rule)
        counts[rule.id] = AtomicInteger(0)
        return rule
    }

    /** 按 id 移除规则；不存在返回 false。 */
    @Synchronized
    fun removeBlockRule(id: String): Boolean {
        counts.remove(id)
        return rules.removeIf { it.id == id }
    }

    /** 清空全部规则，返回清掉的条数。 */
    @Synchronized
    fun clearRules(): Int {
        val n = rules.size
        rules.clear()
        counts.clear()
        return n
    }

    /** 当前规则快照。 */
    fun rules(): List<BlockRule> = rules.toList()

    /** 各规则命中计数（id → blocked 次数）。 */
    fun stats(): Map<String, Int> = counts.mapValues { it.value.get() }

    /** 全部规则命中总数。 */
    fun totalBlocked(): Int = counts.values.sumOf { it.get() }

    /* ───────────── 预设（常见屏蔽场景一键接入） ───────────── */

    /** 屏蔽常见广告 / 分析 / 行为跟踪域（Google/Meta/ads 生态 + 国产常见项）。返回添加的规则数。 */
    @Synchronized
    fun blockTrackers(): Int {
        TRACKER_PATTERNS.forEach { addBlockRule(it, description = "tracker preset") }
        return TRACKER_PATTERNS.size
    }

    /** 屏蔽图片资源（提速主战场：图文页可省 60-80% 流量）。 */
    fun blockImages(): Int = addBlockRule(
        IMAGE_PATTERN, isRegex = true, description = "image preset",
    ).let { 1 }

    /** 屏蔽自定义字体（woff/woff2/ttf/otf/eot）。 */
    fun blockFonts(): Int = addBlockRule(
        FONT_PATTERN, isRegex = true, description = "font preset",
    ).let { 1 }

    /** 屏蔽音视频资源（mp4/webm/m3u8/mp3/wav/ogg/flac/aac）。 */
    fun blockMedia(): Int = addBlockRule(
        MEDIA_PATTERN, isRegex = true, description = "media preset",
    ).let { 1 }

    /** 停止订阅标签变化（宿主释放时调用；规则与计数保留，dispose 后 re-install 不自动）。 */
    fun dispose() {
        engine.removeActiveTabListener(tabListener)
    }

    /* ───────────── 内部：拦截决策与包装安装 ───────────── */

    /**
     * 拦截决策（[InterceptingClient.shouldInterceptRequest] 回调线程调用，
     * 全并发安全）。返回非 null = 屏蔽响应；null = 放行（转回原 client）。
     */
    internal fun maybeBlock(request: WebResourceRequest): WebResourceResponse? {
        // 安全底线一：主框架导航永不屏蔽（过宽规则不能打瞎浏览器本身）
        if (request.isForMainFrame) return null
        // 安全底线二：非 GET 永不屏蔽（POST = 表单提交/状态变更，屏蔽 = 静默吞结果）
        val method = request.method ?: return null
        if (!method.equals("GET", ignoreCase = true)) return null
        val url = request.url?.toString() ?: return null
        for (rule in rules) {
            if (rule.matches(url)) {
                counts[rule.id]?.incrementAndGet()
                return blockedResponse(url)
            }
        }
        return null
    }

    /** 屏蔽响应：图片位（按扩展名粗判）给透明 GIF 占位，其余给空文本。 */
    private fun blockedResponse(url: String): WebResourceResponse {
        return if (IMAGE_EXT_RE.containsMatchIn(url)) {
            WebResourceResponse("image/gif", null, ByteArrayInputStream(TRANSPARENT_GIF))
        } else {
            WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        }
    }

    /** 当前活动 WebView 安装装饰器（幂等；仅主线程调用）。 */
    private fun installOn(webView: WebView?) {
        val wv = webView ?: return
        if (wrapped.containsKey(wv)) return
        val current = runCatching { wv.webViewClient }.getOrNull() ?: return
        if (current is InterceptingClient) {
            wrapped[wv] = true
            return
        }
        runCatching { wv.webViewClient = InterceptingClient(current, this) }
            .onFailure { return }
        wrapped[wv] = true
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    /**
     * 装饰器 client：拦截 [shouldInterceptRequest]，其余转发原 client。
     *
     * 转发面 = 引擎匿名 client 实际覆写的五个方法（onPageStarted /
     * onPageFinished / onRenderProcessGone / shouldOverrideUrlLoading /
     * onReceivedSslError）。引擎后续新增覆写**必须**同步加转发，否则静默
     * 失联 —— 该契约由 review 守护（文件预算不允许引擎继续膨胀，新回调
     * 优先走本类同款装饰模式）。
     */
    private class InterceptingClient(
        private val delegate: WebViewClient,
        private val interceptor: NetworkInterceptor,
    ) : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?,
        ): WebResourceResponse? {
            // 框架不会传 null（平台类型的防御位）；基类行为即「不拦截」，
            // 不转发 null 给 delegate —— 已知为 null 的 WebResourceRequest?
            // 同时适配 String!/WebResourceRequest! 两个重载，转发即歧义。
            if (request == null) return null
            val blocked = interceptor.maybeBlock(request)
            if (blocked != null) return blocked
            return delegate.shouldInterceptRequest(view, request)
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            delegate.onPageStarted(view, url, favicon)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            delegate.onPageFinished(view, url)
        }

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean =
            delegate.onRenderProcessGone(view, detail)

        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
            delegate.shouldOverrideUrlLoading(view, url)

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
            delegate.onReceivedSslError(view, handler, error)
        }
    }

    companion object {
        /** 进程级注册（与 BrowserEngineFactory 同款单例纪律）。 */
        private val instances = ConcurrentHashMap<BrowserEngine, NetworkInterceptor>()

        /** 取引擎对应的拦截器单例（首次调用即订阅并安装）。 */
        fun forEngine(engine: BrowserEngine): NetworkInterceptor =
            instances.getOrPut(engine) { NetworkInterceptor(engine) }

        /** 预设：常见广告 / 分析 / 跟踪域（子串匹配，覆盖主流 + 国产生态）。 */
        val TRACKER_PATTERNS: List<String> = listOf(
            "google-analytics.com", "googletagmanager.com", "googlesyndication.com",
            "googleadservices.com", "doubleclick.net", "adsystem.com", "adnxs.com",
            "facebook.net", "connect.facebook.net", "graph.facebook.com",
            "scorecardresearch.com", "quantserve.com", "hotjar.com", "mixpanel.com",
            "segment.io", "amplitude.com", "clarity.ms", "mc.yandex.ru",
            "top.linkedin.com", "px.ads.linkedin.com", "bat.bing.com",
            "ads-twitter.com", "analytics.tiktok.com", "criteo.com", "taboola.com",
            "outbrain.com", "adroll.com", "appsflyer.com", "sentry.io", "bugsnag.com",
        )

        internal val IMAGE_PATTERN = """\.(png|jpe?g|gif|webp|svg|avif|ico|bmp)([?#]|$)"""
        internal val FONT_PATTERN = """\.(woff2?|ttf|otf|eot)([?#]|$)"""
        internal val MEDIA_PATTERN = """\.(mp4|webm|m3u8|ts|mp3|wav|ogg|flac|aac)([?#]|$)"""

        private val IMAGE_EXT_RE = Regex("""\.(png|jpe?g|gif|webp|svg|avif|ico|bmp)([?#]|$)""", RegexOption.IGNORE_CASE)

        /** 1×1 透明 GIF（43 字节，Base64）—— 图片屏蔽占位，不撕裂布局。 */
        private val TRANSPARENT_GIF: ByteArray = Base64.decode(
            "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7", Base64.DEFAULT
        )
    }
}

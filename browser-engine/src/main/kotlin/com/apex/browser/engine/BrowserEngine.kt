package com.apex.browser.engine

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebSettings
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.GeolocationPermissions
import com.apex.browser.core.BrowserScript
import com.apex.browser.core.CircuitBreaker
import com.apex.browser.core.DomParser
import com.apex.browser.core.ElementNotFoundException
import com.apex.browser.core.PageClassifier
import com.apex.browser.core.PageClassifier.PageTypeInfo
import com.apex.browser.core.PageSnapshot
import com.apex.browser.core.PermanentActionException
import com.apex.browser.core.RetryPolicy
import com.apex.browser.core.withRetry
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.coroutines.resume

/**
 * 内置 WebView 浏览器引擎（对标 Operit 的 BrowserAgent + TabManager + HistoryManager）。
 *
 * 2026 裁决最终形态（综合 Qwen 评审 P0 缺口）：
 * - 单例常驻，由宿主 Service 持有（[BrowserEngineFactory]），后台即可驱动网页自动化（不依赖前台 Activity）。
 * - 稳定 Ref：语义哈希 `data-apex-hash`（"r_xxx"），抗 SPA 局部刷新错位。
 * - 物理触摸注入：点击经 DOM 定位 → 换算 WebView 屏幕坐标 → [WebView.dispatchTouchEvent]，
 *   绕过 `isTrusted` 校验与 JS 事件委托陷阱（替代旧 `el.click()`）。
 * - 显式握手状态机：[BrowserSessionState] 驱动人工接管，[WAITING_HUMAN] 期间所有自动化工具被锁。
 * - P0 缺口补齐：页面加载等待(#1)、动作后验证(#2)、JS 弹窗处理(#3)、渲染进程崩溃恢复(#4)、
 *   动作空间补全 select/toggle(#5)、Cookie 持久化(#6)。
 *
 * 库化改造（自宿主 app 抽出为 apex-kit-browser）：去 Hilt —— 本库不依赖任何 DI
 * 框架，单例语义由 [BrowserEngineFactory] 提供；宿主如用 Hilt 在自己的 Module 里
 * provide 即可（见仓库 README「消费方式」一节）。
 */
class BrowserEngine private constructor(
    private val appContext: Context,
) {

    // ───────── 状态机（显式握手人工接管） ─────────
    enum class BrowserSessionState {
        HIDDEN,          // 后台运行，无 UI
        AGENT_DRIVING,   // Agent 控制中，浮窗可显示实时画面
        WAITING_HUMAN,   // 人工接管中，WebView 接收真实触摸，Agent 工具被锁定
        RECOVERING       // 渲染进程崩溃重建中
    }

    /** Agent 工具在 [WAITING_HUMAN] 期间被调用时抛出，转化为友好的 SYSTEM_LOCKED 提示 */
    class HandoffLockedException(message: String) : IllegalStateException(message)

    @Volatile
    var currentState: BrowserSessionState = BrowserSessionState.HIDDEN
        private set

    // P1 fix（生命周期竞态，两轮混沌审查同题合并）：旧实现 onPermissionRequest 等
    // WebChromeClient 回调里直接 new 裸 CoroutineScope(Dispatchers.Main).launch ——
    // 无 SupervisorJob/异常处理器（enterHandoffMode 未捕获异常直接杀进程）且每次
    // 网页权限请求都产生无主协程抢 stateMutex，连点即堆积、永不取消。改用引擎
    // 单例持有的常驻 mainScope：Main.immediate + 异常记录不崩溃。
    private val mainScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e ->
            android.util.Log.w("BrowserEngine", "main-scope task failed", e)
        }
    )

    private val stateMutex = Mutex()

    // ───────── UI 回调（浮窗等可视层订阅状态变更） ─────────
    /** 可视层（浮窗）订阅状态变更，用于自动展开/收起与刷新控制条 */
    interface BrowserUiCallback {
        fun onStateChanged(state: BrowserSessionState, url: String?, title: String?)
    }

    @Volatile
    private var uiCallbacks = CopyOnWriteArraySet<BrowserUiCallback>()

    /** 浮窗注册自己为状态订阅者（支持多订阅者：霓虹球 + 接管面板） */
    fun addUiCallback(cb: BrowserUiCallback) {
        uiCallbacks.add(cb)
    }

    /** 注销状态订阅者 */
    fun removeUiCallback(cb: BrowserUiCallback) {
        uiCallbacks.remove(cb)
    }

    // ───────── 活动标签通知（v1.4.0：实时页面悬浮窗的数据源） ─────────
    /**
     * 活动标签变化订阅者（标签新建/切换/关闭回落/崩溃重建/导航重建都会触发）。
     *
     * 与 [BrowserUiCallback] 的分工：后者只管会话状态机迁移（HIDDEN/DRIVING/
     * WAITING_HUMAN/RECOVERING），而 Agent 驾驶期间 browser_switch_tab /
     * browser_new_tab 等工具换页不产生状态迁移——可视层（如实时页面悬浮窗）
     * 若只订状态就拿不到新 WebView。本回调把「当前应展示哪个 WebView」的变化
     * 单独广播，通知必须已在主线程（与标签管理方法同线程约定）。
     */
    interface ActiveTabListener {
        fun onActiveTabChanged(webView: WebView?)
    }

    @Volatile
    private var activeTabListeners = CopyOnWriteArraySet<ActiveTabListener>()

    /** 注册活动标签订阅者（实时页面悬浮窗、霓虹球等可视层）。 */
    fun addActiveTabListener(listener: ActiveTabListener) { activeTabListeners.add(listener) }

    /** 注销活动标签订阅者。 */
    fun removeActiveTabListener(listener: ActiveTabListener) { activeTabListeners.remove(listener) }

    /** 统一活动页通知出口：活动 WebView 实例变化时广播（含变 null 的会话收尾）。 */
    private fun notifyActiveTabChanged() {
        val view = activeWebView()
        activeTabListeners.forEach { runCatching { it.onActiveTabChanged(view) } }
    }

    /** 统一状态出口：所有状态变更必须经此，以驱动可视层 */
    private fun setState(next: BrowserSessionState) {
        currentState = next
        val tab = activeTab()
        // P1 fix（生命周期竞态）：uiCallbacks 可能被主线程 add/remove 的同时被 IO 线程
        // （Agent 工具）遍历 —— CopyOnWriteArraySet 保证迭代器快照语义，不会 CME
        uiCallbacks.forEach { it.onStateChanged(next, tab?.url, tab?.title) }
    }

    /** 当前激活标签页的 WebView，供浮窗承载显示 */
    fun activeWebView(): WebView? = activeTab()?.webView

    // ───────── 标签页 / 历史 ─────────
    private val tabs = LinkedHashMap<Int, Tab>()
    private var activeTabId: Int = 0
    private var nextTabId = 1
    private val history = ArrayDeque<String>()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** ref(语义哈希) -> 上次快照时的 Bid 序号，点击/输入时按稳定 ref 定位 */
    private val refToBid = mutableMapOf<String, Int>()

    /**
     * v1.1.0 自愈索引：ref → (tag, 文本) LRU。快照时填充，元素失配时
     * 供模糊重定位（locateByFuzzyJs）回查描述——SPA 局部刷新不再直接失败。
     */
    private val refDescriptorIndex = object : LinkedHashMap<String, Pair<String, String>>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, String>>) = size > 256
    }

    // P1 #7：错误恢复 —— 指数退避重试 + 熔断器（仅瞬态异常重试，语义错误不重试）
    private val retryPolicy = RetryPolicy()
    private val breaker = CircuitBreaker()

    /**
     * v1.3.0 可观测性接线（公开属性）：关键动作（navigate/click/input/select/
     * scroll/snapshot）成功/失败边界与系统事件（渲染崩溃/SSL 拦截/弹窗自动确认/
     * 下载入队）统一入 trace——动作边界包装见同包 internal 扩展
     * `tracedAction`（EngineTracing.kt）。宿主可读 [BrowserTracer.recent] /
     * [BrowserTracer.contextSummary]，或订阅 [BrowserTracer.events] 实时消费。
     */
    val tracer = BrowserTracer()

    /**
     * JS 弹窗仲裁器（v2.0.0）：alert/confirm/prompt 的三模式处置
     * （AUTO_CONFIRM 默认 = v1.x 行为零漂移；AUTO_DISMISS；MANUAL + respond +
     * 15s 超时兼底）。Agent 控制面见 [DialogArbiter.pendingDialogs] / [respond]，
     * 快照融合见 [DialogArbiter.pendingNotice]。
     */
    val dialogArbiter = DialogArbiter(tracer)

    /**
     * JS 控制台捕获（v2.0.0）：console.* 与未捕获异常（Chromium 以 ERROR 级
     * console 上报）环形缓冲 400 条，[ConsoleCapture.recent] 三维过滤，
     * [ConsoleCapture.errorCount] 供动作前后差值判定。
     */
    val consoleCapture = ConsoleCapture()

    /** 文件上传回调挂起（[onShowFileChooser] ↔ [respondFileChooser]） */
    private var pendingFileChooser: ValueCallback<Array<android.net.Uri>>? = null

    data class Tab(
        val id: Int,
        val webView: WebView,
        var title: String = "",
        var url: String = "",
        /** 页面加载完成信号（onPageFinished 置 true，navigate 时置 false） */
        @Volatile var pageFinished: Boolean = false,
        /**
         * v1.8.0 提速：本 Tab 当前 JS 上下文是否已注入网络监控钩子。
         * onPageFinished 注入后置 true（导航重置 JS 上下文，onPageStarted 置回
         * false）；snapshot 据此跳过重复注入——旧实现每次快照都执行一遍
         * NETWORK_MONITOR_JS（脚本有 __apexNetHooked 幂等守卫，但每次仍是
         * 一次 JS 编译+求值往返）。
         */
        @Volatile var netHooked: Boolean = false,
    )

    // P0 #11（安全加固基线）：禁止本地文件访问，避免 UXSS / 路径穿越。
    //
    // DEPRECATION 抑制是有理由的，**不要**当死代码删掉：
    //   - allowFileAccessFromFileURLs 自 API 30 起被系统忽略且恒为 false，但在
    //     API 26~29（本库 minSdk 26）默认仍为 **true**。删掉这一行等于让老设备上的
    //     file:// 页面恢复跨文件读取，是一次静默的安全回归。
    //   - allowUniversalAccessFromFileURLs 默认即 false，这行属防御性冗余，保留。
    // scripts/check_webview_hardening.py 把上述每一条都锁成门禁：删行、改成 true、
    // 或只写在 KDoc 里，都会让 CI 失败。
    @SuppressLint("SetJavaScriptEnabled", "SdCardPath", "DEPRECATION")
    private fun createWebView(): WebView {
        val wv = WebView(applicationContext())
        // JS 是本库能力的前提（DOM 快照、物理触摸注入均需页面侧脚本），
        // 故此处显式开启；页面可达性由 scheme 白名单与上述沙箱设置共同约束。
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.loadWithOverviewMode = true
        wv.settings.useWideViewPort = true
        // ★ 双指缩放（用户反馈「弹出的悬浮页面不可以缩放」）：实时页面悬浮窗
        //   （LiveViewOverlay）/ 全屏接管（BrowserOverlay）/ in-app 浏览器共用
        //   引擎 WebView —— 此前未开启 zoom 支持（默认 setSupportZoom=false），
        //   用户无法捏合缩放页面（登录页小字/验证码看不清时刚需）。
        //   displayZoomControls=false：隐藏系统 +/- 浮动按钮，仅保留捏合手势
        //   （不遮挡页面内容，视觉与物理触摸注入的坐标换算互不干扰 ——
        //   缩放状态下 getBoundingClientRect 的坐标换算仍按视口比例进行）。
        wv.settings.setSupportZoom(true)
        wv.settings.builtInZoomControls = true
        wv.settings.displayZoomControls = false
        // v2.0.0：多窗口支持 —— 开启后 window.open / target=_blank 才会走到
        // onCreateWindow 回调（不开则外链静默失灵，见该回调 KDoc）；弹窗 URL
        // 仍经 scheme 白名单与 newTab 路径，无新增攻击面。
        wv.settings.setSupportMultipleWindows(true)
        wv.settings.allowFileAccess = false
        wv.settings.allowContentAccess = false
        wv.settings.allowFileAccessFromFileURLs = false
        wv.settings.allowUniversalAccessFromFileURLs = false
        // 安全加固（清单 11.1 / 11.2）：禁止 https 页加载 http 混合内容，防中间人注入
        wv.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // 安全加固：生产环境关闭 WebView 远程调试桥（防止 adb 注入与本地端口探测）
        WebView.setWebContentsDebuggingEnabled(false)
        // 反检测（#13 轻量版）：去除 UA 中的 "; wv" / "Version/4.0" WebView 标志，
        // 降低被 Cloudflare/Akamai 等反爬系统识别为机器人的概率。
        wv.settings.userAgentString = wv.settings.userAgentString
            ?.replace("; wv", "")
            ?.replace("Version/4.0 ", "")

        wv.webViewClient = object : WebViewClient() {
            // v1.3.0 修复 goBack/goForward 后状态残留：pageFinished 若不复位，
            // 新导航的 waitForPageFinished 会因旧页遗留的 true 立即「假就绪」。
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                tabs.values.firstOrNull { it.webView === view }?.let {
                    it.pageFinished = false
                    // 新导航重置 JS 上下文：网络监控钩子随之失效，等待重注入
                    it.netHooked = false
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                url?.let {
                    val tab = activeTab()
                    if (tab != null && tab.webView === view) {
                        tab.url = it
                        tab.pageFinished = true
                        history.addLast(it)
                        if (history.size > MAX_HISTORY) history.removeFirst()
                    }
                }
                // 反检测（#13）：每次页面加载完成注入隐身 JS，隐藏自动化痕迹。
                // v1.3.0 修 B10：导航会重置 JS 上下文，网络监控随之丢失（下次
                // snapshot 才补注入，监控存在盲区）—— 与 NETWORK_MONITOR_JS 合并
                // 注入（监控自带 __apexNetHooked 幂等守卫，重复注入无害）。
                view?.evaluateJavascript(BrowserScript.STEALTH_JS + "\n" + BrowserScript.NETWORK_MONITOR_JS, null)
                // v1.8.0 提速：记录注入态，snapshot 跳过重复注入（onPageStarted 复位）
                tabs.values.firstOrNull { it.webView === view }?.netHooked = true
            }

            // P0 #4：渲染进程崩溃恢复
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                val gone = detail?.didCrash() ?: true
                // v1.3.0：系统事件入 trace（可观测性接线）
                tracer.record(
                    "system.render_gone", "didCrash=$gone",
                    "渲染进程崩溃，已销毁重建 WebView", 0, view?.url, currentState.name,
                )
                // 重建：销毁崩溃实例并新建（Chromium 建议崩溃后重建，勿直接复用）
                tabs.values.filter { it.webView === view }.forEach { bad ->
                    bad.webView.destroy()
                    tabs.remove(bad.id)
                }
                if (activeTabId == 0 || !tabs.containsKey(activeTabId)) {
                    val id = newTabSync()
                    activeTabId = id
                }
                // 通知由调用方在 next snapshot/navigate 时感知；此处仅标记恢复
                Handler(Looper.getMainLooper()).post {
                    setState(BrowserSessionState.RECOVERING)
                    // 崩溃重建：活动 WebView 已换新实例，实时悬浮窗同步换挂
                    notifyActiveTabChanged()
                }
                return true // 已处理，不 crash 宿主
            }

            // 安全加固（清单 4.5.8 / 11.1 / 11.2 / V4）：仅允许 http/https 导航，
            // 拦截 file://、content://、javascript: 等危险 scheme，避免本地文件泄露与 UXSS。
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val u = url ?: return true
                val allowed = runCatching { android.net.Uri.parse(u) }
                    .getOrNull()?.scheme?.let { it == "http" || it == "https" } ?: false
                return if (allowed) false else true // 非白名单 scheme：自行吞掉，不导航
            }

            // 安全加固（清单 11.10 / V6）：SSL 错误绝不自动忽略，显式取消加载。
            // WebView 默认即对 SSL 错误取消，此处显式重写以保持可审计、可一致行为。
            override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                lastDialog = "ssl_error: 证书校验失败（${error?.primaryError}），已取消加载"
                // v1.3.0：系统事件入 trace（可观测性接线）
                tracer.record(
                    "system.ssl_blocked", "primaryError=${error?.primaryError}",
                    "SSL 证书校验失败，已取消加载（不自动信任）", 0, view?.url, currentState.name,
                )
                handler?.cancel() // 严禁 handler.proceed()
            }
        }

        // P0 #3：JS 弹窗处理（alert/confirm/prompt 阻塞 JS，必须接管）。
        // v2.0.0：处置决策收敛到 [dialogArbiter]（三模式 + respond + 超时兼底，
        // 独立文件维护；AUTO_CONFIRM 默认与 v1.x 行为零漂移）。
        wv.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(
                view: WebView?, url: String?, message: String?, result: JsResult?
            ): Boolean {
                lastDialog = "alert: ${message ?: ""}"
                return dialogArbiter.alert(message, result)
            }

            override fun onJsConfirm(
                view: WebView?, url: String?, message: String?, result: JsResult?
            ): Boolean {
                lastDialog = "confirm: ${message ?: ""}"
                return dialogArbiter.confirm(url, message, result)
            }

            override fun onJsPrompt(
                view: WebView?, url: String?, message: String?, defaultValue: String?,
                result: android.webkit.JsPromptResult?
            ): Boolean {
                lastDialog = "prompt: ${message ?: ""}"
                return dialogArbiter.prompt(url, message, defaultValue, result)
            }

            // v2.0.0：JS 控制台捕获（console.* 与未捕获异常都经此回调上报，
            // Chromium 把 Uncaught 错误以 ERROR 级 console 消息送达）。
            override fun onConsoleMessage(cm: android.webkit.ConsoleMessage?): Boolean {
                consoleCapture.record(cm?.level()?.name ?: "LOG", cm?.message(), cm?.sourceId(), cm?.lineNumber() ?: 0)
                return true
            }

            // v2.0.0：弹窗/target=_blank 支持 —— setSupportMultipleWindows(true)
            // 后 window.open 与 target=_blank 都走本回调（否则 target=_blank 链接
            // 在 WebView 里静默失灵，半个移动 Web 的外链都打不开）。桥接实现
            // 见同包 PopupBridge.kt（临时 WebView 接住弹窗 URL → newTab；
            // scheme 白名单同口径；非用户手势弹窗直接拦截）。GATE 1 预算纪律。
            override fun onCreateWindow(
                view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?
            ): Boolean = handlePopup(
                view, isDialog, isUserGesture, resultMsg,
                openInNewTab = { u -> mainScope.launch { newTab(u) } },
                trace = { tag, params, msg -> tracer.record(tag, params, msg, 0, view?.url, currentState.name) },
            )

            // v1.3.0 修复：标题此前从未被任何 client 更新（tab.title 只在快照时被动读取），
            // PageSnapshot.title 恒为空串，SPA 动态标题（document.title 变更）也反映不到
            // tabsSnapshot / 浮窗地址栏 / PostActionState.pageTitle。
            // 注：onReceivedTitle 是 WebChromeClient 回调（放在 WebViewClient 里
            // 会「overrides nothing」编译失败）。
            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) {
                    tabs.values.firstOrNull { it.webView === view }?.title = title
                }
            }

            // P0 #5（文件上传）：拦截系统文件选择器
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<android.net.Uri>>,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                pendingFileChooser?.onReceiveValue(null)
                pendingFileChooser = filePathCallback
                // 无法自动解析时进入人工接管（由 Agent 调用 browser_show 让人选文件）
                return true
            }

            // P2 #12：网页权限请求（摄像头/麦克风/地理）处理。
            // 敏感权限（摄像头/麦克风/地理）默认拒绝，避免未经用户确认的自动授权泄露隐私；
            // 进入人工接管模式，提示用户改用 browser_show 在真实页面自行授权。
            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                val resources = request.resources
                val isSensitive = resources.any {
                    it == PermissionRequest.RESOURCE_VIDEO_CAPTURE ||
                    it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                }
                if (isSensitive) {
                    lastDialog = "permission: 网页请求敏感权限(摄像头/麦克风/地理)，已默认拒绝并进入人工接管；" +
                        "如需授权请用 browser_show 在真实页面操作"
                    // 进入人工接管，由用户在真实页面上通过浏览器原生对话框授权
                    mainScope.launch { enterHandoffMode() }
                    request.deny() // 隐私最小化：不自动授予敏感权限
                } else {
                    request.deny()
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                // 地理位置默认拒绝
                callback?.invoke(origin, false, false)
            }
        }

        // P2 #14（文件下载）：通过系统 DownloadManager 下载，记录到最近下载列表供 Agent 查询
        val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                addRequestHeader("User-Agent", userAgent)
                // v1.3.0：转发会话 Cookie —— 登录态后的下载（导出报表/附件）常因
                // DownloadManager 无 Cookie 而 401/重定向到登录页。
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                setTitle(fileName)
                setDescription("Apex Browser 下载")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }
            runCatching { dm.enqueue(req) }.onSuccess { id ->
                lastDownload = DownloadRecord(fileName, url, id)
                lastDialog = "download: 已开始下载 $fileName"
                // v1.3.0：系统事件入 trace（可观测性接线）
                tracer.record(
                    "system.download_enqueued", "file=$fileName",
                    "下载已入队 id=$id", 0, url, currentState.name,
                )
            }.onFailure {
                lastDownload = null
                lastDialog = "download: 下载失败 ${it.message}"
            }
        }
        return wv
    }

    /** 最近一次下载记录（#14），供 [browser_download_list] 读取 */
    data class DownloadRecord(
        val fileName: String,
        val url: String,
        val downloadId: Long,
    )

    @Volatile var lastDownload: DownloadRecord? = null
        private set

    /** 最近一次 JS 弹窗文本，供 snapshot 回报注入 Agent 上下文 */
    @Volatile var lastDialog: String? = null
        private set

    /*
     * ── chrome 人用界面层的增量接口（P2，全部纯增量、默认行为零变化）──
     * 供 browser/chrome（浮窗完整浏览器 UI）读写引擎状态；
     * Agent 自动化路径（BrowserAgentTools）不受任何影响。
     */

    /** 人/Agent 裁决 JS 弹窗后回写文本（保持「snapshot 注入 Agent 上下文」语义，
     *  接管链式 WebChromeClient 后替代旧自动 confirm 内的 lastDialog 赋值）。 */
    fun reportDialogForContext(text: String?) {
        lastDialog = text
    }

    /** chrome 层下载入队后回写（保持 browser_download_list 工具可读）。 */
    fun reportDownload(fileName: String, url: String, dmId: Long) {
        lastDownload = DownloadRecord(fileName, url, dmId)
    }

    /** 标签结构只读快照（id, url, title），chrome 层 TabStrip/标签总览的数据源。 */
    fun tabsSnapshot(): List<Triple<Int, String, String>> =
        tabs.map { Triple(it.key, it.value.url, it.value.title) }

    /** 浏览历史尾部（地址栏 HISTORY 联想数据源；URL-only）。 */
    fun recentHistory(limit: Int): List<String> {
        val list = history.toList()
        return if (limit >= list.size) list else list.takeLast(limit)
    }

    /**
     * 清空会话内浏览历史（v1.9.0，兑现 v1.7.0 已知取舍的 TODO：「引擎
     * recentHistory 的内存 ArrayDeque 无清空 API」—— clearBrowsingData 的
     * histories scope 只清 WebView 原生历史，地址栏联想源是本队列）。
     */
    fun clearRecentHistory() {
        history.clear()
    }

    /**
     * 关闭全部标签（chrome 层「关闭全部」命令；逐个销毁与 closeTab 同路）。
     * 引擎全部标签被关后回到无活动页（activeTabId=0），下次 navigate 自动建页。
     */
    fun closeAllTabs() {
        tabs.keys.toList().forEach { closeTab(it) }
    }

    /**
     * 主线程同步建页（chrome UI 命令路径直调；与 suspend [newTab] 行为一致）。
     * 注意：与 newTab 相同，需在主线程调用。
     */
    fun newTabImmediate(url: String?): Int {
        val id = newTabSync()
        activeTabId = id
        // v1.4.0：活动 WebView 变化（含首建页）→ 实时页面悬浮窗换挂
        notifyActiveTabChanged()
        url?.let {
            // 带 URL 建页 = 浏览器被使用（网页搜索/自动化），驱动霓虹球按需出现
            markBrowserActiveFromHidden()
            loadUrlInternal(it)
        }
        return id
    }

    private fun newTabSync(): Int {
        val id = nextTabId++
        val wv = createWebView()
        tabs[id] = Tab(id, wv)
        return id
    }

    /** 重建当前激活 tab 的 WebView（P2 #15）：销毁旧实例、新建、恢复 URL */
    private fun rebuildActiveWebView() {
        val old = activeTab() ?: return
        val url = old.url
        old.webView.destroy()
        val wv = createWebView()
        tabs[old.id] = old.copy(webView = wv, pageFinished = false)
        if (url.isNotBlank()) wv.loadUrl(url)
        notifyActiveTabChanged()
    }

    /** 由 DI 构造器注入的 Application Context（避免在引擎内直接持 Activity） */
    private fun applicationContext() = appContext.applicationContext

    // ═════════ 状态机 API（显式握手） ═════════

    /** Agent 调用 browser_show 展开浮窗时触发：进入人工接管，锁定自动化工具 */
    suspend fun enterHandoffMode() = stateMutex.withLock {
        if (currentState == BrowserSessionState.RECOVERING) return@withLock
        setState(BrowserSessionState.WAITING_HUMAN)
    }

    /** 人类点击「我已完成操作」按钮时触发：交还 Agent，自动补一次快照对齐状态 */
    suspend fun completeHandoff() = stateMutex.withLock {
        if (currentState != BrowserSessionState.WAITING_HUMAN) return@withLock
        setState(BrowserSessionState.AGENT_DRIVING)
        // 接管完成后强制持久化 Cookie（P0 #6）
        flushCookies()
        // 交还瞬间自动补一次快照，结果由 Agent 下一次 snapshot 直接获得
    }

    /**
     * 结束浏览器会话：状态回落 [BrowserSessionState.HIDDEN]。
     *
     * 供「霓虹球长按关闭」等入口使用 —— 球只随浏览器被调用出现（宿主可视层自定），用户长按球即代表"这次浏览完了"：状态回到
     * HIDDEN 后球自动收起、BrowserOverlay（若在展开）自动收起。
     * RECOVERING 期间不介入（渲染进程正在重建，下一次 navigate 会重新驱动）。
     */
    suspend fun releaseBrowser() = stateMutex.withLock {
        if (currentState == BrowserSessionState.RECOVERING) return@withLock
        // v2.0.0：会话收尾清空 MANUAL 弹窗队列（挂起中的 confirm 全部确认，
        // 防「会话结束后 JsResult 永不回调」的页面挂起残留）。
        dialogArbiter.resolveAll()
        setState(BrowserSessionState.HIDDEN)
    }

    /**
     * 标记"浏览器已被使用"：HIDDEN → AGENT_DRIVING。
     *
     * 调用方须已在主线程。状态机此前只在人工接管握手时变更 —— 纯后台
     * navigate（如网页搜索）永远停留在 HIDDEN，可视层无从感知。现在任何
     * 导航/带 URL 建页都会把会话标记为 Agent 驾驶中，霓虹球据此**按需出现**
     * （不再是 App 一启动就常驻）。已是 AGENT_DRIVING / WAITING_HUMAN /
     * RECOVERING 时不动（保留人工接管锁与重建语义）。
     */
    private fun markBrowserActiveFromHidden() {
        if (currentState == BrowserSessionState.HIDDEN) {
            setState(BrowserSessionState.AGENT_DRIVING)
        }
    }

    /** 所有 AgentTool 执行前必须调用的守卫 */
    fun assertAgentControl() {
        if (currentState == BrowserSessionState.WAITING_HUMAN) {
            throw HandoffLockedException("人类正在接管浏览器，请等待人类点击「我已完成操作」后再执行自动化。")
        }
    }

    // ═════════ 标签页管理 ═════════

    suspend fun newTab(url: String? = null): Int = withContext(Dispatchers.Main) {
        newTabImmediate(url)
    }

    fun activeTab(): Tab? = tabs[activeTabId]

    fun switchTab(id: Int): Boolean {
        if (!tabs.containsKey(id)) return false
        activeTabId = id
        // Agent 驾驶中的换页不产生状态迁移，单独广播让实时悬浮窗换挂 WebView
        notifyActiveTabChanged()
        return true
    }

    fun closeTab(id: Int): Boolean {
        val tab = tabs.remove(id) ?: return false
        tab.webView.destroy()
        if (activeTabId == id) {
            activeTabId = tabs.keys.firstOrNull() ?: 0
            // 关掉的是活动页：活动 WebView 回落/变空，实时悬浮窗需同步换挂或清空
            notifyActiveTabChanged()
        }
        return true
    }

    fun listTabs(): List<Pair<Int, String>> = tabs.map { it.key to it.value.url }

    // ═════════ 导航 + 加载等待（P0 #1） ═════════

    /** 导航到 URL，支持 waitForSelector（元素等待）与超时兜底。
     *  @param onProgress 可选进度回调（百分比 0..100 + 阶段文案），用于把等待过程推给 UI。
     *         不传则与原行为完全一致（向后兼容既有调用方）。
     */
    suspend fun navigate(
        url: String,
        waitForSelector: String? = null,
        timeoutMs: Long = 15000,
        onProgress: ((percent: Int, phase: String) -> Unit)? = null,
    ): NavResult = tracedAction("browser_navigate", "url=$url wait=${waitForSelector ?: "-"}") {
        withContext(Dispatchers.Main) {
        val u = if (url.startsWith("http")) url else "https://$url"
        // 导航即"浏览器被使用"：HIDDEN → AGENT_DRIVING，霓虹球据此按需出现
        // （不是 App 启动就常驻；用户长按球或 releaseBrowser 后回到 HIDDEN 即收起）
        markBrowserActiveFromHidden()
        // P2 #15：长会话内存维护——导航次数超阈值时重建当前 WebView
        if (++navigationCount > MAX_NAVIGATIONS_BEFORE_REBUILD) {
            navigationCount = 0
            rebuildActiveWebView()
        }
        val tab = activeTab() ?: run { newTabSync().also { activeTabId = it } }.let { tabs[it]!! }
        onProgress?.invoke(10, "正在加载 $u")
        tab.pageFinished = false
        loadUrlInternal(u)
        // (1) 基础等待：onPageFinished
        val baseOk = waitForPageFinished(tab, timeoutMs, onProgress)
        // (2) 智能等待：元素出现
        val selOk = if (waitForSelector != null) {
            onProgress?.invoke(80, "等待元素 $waitForSelector 出现")
            waitForSelectorOnPage(tab.webView, waitForSelector, timeoutMs)
        } else true
        // (3) Cookie 持久化时机（P0 #6）
        flushCookies()
        onProgress?.invoke(100, if (baseOk) "页面加载完成" else "页面加载超时（已兜底返回当前状态）")
        NavResult(success = baseOk, selectorFound = selOk, timedOut = !baseOk)
        }
    }

    data class NavResult(val success: Boolean, val selectorFound: Boolean, val timedOut: Boolean)

    private fun loadUrlInternal(u: String) {
        activeTab()?.webView?.loadUrl(u)
    }

    private suspend fun waitForPageFinished(
        tab: Tab,
        timeoutMs: Long,
        onProgress: ((percent: Int, phase: String) -> Unit)? = null,
    ): Boolean {
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < timeoutMs) {
            if (tab.pageFinished) return true
            val elapsed = SystemClock.uptimeMillis() - start
            // 10→75 映射到加载等待阶段，留出余量给后续元素等待
            val percent = 10 + ((elapsed.toDouble() / timeoutMs) * 65).toInt().coerceIn(0, 65)
            onProgress?.invoke(percent, "等待页面加载完成…")
            delay(100)
        }
        // 超时兜底：仍返回当前状态（页面可能已在加载，只是未触发 finish）
        return tab.pageFinished
    }

    /**
     * 等选择器出现 —— 在 Kotlin 侧轮询，而非页面侧 Promise。
     *
     * 修复说明：旧路径用 `BrowserScript.waitForSelectorJs`（页面侧 `new Promise`），
     * 但 `evaluateJavascript` 不等待 Promise，回调恒为 `"null"`，
     * 于是 `selectorFound` 永远 false，`browser_navigate(wait_for=…)` 静默失效。
     */
    private suspend fun waitForSelectorOnPage(wv: WebView, selector: String, timeoutMs: Long): Boolean =
        waitForCondition("selector", selector, timeoutMs).matched

    // ═════════ 独立条件等待 + 页面类型推断（网页自动化完善）═════════

    /** [waitForCondition] 的结果。 */
    data class WaitOutcome(
        val matched: Boolean,
        val detail: String,
        val elapsedMs: Long
    )

    /**
     * 独立条件等待（`browser_wait_for` 工具的引擎面）。
     *
     * [BrowserEngine.navigate] 的 waitForSelector 只覆盖「导航后」窗口；
     * 点击/提交后的异步内容到达（SPA 局部刷新、搜索结果、登录跳转）没有
     * 等待手段——旧方案只能盲 sleep 或反复 snapshot 轮询。v1.3.0 模式从 3 种
     * 扩到 7 种：
     *  - `selector`：CSS 选择器出现（同步检查 + 轮询，不经 Promise——
     *    evaluateJavascript 不等待 Promise 完成，异步形态拿到的恒为 null）；
     *  - `text`：页面可见文本包含子串；
     *  - `url`：当前 URL 包含子串（跳转完成判定）——v1.3.0 起改注入
     *    [BrowserScript.CURRENT_URL_JS] 求值：SPA pushState/replaceState 即时
     *   反映到 location.href，而 native `webView.url` 只在导航提交时更新；
     *  - `element_gone`：选择器从 DOM 消失（弹窗/加载态收起判定，selector 取反）；
     *  - `text_gone`：文本从页面消失（text 取反，如「加载中…」消失）；
     *  - `network_idle`：在途请求数归零（[BrowserScript.networkIdleJs] 轮询，
     *    **连续 2 次为 0** 才算 idle——单次 0 可能只是两次请求的间隙）；
     *  - `selector_count`：选择器命中数满足谓词。value = "<选择器>" + 计数谓词：
     *    `">=10"` / `"<=5"` / `"==3"` / `">N"` / `"<N"`，或纯数字（要求与
     *    选择器间有空白，语义为 `>=`，「等到至少 N 个」是等待场景的主导用法；
     *    空白要求避免与 `.item2` 这类以数字结尾的选择器歧义）。
     *
     * 轮询 [pollMs]（默认 300ms）+ 总超时 [timeoutMs]（默认 10s，上限 60s
     * 防呆）；在 Main dispatcher 执行（WebView 约束），每次检查为一次
     * evaluateJavascript（毫秒级），不阻塞渲染。
     */
    suspend fun waitForCondition(
        mode: String,
        value: String,
        timeoutMs: Long = 10_000,
        pollMs: Long = 300
    ): WaitOutcome = withContext(Dispatchers.Main) {
        val tab = activeTab()
            ?: return@withContext WaitOutcome(false, "no active tab", 0)
        if (mode !in WAIT_MODES) {
            return@withContext WaitOutcome(false, "unknown mode '$mode' (use selector|text|url|element_gone|text_gone|network_idle|selector_count|dom_stable)", 0)
        }
        // selector_count 谓词只解析一次；解析失败立即返回可诊断错误而非静默超时
        val countPred = if (mode == "selector_count") parseCountPredicate(value) else null
        if (mode == "selector_count" && countPred == null) {
            return@withContext WaitOutcome(false, "invalid selector_count value '$value' (expect '<selector>>=10' / '<selector> 10')", 0)
        }
        val wv = tab.webView
        // 预算纪律（GATE 1，同 CountPredicate 先例）：轮询循环本体迁
        // WaitPredicates.kt（纯代码搬移；evaluateBoolean/evaluateJson 以 lambda
        // 注入，私有可见性不外泄）
        waitConditionLoop(
            wv = wv,
            mode = mode,
            value = value,
            countPred = countPred,
            timeoutMs = timeoutMs,
            pollMs = pollMs,
            evaluateBoolean = { view, js -> evaluateBoolean(view, js) },
            evaluateJson = { view, js -> evaluateJson(view, js) },
        )
    }

    /**
     * 页面类型推断（gap audit「缺失 H」的 Agent 框架层落点）。
     *
     * 一次 JS 采集页面形态信号（输入框/密码框/按钮/链接/正文/视频/列表项/
     * 搜索框/导航/文本量），再由 [PageClassifier] 按优先级分类，返回「类型 + 该类型的
     * 典型动作建议」——帮模型在 snapshot 全量元素前先建立页面心智模型，
     * 决定 focus 策略（表单页抓 FORM_FIELDS、文章页抓 CONTENT_SUMMARY）。
     *
     * 采集与分类已分离：JS 在 [BrowserScript.PAGE_TYPE_JS]，分类在 [PageClassifier]。
     * 结果类型随之从「引擎内的嵌套 data class」下沉为 [PageTypeInfo]（定义在
     * `:browser-core`），使分类策略可在纯 JVM 单测中验证；宿主按推断类型使用即可，
     * 无需改动调用点。
     */
    suspend fun pageType(): PageTypeInfo = withContext(Dispatchers.Main) {
        val tab = activeTab()
            ?: return@withContext PageTypeInfo("unknown", "无激活标签页", emptyMap())
        val raw = runCatching { evaluateJson(tab.webView, BrowserScript.PAGE_TYPE_JS) }
            .getOrDefault("null")
        PageClassifier.fromJson(raw)
    }

    suspend fun goBack(): Boolean = withContext(Dispatchers.Main) {
        val wv = activeTab()?.webView ?: return@withContext false
        if (wv.canGoBack()) { wv.goBack(); return@withContext true }
        false
    }

    suspend fun goForward(): Boolean = withContext(Dispatchers.Main) {
        val wv = activeTab()?.webView ?: return@withContext false
        if (wv.canGoForward()) { wv.goForward(); return@withContext true }
        false
    }

    /**
     * 重载当前页（v1.3.0）：reload + [waitForPageFinished] 语义，返回超时窗口内
     * 是否完成加载。此前宿主只能裸调 `webView.reload()`，无等待、无就绪信号。
     */
    suspend fun reload(timeoutMs: Long = 15_000): Boolean = withContext(Dispatchers.Main) {
        val tab = activeTab() ?: return@withContext false
        tab.pageFinished = false
        tab.webView.reload()
        waitForPageFinished(tab, timeoutMs)
    }

    /**
     * 停止当前页加载（v1.3.0）：浏览器「停止」按钮 / Agent 逃生舱（长加载页先 stop
     * 再 snapshot 拿已到内容）。须在主线程调用（与 [performMaintenance]/[destroy] 同约定）。
     */
    fun stopLoading() {
        activeTab()?.webView?.stopLoading()
    }

    /**
     * 页内查找（v1.3.0）：`findAllAsync` + FindListener 桥接，返回命中数。
     *
     * 取代裸调 `webView.findAllAsync`（无返回值）与 deprecated 的同步 `findAll`。
     * 注意 WebView 的 FindListener 是**单槽**：本调用会替换 chrome 层
     * ChainFindListener 的注册——Agent 侧查找计数优先，chrome 页内查找的计数
     * 回填依赖其下次接线时重设监听器（已知取舍）。
     */
    suspend fun findInPage(query: String): Int = withContext(Dispatchers.Main) {
        val wv = activeTab()?.webView ?: return@withContext 0
        runCatching {
            kotlinx.coroutines.withTimeout(8000) {
                suspendCancellableCoroutine { cont ->
                    wv.setFindListener { _, numberOfMatches, isDoneCounting ->
                        if (isDoneCounting && cont.isActive) cont.resume(numberOfMatches)
                    }
                    wv.findAllAsync(query)
                }
            }
        }.getOrDefault(0)
    }

    // ═════════ 快照 ═════════

    /**
     * 页面快照。
     * @param tokenBudget 字符预算（[DomParser.buildSummary] 使用）
     * @param strategy 剪枝策略（#19/#20），默认 [INTERACTIVE_ONLY] 即原有行为
     * @param allowA11yFallback 主快照交互元素过少（<5）时是否降级用 A11y 补充源（#17）
     */
    suspend fun snapshot(
        tokenBudget: Int = 1600,
        strategy: DomParser.SnapshotStrategy = DomParser.SnapshotStrategy.INTERACTIVE_ONLY,
        allowA11yFallback: Boolean = true,
    ): PageSnapshot = tracedAction("browser_snapshot", "budget=$tokenBudget strategy=$strategy") {
        withContext(Dispatchers.Main) {
        val tab = activeTab() ?: return@withContext emptySnapshot()
        // P1 #7：快照超时视为可重试（主线程偶发卡顿），熔断保护
        val snap = withRetry(retryPolicy, breaker) {
            // 注入网络监控（#18）。v1.8.0 提速：onPageFinished 已随每次导航注入
            // （netHooked 置位），此处仅在「未注入」时补一次（CSP 拦截 onPageFinished
            // 注入、或建页早于钩子引入的存量会话），不再每次快照重复求值。
            if (!tab.netHooked) {
                runCatching { tab.webView.evaluateJavascript(BrowserScript.NETWORK_MONITOR_JS, null) }
                tab.netHooked = true
            }
            var wrapped = evaluateSnapshotJs(tab.webView, strategy)
            val raw = runCatching {
                json.parseToJsonElement(wrapped).jsonPrimitive.content
            }.getOrDefault(wrapped)
            val wv = tab.webView
            var snap = DomParser.parse(
                rawJson = raw,
                url = wv.url ?: tab.url,
                title = tab.title,
                scrollY = wv.scrollY,
                scrollHeight = (wv.contentHeight * wv.resources.displayMetrics.density).toInt(),
                viewportHeight = wv.height,
                tokenBudget = tokenBudget,
                strategy = strategy,
            )
            // #17 降级：主快照元素过少，说明可能被 CSP 拦截或页面极简，用 A11y 源补充
            if (allowA11yFallback && snap.interactiveElements.size < 5) {
                val a11yRaw = runCatching {
                    json.parseToJsonElement(evaluateA11yJs(wv)).jsonPrimitive.content
                }.getOrDefault("[]")
                val a11ySnap = DomParser.parse(
                    rawJson = a11yRaw,
                    url = wv.url ?: tab.url,
                    title = tab.title,
                    scrollY = wv.scrollY,
                    scrollHeight = (wv.contentHeight * wv.resources.displayMetrics.density).toInt(),
                    viewportHeight = wv.height,
                    tokenBudget = tokenBudget,
                    strategy = DomParser.SnapshotStrategy.INTERACTIVE_ONLY,
                )
                if (a11ySnap.interactiveElements.size > snap.interactiveElements.size) {
                    snap = a11ySnap
                }
            }
            refToBid.clear()
            snap.interactiveElements.forEach { refToBid[it.ref] = it.bid }
            // v1.1.0 自愈索引同步（tag+text 供失配时模糊重定位）
            snap.interactiveElements.forEach {
                if (it.ref.isNotBlank()) refDescriptorIndex[it.ref] = it.tag to it.text
            }
            snap
        }
        // v1.3.0 快照融合：时间戳 + 弹窗通知（消费即清）。lastDialog 非空时写入
        // 本次快照并随即清空——模型在下一次 snapshot 必然看到这则通知，且不会
        // 重复收到同一条；timestampMs 供消费方判快照新鲜度。
        // v2.0.0：MANUAL 弹窗模式下挂起中的弹窗队列也入通知（否则模型对
        // 「页面为何挂起」不可见）。
        val notice = lastDialog ?: dialogArbiter.pendingNotice()
        if (lastDialog != null) lastDialog = null
        snap.copy(timestampMs = System.currentTimeMillis(), dialogNotice = notice)
        }
    }

    private suspend fun evaluateSnapshotJs(wv: WebView, strategy: DomParser.SnapshotStrategy): String =
        try {
            kotlinx.coroutines.withTimeout(8000) {
                suspendCancellableCoroutine { cont ->
                    wv.evaluateJavascript(BrowserScript.snapshotJs(strategy)) { result ->
                        cont.resume(result ?: "[]")
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            // v1.3.0 超时可重试修复：withTimeout 抛的 TimeoutCancellationException 属取消
            // 异常族，不转译会被 withRetry 归入「不可重试」直接上抛——而本函数 KDoc
            // 一直承诺「快照超时视为可重试（熔断保护）」。转成 j.u.c.TimeoutException
            // （在 RetryPolicy.retryableExceptions 集合内）兑现承诺。
            // 防误伤：外层协程已被取消/外层 withTimeout 已触发时，这不是本块超时，
            // 必须原样上抛（转译会破坏外层取消语义）。
            if (!currentCoroutineContext().isActive) throw e
            throw java.util.concurrent.TimeoutException("快照求值超时（8000ms 内 WebView 未回调，可能主线程卡顿）")
        }

    private suspend fun evaluateA11yJs(wv: WebView): String = kotlinx.coroutines.withTimeout(8000) {
        suspendCancellableCoroutine { cont ->
            wv.evaluateJavascript(BrowserScript.A11Y_FALLBACK_JS) { result ->
                cont.resume(result ?: "[]")
            }
        }
    }

    /** 读取已挂载的网络监控日志（#18），返回最近 N 条 fetch/xhr 记录；v1.1.0 支持按 URL 子串过滤 */
    suspend fun networkLog(
        limit: Int = 50,
        urlContains: String? = null,
    ): List<Map<String, Any?>> = withContext(Dispatchers.Main) {
        val tab = activeTab() ?: return@withContext emptyList()
        runCatching {
            val jsonStr = kotlinx.coroutines.withTimeout(5000) {
                suspendCancellableCoroutine<String> { cont ->
                    tab.webView.evaluateJavascript(
                        BrowserScript.networkLogJs(limit)
                    ) { cont.resume(unwrapWebViewJson(it ?: "[]")) }
                }
            }
            val all = json.parseToJsonElement(jsonStr).jsonArray.map { it.jsonObject.toMap() }
            if (urlContains.isNullOrBlank()) all
            else all.filter { (it["url"]?.toString() ?: "").contains(urlContains, ignoreCase = true) }
        }.getOrDefault(emptyList())
    }

    // ═════════ 点击：物理触摸注入（P0 主线 #2 + 创新二） ═════════

    /**
     * 物理触摸注入点击：DOM(ref) 定位 → WebView 屏幕坐标 → dispatchTouchEvent。
     * 返回 [PostActionState] 供动作后验证(#2)。
     *
     * v1.1.0 三重错误率削减：
     * 1. **密度换算修复**：旧实现把 getBoundingClientRect 的 CSS 像素直接当
     *    视图物理像素派发——density>1 的设备上点击点系统性偏向左上（误点主因）。
     *    现按「视图宽 / CSS 视口宽」实测缩放系数后换算（同时正确处理页面缩放）；
     * 2. **先滚动后点击**：scrollIntoView(block:center) 免除折叠线以下元素
     *    坐标越界；命中测试（elementFromPoint）发现遮挡时下移重试一次，
     *    仍被遮则明确报「被 XX 遮挡」而非静默误点；
     * 3. **模糊自愈**：ref 失配时用快照缓存的 tag+文本模糊重定位（重打原 ref），
     *    SPA 局部刷新不再直接失败。
     */
    suspend fun clickElement(ref: String): PostActionState = tracedAction("browser_click", "ref=$ref") {
        withContext(Dispatchers.Main) {
        val wv = activeTab()?.webView ?: return@withContext PostActionState.failed("无激活标签页")
        // P1 #7：重试+熔断；元素找不到视为可重试瞬态（可能页面未渲染完）
        withRetry(retryPolicy, breaker) {
            val before = readQuickProbe(wv)
            val target = resolveClickTarget(wv, ref)
                ?: throw ElementNotFoundException(
                    "找不到 ref=$ref 对应元素（可能页面未渲染完成；可先 snapshot 刷新索引后重试）"
                )
            // 构造真实触摸事件，DOWN~UP 间 30~80ms 随机延迟模拟人类按压
            val downTime = SystemClock.uptimeMillis()
            val hold = (30L..80L).random()
            val x = target.first
            val y = target.second
            val down = android.view.MotionEvent.obtain(downTime, downTime, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
            val up = android.view.MotionEvent.obtain(downTime, downTime + hold, android.view.MotionEvent.ACTION_UP, x, y, 0)
            wv.dispatchTouchEvent(down)
            wv.dispatchTouchEvent(up)
            down.recycle(); up.recycle()
            // v1.8.0 提速：固定 300~800ms 随机等待 → 探针轮询早退（典型点击
            // ~140ms 即返回；无变化动作以 600ms 封顶，不劣于旧随机上界）
            awaitActionSettle(wv, before, minMs = 120, maxMs = 600)
        }
        }
    }

    /**
     * 解析点击目标：ref 查询 → 模糊自愈 → 滚动到中央 → CSS→物理换算 → 遮挡检测。
     * 返回视图像素坐标；元素不可用时 null。
     */
    private suspend fun resolveClickTarget(wv: WebView, ref: String): Pair<Float, Float>? {
        var css = queryCssRect(wv, ref)
        if (css == null) {
            css = fuzzyRelocate(wv, ref)
            if (css == null) return null
        }
        // 遮挡检测：中心点被无关元素拦截 → 下移重试一次（粘性顶栏场景）
        val occluded = readElementAtPoint(wv, css.centerX, css.centerY, ref)
        if (occluded != null && !occluded) {
            wv.evaluateJavascript("window.scrollBy(0, 80); true;", null)
            delay(250)
            css = queryCssRect(wv, ref) ?: return null
            val stillOccluded = readElementAtPoint(wv, css.centerX, css.centerY, ref)
            if (stillOccluded != null && !stillOccluded) {
                throw ElementNotFoundException("元素 ref=$ref 被粘性元素遮挡，无法安全点击（可尝试先滚动页面）")
            }
        }
        // CSS → 视图物理像素：实测缩放系数（同时覆盖 density 与页面缩放）
        val scale = cssScaleFactor(wv, css.vw)
        val px = (css.centerX * scale).coerceIn(6f, (wv.width - 6).toFloat().coerceAtLeast(6f))
        val py = (css.centerY * scale).coerceIn(6f, (wv.height - 6).toFloat().coerceAtLeast(6f))
        return px to py
    }

    /** 快照矩形（CSS 视口坐标 + 视口宽高）。 */
    private class CssRect(val centerX: Float, val centerY: Float, val vw: Float, val vh: Float)

    private suspend fun queryCssRect(wv: WebView, ref: String): CssRect? {
        val jsonStr = evaluateJson(wv, BrowserScript.scrollIntoViewAndRectJs(ref))
        return parseRectJson(jsonStr)
    }

    private fun parseRectJson(jsonStr: String): CssRect? = runCatching {
        val obj = json.parseToJsonElement(jsonStr).jsonObject
        val x = obj["x"]?.jsonPrimitive?.content?.toFloatOrNull() ?: return null
        val y = obj["y"]?.jsonPrimitive?.content?.toFloatOrNull() ?: return null
        val vw = obj["vw"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f
        val vh = obj["vh"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f
        CssRect(x, y, vw, vh)
    }.getOrNull()

    /**
     * CSS → 物理像素缩放系数：视图宽 / CSS 视口宽（实测，免 density 与
     * 页面缩放的双重猜测；视口宽拿不到时退回 density）。
     */
    private fun cssScaleFactor(wv: WebView, cssViewportWidth: Float): Float {
        if (cssViewportWidth > 0f && wv.width > 0) return wv.width.toFloat() / cssViewportWidth
        return wv.resources.displayMetrics.density
    }

    /** 命中测试：中心点是否落在目标元素（或其后代）上；无法判定时 null（不阻断）。 */
    private suspend fun readElementAtPoint(wv: WebView, cssX: Float, cssY: Float, ref: String): Boolean? {
        val jsonStr = runCatching { evaluateJson(wv, BrowserScript.elementAtPointJs(cssX, cssY, ref)) }.getOrNull() ?: return null
        return runCatching {
            val obj = json.parseToJsonElement(jsonStr).jsonObject
            if (obj["hit"]?.jsonPrimitive?.content == "true") {
                obj["isTargetOrChild"]?.jsonPrimitive?.content == "true"
            } else null
        }.getOrNull()
    }

    /**
     * 模糊自愈：ref 失配时用快照缓存的 tag+文本模糊重定位，命中后**重打原 ref**。
     * 返回新矩形；无缓存或未命中返回 null。
     */
    private suspend fun fuzzyRelocate(wv: WebView, ref: String): CssRect? {
        val (tag, text) = refDescriptorIndex[ref] ?: return null
        if (text.isBlank()) return null
        val jsonStr = runCatching {
            evaluateJson(wv, BrowserScript.locateByFuzzyJs(tag.ifBlank { "*" }, text, ref))
        }.getOrNull() ?: return null
        val rect = parseRectJson(jsonStr) ?: return null
        return rect
    }

    // ═════════ 输入 / 选择 / 切换（P0 #5 动作空间补全） ═════════

    /**
     * 文本输入（v1.1.0 重写）：React/Vue 安全写值 + contenteditable + 多行安全
     * + 模糊自愈 + 可选回车提交。
     *
     * 旧实现三重缺陷：`el.value = x` 直赋被受控组件弹回；换行未转义直接
     * 撕裂 JS 字符串（多行输入必炸）；找不到元素时报错但 SPA 局部刷新后
     * 元素其实还在（哈希变了）。详见 [BrowserScript.setNativeValueJs]。
     */
    suspend fun inputText(
        ref: String,
        text: String,
        append: Boolean = false,
        pressEnter: Boolean = false,
    ): PostActionState = tracedAction("browser_input", "ref=$ref text=${text.take(40)} append=$append enter=$pressEnter") {
        withContext(Dispatchers.Main) {
        val wv = activeTab()?.webView ?: return@withContext PostActionState.failed("无激活标签页")
        withRetry(retryPolicy, breaker) {
            val before = readQuickProbe(wv)
            val resultJson = evaluateJson(wv, BrowserScript.setNativeValueJs(ref, text, append))
            val parsed = runCatching {
                json.parseToJsonElement(resultJson).jsonObject
            }.getOrNull()
            when (parsed?.get("ok")?.jsonPrimitive?.content) {
                "true" -> Unit
                "false" -> {
                    val reason = parsed["reason"]?.jsonPrimitive?.content ?: ""
                    if (reason == "not_found") {
                        // 模糊自愈：SPA 局部刷新后哈希失配，按缓存 tag+文本重定位
                        if (fuzzyRelocate(wv, ref) == null) {
                            throw ElementNotFoundException("找不到输入框 ref=$ref（可先 snapshot 刷新索引后重试）")
                        }
                        val retryJson = evaluateJson(wv, BrowserScript.setNativeValueJs(ref, text, append))
                        val retryParsed = runCatching {
                            json.parseToJsonElement(retryJson).jsonObject
                        }.getOrNull()
                        if (retryParsed?.get("ok")?.jsonPrimitive?.content != "true") {
                            throw ElementNotFoundException("输入失败：重定位后仍无法写入 ref=$ref")
                        }
                    } else {
                        // v1.3.0：参数与页面现实不匹配，重试永远无效——永久性语义错误，
                        // 不烧退避、不计熔断
                        throw PermanentActionException("ref=$ref 不是可输入元素（reason=$reason）")
                    }
                }
                // v1.3.0：回传非预期形态无法自愈（脚本被改形/中断），同样永久失败
                else -> throw PermanentActionException("输入结果解析失败 ref=$ref（页面侧回传非预期形态）")
            }
            if (pressEnter) {
                evaluateBoolean(wv, BrowserScript.pressKeyJs("enter"))
                // v1.8.0 提速：回车常触发导航/搜索——探针早退等 URL 变化（原固定 400ms）
                awaitActionSettle(wv, before, minMs = 150, maxMs = 600)
            } else {
                // 纯输入：搜索建议等异步 UI 常在输入后出现（interactiveCount 变化）
                awaitActionSettle(wv, before, minMs = 80, maxMs = 250)
            }
        }
        }
    }

    /**
     * <select> 选择：按 value 或可见文本（v1.1.0：模糊自愈 + 前后 diff）。
     *
     * v1.3.0 错误语义拆分：元素在而选项不匹配 = [PermanentActionException]
     * （重试同一 value 永远失败，且会烧退避 + 误开熔断）；元素不在 =
     * [ElementNotFoundException]（页面可能未渲染完，保持可重试）。
     */
    suspend fun selectOption(ref: String, value: String, byText: Boolean = false): PostActionState =
        tracedAction("browser_select", "ref=$ref value=$value byText=$byText") {
            withContext(Dispatchers.Main) {
            val wv = activeTab()?.webView ?: return@withContext PostActionState.failed("无激活标签页")
            withRetry(retryPolicy, breaker) {
                val before = readQuickProbe(wv)
                var ok = evaluateBoolean(wv, BrowserScript.selectJs(ref, value, byText))
                if (!ok && fuzzyRelocate(wv, ref) != null) {
                    ok = evaluateBoolean(wv, BrowserScript.selectJs(ref, value, byText))
                }
                if (ok) {
                    // v1.8.0 提速：联动下拉/级联表单异步刷新（原固定 200ms）
                    awaitActionSettle(wv, before, minMs = 80, maxMs = 400)
                } else if (queryCssRect(wv, ref) != null) {
                    // 元素在 DOM 里，selectJs 却未命中选项 → 选项不匹配（永久错误）
                    throw PermanentActionException("select 选项不匹配：ref=$ref 无 value=$value（byText=$byText）对应的选项")
                } else {
                    throw ElementNotFoundException("找不到 select ref=$ref（可先 snapshot 刷新索引后重试）")
                }
            }
            }
        }

    /** checkbox / radio 切换：物理触摸点击（交互模式与文本输入不同） */
    suspend fun toggle(ref: String): PostActionState = clickElement(ref)

    // ═════════ 文件上传（P0 #5 / #14 基础） ═════════

    /** 由 Agent 指定本地文件路径完成上传；无挂起回调时返回 false（需人工接管） */
    // P0 fix（生命周期竞态）：本函数由 Agent 工具在 Dispatchers.IO 上调用（ToolRegistry
    // flowOn(Dispatchers.IO)），而 onShowFileChooser 在主线程写入 pendingFileChooser，
    // 且 WebView 的 ValueCallback 契约要求必须在主线程回调 onReceiveValue —— 旧实现在
    // IO 线程读-置空-回调，既存在数据竞争，也可能触发 Chromium 线程断言 native 崩溃。
    // 现收敛到主线程执行，与 onShowFileChooser 同线程串行化，竞态消失。
    suspend fun respondFileChooser(uri: android.net.Uri): Boolean = withContext(Dispatchers.Main) {
        val cb = pendingFileChooser ?: return@withContext false
        pendingFileChooser = null
        runCatching { cb.onReceiveValue(arrayOf(uri)) }.isSuccess
    }

    // ═════════ 滚动（含无限滚动检测，P0 #10 增强） ═════════

    suspend fun scroll(
        deltaY: Int,
        waitForNewContent: Boolean = false,
        maxWaitMs: Long = 3000,
        onProgress: ((percent: Int, phase: String) -> Unit)? = null,
    ): ScrollResult = tracedAction("browser_scroll", "deltaY=$deltaY waitNew=$waitForNewContent") {
        withContext(Dispatchers.Main) {
        val wv = activeTab()?.webView ?: return@withContext ScrollResult(scrolled = false)
        onProgress?.invoke(10, "已滚动 ${if (deltaY >= 0) "+" else ""}$deltaY px${if (waitForNewContent) "，等待新内容…" else ""}")
        val before = probePage(wv).newElementsCount
        wv.evaluateJavascript(BrowserScript.scrollByJs(deltaY), null)
        if (waitForNewContent) {
            val start = SystemClock.uptimeMillis()
            var max = before
            while (SystemClock.uptimeMillis() - start < maxWaitMs) {
                delay(300)
                val now = probePage(wv).newElementsCount
                max = maxOf(max, now)
                val elapsed = SystemClock.uptimeMillis() - start
                val percent = 20 + ((elapsed.toDouble() / maxWaitMs) * 70).toInt().coerceIn(0, 70)
                onProgress?.invoke(percent, "等待新内容加载…")
                if (now > before) break
            }
            onProgress?.invoke(100, "新内容检测完成（新增 ${max - before} 个元素）")
            ScrollResult(scrolled = true, newElementsDetected = max - before)
        } else {
            onProgress?.invoke(100, "滚动完成")
            ScrollResult(scrolled = true)
        }
        }
    }

    data class ScrollResult(val scrolled: Boolean, val newElementsDetected: Int = 0)

    // ═════════ v1.8.0 滚动到页面边界 ═════════
    // scrollToBoundary 见同包 ScrollBoundary.kt 扩展（GATE 1 文件预算）。

    // ═════════ 截图（视口，P0 #6 路线图） ═════════

    suspend fun screenshot(
        onProgress: ((percent: Int, phase: String) -> Unit)? = null,
    ): ByteArray? = withContext(Dispatchers.Main) {
        val wv = activeTab()?.webView ?: return@withContext null
        onProgress?.invoke(50, "正在渲染视口截图…")
        // P1-4（6-c）：后台（未 attach/layout）WebView 尚未布局时 width/height 均为 0，
        // Bitmap.createBitmap(0,0) 必抛 IllegalArgumentException。先手动 measure/layout
        // 撑起内容尺寸（宽 1080 基准 + 页面内容比推高度，4096 封顶）；仍失败则抛带
        // 明确信息的异常（SafeAgentTool 兜底转错误串，而非晦涩的 native 崩溃）。
        if (wv.width <= 0 || wv.height <= 0) {
            val ratio = if (wv.contentHeight > 0) wv.contentHeight.toFloat() / wv.width.coerceAtLeast(1) else 1.5f
            val safeHeight = Math.min(4096, (1080f * ratio).toInt()).coerceAtLeast(1)
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(safeHeight, View.MeasureSpec.AT_MOST)
            )
            wv.layout(0, 0, wv.measuredWidth, wv.measuredHeight)
        }
        check(wv.width > 0 && wv.height > 0) {
            "无法截图：WebView 尺寸为 0（页面尚未完成布局，请先 browser_show 展开浮窗或稍后重试）"
        }
        val bmp = Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        wv.draw(canvas)
        val stream = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 90, stream)
        bmp.recycle()
        onProgress?.invoke(100, "截图完成")
        stream.toByteArray()
    }

    // ═════════ Cookie 持久化（P0 #6） ═════════

    /** 强制把内存 Cookie 刷盘，避免应用被杀后登录态丢失 */
    fun flushCookies() {
        runCatching { CookieManager.getInstance().flush() }
    }

    // ═════════ v1.1.0 高级动作空间（键鼠 / 抽取 / 逃生舱） ═════════

    /**
     * 键盘事件注入（v1.1.0）：对 activeElement 派发 keydown/keypress/keyup。
     *
     * 支持键名：enter / tab / escape / backspace / delete / arrowup /
     * arrowdown / arrowleft / arrowright / pageup / pagedown / home / end /
     * space，或任意单字符。Enter 携带表单隐式提交语义（requestSubmit）。
     */
    suspend fun pressKey(key: String): PostActionState = withContext(Dispatchers.Main) {
        val wv = activeTab()?.webView ?: return@withContext PostActionState.failed("无激活标签页")
        withRetry(retryPolicy, breaker) {
            val before = readQuickProbe(wv)
            val ok = evaluateBoolean(wv, BrowserScript.pressKeyJs(key))
            if (ok) awaitActionSettle(wv, before, minMs = 100, maxMs = 500)
            else PostActionState.failed("按键 $key 派发失败")
        }
    }

    /** 悬停（v1.1.0）：mouseover/mouseenter/mousemove 事件序列，驱动下拉菜单与 :hover 样式。 */
    suspend fun hover(ref: String): PostActionState = withContext(Dispatchers.Main) {
        val wv = activeTab()?.webView ?: return@withContext PostActionState.failed("无激活标签页")
        withRetry(retryPolicy, breaker) {
            var ok = evaluateBoolean(wv, BrowserScript.hoverJs(ref))
            if (!ok && fuzzyRelocate(wv, ref) != null) {
                ok = evaluateBoolean(wv, BrowserScript.hoverJs(ref))
            }
            if (ok) {
                // v1.8.0 提速：悬停菜单/tooltip 异步展开（原固定 250ms）
                awaitActionSettle(wv, readQuickProbe(wv), minMs = 100, maxMs = 400)
            } else throw ElementNotFoundException("找不到悬停目标 ref=$ref")
        }
    }

    /**
     * 结构化内容抽取（v1.1.0）：正文 / 表格 / 链接 / 元信息四模式。
     *
     * 返回 JSON 文本（article: {title,url,wordCount,text}；tables: 数组；
     * links: 数组；meta: 对象）——Agent 拿到即可直接消费，无需二次 snapshot
     * 再自己拼。失败返回 null。
     */
    suspend fun extractContent(
        mode: BrowserScript.ExtractMode = BrowserScript.ExtractMode.ARTICLE,
    ): String? = withContext(Dispatchers.Main) {
        val wv = activeTab()?.webView ?: return@withContext null
        runCatching {
            val raw = kotlinx.coroutines.withTimeout(8000) {
                suspendCancellableCoroutine<String> { cont ->
                    wv.evaluateJavascript(BrowserScript.extractContentJs(mode.value)) { cont.resume(it ?: "null") }
                }
            }
            // evaluateJavascript 返回 JSON 字符串字面量（带引号），解一层
            json.parseToJsonElement(raw).jsonPrimitive.content
        }.getOrNull()
    }

    /**
     * 原生 JS 逃生舱（v1.1.0）：执行任意脚本并返回结果的字符串形式。
     *
     * 供 Agent 处理本库动作空间覆盖不到的长尾页面逻辑；与 [extractContent]
     * 互补。脚本异常/超时返回 null。
     */
    suspend fun executeJavaScript(script: String, timeoutMs: Long = 8000): String? =
        withContext(Dispatchers.Main) {
            val wv = activeTab()?.webView ?: return@withContext null
            runCatching {
                kotlinx.coroutines.withTimeout(timeoutMs) {
                    suspendCancellableCoroutine<String> { cont ->
                        wv.evaluateJavascript(script) { cont.resume(it ?: "null") }
                    }
                }
            }.getOrNull()
        }

    /**
     * 读取 Cookie（v1.1.0）：url 为空取当前页。返回 "k1=v1; k2=v2" 形式，
     * 供 Agent 做登录态诊断或跨标签验证。
     */
    suspend fun getCookies(url: String? = null): String? = withContext(Dispatchers.Main) {
        val target = url?.takeIf { it.isNotBlank() }
            ?: activeTab()?.webView?.url
            ?: return@withContext null
        runCatching { CookieManager.getInstance().getCookie(target) }.getOrNull()
    }

    /**
     * 按文本模糊查找元素（v1.1.0 Agent 侧 API）：返回匹配元素的
     * ref/tag/text 矩形列表。Agent 在 ref 失配且快照刷新也无效时，
     * 可用它主动按可见文本重新锚定（与引擎内建的模糊自愈同源）。
     *
     * v1.3.0（B2 安全债清偿）：内联 JS 迁移到 [BrowserScript.locateJs] ——
     * 旧内联版手写转义只覆盖引号，换行 / U+2028 / 控制字符全部裸拼，
     * 多行搜索词直接撕裂整段脚本；现与全库统一走 JsLiteral.string。
     */
    suspend fun locateElements(
        textContains: String,
        tag: String = "*",
        limit: Int = 10,
    ): List<Map<String, String>> = withContext(Dispatchers.Main) {
        val wv = activeTab()?.webView ?: return@withContext emptyList()
        runCatching {
            val raw = executeJavaScript(BrowserScript.locateJs(textContains, tag, limit))
                ?: return@withContext emptyList()
            val inner = json.parseToJsonElement(raw).jsonPrimitive.content
            json.parseToJsonElement(inner).jsonArray.map { el ->
                val obj = el.jsonObject
                buildMap {
                    obj.forEach { (k, v) -> put(k, v.jsonPrimitive.content) }
                }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 触摸拖拽（v1.1.0）：从 fromRef 元素中心拖到 toRef 元素中心。
     *
     * DOWN → 线性插值 MOVE（[steps] 步，每步 ~16ms）→ UP，
     * 驱动滑块、排序、拖放等交互；坐标换算与点击同源（实测缩放系数）。
     */
    suspend fun drag(fromRef: String, toRef: String, steps: Int = 14): PostActionState =
        withContext(Dispatchers.Main) {
            val wv = activeTab()?.webView ?: return@withContext PostActionState.failed("无激活标签页")
            withRetry(retryPolicy, breaker) {
                val before = readQuickProbe(wv)
                val from = queryCssRect(wv, fromRef) ?: fuzzyRelocate(wv, fromRef)
                    ?: throw ElementNotFoundException("找不到拖拽起点 ref=$fromRef")
                val to = queryCssRect(wv, toRef) ?: fuzzyRelocate(wv, toRef)
                    ?: throw ElementNotFoundException("找不到拖拽终点 ref=$toRef")
                val scale = cssScaleFactor(wv, from.vw)
                val x0 = from.centerX * scale
                val y0 = from.centerY * scale
                val x1 = (to.centerX * scale).coerceIn(6f, (wv.width - 6).toFloat().coerceAtLeast(6f))
                val y1 = (to.centerY * scale).coerceIn(6f, (wv.height - 6).toFloat().coerceAtLeast(6f))
                val n = steps.coerceIn(4, 40)
                val downTime = SystemClock.uptimeMillis()
                var last = android.view.MotionEvent.obtain(downTime, downTime, android.view.MotionEvent.ACTION_DOWN, x0, y0, 0)
                wv.dispatchTouchEvent(last)
                for (i in 1 until n) {
                    val alpha = i.toFloat() / n
                    val mx = x0 + (x1 - x0) * alpha
                    val my = y0 + (y1 - y0) * alpha
                    val move = android.view.MotionEvent.obtain(downTime, downTime + i * 16L, android.view.MotionEvent.ACTION_MOVE, mx, my, 0)
                    wv.dispatchTouchEvent(move)
                    last.recycle()
                    last = move
                    delay(16)
                }
                val up = android.view.MotionEvent.obtain(downTime, downTime + n * 16L, android.view.MotionEvent.ACTION_UP, x1, y1, 0)
                wv.dispatchTouchEvent(up)
                last.recycle(); up.recycle()
                // v1.8.0 提速：拖放落点后的异步 UI 反馈（原固定 400ms）
                awaitActionSettle(wv, before, minMs = 150, maxMs = 700)
            }
        }

    // ═════════ v1.8.0 批量动作执行 ═════════
    // batchExecute / BatchOutcome 见同包 BatchExecution.kt（GATE 1 文件预算，
    // 扩展函数形态；导入方 `import com.apex.browser.engine.batchExecute` 后
    // 语法与成员方法一致）。

    // ═════════ 动作后验证探针（P0 #2） ═════════

    data class PostActionState(
        val success: Boolean,
        val urlChanged: Boolean = false,
        val newElementsCount: Int = 0,
        val pageTitle: String = "",
        val scrollY: Int = 0,
        val failReason: String? = null,
        /** v1.1.0：动作后的当前 URL（Agent 判定跳转结果的直接证据）。 */
        val currentUrl: String = "",
    ) {
        fun toText(): String = if (!success) "Error: $failReason"
        else "动作完成 · URL=${currentUrl.ifBlank { "未变" }} · URL变化=$urlChanged · 新增元素=$newElementsCount · 标题=$pageTitle"

        companion object {
            fun failed(reason: String) = PostActionState(success = false, failReason = reason)
        }
    }

    // ═════════ 探针 / 动作后验证 / 自适应等待（v1.8.0 拆出） ═════════
    // QuickProbe / readQuickProbe / probePage / awaitActionSettle / probeChanged
    // 见同包 ActionProbe.kt（internal 扩展；GATE 1 文件预算）。

    // ═════════ JS 求值封装 ═════════

    private suspend fun evaluateBoolean(wv: WebView, js: String): Boolean =
        suspendCancellableCoroutine { cont ->
            wv.evaluateJavascript(js) { result -> cont.resume(result == "true") }
        }

    internal suspend fun evaluateJson(wv: WebView, js: String): String =
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
     * 一层（`"{\"ok\":true}"`）——直接 `parseToJsonElement(...).jsonObject`
     * 必抛 IllegalArgumentException。快照路径（snapshot / A11y fallback）早已
     * 用 `parseToJsonElement(wrapped).jsonPrimitive.content` 剥壳，但
     * inputText / queryCssRect / readElementAtPoint / fuzzyRelocate /
     * readQuickProbe / pageType / networkLog 全部漏了——即输入与点击在
     * 「元素确实存在」时也必然失败，模糊自愈与探针 diff 全部静默失效。
     *
     * 统一收敛到本函数：结果首尾是引号（字符串字面量编码）→ 剥一层再回；
     * 其余形状（布尔/数值/null/对象——脚本不以 stringify 结尾的场景）原样返回。
     */
    private fun unwrapWebViewJson(result: String): String {
        if (result.length < 2 || !result.startsWith("\"") || !result.endsWith("\"")) return result
        return runCatching {
            (json.parseToJsonElement(result) as? JsonPrimitive)?.content ?: result
        }.getOrDefault(result)
    }

    private fun emptySnapshot() = PageSnapshot(
        url = "", title = "", scrollY = 0, scrollHeight = 0, viewportHeight = 0,
        interactiveCount = 0, domSummary = "(无激活标签页)", interactiveElements = emptyList()
    )

    // ═════════ 内存维护（P2 #15） ═════════

    /** 累计导航次数，超过阈值后强制重建 WebView，防止长会话内存累积 */
    private var navigationCount = 0

    /**
     * 主动维护：清理缓存与历史（保留当前页），应对长时间运行的 Agent 会话。
     * 由系统 [onTrimMemory] 或导航计数阈值触发。
     */
    fun performMaintenance() {
        runCatching {
            tabs.values.forEach { it.webView.clearCache(true) }
            tabs.values.forEach { it.webView.clearHistory() }
        }
    }

    /** 系统内存压力回调（由 宿主 Service 的 onTrimMemory 转发） */
    fun onTrimMemory(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            flushCookies()
            performMaintenance()
        }
    }

    fun destroy() {
        tabs.values.forEach { it.webView.destroy() }
        tabs.clear()
        flushCookies()
    }

    companion object {
        /** 工厂入口（去 Hilt：单例语义见 [com.apex.browser.engine.di.BrowserEngineFactory]） */
        fun create(context: Context): BrowserEngine =
            BrowserEngine(context.applicationContext)

        private const val MAX_HISTORY = 100
        /** 导航次数阈值：超过后下次 navigate 前重建 WebView（P2 #15） */
        private const val MAX_NAVIGATIONS_BEFORE_REBUILD = 50

        /** [waitForCondition] 支持的等待模式（v1.3.0 扩到 7 种，见其 KDoc） */
        private val WAIT_MODES = setOf("selector", "text", "url", "element_gone", "text_gone", "network_idle", "selector_count", "dom_stable")

        // 反检测隐身 JS（#13 轻量版）已于 v1.3.0 下沉到
        // [com.apex.browser.core.BrowserScript.STEALTH_JS]（JS 工厂只放 BrowserScript.kt
        // 的收敛口径），onPageFinished 与 NETWORK_MONITOR_JS 合并注入。
    }
}

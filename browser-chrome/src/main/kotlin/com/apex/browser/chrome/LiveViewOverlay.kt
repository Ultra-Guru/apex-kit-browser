package com.apex.browser.chrome

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.apex.browser.engine.BrowserEngine
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 实时页面悬浮窗 —— Agent 驾驶期间「看得见的浏览器」（v1.4.0 新增）。
 *
 * 设计动机（宿主 Issue #375 一并修复的 UX 面）：
 * 1. 旧 BrowserOverlay 只在 WAITING_HUMAN 展开完整 chrome；AGENT_DRIVING 期间
 *    页面在无窗的 WebView 里跑，用户既看不见实时画面，WebView 也因脱离视图树
 *    测量为 0x0 —— 物理触摸注入的坐标换算与命中测试不可靠；
 * 2. 用户要的是：Agent 一用浏览器，**透明悬浮窗自动弹出显示实时页面**，点悬浮
 *    球可随时收起/再看，从浏览器功能页也能一键「查看实时页面」。
 *
 * 形态（透明玻璃卡片，非全屏）：
 * - 窗口尺寸**用户可调**（v1.6.0）：默认宽 85 屏宽、高 60 屏高，居中，
 *   PixelFormat.TRANSLUCENT；右下角缩放把手拖拽改尺寸（钳位防过小/过大），
 *   比例持久化到 SharedPreferences，重建窗口时恢复用户偏好；
 * - 顶部控制条：状态呼吸点 + 页面标题/URL + 「接管」胶囊 + 收起按钮，可拖动整卡；
 * - 页面区直接承载引擎 active WebView（与人工接管共用同一实例，实时画面零拷贝）；
 * - 窗口小于屏幕：卡外触摸自然穿透到下层应用，不打扰手机正常使用。
 *
 * 三态生命周期（决策见 [LiveViewPolicy]）：VISIBLE 显示 / GHOST 隐形宿主（窗口
 * 仍在、视图 INVISIBLE + FLAG_NOT_TOUCHABLE，WebView 保持测量，自动化坐标不
 * 失真）/ GONE 整窗移除。
 *
 * 与 BrowserOverlay 的分工：WAITING_HUMAN 时全屏接管 chrome 独占 WebView，
 * 本卡退 GHOST；交还（AGENT_DRIVING）后保持 GHOST（v2.1.1：不自动弹卡，点球唤出）。
 *
 * v1.5.0 新增：与 [InAppChromeHost]（宿主 App 内嵌浏览器）互斥 —— in-app
 * 承载期间（[InAppHostingArbiter.isInAppHosting]）本卡转 GHOST（页面在 App
 * 内直接可见，卡片不与 in-app 容器争 WebView；换挂/自愈同步被守卫拦下），
 * in-app 退场（页面离开/Activity 暂停）时按引擎状态机重放迁移 —— 用户回到
 * 桌面/其他应用时实时卡片依旧弹出。
 *
 * 纯 View 实现说明：刻意不用 Compose（浮窗内 Compose 有 ViewTreeLifecycleOwner
 * 系列窗口层级坑，#375 即此类），控制条十几个 View 足够，崩溃面最小。
 *
 * 线程纪律：与引擎/球一致 —— 主线程 Handler + 单例常驻
 * Main.immediate 作用域（SupervisorJob + 异常记录不崩溃）。
 */
class LiveViewOverlay private constructor(
    private val appContext: Context,
    private val engine: BrowserEngine,
) : BrowserEngine.BrowserUiCallback, BrowserEngine.ActiveTabListener {

    companion object {
        private const val TAG = "LiveViewOverlay"

        /** 状态驱动的呈现迁移延迟：让 BrowserOverlay 的同批 post 先行（交还时它
         * 先 detach WebView，本卡随后换挂，避免抢挂后又被摘走）。 */
        private const val STATE_APPLY_DELAY_MS = 64L

        /** 可见期间的轻量轮询：标题/URL/进度刷新 + 换挂自愈。
         *  v1.6.0 自适应节流：页面加载中（progress<100）用快档保证进度条流畅，
         *  静止时降为慢档 —— 常驻会话的唤醒次数 ↓（原来固定 1.2s 不分场景）。 */
        internal const val POLL_ACTIVE_MS = 500L
        internal const val POLL_IDLE_MS = 2_000L

        /** 窗口尺寸比例的持久化（宽度/高度各占屏幕的比例）。 */
        private const val SIZE_PREFS = "apex_live_view_size"
        private const val KEY_WIDTH_RATIO = "width_ratio"
        private const val KEY_HEIGHT_RATIO = "height_ratio"

        /** 尺寸钳位（占屏幕比例）：过小看不清页面，过大失去「小窗」语义。 */
        internal const val MIN_WIDTH_RATIO = 0.35f
        internal const val MIN_HEIGHT_RATIO = 0.25f
        internal const val MAX_WIDTH_RATIO = 0.95f
        internal const val MAX_HEIGHT_RATIO = 0.85f
        internal const val DEFAULT_WIDTH_RATIO = 0.85f
        internal const val DEFAULT_HEIGHT_RATIO = 0.60f

        /** 拖动/缩放参数应用的帧对齐节流（~60fps：updateViewLayout 全量
         *  relayout 走 WMS，每次 MOVE 都发会造成 Motion 峰值期的窗口抖动）。 */
        internal const val PARAM_APPLY_MIN_INTERVAL_MS = 16L

        /** 纯函数：按页面进度选下一轮询档位（单测锁定语义）。 */
        internal fun pollIntervalFor(progress: Int): Long =
            if (progress in 0..99) POLL_ACTIVE_MS else POLL_IDLE_MS

        /** 纯函数：尺寸比例钳位（单测锁定语义）。 */
        internal fun clampSizeRatio(value: Float, min: Float, max: Float): Float =
            value.coerceIn(min, max)

        private val COLOR_RUNNING = 0xFF00E5FF.toInt()
        private val COLOR_WAITING = 0xFFFFB800.toInt()
        private val COLOR_RECOVERING = 0xFFFF2A55.toInt()
        private val COLOR_IDLE = 0xFF9AA7B0.toInt()

        private val COLOR_CARD_SCRIM = 0xE60E1318.toInt()
        private val COLOR_BAR_SCRIM = 0xF20B0F13.toInt()
        private val COLOR_TEXT_PRIMARY = 0xF2FFFFFF.toInt()
        private val COLOR_TEXT_SECONDARY = 0xB3FFFFFF.toInt()
        private val COLOR_TAKE_OVER = 0xFF00E5FF.toInt()

        fun create(
            context: Context,
            engine: BrowserEngine,
        ): LiveViewOverlay = LiveViewOverlay(context.applicationContext, engine)
    }

    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // 单例常驻作用域：接管动作（enterHandoffMode）异常记录不杀进程（同球/引擎纪律）
    private val mainScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e ->
            Log.w(TAG, "handoff action failed", e)
        }
    )

    @Volatile private var mode = LiveViewMode.GONE
    @Volatile private var userDismissed = false

    // ───────── 窗口视图树（VISIBLE 期间才有内容，GHOST 仅保测量宿主） ─────────
    @Volatile private var rootView: LinearLayout? = null
    @Volatile private var webViewHost: FrameLayout? = null
    @Volatile private var windowParams: WindowManager.LayoutParams? = null
    @Volatile private var statusDot: View? = null
    @Volatile private var urlText: TextView? = null
    @Volatile private var progressBar: ProgressBar? = null
    @Volatile private var windowFocusable = false

    @Volatile private var attachedWebView: WebView? = null
    private var dotPulse: ObjectAnimator? = null

    /** 右下角缩放把手（重建窗口时复用引用；GONE 后置 null）。 */
    @Volatile private var resizeGrip: View? = null

    /** 参数应用节流游标（拖动/缩放共用，帧对齐 ~60fps）。 */
    private var lastParamsApplyMs = 0L

    /** v1.5.0：in-app 承载权变化 —— 进场转 GHOST / 退场按状态机重放 ——先于 init 声明。 */
    private val arbiterListener = InAppHostingArbiter.Listener { active ->
        mainHandler.post {
            if (active) {
                if (mode == LiveViewMode.VISIBLE) becomeGhost()
            } else {
                applyTransition(stateEventFor(engine.currentState))
            }
        }
    }

    init {
        engine.addUiCallback(this)
        engine.addActiveTabListener(this)
        InAppHostingArbiter.addListener(arbiterListener)
    }

    // ───────── 宿主 API（悬浮球 / BrowserScreen「查看实时页面」） ─────────

    /** 当前卡片是否对用户可见（GHOST/GONE 均为否）。 */
    val isShowing: Boolean get() = mode == LiveViewMode.VISIBLE

    /** 强制唤起实时卡片（复位用户收起标记）：会话未激活时仅静默无窗。 */
    fun show() = mainHandler.post { applyTransition(LiveViewEvent.USER_SHOW) }

    /** 用户主动收起（卡片「✕」）：进入 GHOST 无头宿主，自动化继续。 */
    fun hideByUser() = mainHandler.post { applyTransition(LiveViewEvent.USER_DISMISS) }

    /** 悬浮球点击切换：可见→GHOST，隐/无→VISIBLE。 */
    fun toggle() = mainHandler.post { applyTransition(LiveViewEvent.USER_TOGGLE) }

    // ───────── 引擎订阅：状态机 + 活动标签 ─────────

    override fun onStateChanged(
        state: BrowserEngine.BrowserSessionState,
        url: String?,
        title: String?,
    ) {
        // 状态驱动延迟应用：与 BrowserOverlay 的同批 post 保持先后（见类头说明）
        mainHandler.postDelayed({ applyTransition(stateEventFor(state)) }, STATE_APPLY_DELAY_MS)
    }

    /** 引擎会话状态 → 实时卡迁移事件（状态回调与仲裁退场重放共用）。 */
    private fun stateEventFor(state: BrowserEngine.BrowserSessionState): LiveViewEvent =
        when (state) {
            BrowserEngine.BrowserSessionState.HIDDEN -> LiveViewEvent.STATE_HIDDEN
            BrowserEngine.BrowserSessionState.AGENT_DRIVING -> LiveViewEvent.STATE_DRIVING
            BrowserEngine.BrowserSessionState.WAITING_HUMAN -> LiveViewEvent.STATE_WAITING
            BrowserEngine.BrowserSessionState.RECOVERING -> LiveViewEvent.STATE_RECOVERING
        }

    override fun onActiveTabChanged(webView: WebView?) {
        mainHandler.post { rebindWebView(webView) }
    }

    // ───────── 迁移执行 ─────────

    /** 主线程内统一出口：策略裁决 → 窗口操作。 */
    private fun applyTransition(event: LiveViewEvent) {
        val target = LiveViewPolicy.resolve(event, mode, userDismissed)
        if (target.mode == mode && target.userDismissed == userDismissed) return
        userDismissed = target.userDismissed
        when (target.mode) {
            LiveViewMode.VISIBLE -> becomeVisible()
            LiveViewMode.GHOST -> becomeGhost()
            LiveViewMode.GONE -> becomeGone()
        }
    }

    /** VISIBLE：建窗（若 GONE）或解除隐身（若 GHOST）+ 换挂 + 启动轮询与呼吸点。 */
    private fun becomeVisible() {
        // v1.5.0：in-app 承载期间页面在宿主 App 内直接可见 —— 本卡让位 GHOST
        //（保测量语义交由 in-app 容器承担），仲裁退场时按状态机重放恢复。
        if (InAppHostingArbiter.isInAppHosting) {
            becomeGhost()
            return
        }
        // 数据级前置：无活动页面（会话从未开启）时无实时画面可示 —— 静默不建空卡
        // （策略层保持纯状态机；「有没有内容可放」属 View 层事实）
        if (engine.activeWebView() == null && engine.currentState == BrowserEngine.BrowserSessionState.HIDDEN) {
            mode = LiveViewMode.GONE
            return
        }
        if (rootView == null) {
            buildWindow() ?: run {
                // 无悬浮窗权限 / 系统拒绝：降级 GONE，引擎照常无头驱动
                mode = LiveViewMode.GONE
                return
            }
        } else {
            val root = rootView ?: return
            val params = windowParams ?: return
            root.visibility = View.VISIBLE
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            runCatching { windowManager.updateViewLayout(root, params) }
                .onFailure { Log.w(TAG, "unghost failed: ${it.message}") }
        }
        mode = LiveViewMode.VISIBLE
        applyStatusColor()
        rebindWebView(engine.activeWebView())
        startPolling()
        startDotPulse()
    }

    /** GHOST：窗口保活隐形 + 免触摸；WebView 留在宿主内保测量（自动化坐标不失真）。 */
    private fun becomeGhost() {
        stopPolling()
        stopDotPulse()
        val root = rootView
        val params = windowParams
        if (root == null || params == null) {
            mode = LiveViewMode.GONE
            return
        }
        root.visibility = View.INVISIBLE
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        // 隐身同时放弃焦点：不抢下层应用的键盘焦点（与 BrowserOverlay 同款纪律）
        windowFocusable = false
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        runCatching { windowManager.updateViewLayout(root, params) }
            .onFailure { Log.w(TAG, "ghost failed: ${it.message}") }
        mode = LiveViewMode.GHOST
    }

    /** GONE：整窗移除（会话收尾）；WebView 交还引擎无头驱动。 */
    private fun becomeGone() {
        stopPolling()
        stopDotPulse()
        detachWebView()
        val root = rootView
        if (root != null) {
            runCatching { windowManager.removeView(root) }
                .onFailure { Log.w(TAG, "remove failed: ${it.message}") }
        }
        rootView = null
        webViewHost = null
        windowParams = null
        statusDot = null
        urlText = null
        progressBar = null
        resizeGrip = null
        attachedWebView = null
        windowFocusable = false
        mode = LiveViewMode.GONE
    }

    // ───────── 窗口构建（纯 View，零 Compose 依赖） ─────────

    /** 建窗成功返回 root；失败（无权限等）返回 null 由调用方降级。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun buildWindow(): LinearLayout? {
        val density = appContext.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()
        val screenWidth = appContext.resources.displayMetrics.widthPixels
        val screenHeight = appContext.resources.displayMetrics.heightPixels

        // v1.6.0：初始尺寸恢复用户上次拖拽的比例（无记录用默认 85%/60%）
        val prefs = appContext.getSharedPreferences(SIZE_PREFS, Context.MODE_PRIVATE)
        val widthRatio = clampSizeRatio(
            prefs.getFloat(KEY_WIDTH_RATIO, DEFAULT_WIDTH_RATIO),
            MIN_WIDTH_RATIO, MAX_WIDTH_RATIO,
        )
        val heightRatio = clampSizeRatio(
            prefs.getFloat(KEY_HEIGHT_RATIO, DEFAULT_HEIGHT_RATIO),
            MIN_HEIGHT_RATIO, MAX_HEIGHT_RATIO,
        )

        val params = WindowManager.LayoutParams(
            (screenWidth * widthRatio).toInt(),
            (screenHeight * heightRatio).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 卡内可交互（滚动真实页面/点按钮）；卡外触摸穿透下层应用；
            // 默认不可聚焦 —— 不抢下层键盘焦点，首次卡内按下才临时聚焦（IME 用）
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        // 圆角玻璃卡：半透明底 + 圆角裁剪（页面内容不溢出圆角）
        val root = TouchActivatingLinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(COLOR_CARD_SCRIM)
            }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(18).toFloat())
                }
            }
            onFirstTouch = { makeWindowFocusable() }
        }

        // ── 顶栏：状态点 + 标题/URL + 接管 + 收起（可拖动整卡） ──
        val bar = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(
                    dp(18).toFloat(), dp(18).toFloat(),
                    dp(18).toFloat(), dp(18).toFloat(),
                    0f, 0f, 0f, 0f,
                )
                setColor(COLOR_BAR_SCRIM)
            }
            setPadding(dp(12), 0, dp(6), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)
            )
        }
        val dot = View(appContext).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                marginEnd = dp(8)
            }
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(COLOR_RUNNING) }
        }
        val title = TextView(appContext).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            text = appContext.getString(R.string.browser_live_url_empty)
            textSize = 13f
            setTextColor(COLOR_TEXT_PRIMARY)
        }
        val takeOver = TextView(appContext).apply {
            text = appContext.getString(R.string.browser_live_take_over)
            textSize = 12f
            setTextColor(0xFF04191E.toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(COLOR_TAKE_OVER)
            }
            setPadding(dp(12), dp(6), dp(12), dp(6))
            isClickable = true
            contentDescription = appContext.getString(R.string.browser_live_take_over_cd)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)
            ).apply { marginStart = dp(8); marginEnd = dp(6) }
            setOnClickListener { enterHandoff() }
        }
        val close = TextView(appContext).apply {
            text = "\u2715"
            textSize = 15f
            setTextColor(COLOR_TEXT_SECONDARY)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isClickable = true
            contentDescription = appContext.getString(R.string.browser_live_close_cd)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)
            )
            setOnClickListener { hideByUser() }
        }
        bar.addView(dot)
        bar.addView(title)
        bar.addView(takeOver)
        bar.addView(close)
        attachBarDrag(bar, params)

        // ── 细进度条：轮询读 WebView.progress，加载完成淡出 ──
        val progress = ProgressBar(appContext, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2)
            )
            max = 100
            progress = 100
            progressTintList = android.content.res.ColorStateList.valueOf(COLOR_TAKE_OVER)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x33FFFFFF)
        }

        // ── 页面宿主：承载引擎 active WebView（与人工接管共用同一实例） ──
        val host = TouchActivatingFrameLayout(appContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            onFirstTouch = { makeWindowFocusable() }
        }

        // ── 右下角缩放把手（v1.6.0）：拖拽改变窗口宽高，比例持久化 ──
        val grip = TextView(appContext).apply {
            text = "\u25E2"                       // ◢ 斜角缩放意象
            textSize = 14f
            setTextColor(COLOR_TEXT_SECONDARY)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(
                    dp(16).toFloat(), dp(16).toFloat(),
                    0f, 0f, 0f, 0f,
                    0f, 0f,
                )
                setColor(0x2EFFFFFF)
            }
            elevation = dp(6).toFloat()          // 恒在换挂进来的 WebView 之上
            contentDescription = appContext.getString(R.string.browser_live_resize_cd)
            layoutParams = FrameLayout.LayoutParams(
                dp(40), dp(40), Gravity.BOTTOM or Gravity.END
            )
        }
        attachCornerResize(grip, params, screenWidth, screenHeight)

        root.addView(bar, bar.layoutParams)
        root.addView(progress, progress.layoutParams)
        root.addView(host, host.layoutParams)
        host.addView(grip, grip.layoutParams)

        return try {
            windowManager.addView(root, params)
            windowParams = params
            rootView = root
            webViewHost = host
            resizeGrip = grip
            statusDot = dot
            urlText = title
            progressBar = progress
            root
        } catch (e: Exception) {
            // 无 SYSTEM_ALERT_WINDOW 权限或系统拒绝：留痕降级（引擎照常无头工作）
            Log.w(TAG, "addView rejected: ${e.message}")
            null
        }
    }

    /** 顶栏拖动整卡（TYPE overlay 无.decorView，用 updateViewLayout 平移窗口）。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachBarDrag(bar: View, params: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        bar.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val w = appContext.resources.displayMetrics.widthPixels / 2
                    val h = appContext.resources.displayMetrics.heightPixels / 2
                    params.x = (startX + (event.rawX - downX).toInt()).coerceIn(-w, w)
                    params.y = (startY + (event.rawY - downY).toInt()).coerceIn(-h, h)
                    applyParamsThrottled()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    /** 右下角缩放把手（v1.6.0）：拖拽改变窗口宽高（中心锚定），比例持久化。
     *
     *  尺寸钳位到 [MIN_*_RATIO, MAX_*_RATIO] × 屏幕像素 —— 过小看不清页面，
     *  过大失去「小窗」语义；ACTION_UP 落盘 SharedPreferences，下次建窗恢复。
     *  MOVE 沿用 [applyParamsThrottled] 帧对齐节流，与顶栏拖动同套纪律。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachCornerResize(
        grip: View,
        params: WindowManager.LayoutParams,
        screenWidth: Int,
        screenHeight: Int,
    ) {
        var downX = 0f
        var downY = 0f
        var startW = 0
        var startH = 0
        val minW = (screenWidth * MIN_WIDTH_RATIO).toInt()
        val maxW = (screenWidth * MAX_WIDTH_RATIO).toInt()
        val minH = (screenHeight * MIN_HEIGHT_RATIO).toInt()
        val maxH = (screenHeight * MAX_HEIGHT_RATIO).toInt()
        grip.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startW = params.width; startH = params.height
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.width = (startW + (event.rawX - downX).toInt()).coerceIn(minW, maxW)
                    params.height = (startH + (event.rawY - downY).toInt()).coerceIn(minH, maxH)
                    applyParamsThrottled()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    applyParamsNow()
                    persistSizeRatio(params, screenWidth, screenHeight)
                    true
                }
                else -> false
            }
        }
    }

    /** 帧对齐节流：≥16ms 才真正调 updateViewLayout，尾帧在 UP/CANCEL 补发。 */
    private fun applyParamsThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastParamsApplyMs >= PARAM_APPLY_MIN_INTERVAL_MS) applyParamsNow()
    }

    private fun applyParamsNow() {
        val root = rootView ?: return
        val params = windowParams ?: return
        lastParamsApplyMs = System.currentTimeMillis()
        runCatching { windowManager.updateViewLayout(root, params) }
            .onFailure { Log.w(TAG, "apply params failed: ${it.message}") }
    }

    /** 尺寸比例落盘（下次建窗恢复用户偏好）。 */
    private fun persistSizeRatio(params: WindowManager.LayoutParams, screenWidth: Int, screenHeight: Int) {
        if (screenWidth <= 0 || screenHeight <= 0) return
        runCatching {
            appContext.getSharedPreferences(SIZE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putFloat(KEY_WIDTH_RATIO, clampSizeRatio(params.width.toFloat() / screenWidth, MIN_WIDTH_RATIO, MAX_WIDTH_RATIO))
                .putFloat(KEY_HEIGHT_RATIO, clampSizeRatio(params.height.toFloat() / screenHeight, MIN_HEIGHT_RATIO, MAX_HEIGHT_RATIO))
                .apply()
        }.onFailure { Log.w(TAG, "persist size failed: ${it.message}") }
    }

    /** 「接管」：进入人工接管 —— 状态机 WAITING_HUMAN，全屏接管 chrome 接管 WebView。 */
    private fun enterHandoff() {
        mainScope.launch { engine.enterHandoffMode() }
    }

    // ───────── WebView 换挂（与 BrowserOverlay 同款再父级纪律） ─────────

    private fun rebindWebView(view: WebView?) {
        val host = webViewHost ?: return
        // 人工接管期间全屏 chrome 独占 WebView，本卡不得抢挂（交还后状态回调再回 VISIBLE）
        if (engine.currentState == BrowserEngine.BrowserSessionState.WAITING_HUMAN) return
        // v1.5.0：in-app 承载期间 WebView 归宿主 App 容器，本卡不抢挂（轮询自愈同走此口）
        if (InAppHostingArbiter.isInAppHosting) return
        if (view == null) {
            attachedWebView = null
            return
        }
        if (view.parent === host) {
            attachedWebView = view
            return
        }
        (view.parent as? ViewGroup)?.removeView(view)
        host.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        attachedWebView = view
    }

    private fun detachWebView() {
        val wv = attachedWebView ?: return
        (wv.parent as? ViewGroup)?.removeView(wv)
        attachedWebView = null
    }

    // ───────── 可见期轮询：标题/URL/进度 + 换挂自愈 ─────────

    private val pollRunnable = Runnable { pollOnce() }

    private fun pollOnce() {
        if (mode != LiveViewMode.VISIBLE) return
        val tab = engine.activeTab()
        val wv = tab?.webView
        // 自愈：任何时序把 WebView 摘离了宿主（如交还竞态）都在下个轮询周期补挂
        if (wv != null && wv.parent !== webViewHost) rebindWebView(wv)
        urlText?.text = tab?.title?.takeIf { it.isNotBlank() }
            ?: tab?.url?.takeIf { it.isNotBlank() }
            ?: appContext.getString(R.string.browser_live_url_empty)
        val p = (wv?.progress ?: 100).coerceIn(0, 100)
        progressBar?.let { bar ->
            bar.progress = p
            bar.visibility = if (p >= 100) View.INVISIBLE else View.VISIBLE
        }
        applyStatusColor()
        // v1.6.0 自适应节流：加载中快档（进度条流畅 + 自愈及时），静止慢档省电
        mainHandler.postDelayed(pollRunnable, pollIntervalFor(p))
    }

    private fun startPolling() {
        mainHandler.removeCallbacks(pollRunnable)
        mainHandler.postDelayed(pollRunnable, POLL_ACTIVE_MS)
    }

    private fun stopPolling() {
        mainHandler.removeCallbacks(pollRunnable)
    }

    // ───────── 状态视觉：呼吸点颜色 + 脉冲 ─────────

    private fun applyStatusColor() {
        val color = when (engine.currentState) {
            BrowserEngine.BrowserSessionState.AGENT_DRIVING -> COLOR_RUNNING
            BrowserEngine.BrowserSessionState.WAITING_HUMAN -> COLOR_WAITING
            BrowserEngine.BrowserSessionState.RECOVERING -> COLOR_RECOVERING
            BrowserEngine.BrowserSessionState.HIDDEN -> COLOR_IDLE
        }
        (statusDot?.background as? GradientDrawable)?.setColor(color)
    }

    private fun startDotPulse() {
        val dot = statusDot ?: return
        stopDotPulse()
        dotPulse = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.35f).apply {
            duration = 900
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun stopDotPulse() {
        runCatching { dotPulse?.cancel() }
        dotPulse = null
        statusDot?.alpha = 1f
    }

    // ───────── 窗口聚焦（页面输入框需要 IME 时首次触摸激活） ─────────

    private fun makeWindowFocusable() {
        if (windowFocusable) return
        val params = windowParams ?: return
        val root = rootView ?: return
        windowFocusable = true
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        runCatching { windowManager.updateViewLayout(root, params) }
            .onFailure { Log.w(TAG, "focusable failed: ${it.message}") }
    }

    // ───────── 触摸激活容器（父容器分发先于子 View，统一承担首触聚焦） ─────────

    private class TouchActivatingLinearLayout(context: Context) : LinearLayout(context) {
        var onFirstTouch: (() -> Unit)? = null
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.action == MotionEvent.ACTION_DOWN) onFirstTouch?.invoke()
            return super.dispatchTouchEvent(ev)
        }
    }

    private class TouchActivatingFrameLayout(context: Context) : FrameLayout(context) {
        var onFirstTouch: (() -> Unit)? = null
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.action == MotionEvent.ACTION_DOWN) onFirstTouch?.invoke()
            return super.dispatchTouchEvent(ev)
        }
    }
}

/**
 * 实时页面悬浮窗进程级单例工厂（库零 DI 依赖，宿主在自己的 Hilt Module 里
 * provide 即可，见 [BrowserOverlayFactory] 同款接法）。
 *
 * 注册顺序提示：宿主请先创建 [com.apex.browser.chrome.BrowserOverlay] 再创建
 * 本单例（引擎回调按注册序派发，交还竞态时全屏 chrome 先摘 WebView、本卡随后
 * 换挂；即使顺序异常，轮询自愈也会在下个周期补挂）。
 */
object LiveViewOverlayFactory {

    @Volatile
    private var instance: LiveViewOverlay? = null

    fun get(context: Context, engine: BrowserEngine): LiveViewOverlay =
        instance ?: synchronized(this) {
            instance ?: LiveViewOverlay.create(context, engine).also { instance = it }
        }

    /** 仅测试用：重置单例。 */
    @Synchronized
    fun reset() {
        instance = null
    }
}

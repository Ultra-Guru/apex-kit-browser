package com.apex.browser.chrome

/*
 * LiveViewPolicy —— 实时页面悬浮窗的呈现决策逻辑（纯 JVM，可单测）。
 *
 * 背景（v2.1.1 修订）：AGENT_DRIVING 期间**默认不弹实时卡**（GHOST 无头宿主保测量），
 * 用户点悬浮球才唤出画面——自动化启动只出悬浮球；
 * 而完整浏览器 chrome（BrowserChrome）只在人工接管（WAITING_HUMAN）时展开。
 * 本策略把「窗口处于什么呈现态」的纯决策从 Android View 代码里剥离出来，
 * 让状态机行为可以用普通 JVM 测试锁住，View 层只负责执行。
 *
 * 三种呈现态（Mode）：
 * - VISIBLE：透明玻璃卡片浮窗正常显示（页面区承载引擎的 WebView，实时画面）；
 * - GHOST：窗口仍挂在 WindowManager 上但根视图 INVISIBLE 且 FLAG_NOT_TOUCHABLE
 *   ——用户手动收起后的「无头宿主」：WebView 保持在视图树内被测量布局，
 *   自动化的 CSS→物理坐标换算与命中测试不因脱离窗口而失真；
 * - GONE：窗口整体移除（会话结束 HIDDEN / 无悬浮窗权限降级）。
 *
 * 事件（Event）与裁决表：
 * - STATE_HIDDEN：会话结束 → GONE 且复位 userDismissed（下次会话恢复自动弹出）；
 * - STATE_DRIVING：Agent 开始/恢复驾驶 → 未被用户收起则 VISIBLE；已被收起则
 *   保持 GHOST（窗口不存在时建成 GHOST，保证 WebView 始终有宿主）；
 * - STATE_WAITING：人工接管 → GHOST（全屏接管 chrome 是此时唯一界面，WebView
 *   交给它承载；卡片退居幕后等交还后回归）；
 * - STATE_RECOVERING：渲染进程重建 → 维持现状（换挂由 ActiveTabListener 驱动）；
 * - USER_SHOW：外部强制唤起（BrowserScreen「查看实时页面」/悬浮球）→ VISIBLE；
 * - USER_TOGGLE：悬浮球点击切换 → VISIBLE↔GHOST 往返；
 * - USER_DISMISS：卡片右上角收起 → GHOST 且标记 userDismissed。
 */

/** 实时页面悬浮窗呈现态。 */
enum class LiveViewMode { VISIBLE, GHOST, GONE }

/** 驱动呈现态迁移的事件（引擎状态迁移 + 用户操作）。 */
enum class LiveViewEvent {
    STATE_HIDDEN,
    STATE_DRIVING,
    STATE_WAITING,
    STATE_RECOVERING,
    USER_SHOW,
    USER_TOGGLE,
    USER_DISMISS,
}

/** 一次迁移的目标：呈现态 + 用户收起标记的终值。 */
data class LiveViewTransition(
    val mode: LiveViewMode,
    val userDismissed: Boolean,
)

object LiveViewPolicy {

    /**
     * 裁决：事件 ×（当前呈现态, 用户收起标记）→ 目标呈现态。
     *
     * 约定：STATE_RECOVERING 对任何现状都是 NO_OP 语义（目标 == 当前态），
     * 调用方据此短路，避免重建窗口打断换挂流程。
     */
    fun resolve(
        event: LiveViewEvent,
        mode: LiveViewMode,
        userDismissed: Boolean,
    ): LiveViewTransition = when (event) {
        LiveViewEvent.STATE_HIDDEN -> LiveViewTransition(LiveViewMode.GONE, userDismissed = false)

        LiveViewEvent.STATE_DRIVING ->
            // v2.1.1（用户裁决）：自动化启动**不再弹出实时卡**——保持 GHOST 无头宿主
            // （窗口仍挂 WindowManager 保 WebView 测量，视觉零打扰），画面只在用户
            // 点悬浮球（USER_SHOW / USER_TOGGLE）时唤出。userDismissed 语义保留：
            // 用户收起过就不再自动复现；WAITING_HUMAN→交还后同样回 GHOST 而非弹卡。
            LiveViewTransition(LiveViewMode.GHOST, userDismissed = userDismissed)

        LiveViewEvent.STATE_WAITING -> LiveViewTransition(LiveViewMode.GHOST, userDismissed = userDismissed)

        LiveViewEvent.STATE_RECOVERING -> LiveViewTransition(mode, userDismissed = userDismissed)

        LiveViewEvent.USER_SHOW -> LiveViewTransition(LiveViewMode.VISIBLE, userDismissed = false)

        LiveViewEvent.USER_TOGGLE ->
            if (mode == LiveViewMode.VISIBLE) LiveViewTransition(LiveViewMode.GHOST, userDismissed = true)
            else LiveViewTransition(LiveViewMode.VISIBLE, userDismissed = false)

        LiveViewEvent.USER_DISMISS -> LiveViewTransition(LiveViewMode.GHOST, userDismissed = true)
    }
}

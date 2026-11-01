package com.apex.browser.chrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * 纯逻辑单测：实时页面悬浮窗呈现策略（JVM 即可运行，无需设备）。
 * 锁住三条核心 UX 契约：
 * 1. Agent 驾驶 → 自动弹出（用户没收起过）；
 * 2. 用户收起后 agent 继续驾驶 → 保持 GHOST 无头宿主（WebView 不脱离测量）；
 * 3. 会话结束（HIDDEN）→ 整窗移除且复位收起标记（下次自动弹出恢复）。
 */
class LiveViewPolicyTest {

    private val V = LiveViewMode.VISIBLE
    private val G = LiveViewMode.GHOST
    private val X = LiveViewMode.GONE

    /* ---- STATE_DRIVING：v2.1.1 起默认 GHOST（自动化启动不弹卡，点球唤出）---- */

    @Test
    fun `驾驶开始 - 从 GONE 进 GHOST 无头宿主不弹卡`() {
        val t = LiveViewPolicy.resolve(LiveViewEvent.STATE_DRIVING, X, userDismissed = false)
        assertEquals(G, t.mode)
        assertFalse(t.userDismissed)
    }

    @Test
    fun `驾驶开始 - 保持 GHOST 无头宿主`() {
        val t = LiveViewPolicy.resolve(LiveViewEvent.STATE_DRIVING, G, userDismissed = false)
        assertEquals(G, t.mode)
        assertFalse(t.userDismissed)
    }

    @Test
    fun `驾驶开始但用户已收起 - 保持 GHOST 无头宿主`() {
        val t = LiveViewPolicy.resolve(LiveViewEvent.STATE_DRIVING, G, userDismissed = true)
        assertEquals(G, t.mode)
        assertTrue(t.userDismissed)
    }

    @Test
    fun `交还后恢复驾驶但用户已收起 - 仍保持 GHOST`() {
        // WAITING_HUMAN → GHOST（保留 userDismissed）→ completeHandoff → DRIVING
        val ghost = LiveViewPolicy.resolve(LiveViewEvent.STATE_WAITING, V, userDismissed = true)
        assertEquals(G, ghost.mode)
        assertTrue(ghost.userDismissed)
        val back = LiveViewPolicy.resolve(LiveViewEvent.STATE_DRIVING, ghost.mode, ghost.userDismissed)
        assertEquals(G, back.mode)
        assertTrue(back.userDismissed)
    }

    /* ---- STATE_HIDDEN：整窗移除 + 复位 ---- */

    @Test
    fun `会话结束 - 任意现状都回 GONE 并复位收起标记`() {
        LiveViewPolicy.resolve(LiveViewEvent.STATE_HIDDEN, V, true).let {
            assertEquals(X, it.mode); assertFalse(it.userDismissed)
        }
        LiveViewPolicy.resolve(LiveViewEvent.STATE_HIDDEN, G, true).let {
            assertEquals(X, it.mode); assertFalse(it.userDismissed)
        }
        LiveViewPolicy.resolve(LiveViewEvent.STATE_HIDDEN, X, false).let {
            assertEquals(X, it.mode); assertFalse(it.userDismissed)
        }
    }

    @Test
    fun `会话结束复位后再次驾驶 - 仍默认 GHOST 不弹卡`() {
        val gone = LiveViewPolicy.resolve(LiveViewEvent.STATE_HIDDEN, G, true)
        val again = LiveViewPolicy.resolve(LiveViewEvent.STATE_DRIVING, gone.mode, gone.userDismissed)
        assertEquals(G, again.mode)
        assertFalse(again.userDismissed)
    }

    /* ---- STATE_WAITING：全屏接管独占，卡片退 GHOST ---- */

    @Test
    fun `人工接管 - 卡片退 GHOST 且保留收起标记`() {
        val t = LiveViewPolicy.resolve(LiveViewEvent.STATE_WAITING, V, userDismissed = false)
        assertEquals(G, t.mode)
        assertFalse(t.userDismissed)
    }

    /* ---- STATE_RECOVERING：维持现状 ---- */

    @Test
    fun `渲染重建 - 维持现状与标记`() {
        LiveViewPolicy.resolve(LiveViewEvent.STATE_RECOVERING, V, false).let {
            assertEquals(V, it.mode); assertFalse(it.userDismissed)
        }
        LiveViewPolicy.resolve(LiveViewEvent.STATE_RECOVERING, G, true).let {
            assertEquals(G, it.mode); assertTrue(it.userDismissed)
        }
    }

    /* ---- 用户操作：唤起 / 切换 / 收起 ---- */

    @Test
    fun `外部强制唤起 - 复位收起并显示`() {
        val t = LiveViewPolicy.resolve(LiveViewEvent.USER_SHOW, G, userDismissed = true)
        assertEquals(V, t.mode)
        assertFalse(t.userDismissed)
    }

    @Test
    fun `悬浮球切换 - 可见转收起`() {
        val t = LiveViewPolicy.resolve(LiveViewEvent.USER_TOGGLE, V, userDismissed = false)
        assertEquals(G, t.mode)
        assertTrue(t.userDismissed)
    }

    @Test
    fun `悬浮球切换 - 隐形或无窗转显示`() {
        LiveViewPolicy.resolve(LiveViewEvent.USER_TOGGLE, G, true).let {
            assertEquals(V, it.mode); assertFalse(it.userDismissed)
        }
        LiveViewPolicy.resolve(LiveViewEvent.USER_TOGGLE, X, false).let {
            assertEquals(V, it.mode); assertFalse(it.userDismissed)
        }
    }

    @Test
    fun `卡片收起按钮 - GHOST 且标记收起`() {
        val t = LiveViewPolicy.resolve(LiveViewEvent.USER_DISMISS, V, userDismissed = false)
        assertEquals(G, t.mode)
        assertTrue(t.userDismissed)
    }
}

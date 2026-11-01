package com.apex.browser.core

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NavigationCoordinator] 的意图测试（v2.1.0）：异步导航协议的核心不变量——
 * 写守卫（SUPERSEDED 晚到不得写入）、广播唤醒（全部等待者）、TTL 惰性折叠、
 * 在途上限、tab 关闭 / 引擎释放终态化。并发测试 B（评审）：同 tab 两次导航，
 * 新导航接管后旧导航不得覆盖状态。
 */
class NavigationCoordinatorTest {

    private var clockMs = 1_000_000L
    private fun coordinator(ttlMs: Long = NavigationCoordinator.DEFAULT_TTL_MS, maxLive: Int = 32) =
        NavigationCoordinator(nowMs = { clockMs }, ttlMs = ttlMs, maxLiveNavigations = maxLive)

    private fun nav(c: NavigationCoordinator, tabId: Int, url: String = "https://a.example") =
        c.startNavigation(tabId, url, currentDocGeneration = 1, currentDocId = null)

    // ── 基本生命周期 ─────────────────────────────────────────────

    @Test
    fun `发起为STARTED_提交后COMMITTED且docId以最终提交为准`() {
        val c = coordinator()
        val h = nav(c, tabId = 1)
        assertEquals(NavigationStatus.STARTED, c.navigationState(h.navigationId)?.navigationStatus)
        assertNull(c.navigationState(h.navigationId)?.docId)

        assertTrue(c.markCommitted(h.navigationId, "d1g2", "redirect"))
        assertTrue(c.markCommitted(h.navigationId, "d1g3", "final")) // 重定向链：最终提交覆盖
        val s = c.navigationState(h.navigationId)!!
        assertEquals(NavigationStatus.COMMITTED, s.navigationStatus)
        assertEquals("d1g3", s.docId)
    }

    @Test
    fun `DOM_READY入就绪并产生事件_事件键为docId`() {
        val c = coordinator()
        val h = nav(c, tabId = 1)
        c.markCommitted(h.navigationId, "d1g2")
        c.setReadiness(h.navigationId, ReadinessStatus.DOM_READY)
        val s = c.navigationState(h.navigationId)!!
        assertEquals(ReadinessStatus.DOM_READY, s.readinessStatus)
        assertNotNull(s.settledAtMs)
        val page = c.docRegistry.readSince("d1g2", 0, 50)
        assertTrue(page.events.any { it.type == DocEventType.NAV_COMMITTED })
        assertTrue(page.events.any { it.type == DocEventType.DOM_READY })
    }

    // ── SUPERSEDED（并发测试 B 的状态机面） ─────────────────────

    @Test
    fun `同tab新导航取代旧导航_旧导航终态化且写守卫拒绝晚到写入`() {
        val c = coordinator()
        val old = nav(c, tabId = 1)
        // 新导航发起 → 旧导航立即 SUPERSEDED
        val new = nav(c, tabId = 1)
        assertEquals(NavigationStatus.SUPERSEDED, c.navigationState(old.navigationId)?.navigationStatus)
        assertEquals(NavigationStatus.STARTED, c.navigationState(new.navigationId)?.navigationStatus)
        // 活跃导航已切换为新导航（写守卫的依据）
        assertEquals(new.navigationId, c.activeNavigationFor(1))

        // 晚到协程以旧导航身份写状态：写守卫必须拒绝（静默 false）
        assertFalse(c.markCommitted(old.navigationId, "d1g99"))
        assertFalse(c.setReadiness(old.navigationId, ReadinessStatus.DOM_READY))
        assertEquals("d1g99 不得生效", null, c.navigationState(old.navigationId)?.docId)
        // 新导航不受影响
        assertTrue(c.markCommitted(new.navigationId, "d1g2"))
        assertEquals("d1g2", c.navigationState(new.navigationId)?.docId)
    }

    // ── 广播唤醒（评审：全部唤醒，不是后到覆盖先到） ────────────

    @Test
    fun `两个等待者都被同一状态写入唤醒`() = runBlocking {
        val c = coordinator()
        val h = nav(c, tabId = 1)
        c.markCommitted(h.navigationId, "d1g2")
        val w1 = async { c.awaitNavigation(h.navigationId, 5_000) { it.readinessStatus == ReadinessStatus.DOM_READY } }
        val w2 = async { c.awaitNavigation(h.navigationId, 5_000) { it.readinessStatus == ReadinessStatus.DOM_READY } }
        withTimeout(2_000) {
            launch { c.setReadiness(h.navigationId, ReadinessStatus.DOM_READY) }
            assertEquals(ReadinessStatus.DOM_READY, w1.await()?.readinessStatus)
            assertEquals(ReadinessStatus.DOM_READY, w2.await()?.readinessStatus)
        }
        Unit
    }

    @Test
    fun `取消导航唤醒全部等待者且状态为CANCELLED`() = runBlocking {
        val c = coordinator()
        val h = nav(c, tabId = 1)
        val waiter = async { c.awaitNavigation(h.navigationId, 5_000) { it.readinessStatus == ReadinessStatus.DOM_READY } }
        withTimeout(2_000) {
            launch { assertTrue(c.cancelNavigation(h.navigationId, "model changed mind")) }
            val s = waiter.await()!!
            assertEquals(NavigationStatus.CANCELLED, s.navigationStatus)
        }
        Unit
    }

    @Test
    fun `等待超时返回当前状态不抛异常`() = runBlocking {
        val c = coordinator()
        val h = nav(c, tabId = 1)
        val s = withTimeout(3_000) { c.awaitNavigation(h.navigationId, 150) { false } }
        assertEquals(NavigationStatus.STARTED, s?.navigationStatus)
        Unit
    }

    // ── 终态族 ─────────────────────────────────────────────────

    @Test
    fun `tab关闭终态化该tab全部在途导航`() {
        val c = coordinator()
        val h1 = nav(c, tabId = 1)
        val h2 = nav(c, tabId = 2)
        c.tabClosed(1)
        assertEquals(NavigationStatus.TAB_CLOSED, c.navigationState(h1.navigationId)?.navigationStatus)
        assertEquals(NavigationStatus.STARTED, c.navigationState(h2.navigationId)?.navigationStatus)
    }

    @Test
    fun `引擎释放终态化全部在途导航`() {
        val c = coordinator()
        val h1 = nav(c, tabId = 1)
        val h2 = nav(c, tabId = 2)
        c.engineDisposed()
        assertEquals(NavigationStatus.ENGINE_DISPOSED, c.navigationState(h1.navigationId)?.navigationStatus)
        assertEquals(NavigationStatus.ENGINE_DISPOSED, c.navigationState(h2.navigationId)?.navigationStatus)
        // 终态后写守卫拒绝任何写入
        assertFalse(c.markCommitted(h1.navigationId, "d1g9"))
        assertFalse(c.setReadiness(h2.navigationId, ReadinessStatus.DOM_READY))
    }

    // ── TTL 惰性折叠（虚拟时钟驱动，无真实等待） ────────────────

    @Test
    fun `终态超过TTL读取时折叠为EXPIRED_未终态不受TTL影响`() {
        val c = coordinator(ttlMs = 1_000)
        val h = nav(c, tabId = 1)
        c.markCommitted(h.navigationId, "d1g2")
        clockMs += 10_000 // 远超 TTL，但导航未终态（COMMITTED 非终态）→ 不折叠
        assertEquals(NavigationStatus.COMMITTED, c.navigationState(h.navigationId)?.navigationStatus)

        val h2 = nav(c, tabId = 2)
        c.cancelNavigation(h2.navigationId)
        clockMs += 2_000 // 终态 + 超 TTL → EXPIRED
        assertEquals(NavigationStatus.EXPIRED, c.navigationState(h2.navigationId)?.navigationStatus)
    }

    @Test
    fun `在途上限超限拒绝新导航`() {
        val c = coordinator(maxLive = 2)
        nav(c, tabId = 1)
        nav(c, tabId = 2)
        try {
            nav(c, tabId = 3)
            throw AssertionError("应当拒绝第 3 个在途导航")
        } catch (e: NavigationCoordinator.NavigationRejectedException) {
            // 预期：宁可快速拒绝不可无限堆积
        }
        // 终态化一个后可再发起
        c.cancelNavigation(c.activeNavigationFor(1)!!)
        nav(c, tabId = 3) // 不抛
    }

    // ── 显式释放 ───────────────────────────────────────────────

    @Test
    fun `release仅对终态生效_在途拒绝`() {
        val c = coordinator()
        val live = nav(c, tabId = 1)
        assertFalse(c.releaseNavigation(live.navigationId)) // 在途不可释放
        c.cancelNavigation(live.navigationId)
        assertTrue(c.releaseNavigation(live.navigationId))
        assertNull(c.navigationState(live.navigationId)) // 状态记录已移除
    }
}

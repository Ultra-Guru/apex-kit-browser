package com.apex.browser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AsyncDocRegistry] 意图测试（v2.1.0）：游标协议（oldestSeq/nextSeq/truncated）、
 * 桶淘汰（EVICTED 不是空列表）、环形覆盖丢失可检测、显式 drop。
 */
class AsyncDocRegistryTest {

    @Test
    fun `seq进程级单调递增_跨桶共享`() {
        val r = AsyncDocRegistry()
        val e1 = r.append("d1g1", DocEventType.NAV_COMMITTED, "a", 1)
        val e2 = r.append("d1g1", DocEventType.DOM_READY, "b", 2)
        val e3 = r.append("d1g2", DocEventType.NAV_COMMITTED, "c", 3)
        assertTrue(e1.seq < e2.seq && e2.seq < e3.seq)
        assertEquals(e3.seq + 1, r.currentSeq() + 1)
    }

    @Test
    fun `增量读取since_seq只回新事件`() {
        val r = AsyncDocRegistry()
        r.append("d1g1", DocEventType.NAV_COMMITTED, "a", 1)
        r.append("d1g1", DocEventType.DOM_READY, "b", 2)
        r.append("d1g1", DocEventType.READINESS, "c", 3)
        val page = r.readSince("d1g1", sinceSeq = 1, limit = 10)
        assertEquals(2, page.events.size)
        assertEquals("b", page.events.first().detail)
        assertFalse(page.truncated)
        assertFalse(page.hasMore)
    }

    @Test
    fun `limit截断时hasMore为真`() {
        val r = AsyncDocRegistry()
        repeat(5) { r.append("d1g1", DocEventType.READINESS, "e$it", it.toLong()) }
        val page = r.readSince("d1g1", 0, limit = 2)
        assertEquals(2, page.events.size)
        assertTrue(page.hasMore)
    }

    @Test
    fun `环形覆盖后truncated为真且从oldestSeq起读`() {
        val r = AsyncDocRegistry(bucketCapacity = 3)
        repeat(6) { r.append("d1g1", DocEventType.READINESS, "e$it", it.toLong()) }
        // since=1：第 1 条已被覆盖 —— 必须可检测
        val page = r.readSince("d1g1", sinceSeq = 1, limit = 10)
        assertTrue(page.truncated)
        assertTrue(page.events.isNotEmpty())
        assertEquals(page.events.first().seq, page.oldestSeq)
    }

    @Test
    fun `只保留最近N个桶_淘汰桶返回EVICTED而非空列表`() {
        val r = AsyncDocRegistry(maxBuckets = 2)
        r.append("d1g1", DocEventType.NAV_COMMITTED, "a", 1)
        r.append("d1g2", DocEventType.NAV_COMMITTED, "b", 2)
        // 第 3 个 docId 挤掉最旧的 d1g1
        r.append("d1g3", DocEventType.NAV_COMMITTED, "c", 3)
        val evicted = r.readSince("d1g1", 0, 10, knownDocIds = r.knownDocIds() + "d1g1")
        assertTrue("淘汰桶必须明确报太老了", evicted.evicted)
        assertTrue(evicted.events.isEmpty())
        // 未知 docId（从未存在）不是 EVICTED
        val unknown = r.readSince("d9g9", 0, 10, knownDocIds = r.knownDocIds())
        assertFalse(unknown.evicted)
        assertTrue(unknown.events.isEmpty())
    }

    @Test
    fun `drop显式回收桶`() {
        val r = AsyncDocRegistry()
        r.append("d1g1", DocEventType.NAV_COMMITTED, "a", 1)
        r.drop("d1g1")
        assertFalse(r.knownDocIds().contains("d1g1"))
    }
}

package com.apex.browser.engine

import com.apex.browser.core.DomElement
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.1.0 运行时纯组件的意图测试（不触 WebView，纯 JVM）：
 * - [TabWorker]：同一 tab 串行（互斥）、并发调用全部完成（评审 A3 调度模型）；
 * - [SnapshotCache]：按 docId 键控，文档切换失效（DIFF 绝不拿旧页当基准）。
 */
class RuntimeServicesTest {

    @Test
    fun `TabWorker同tab互斥执行`() = runBlocking {
        val worker = TabWorker(tabId = 1)
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val jobs = (0..7).map { i ->
            async {
                worker.exclusive {
                    val now = concurrent.incrementAndGet()
                    maxConcurrent.updateAndGet { prev -> maxOf(prev, now) }
                    Thread.sleep(10) // 模拟短临界区
                    concurrent.decrementAndGet()
                    i
                }
            }
        }
        jobs.forEach { it.await() } // 全部完成后断言：worker 互斥保证峰值并发为 1
        assertEquals(1, maxConcurrent.get())
        Unit
    }

    @Test
    fun `SnapshotCache按docId键控_文档切换即失效`() {
        val cache = SnapshotCache()
        val elements = listOf(DomElement(bid = 1, ref = "r_a", tag = "button"))
        cache.put(tabId = 1, docId = "d1g1", elements = elements)
        assertEquals("d1g1", cache.get(1)?.docId)

        // 文档切换：invalidate（EngineRuntime.onPageCommitted 调用点）
        cache.invalidate(1)
        assertNull(cache.get(1))

        cache.put(1, "d1g2", elements)
        assertEquals("d1g2", cache.get(1)?.docId)
        cache.clear()
        assertNull(cache.get(1))
    }

    @Test
    fun `快照缓存按tab隔离`() {
        val cache = SnapshotCache()
        cache.put(1, "d1g1", listOf(DomElement(bid = 1, ref = "r_a", tag = "button")))
        cache.put(2, "d1g2", emptyList())
        assertEquals("d1g1", cache.get(1)?.docId)
        assertEquals("d1g2", cache.get(2)?.docId)
        cache.invalidate(2)
        assertNull(cache.get(2))
        assertTrue(cache.get(1) != null)
    }
}

package com.apex.browser.core

/**
 * v2.1.0 文档事件注册表（纯 JVM）：按 docId 分桶的环形事件缓冲 + 增量游标读取。
 *
 * 评审 P0-2 / P1-5 的落点：
 * - 每个 docId 一个独立桶（环形 ≤[bucketCapacity] 条）；
 * - **只保留最近 [maxBuckets] 个 docId 桶**，淘汰最旧桶 —— 已淘汰桶的查询返回
 *   [EventPage.evicted]=true（明确的「太老了」），而不是空列表（空列表=「没有新事件」）；
 * - [EventPage.truncated]=true 表示 since_seq 之前的事件已被环形覆盖 —— 丢失必须可检测；
 * - seq 为进程级单调递增（跨桶共享），保证多 tab 并发产生的事件有统一游标。
 *
 * 线程模型：全部方法 synchronized（写入路径来自主线程回调 + worker 协程，读路径来自
 * 工具协程；锁内只有内存操作，无阻塞调用，竞争面可忽略）。
 */
class AsyncDocRegistry(
    private val bucketCapacity: Int = DEFAULT_BUCKET_CAPACITY,
    private val maxBuckets: Int = DEFAULT_MAX_BUCKETS,
) {

    private class Bucket {
        val events = ArrayDeque<DocEvent>()
        /** 桶内现存最早事件 seq（环形覆盖后 > 桶内首条之前的都已丢失）。 */
        var oldestSeq: Long = Long.MAX_VALUE
        fun add(e: DocEvent) {
            events.addLast(e)
            if (oldestSeq == Long.MAX_VALUE) oldestSeq = e.seq
            while (events.size > bucketCapacity) {
                events.removeFirst()
                oldestSeq = events.first().seq
            }
        }
    }

    private val lock = Any()
    private val buckets = LinkedHashMap<String, Bucket>()
    private var nextSeqValue: Long = 1L

    /** 追加一条事件（分配进程级 seq）。type/docId/detail 由调用方给定。 */
    fun append(docId: String, type: DocEventType, detail: String, timestampMs: Long): DocEvent {
        synchronized(lock) {
            val e = DocEvent(seq = nextSeqValue++, docId = docId, type = type, detail = detail, timestampMs = timestampMs)
            buckets.getOrPut(docId) { Bucket() }.add(e)
            // LRU：touch 后重插到尾部，淘汰时移除头部（最旧 docId）
            val b = buckets.remove(docId)!!
            buckets[docId] = b
            while (buckets.size > maxBuckets) {
                buckets.remove(buckets.keys.first())
            }
            return e
        }
    }

    /**
     * 增量读取：返回 seq > [sinceSeq] 的事件（最多 [limit] 条）。
     * 桶存在但 since_seq 落在环形覆盖区 → truncated=true + oldestSeq 起始；
     * 桶不存在 → 若 [knownDocIds] 曾记录过该 docId（已淘汰）则 evicted=true，
     * 否则按「未知文档」返回空页（调用方上层结合 coordinator 判定 EXPIRED/不存在）。
     */
    fun readSince(docId: String, sinceSeq: Long, limit: Int, knownDocIds: Set<String> = emptySet()): EventPage {
        synchronized(lock) {
            val bucket = buckets[docId]
            if (bucket == null) {
                val evicted = docId in knownDocIds
                return EventPage(
                    docId = docId, events = emptyList(),
                    oldestSeq = if (evicted) -1 else 0, nextSeq = nextSeqValue - 1,
                    truncated = false, evicted = evicted,
                )
            }
            val all = bucket.events
            val newest = all.lastOrNull()?.seq ?: (nextSeqValue - 1)
            val fresh = all.filter { it.seq > sinceSeq }
            val truncated = sinceSeq > 0 && sinceSeq < bucket.oldestSeq - 1 && all.isNotEmpty() &&
                all.first().seq > sinceSeq + 1 // since 到首条之间有空洞 = 已覆盖丢失
            val page = fresh.take(limit)
            return EventPage(
                docId = docId, events = page,
                oldestSeq = bucket.oldestSeq, nextSeq = newest + 1,
                truncated = truncated, evicted = false,
                hasMore = fresh.size > page.size,
            )
        }
    }

    /** 当前全局游标（下一事件将使用该 seq）。 */
    fun currentSeq(): Long = synchronized(lock) { nextSeqValue - 1 }

    /** 丢弃某桶（显式 release 时回收内存；未知桶静默忽略）。 */
    fun drop(docId: String) {
        synchronized(lock) { buckets.remove(docId) }
    }

    /** 已知 docId 全集（含已淘汰）——供 evicted 判定与诊断。容量极小（≤3 活跃 + 记录），直接记录。 */
    fun knownDocIds(): Set<String> = synchronized(lock) { buckets.keys.toSet() }

    companion object {
        /** 单桶环形容量：导航/就绪级事件每文档远用不满 128 条。 */
        const val DEFAULT_BUCKET_CAPACITY = 128

        /** 活跃桶上限：只保留最近 3 个文档的事件（评审 P1-5）。 */
        const val DEFAULT_MAX_BUCKETS = 3
    }
}

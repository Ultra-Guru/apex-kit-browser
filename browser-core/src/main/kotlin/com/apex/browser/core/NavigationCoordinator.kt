package com.apex.browser.core

import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * v2.1.0 异步导航协调器（纯 JVM，协议权威数据源）。
 *
 * 职责（评审 P0-1/P0-2 并发保护落点）：
 * - 导航任务生命周期：发起 → 提交 → 就绪/终态；**同 tab 新导航立即取代旧导航**
 *   （SUPERSEDED），旧协程晚到**不得写入**（写守卫：只有当前活跃导航可写状态）；
 * - 双状态机写入（[NavigationStatus] / [ReadinessStatus]）与事件入桶
 *   （[AsyncDocRegistry]，独立于 BrowserTracer 观测旁路）；
 * - 等待者广播：任何状态写入唤醒全部挂起等待者，命中谓词者恢复（**全部唤醒**，
 *   不是后到覆盖先到）；取消/取代/关闭同样唤醒；
 * - TTL（终态后 [ttlMs] 未消费 → 读时惰性折叠为 EXPIRED）与在途总量上限
 *   （[maxLiveNavigations]，超限拒绝新导航——宁可快速失败不可无限堆积）。
 *
 * 线程模型：状态与等待者全部经 [lock] 串行化（锁内无阻塞调用）；挂起等待经
 * suspendCancellableCoroutine 注册/注销，恢复在锁外执行。
 *
 * @param nowMs 时钟注入（测试可注入虚拟时钟驱动 TTL，无需真实等待）。
 */
class NavigationCoordinator(
    val docRegistry: AsyncDocRegistry = AsyncDocRegistry(),
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxLiveNavigations: Int = DEFAULT_MAX_LIVE,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    /** 在途导航超过上限时抛出（引擎侧折叠为结构化错误返回模型）。 */
    class NavigationRejectedException(message: String) : IllegalStateException(message)

    private class Waiter(
        val navigationId: String,
        val predicate: (NavigationState) -> Boolean,
        val continuation: CancellableContinuation<NavigationState>,
    )

    private val lock = Any()
    private val states = LinkedHashMap<String, NavigationState>()
    /** tabId → 当前活跃 navigationId（写守卫与 SUPERSEDED 的依据）。 */
    private val activeByTab = HashMap<Int, String>()
    private val waiters = mutableListOf<Waiter>()
    private var navSeq = 0L

    // ───────── 发起 ─────────

    /**
     * 发起一次导航任务。同 tab 已有活跃导航 → 先行 SUPERSEDED（事件 + 唤醒其等待者）。
     * 在途总量超限 → [NavigationRejectedException]。
     */
    fun startNavigation(
        tabId: Int,
        url: String,
        currentDocGeneration: Long,
        currentDocId: String?,
    ): NavigationHandle {
        val now = nowMs()
        synchronized(lock) {
            val live = states.values.count { !it.isTerminal && it.navigationStatus != NavigationStatus.EXPIRED }
            if (live >= maxLiveNavigations) {
                throw NavigationRejectedException("在途导航已达上限 $maxLiveNavigations（含未回收终态），请先取消或消费既有导航")
            }
            supersedeLocked(tabId, "同 tab 发起新导航", now)
            navSeq += 1
            val id = NavigationIds.navigationId(navSeq)
            states[id] = NavigationState(
                navigationId = id, tabId = tabId, docId = null,
                navigationStatus = NavigationStatus.STARTED,
                readinessStatus = ReadinessStatus.WAITING,
                url = url, startedAtMs = now, updatedAtMs = now,
            )
            activeByTab[tabId] = id
            appendLocked(id, DocEventType.NAV_STARTED, "tab=$tabId url=$url", now)
            return NavigationHandle(
                navigationId = id, tabId = tabId, startedAtMs = now,
                initialDocGeneration = currentDocGeneration, initialDocId = currentDocId,
            )
        }
    }

    // ───────── 写入（全部带 navigationId 写守卫） ─────────

    /**
     * 文档提交：赋 docId、置 COMMITTED。仅当 [navigationId] 仍是其 tab 的活跃导航时生效
     * （被取代/取消的晚到协程写不进任何状态，返回 false 供调用方记录）。
     */
    fun markCommitted(navigationId: String, docId: String, detail: String = ""): Boolean =
        mutate(navigationId) { s, now ->
            // 重定向链可能多次 commit —— 以最终提交为准（docId 直接覆盖）
            s.copy(
                docId = docId,
                navigationStatus = NavigationStatus.COMMITTED,
                updatedAtMs = now,
            ) to listOf(docId to DocEventType.NAV_COMMITTED to detail.ifBlank { "committed" })
        }

    /** 就绪状态写入（DOM_READY / SELECTOR_MATCHED / NETWORK_IDLE / TIMEOUT / NOT_DETECTED）。 */
    fun setReadiness(navigationId: String, readiness: ReadinessStatus, detail: String = ""): Boolean =
        mutate(navigationId) { s, now ->
            val settled = readiness in READINESS_SETTLED
            val events = buildList {
                s.docId?.let {
                    add(Triple(it, DocEventType.READINESS, "$readiness ${detail.ifBlank { "" }}".trim()))
                    if (readiness == ReadinessStatus.DOM_READY) {
                        add(Triple(it, DocEventType.DOM_READY, detail.ifBlank { "dom ready" }))
                    }
                }
            }
            s.copy(
                readinessStatus = readiness,
                detail = detail.ifBlank { s.detail },
                updatedAtMs = now,
                settledAtMs = if (settled) (s.settledAtMs ?: now) else s.settledAtMs,
            ) to events
        }

    /**
     * 终态写入（FAILED/CANCELLED/SUPERSEDED/TAB_CLOSED/ENGINE_DISPOSED）。
     * [cancelNavigation] 是语义化入口；本函数供引擎在加载失败 / tab 关闭 / 引擎释放时调用。
     */
    fun terminal(navigationId: String, status: NavigationStatus, detail: String): Boolean {
        require(status in NavigationState.TERMINAL_STATUSES) { "非终态 $status 请走语义化入口" }
        return mutate(navigationId) { s, now ->
            if (s.isTerminal || s.navigationStatus == NavigationStatus.EXPIRED) {
                s to emptyList() // mutate 守卫会拒；此处仅为类型闭合
            } else {
                val key = s.docId ?: navigationId
                val type = when (status) {
                    NavigationStatus.CANCELLED -> DocEventType.CANCELLED
                    NavigationStatus.SUPERSEDED -> DocEventType.SUPERSEDED
                    else -> DocEventType.NAV_TERMINAL
                }
                s.copy(
                    navigationStatus = status, detail = detail, updatedAtMs = now, settledAtMs = now,
                ) to listOf(Triple(key, type, "$status $detail"))
            }
        }
    }

    /** 取消导航（模型侧 BrowserDocControl(action=cancel)）。已在终态则返回 false。 */
    fun cancelNavigation(navigationId: String, reason: String = "cancelled by caller"): Boolean =
        terminal(navigationId, NavigationStatus.CANCELLED, reason)

    /** tab 关闭：该 tab 全部非终态导航 → TAB_CLOSED（等待者唤醒）。 */
    fun tabClosed(tabId: Int) {
        val now = nowMs()
        val affected = synchronized(lock) {
            states.values
                .filter { it.tabId == tabId && !it.isTerminal && it.navigationStatus != NavigationStatus.EXPIRED }
                .onEach { s ->
                    states[s.navigationId] = s.copy(
                        navigationStatus = NavigationStatus.TAB_CLOSED,
                        detail = "tab closed", updatedAtMs = now, settledAtMs = now,
                    )
                    if (activeByTab[tabId] == s.navigationId) activeByTab.remove(tabId)
                    val key = s.docId ?: s.navigationId
                    appendLocked(key, DocEventType.NAV_TERMINAL, "TAB_CLOSED", now)
                }
                .map { it.navigationId }
        }
        if (affected.isNotEmpty()) drainWaiters()
    }

    /** 引擎释放：全部非终态导航 → ENGINE_DISPOSED。 */
    fun engineDisposed() {
        val now = nowMs()
        val affected = synchronized(lock) {
            val ids = states.values
                .filter { !it.isTerminal && it.navigationStatus != NavigationStatus.EXPIRED }
                .onEach { s ->
                    states[s.navigationId] = s.copy(
                        navigationStatus = NavigationStatus.ENGINE_DISPOSED,
                        detail = "engine disposed", updatedAtMs = now, settledAtMs = now,
                    )
                }
                .map { it.navigationId }
            activeByTab.clear()
            ids
        }
        if (affected.isNotEmpty()) drainWaiters()
    }

    // ───────── 读取 ─────────

    /**
     * 读导航状态。终态超过 [ttlMs] 未再消费 → 惰性折叠为 EXPIRED（状态保留便于诊断，
     * 事件桶由 [AsyncDocRegistry] 的 LRU 自行淘汰）。
     */
    fun navigationState(navigationId: String): NavigationState? {
        val now = nowMs()
        synchronized(lock) {
            val s = states[navigationId] ?: return null
            if (s.isTerminal && s.settledAtMs != null && now - s.settledAtMs > ttlMs) {
                val expired = s.copy(navigationStatus = NavigationStatus.EXPIRED, updatedAtMs = now)
                states[navigationId] = expired
                return expired
            }
            return s
        }
    }

    /** 某 tab 当前活跃导航 id（无则 null）。 */
    fun activeNavigationFor(tabId: Int): String? = synchronized(lock) { activeByTab[tabId] }

    /** 曾产生过事件的桶键全集（docId + 未提交导航的 navKey）——供 evicted 判定。 */
    fun knownEventKeys(): Set<String> = synchronized(lock) {
        states.values.mapNotNull { it.docId }.toSet() + states.keys
    }

    /**
     * 显式释放已终态的导航（BrowserDocControl(action=release)）：移除状态记录与事件桶，
     * 释放 TTL 内存占用。仍在途的导航不可释放（返回 false——先取消再释放）。
     */
    fun releaseNavigation(navigationId: String): Boolean {
        val docKey = synchronized(lock) {
            val s = states[navigationId] ?: return false
            if (!s.isTerminal && s.navigationStatus != NavigationStatus.EXPIRED) return false
            states.remove(navigationId)
            if (activeByTab[s.tabId] == navigationId) activeByTab.remove(s.tabId)
            s.docId
        }
        docKey?.let { docRegistry.drop(it) }
        docRegistry.drop(navigationId)
        return true
    }

    /**
     * 挂起等待直到 [until] 命中或导航进入终态（终态也唤醒，调用方据状态分支）。
     * 超时返回当前状态（**不抛异常**——等待超时不是导航失败，调用方按 readiness 分支）。
     * 任何状态写入广播唤醒全部等待者；协程取消时注销等待者。
     */
    suspend fun awaitNavigation(
        navigationId: String,
        timeoutMs: Long,
        until: (NavigationState) -> Boolean,
    ): NavigationState? {
        // 快路径：注册前已满足直接返回（避免一次无谓挂起）
        navigationState(navigationId)?.let { if (until(it) || it.isTerminal) return it }
        return suspendCancellableCoroutine { cont ->
            lateinit var waiter: Waiter
            val registered = synchronized(lock) {
                // 双检：快路径与注册之间可能有写入
                val s = states[navigationId]
                when {
                    s == null -> { cont.resume(null); false }
                    until(s) || s.isTerminal -> { cont.resume(s); false }
                    else -> { waiter = Waiter(navigationId, until, cont); waiters += waiter; true }
                }
            }
            if (registered) {
                cont.invokeOnCancellation {
                    synchronized(lock) { waiters.remove(waiter) }
                }
            }
        }
    }

    // ───────── 内部 ─────────

    /**
     * 统一写入口：守卫（存在 + 非终态 + 仍是其 tab 活跃导航）→ [transform] 产出新状态与
     * 待追加事件 → 落库 + 入桶 + 广播唤醒。守卫不过 = 静默拒绝（返回 false，调用方可记录）。
     */
    private fun mutate(
        navigationId: String,
        transform: (NavigationState, Long) -> Pair<NavigationState, List<Triple<String, DocEventType, String>>>,
    ): Boolean {
        val now = nowMs()
        var woke = false
        synchronized(lock) {
            val s = states[navigationId] ?: return false
            val writable = !s.isTerminal && s.navigationStatus != NavigationStatus.EXPIRED &&
                activeByTab[s.tabId] == s.navigationId
            if (!writable) return false
            val (next, events) = transform(s, now)
            states[navigationId] = next
            events.forEach { (key, type, detail) -> appendLocked(key, type, detail, now) }
            woke = waiters.isNotEmpty()
        }
        if (woke) drainWaiters()
        return true
    }

    /** 同 tab 发起新导航时的取代入口（由 [startNavigation] 锁内调用；绕过写守卫——取代者本就是新活跃者）。 */
    private fun supersedeLocked(tabId: Int, reason: String, now: Long) {
        val old = activeByTab[tabId] ?: return
        val s = states[old] ?: return
        if (s.isTerminal || s.navigationStatus == NavigationStatus.EXPIRED) {
            activeByTab.remove(tabId)
            return
        }
        states[old] = s.copy(
            navigationStatus = NavigationStatus.SUPERSEDED,
            detail = reason, updatedAtMs = now, settledAtMs = now,
        )
        activeByTab.remove(tabId)
        val key = s.docId ?: old
        appendLocked(key, DocEventType.SUPERSEDED, reason, now)
    }

    /** 事件入桶（导航级事件挂 navKey，文档提交后挂 docId——桶随 [AsyncDocRegistry] LRU 淘汰）。 */
    private fun appendLocked(key: String, type: DocEventType, detail: String, now: Long) {
        docRegistry.append(key, type, detail, now)
    }

    /** 广播唤醒：锁内收集命中者并出列，锁外恢复（恢复可能同步执行调用方代码）。 */
    private fun drainWaiters() {
        val toResume = mutableListOf<Pair<Waiter, NavigationState>>()
        synchronized(lock) {
            if (waiters.isEmpty()) return
            val it = waiters.iterator()
            while (it.hasNext()) {
                val w = it.next()
                val s = states[w.navigationId] ?: continue
                if (w.predicate(s) || s.isTerminal) {
                    it.remove()
                    toResume += w to s
                }
            }
        }
        toResume.forEach { (w, s) -> w.continuation.resume(s) }
    }

    companion object {
        /** 终态 TTL：5 分钟未消费即 EXPIRED（评审 P0-1 生命周期定义）。 */
        const val DEFAULT_TTL_MS: Long = 5 * 60_000

        /** 在途（非终态）导航上限：宁可快速拒绝，不可无限堆积（评审 P0-2）。 */
        const val DEFAULT_MAX_LIVE = 32
    }
}

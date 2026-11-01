package com.apex.browser.engine

import android.util.Log
import com.apex.browser.core.AsyncDocRegistry
import com.apex.browser.core.DomElement
import com.apex.browser.core.NavigationCoordinator
import com.apex.browser.core.NavigationIds
import com.apex.browser.core.ReadinessStatus
import com.apex.browser.core.TaskOutcome
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.withLock

/**
 * v2.1.0 引擎运行时服务持有器（internal）：异步导航协调器 / 文档事件注册表 /
 * TabWorker / 任务注册表 / 快照缓存 / 事件中枢的唯一归属。
 *
 * 生命周期与 [BrowserEngine] 一致（惰性创建，[onEngineDisposed] 全量回收）；
 * 引擎只在四个钩子处触碰本类（onPageCommitted / onPageFinished / onTabClosed /
 * onEngineDisposed）+ 同步导航注册——协议权威数据源（[navigation]）与
 * BrowserTracer 观测旁路严格分离。
 */
internal class EngineRuntime(private val engine: BrowserEngine) {

    /**
     * 宿主回退开关（browserAsyncNavEnabled）：false 时 navigateAsync / runTask
     * 内部降级为阻塞语义（结果形状兼容，只是不异步）——某设备/WebView 版本
     * 异步路径异常时的逃生口（评审 P0 回退 flag）。
     */
    val asyncEnabled = AtomicBoolean(true)

    /** 导航协调器（双状态机 + SUPERSEDED 写守卫 + TTL + 在途上限）。 */
    val navigation = NavigationCoordinator()

    /** 文档事件注册表（分桶环形 + since_seq 游标）。 */
    val docEvents: AsyncDocRegistry get() = navigation.docRegistry

    /**
     * 运行时协程作用域：Main.immediate + SupervisorJob + 异常记录（与引擎 mainScope
     * 同纪律，独立生命周期便于 [onEngineDisposed] 一次性取消）。
     */
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> Log.w("BrowserEngine.runtime", "runtime task failed", e) }
    )

    private val workers = ConcurrentHashMap<Int, TabWorker>()
    private val hubs = ConcurrentHashMap<Int, TabEventHub>()
    internal val snapshotCache = SnapshotCache()
    private val tasks = ConcurrentHashMap<String, TaskEntry>()
    private val taskSeq = AtomicLong(0)

    // ───────── 组件获取 ─────────

    fun workerFor(tabId: Int): TabWorker = workers.getOrPut(tabId) { TabWorker(tabId) }

    fun hubFor(tabId: Int): TabEventHub = hubs.getOrPut(tabId) { TabEventHub(engine, tabId) }

    /** tab 当前 docId（尚无已提交文档时 null）。 */
    fun docIdOf(tab: BrowserEngine.Tab): String? =
        if (tab.docGeneration > 0) NavigationIds.docId(tab.id, tab.docGeneration) else null

    // ───────── 引擎钩子（BrowserEngine.kt 唯一触碰面） ─────────

    /**
     * 同步 [BrowserEngine.navigate] 的注册入口：等待循环维持既有实现（阻塞语义零
     * 变更），仅把任务注册进协调器——COMMITTED/DOM_READY 由引擎钩子统一写入，
     * 该 tab 在途异步导航被 SUPERSEDED（同步与人工路径优先）。
     * 在途上限拒绝时注册跳过（同步路径永不因协议注册失败而失败；观测旁路仍在）。
     */
    fun registerSyncNavigation(tab: BrowserEngine.Tab, url: String) {
        runCatching { navigation.startNavigation(tab.id, url, tab.docGeneration, docIdOf(tab)) }
            .onFailure {
                if (it is NavigationCoordinator.NavigationRejectedException) {
                    Log.w("BrowserEngine.runtime", "sync nav register skipped: ${it.message}")
                } else throw it
            }
    }

    /** 主框架文档提交（onPageCommitVisible）：快照缓存失效 + 活跃导航 COMMITTED + docId。 */
    fun onPageCommitted(tab: BrowserEngine.Tab, url: String?) {
        snapshotCache.invalidate(tab.id) // 文档切换 → 旧页缓存绝不作为 DIFF 基准
        val nav = navigation.activeNavigationFor(tab.id) ?: return
        val docId = NavigationIds.docId(tab.id, tab.docGeneration)
        navigation.markCommitted(nav, docId, "url=${url ?: tab.url}")
    }

    /** pageFinished（onPageFinished）：活跃导航 DOM_READY（等待者广播唤醒）。 */
    fun onPageFinished(tab: BrowserEngine.Tab) {
        val nav = navigation.activeNavigationFor(tab.id) ?: return
        navigation.setReadiness(nav, ReadinessStatus.DOM_READY, "onPageFinished")
    }

    /** tab 关闭：在途导航 TAB_CLOSED 终态化 + 本 tab 全部运行时组件回收。 */
    fun onTabClosed(tabId: Int) {
        navigation.tabClosed(tabId)
        workers.remove(tabId)
        hubs.remove(tabId)
        snapshotCache.invalidate(tabId)
        tasks.values.removeAll { it.tabId == tabId && it.outcome == null }
    }

    /** 引擎释放：先终态化全部在途导航并停协程（顺序不可反——等待者恢复可能触碰 WebView）。 */
    fun onEngineDisposed() {
        navigation.engineDisposed()
        scope.cancel()
        tasks.clear()
        workers.clear()
        hubs.clear()
        snapshotCache.clear()
    }

    // ───────── 后台任务注册表 ─────────

    fun nextTaskId(): String = "t${taskSeq.incrementAndGet()}"

    fun putTask(entry: TaskEntry) {
        tasks[entry.taskId] = entry
        // 终态且已消费结果的条目超过上限时淘汰最旧（防长会话堆积）
        if (tasks.size > MAX_TASK_ENTRIES) {
            tasks.values.filter { it.outcome != null }
                .minByOrNull { it.createdAtMs }
                ?.let { tasks.remove(it.taskId) }
        }
    }

    fun getTask(taskId: String): TaskEntry? = tasks[taskId]

    fun removeTask(taskId: String) {
        tasks.remove(taskId)
    }

    internal class TaskEntry(
        val taskId: String,
        val job: Job,
        val tabId: Int,
        val createdAtMs: Long = System.currentTimeMillis(),
        @Volatile var outcome: TaskOutcome? = null,
    )

    companion object {
        private const val MAX_TASK_ENTRIES = 32
    }
}

/**
 * v2.1.0 Tab 级串行 worker（评审 A3 调度模型）：同一 tab 的任务步骤严格串行
 * （导航/点击/输入互相覆盖是状态竞争的根源），跨 tab 各自 worker 天然并行
 * （Main 线程只做派发，renderer 侧并行）。
 *
 * 串行域口径（v1 如实声明）：任务内步骤与任务之间（同 tab）经本锁互斥；
 * 散工具调用与任务的原子互斥留待工具面接线——WebView Main 线程派发保证单次
 * 求值原子，但跨多次求值的逻辑竞态仍需工具面配合（文档已注明）。
 */
internal class TabWorker(val tabId: Int) {
    private val mutex = kotlinx.coroutines.sync.Mutex()

    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }
}

/**
 * v2.1.0 快照缓存（DIFF/FOCUSED 的基准库）：每 tab 缓存最近一次快照的元素集，
 * **按 docId 键控**——文档切换即失效（[EngineRuntime.onPageCommitted] 强制 invalidate），
 * 绝不拿旧页当 DIFF 基准（评审 P1-1/P2 缓存纪律）。
 */
internal class SnapshotCache {
    internal data class Cached(val docId: String, val elements: List<DomElement>)

    private val perTab = ConcurrentHashMap<Int, Cached>()

    fun put(tabId: Int, docId: String, elements: List<DomElement>) {
        perTab[tabId] = Cached(docId, elements)
    }

    fun get(tabId: Int): Cached? = perTab[tabId]

    fun invalidate(tabId: Int) {
        perTab.remove(tabId)
    }

    fun clear() = perTab.clear()
}

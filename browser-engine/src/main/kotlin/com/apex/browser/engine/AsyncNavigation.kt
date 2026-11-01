package com.apex.browser.engine

import com.apex.browser.core.EventPage
import com.apex.browser.core.NavigationCoordinator
import com.apex.browser.core.NavigationHandle
import com.apex.browser.core.NavigationState
import com.apex.browser.core.NavigationStatus
import com.apex.browser.core.READINESS_SETTLED
import com.apex.browser.core.ReadinessStatus
import com.apex.browser.core.WaitCondition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v2.1.0 异步导航协议的引擎公开面（扩展函数——库仓「公开面纯增量」纪律的落点）。
 *
 * 与同步 [BrowserEngine.navigate] 共享同一协调器（状态/事件单源）：同步导航注册进
 * 同一协议（COMMITTED/DOM_READY 由引擎钩子写入），等待循环维持既有实现不变；
 * 异步路径 = startNavigation + 立即回 handle + 协程推进，模型可先做其他独立工作。
 *
 * 超时语义（评审 P0：**停止等待 ≠ 取消导航**）——仅作用于就绪等待：
 * readiness TIMEOUT 后导航继续后台推进，模型可重新 wait；commit 阶段超时属导航
 * 失败（FAILED，页面迟迟不提交本就是病态）。要真取消走 [cancelNavigation]。
 */

/**
 * 发起异步导航：立即返回 [NavigationHandle]，加载与就绪判定后台推进。
 *
 * @param newTab 在新标签打开（默认当前/新建活动标签）
 * @param waitForSelector 兼容参数：等价 waitUntil="selector"
 * @param waitUntil 就绪目标："dom_ready"（默认）| "selector"（需 waitForSelector）| "network_idle"
 * @param timeoutMs 提交阶段与就绪阶段的各自超时
 *
 * 回退开关（[EngineRuntime.asyncEnabled]=false）：降级为阻塞 [BrowserEngine.navigate]，
 * handle 在导航完成后返回（状态已 settled，语义兼容只是不异步）。
 *
 * @throws NavigationCoordinator.NavigationRejectedException 在途导航超上限
 */
suspend fun BrowserEngine.navigateAsync(
    url: String,
    newTab: Boolean = false,
    waitForSelector: String? = null,
    timeoutMs: Long = 15_000,
    waitUntil: String = "dom_ready",
): NavigationHandle {
    val runtime = this.runtime
    val normalized = if (url.startsWith("http")) url else "https://$url"
    markBrowserActiveFromHidden()

    // 回退路径：阻塞语义（handle 事后合成，协议形状一致——状态已 settled）
    if (!runtime.asyncEnabled.get()) {
        if (newTab) newTab(null)
        val tab = requireActiveTab()
        runtime.registerSyncNavigation(tab, normalized)
        navigate(normalized, waitForSelector, timeoutMs)
        return NavigationHandle(
            navigationId = runtime.navigation.activeNavigationFor(tab.id) ?: "n_degraded",
            tabId = tab.id,
            startedAtMs = System.currentTimeMillis(),
            initialDocGeneration = tab.docGeneration,
            initialDocId = runtime.docIdOf(tab),
        )
    }

    // 导航前先锁 worker：与同 tab 上的其他任务步骤互斥（评审 A3 同 tab 串行）。
    // handle 必须在锁外立即返回——worker 只包住后台推进协程。
    if (newTab) newTab(null)
    val tab = requireActiveTab()
    val handle = runtime.navigation.startNavigation(tab.id, normalized, tab.docGeneration, runtime.docIdOf(tab))
    val mode = if (waitForSelector != null) "selector" else waitUntil
    val worker = runtime.workerFor(tab.id)
    runtime.scope.launch {
        worker.exclusive { runNavigationJob(handle.navigationId, tab, normalized, mode, waitForSelector, timeoutMs) }
    }
    return handle
}

/** 无激活 tab 时新建（与同步 navigate 的「导航即建页」语义一致）。 */
private suspend fun BrowserEngine.requireActiveTab(): BrowserEngine.Tab {
    activeTab()?.let { return it }
    newTab(null)
    return activeTab() ?: error("tab 创建失败（Main 线程不可达或引擎已释放）")
}

/** 后台推进协程：提交等待 → 就绪等待；全部状态写入经协调器（写守卫自动拒绝被取代者）。 */
private suspend fun BrowserEngine.runNavigationJob(
    navigationId: String,
    tab: BrowserEngine.Tab,
    url: String,
    mode: String,
    waitForSelector: String?,
    timeoutMs: Long,
) {
    try {
        // (1) 提交阶段：loadUrl + 等 onPageCommitVisible（提交超时 = 导航失败，非就绪超时）
        withContext(Dispatchers.Main) { tab.webView.loadUrl(url) }
        val committed = runtime.navigation.awaitNavigation(navigationId, timeoutMs) {
            it.navigationStatus != NavigationStatus.STARTED
        }
        when {
            committed == null -> return // 状态被 release（罕见）：静默收场
            committed.isTerminal -> return // 取消/取代/关闭已终态化，无需推进
            // 提交超时（awaitNavigation 到期返回 STARTED 快照）= 导航失败
            committed.navigationStatus == NavigationStatus.STARTED -> {
                runtime.navigation.terminal(
                    navigationId, NavigationStatus.FAILED,
                    "commit 未在 ${timeoutMs}ms 内发生（页面加载极慢或失败）",
                )
                return
            }
            else -> Unit // COMMITTED
        }

        // (2) 就绪阶段：按 waitUntil 等待（超时 = 停止等待不取消，readiness TIMEOUT）
        val outcome: ReadinessStatus = when (mode) {
            "dom_ready" -> {
                // DOM_READY 由 onPageFinished 钩子写入；这里挂起等广播
                val st = runtime.navigation.awaitNavigation(navigationId, timeoutMs) {
                    it.readinessStatus in READINESS_SETTLED
                }
                if (st != null && st.readinessStatus in READINESS_SETTLED) st.readinessStatus
                else ReadinessStatus.TIMEOUT
            }
            "selector" -> {
                val sel = waitForSelector
                    ?: return finishWithReadiness(navigationId, ReadinessStatus.TIMEOUT, "selector 模式缺 waitForSelector")
                val o = runtime.hubFor(tab.id).await(tab, WaitCondition("selector", sel, timeoutMs))
                if (o.matched) ReadinessStatus.SELECTOR_MATCHED else ReadinessStatus.TIMEOUT
            }
            "network_idle" -> {
                val o = runtime.hubFor(tab.id).await(tab, WaitCondition("network_idle", "", timeoutMs))
                if (o.matched) ReadinessStatus.NETWORK_IDLE
                else if (o.notDetected) ReadinessStatus.NOT_DETECTED
                else ReadinessStatus.TIMEOUT
            }
            else -> ReadinessStatus.TIMEOUT
        }
        finishWithReadiness(navigationId, outcome)
    } catch (e: CancellationException) {
        throw e // 协程取消（引擎释放/worker 取消）由协调器终态化，这里正常退出
    } catch (e: Exception) {
        runtime.navigation.terminal(navigationId, NavigationStatus.FAILED, "导航协程异常：${e.message?.take(160)}")
    }
}

private fun BrowserEngine.finishWithReadiness(navigationId: String, readiness: ReadinessStatus, detail: String = "") {
    runtime.navigation.setReadiness(navigationId, readiness, detail)
}

// ───────── 协议查询 / 控制 ─────────

/** 读导航状态（EXPIRED 惰性折叠；未知 id 返回 null）。 */
fun BrowserEngine.navigationState(navigationId: String): NavigationState? =
    runtime.navigation.navigationState(navigationId)

/**
 * 增量读取文档事件（since_seq 游标；桶淘汰返回 evicted=true；环形覆盖返回 truncated=true——
 * 丢失必须可检测，评审 P0-2）。
 */
fun BrowserEngine.docEvents(
    navigationOrDocId: String,
    sinceSeq: Long = 0,
    limit: Int = 50,
): EventPage = runtime.docEvents.readSince(
    navigationOrDocId, sinceSeq, limit.coerceIn(1, 128), runtime.navigation.knownEventKeys(),
)

/**
 * 取消导航：在途 → CANCELLED（等待者广播唤醒）；已终态返回 false。
 * 已发出的加载本身无法撤回——由后续导航取代，或模型不再消费该 handle（文档口径）。
 */
fun BrowserEngine.cancelNavigation(navigationId: String, reason: String = "cancelled by caller"): Boolean =
    runtime.navigation.cancelNavigation(navigationId, reason)

/** 显式释放已终态导航（状态记录 + 事件桶回收）；仍在途返回 false（先取消再释放）。 */
fun BrowserEngine.releaseNavigation(navigationId: String): Boolean =
    runtime.navigation.releaseNavigation(navigationId)

/** 当前活动 tab 的 docId（工具面返回给模型做 expected_doc_id 校验用；无提交文档为 null）。 */
fun BrowserEngine.activeDocId(): String? = activeTab()?.let { runtime.docIdOf(it) }

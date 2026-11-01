package com.apex.browser.core

import kotlinx.serialization.Serializable

/**
 * v2.1.0 异步导航协议的数据模型（纯 JVM，无 Android 依赖）。
 *
 * 三标识分离（评审 P0-1）——一次「导航任务」与「实际文档」是两个生命周期：
 * - [NavigationHandle.navigationId]：一次导航任务，从发起（或降级同步路径合成）到终态；
 * - [NavigationState.docId]：一个实际加载的文档 `d<tabId>g<generation>`，在**主框架文档
 *   提交时**才赋值（导航请求可能重定向 / 失败 / 被取代，不代表任何文档）；
 * - tabId：浏览器标签，从创建到关闭。
 *
 * 双状态机（评审 P0-2）——「页面加载失败」与「等待条件未满足」不能混为一谈：
 * - [NavigationStatus] 回答「这次导航怎么样了」；
 * - [ReadinessStatus] 回答「Agent 等的条件满足了没」。
 * 页面 COMMITTED 但 readiness TIMEOUT 是合法组合：页面正常，只是条件没等到。
 */
object NavigationIds {
    /** 文档代际格式：`d<tabId>g<generation>`。generation 为 0 表示「尚无已提交文档」。 */
    fun docId(tabId: Int, generation: Long): String = "d${tabId}g$generation"

    private const val NAV_PREFIX = "n"

    /** 导航任务 id：`n<自增序号>`。 */
    fun navigationId(seq: Long): String = "$NAV_PREFIX$seq"

    /** 从任意标识串解析导航 id（容忍调用方误传 docId 时返回 null）。 */
    fun asNavigationId(raw: String): String? = raw.takeIf { it.startsWith(NAV_PREFIX) && it.length > 1 }
}

/** 导航任务状态（「这次导航怎么样了」）。 */
enum class NavigationStatus {
    /** 已发起，尚未提交文档。 */
    STARTED,
    /** 主框架文档已提交（onPageCommitVisible 等价信号），docId 已赋值。 */
    COMMITTED,
    /** 导航失败（加载错误 / scheme 拦截 / 提交前异常）。 */
    FAILED,
    /** 被显式取消（cancelNavigation）。 */
    CANCELLED,
    /** 同一 tab 发起了新导航，本导航被取代；其协程晚到也不得写入任何状态。 */
    SUPERSEDED,
    /** 所属 tab 已关闭。 */
    TAB_CLOSED,
    /** 引擎释放（destroy）。 */
    ENGINE_DISPOSED,
    /** 终态后超过 TTL 未再被消费，事件与状态视为过期（惰性判定，读时折叠）。 */
    EXPIRED,
}

/** 就绪状态（「Agent 等的条件满足了没」）——与 [NavigationStatus] 正交。 */
enum class ReadinessStatus {
    /** 尚在等待（含「还没开始等」）。 */
    WAITING,
    /** DOM 已就绪（文档提交 + pageFinished）。 */
    DOM_READY,
    /** 指定 CSS 选择器已出现。 */
    SELECTOR_MATCHED,
    /** 网络静默（静默窗口内 fetch/xhr 计数为零）。 */
    NETWORK_IDLE,
    /** 等待条件超时 —— **不等于页面失败**，页面可能仍在正常加载。 */
    TIMEOUT,
    /** 网络监控钩子未注入，无法可靠判定 network_idle（诚实上报，绝不假装支持）。 */
    NOT_DETECTED,
}

/** 就绪终态集合：任一命中即视为「导航已settled」（TTL 与唤醒以此为锚）。 */
val READINESS_SETTLED: Set<ReadinessStatus> =
    setOf(ReadinessStatus.DOM_READY, ReadinessStatus.SELECTOR_MATCHED, ReadinessStatus.NETWORK_IDLE)

/**
 * 异步导航句柄（[BrowserNavigateAsync 工具面]立即返回值）。
 *
 * [initialDocGeneration] 是发起时的文档代际（导航成功后 docId 的代际必然 ≥ 此值+1），
 * 供调用方做「发起前后文档是否换过」的快速判断。
 */
data class NavigationHandle(
    val navigationId: String,
    val tabId: Int,
    val startedAtMs: Long,
    val initialDocGeneration: Long,
    /** 发起时该 tab 的 docId（可能为 null = 该 tab 尚无已提交文档）。 */
    val initialDocId: String?,
)

/**
 * 导航任务的权威状态（coordinator 独立存储——BrowserTracer 只是观测旁路，协议数据源在此）。
 */
data class NavigationState(
    val navigationId: String,
    val tabId: Int,
    /** 已提交文档 id；COMMIT 前为 null。 */
    val docId: String?,
    val navigationStatus: NavigationStatus,
    val readinessStatus: ReadinessStatus,
    /** 发起时的目标 URL（诊断用；已提交文档的真实 URL 以 docId 对应文档为准）。 */
    val url: String,
    /** 失败 / 取消 / 取代的原因摘要。 */
    val detail: String? = null,
    val startedAtMs: Long,
    val updatedAtMs: Long,
    /** settled（终态或就绪命中）时刻，TTL 锚点；未 settled 为 null。 */
    val settledAtMs: Long? = null,
) {
    /** 是否已进入不可逆终态（EXPIRED 除外——它由读时惰性折叠）。 */
    val isTerminal: Boolean =
        navigationStatus in TERMINAL_STATUSES

    companion object {
        val TERMINAL_STATUSES: Set<NavigationStatus> = setOf(
            NavigationStatus.FAILED,
            NavigationStatus.CANCELLED,
            NavigationStatus.SUPERSEDED,
            NavigationStatus.TAB_CLOSED,
            NavigationStatus.ENGINE_DISPOSED,
        )
    }
}

/**
 * 条件等待请求（导航就绪 / BrowserWaitFor / 任务 wait 步骤共用同一模型）。
 *
 * [mode] 与引擎 waitForCondition 的七模式 + dom_stable 同词汇表：
 * selector / text / url / element_gone / text_gone / network_idle / selector_count / dom_stable。
 */
data class WaitCondition(
    val mode: String,
    val value: String = "",
    val timeoutMs: Long = 10_000,
    /** network_idle 静默窗口毫秒（默认 750；评审 P1-3：短窗口会被连发请求击穿）。 */
    val quietWindowMs: Long = 750,
) {
    fun describe(): String = if (value.isBlank()) mode else "$mode('$value')"
}

/** 事件类型（按 docId 分桶记录；导航级事件挂在所属 docId，未提交前挂 navKey 桶）。 */
enum class DocEventType {
    NAV_STARTED, NAV_COMMITTED, DOM_READY, READINESS, NAV_TERMINAL, SUPERSEDED, CANCELLED,
}

/**
 * 单条文档事件。[seq] 为**进程级单调递增**游标（跨桶共享），消费方按 since_seq 增量拉取。
 */
@Serializable
data class DocEvent(
    val seq: Long,
    val docId: String,
    val type: DocEventType,
    val detail: String,
    val timestampMs: Long,
)

/**
 * 事件增量页（评审 P0-2：游标三字段 oldestSeq/nextSeq/truncated 让消费方能检测丢失）。
 *
 * - [truncated]=true 表示 [sinceSeq] 之前的事件已被环形覆盖 —— 消费方必须知道有丢失，
 *   而不是把残缺序列当成完整历史；
 * - [evicted]=true 表示 [docId] 所属的桶已因「只保留最近 N 个文档」被淘汰——
 *   返回**明确的太老了**，而非空列表（空列表的语义是「没有新事件」）。
 */
data class EventPage(
    val docId: String,
    val events: List<DocEvent>,
    val oldestSeq: Long,
    val nextSeq: Long,
    val truncated: Boolean,
    val evicted: Boolean = false,
    val hasMore: Boolean = false,
)

@file:JvmName("DomModel")

package com.apex.browser.core

import kotlinx.serialization.Serializable

/**
 * 浏览器页面中的单个可交互 / 可索引元素。
 *
 * 相比 Operit 的 [data-bid] 方案，这里额外保留 [rect]（相对 WebView 的像素矩形）、
 * [isVisible]、[depth]（DOM 深度），使 Agent 既能做「DOM 级精确点击」，也能在
 * 需要时用坐标兜底，并能用 [depth] 控制摘要压缩层级。
 */
@Serializable
data class DomElement(
    /** 展示用顺序索引（1..n），仅作人类可读编号，不作定位主键 */
    val bid: Int,
    /** 语义哈希稳定引用（data-apex-hash），如 "r_3k9f"：基于 role+text+tag+相对位置计算，
     *  刷新/局部变动后稳定；所有点击、输入、选择操作均优先用本字段定位（对标 Operit 的 aria-ref）。 */
    val ref: String = "",
    val tag: String,
    val text: String = "",
    /** 可读的稳定描述，例如 "BUTTON 搜索" 或 "A 新闻标题" */
    val label: String = "",
    val attributes: Map<String, String> = emptyMap(),
    /** 相对页面内容区的像素矩形（x, y, width, height） */
    val rect: Rect = Rect(0, 0, 0, 0),
    val isVisible: Boolean = true,
    val isInteractive: Boolean = false,
    /** DOM 深度，用于摘要压缩时优先裁剪深层非交互节点 */
    val depth: Int = 0,
    /** 直接子节点数量，用于判断容器 */
    val childCount: Int = 0,
)

@Serializable
data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * 一帧页面的结构化快照，发送给 Agent。
 *
 * [interactiveElements] 是经过裁剪、带 [DomElement.bid] 的可交互元素列表；
 * [domSummary] 是面向 LLM 的紧凑文本树（已压缩），用于整体理解页面。
 */
@Serializable
data class PageSnapshot(
    val url: String,
    val title: String,
    val scrollY: Int,
    val scrollHeight: Int,
    val viewportHeight: Int,
    val interactiveCount: Int,
    /** 紧凑的可读文本树，已按 token 预算裁剪，直接进 prompt */
    val domSummary: String,
    /** 完整可交互元素列表，供工具按 bid 精准操作 */
    val interactiveElements: List<DomElement>,
    /**
     * 注入脚本是否因单次抓取上限（[BrowserScript.SNAPSHOT_MAX_ELEMENTS]）而截断。
     *
     * 意图：不告知截断，模型会把「看到的 N 个」当成「页面全部」，据此规划后续动作
     * 必然踩空（稠密列表页最典型）。宿主可据此决定是否滚动翻页后重抓。
     */
    val truncated: Boolean = false,
    /** 页面实际匹配到的元素总数（可能大于 [interactiveCount]，差值即剪枝或截断掉的） */
    val totalCandidateCount: Int = 0,
    /**
     * 快照生成时刻（System.currentTimeMillis()，v1.3.0）。
     *
     * 消费方据此判断快照新鲜度（多轮对话间页面是否已被自动化/用户改变）、
     * 对多来源快照排序。0 表示未填充（兼容旧构造点与反序列化旧数据）。
     */
    val timestampMs: Long = 0,
    /**
     * 本次快照捕获到的 JS 弹窗 / SSL 拦截 / 权限请求 / 下载通知（v1.3.0）。
     *
     * 引擎把最近一次未消费的弹窗文本写入快照后**随即清空**（消费即清语义）——
     * 模型在下一次 snapshot 时必然看到这则通知，而不会反复收到同一条。
     * null 表示期间无弹窗事件。字段为带默认值的尾参，既有构造点零改动。
     */
    val dialogNotice: String? = null,
    /**
     * 快照所属文档 id（`d<tabId>g<generation>`，v2.1.0）。空串 = 未填充（无激活标签页
     * 等退化路径）。模型把它带回 `expected_doc_id` 即可获得 STALE_DOCUMENT 前置校验
     * （评审 P1-1 快照过期闭环）；DIFF 模式以本字段做基准命中判定。
     */
    val docId: String = "",
)

/** 从 JS 注入点拿到的原始元素（序列化自 injected JS） */
@Serializable
internal data class RawDomElement(
    val tag: String,
    val text: String?,
    val attributes: Map<String, String>,
    val rect: Rect,
    val isVisible: Boolean,
    val isInteractive: Boolean,
    val depth: Int,
    val childCount: Int,
)

/**
 * 注入脚本的回传信封（[BrowserScript.snapshotJs] 的新版返回形态）。
 *
 * 元素数组之外额外携带 `total` / `truncated`，让 [DomParser] 能如实告知模型
 * 「页面里还有更多元素」—— 否则单次抓取上限会表现为「页面就这些」。
 *
 * 字段名与 JS 侧字面量一一对应（`v` / `total` / `truncated` / `elements`）。
 */
@Serializable
internal data class RawSnapshotEnvelope(
    val v: Int = 1,
    val total: Int = 0,
    val truncated: Boolean = false,
    val elements: List<RawDomElement> = emptyList(),
)

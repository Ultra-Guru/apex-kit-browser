package com.apex.browser.core

/**
 * v2.1.0 预留接口（P2，本批不实现 VISUAL 档）：页面元素定位策略与降级链。
 *
 * 本批引擎内建使用 [DefaultInteractionChain] 的前两档（selector 直操作 → ref 模糊
 * 重锚定）；VISUAL（截图+视觉模型判定）与 HYBRID 留待后续接入。
 *
 * 定位纪律（评审 P2 缓存教训）：任何「最近一次成功定位」的缓存**必须以 docId 为键**，
 * 文档切换即失效 —— 本接口的实现不得跨文档复用元素句柄。
 */
enum class InteractionStrategy {
    /** CSS selector 直查（页面明确提供选择器时的最快路径）。 */
    FAST_SELECTOR,
    /** 无障碍语义定位（aria-label/role/ref 模糊重锚定）。 */
    ACCESSIBILITY,
    /** 结构化 DOM 查询（复杂结构下的局部快照+分析）。 */
    DOM_QUERY,
    /** 视觉定位（截图+视觉判定）——P2 预留。 */
    VISUAL,
    /** 混合（结构与视觉都需要理解的页面）——P2 预留。 */
    HYBRID,
}

/** 定位策略的降级链契约。 */
interface BrowserInteractionStrategy {
    /** 首选策略。 */
    val preferred: InteractionStrategy

    /** 首选失败后的降级顺序（不含 preferred 自身；以 [InteractionStrategy.FAST_SELECTOR] 起链）。 */
    fun fallbackChain(): List<InteractionStrategy>
}

/** 默认降级链：selector → 无障碍 → DOM 查询 →（P2）视觉。 */
object DefaultInteractionChain : BrowserInteractionStrategy {
    override val preferred: InteractionStrategy = InteractionStrategy.FAST_SELECTOR
    override fun fallbackChain(): List<InteractionStrategy> = listOf(
        InteractionStrategy.ACCESSIBILITY,
        InteractionStrategy.DOM_QUERY,
        InteractionStrategy.VISUAL,
    )
}

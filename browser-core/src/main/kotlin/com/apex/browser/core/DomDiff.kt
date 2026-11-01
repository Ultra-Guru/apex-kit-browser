package com.apex.browser.core

/**
 * v2.1.0 增量快照差分（纯 JVM）：以 ref 为主键对比两次元素集，产出 added/removed/updated。
 *
 * DIFF 模式的落点：引擎缓存最近一次快照元素（按 docId 键控，文档切换即失效），
 * 本次抓取后与基准差分 —— 模型只收变化部分，不再反复吃全页。
 *
 * 主键纪律：ref（data-apex-hash）是唯一定位主键；无 ref 元素无法建立对应关系，
 * 一律按「added」处理（保守方向：多报一条变更好过漏报一条）。
 */
object DomDiff {

    /** 单条变更（updated 保留新旧值，调用方按需渲染）。 */
    sealed interface Change {
        data class Added(val element: DomElement) : Change
        data class Removed(val ref: String, val label: String) : Change
        data class Updated(val ref: String, val label: String, val oldText: String, val newText: String) : Change
    }

    /** 差分结果（纯数据；[changes] 上限由调用方预算决定，此处不截断）。 */
    data class Diff(
        val baseDocId: String,
        val currentDocId: String,
        val changes: List<Change>,
    ) {
        fun isEmpty(): Boolean = changes.isEmpty()

        /** 面向模型的紧凑文本（空差分给出明确「无变化」）。 */
        fun render(maxChars: Int = 2000): String {
            if (changes.isEmpty()) return "(与基准 ${baseDocId} 相比无变化)"
            val sb = StringBuilder()
            var count = 0
            for (c in changes) {
                val line = when (c) {
                    is Change.Added -> "+ [${c.element.ref}] ${c.element.label}"
                    is Change.Removed -> "- [${c.ref}] ${c.label}"
                    is Change.Updated -> "~ [${c.ref}] ${c.label}: \"${c.oldText.take(40)}\" → \"${c.newText.take(40)}\""
                }
                if (sb.length + line.length + 1 > maxChars) {
                    sb.appendLine("…（其余 ${changes.size - count} 条变更截断）")
                    break
                }
                sb.appendLine(line)
                count++
            }
            return sb.toString().trim()
        }
    }

    /**
     * 以 ref 为主键差分。updated 判定：同 ref 下 text 或可见性或矩形变化。
     * [baseDocId]/[currentDocId] 不一致时照常计算（调用方负责 STALE 校验，
     * 本函数只做集合运算）。
     */
    fun diff(base: List<DomElement>, current: List<DomElement>, baseDocId: String, currentDocId: String): Diff {
        val baseByRef = base.associateBy { it.ref }.filterKeys { it.isNotBlank() }
        val currentByRef = current.associateBy { it.ref }.filterKeys { it.isNotBlank() }
        val changes = mutableListOf<Change>()

        for (e in current) {
            if (e.ref.isBlank()) {
                changes += Change.Added(e)
                continue
            }
            val old = baseByRef[e.ref]
            when {
                old == null -> changes += Change.Added(e)
                old.text != e.text || old.isVisible != e.isVisible || old.rect != e.rect ->
                    changes += Change.Updated(e.ref, e.label, old.text, e.text)
            }
        }
        for ((ref, old) in baseByRef) {
            if (ref !in currentByRef) changes += Change.Removed(ref, old.label)
        }
        return Diff(baseDocId, currentDocId, changes)
    }
}

package com.apex.browser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DomDiff] 意图测试（v2.1.0）：以 ref 为主键的 added/removed/updated 三类变更，
 * 无 ref 元素保守按 added 处理，render 的空差分明确「无变化」。
 */
class DomDiffTest {

    private fun el(ref: String, text: String, visible: Boolean = true) = DomElement(
        bid = 1, ref = ref, tag = "button", text = text, label = "按钮 $text",
        isVisible = visible, isInteractive = true,
    )

    @Test
    fun `三类变更全路径`() {
        val base = listOf(el("r_a", "旧A"), el("r_b", "B"), el("r_c", "C"))
        val current = listOf(el("r_a", "新A"), el("r_b", "B"), el("r_d", "D"))
        val diff = DomDiff.diff(base, current, "d1g1", "d1g1")
        val added = diff.changes.filterIsInstance<DomDiff.Change.Added>()
        val removed = diff.changes.filterIsInstance<DomDiff.Change.Removed>()
        val updated = diff.changes.filterIsInstance<DomDiff.Change.Updated>()
        assertEquals(listOf("r_d"), added.map { it.element.ref })
        assertEquals(listOf("r_c"), removed.map { it.ref })
        assertEquals(listOf("r_a"), updated.map { it.ref })
        assertEquals("旧A", updated.first().oldText)
        assertEquals("新A", updated.first().newText)
        assertTrue(diff.render().contains("~ [r_a]"))
    }

    @Test
    fun `可见性变化也是updated`() {
        val base = listOf(el("r_a", "A"))
        val current = listOf(el("r_a", "A", visible = false))
        val diff = DomDiff.diff(base, current, "d1g1", "d1g1")
        assertEquals(1, diff.changes.size)
        assertTrue(diff.changes.first() is DomDiff.Change.Updated)
    }

    @Test
    fun `无ref元素保守按added`() {
        val noRef = el("", "无名元素")
        val diff = DomDiff.diff(emptyList(), listOf(noRef), "d1g1", "d1g1")
        assertEquals(1, diff.changes.size)
        assertTrue(diff.changes.first() is DomDiff.Change.Added)
    }

    @Test
    fun `空差分render给出明确无变化`() {
        val base = listOf(el("r_a", "A"))
        val diff = DomDiff.diff(base, listOf(el("r_a", "A")), "d1g1", "d1g1")
        assertTrue(diff.isEmpty())
        assertTrue(diff.render().contains("无变化"))
    }
}

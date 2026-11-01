package com.apex.browser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TaskValidator] 意图测试（v2.1.0）：预校验先于执行的完备性矩阵——
 * 错误信息必须指出第几步、缺什么、怎么改（面向模型，同 BatchActionValidator 纪律）。
 */
class TaskValidatorTest {

    private fun navigate(url: String = "https://a.example") = TaskStep(type = TaskActionType.NAVIGATE, url = url)
    private fun click(selector: String = "#submit") = TaskStep(type = TaskActionType.CLICK, selector = selector)
    private fun fill(selector: String = "#q", text: String = "hello") =
        TaskStep(type = TaskActionType.FILL, selector = selector, text = text)

    @Test
    fun `合法任务通过`() {
        val errors = TaskValidator.validate(
            listOf(
                navigate(),
                TaskStep(type = TaskActionType.WAIT_FOR, waitMode = "selector", waitValue = ".results"),
                fill(),
                click(),
                TaskStep(type = TaskActionType.EXTRACT, extractMode = "text", selector = ".results"),
            )
        )
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `空任务与超步数拒绝`() {
        assertTrue(TaskValidator.validate(emptyList()).isNotEmpty())
        assertTrue(TaskValidator.validate(List(13) { click() }).first().contains("上限"))
    }

    @Test
    fun `navigate缺url被指出步号`() {
        val errors = TaskValidator.validate(listOf(click(), navigate(url = "")))
        assertEquals(1, errors.size)
        assertTrue(errors.first().contains("第 2 步"))
        assertTrue(errors.first().contains("url"))
    }

    @Test
    fun `wait_for缺waitValue的network_idle例外`() {
        assertTrue(TaskValidator.validate(listOf(TaskStep(type = TaskActionType.WAIT_FOR, waitMode = "network_idle"))).isEmpty())
        assertTrue(TaskValidator.validate(listOf(TaskStep(type = TaskActionType.WAIT_FOR, waitMode = "selector"))).first().contains("waitValue"))
        assertTrue(TaskValidator.validate(listOf(TaskStep(type = TaskActionType.WAIT_FOR, waitMode = "unknown_mode"))).first().contains("未知"))
    }

    @Test
    fun `extract_text需要selector`() {
        assertTrue(TaskValidator.validate(listOf(TaskStep(type = TaskActionType.EXTRACT, extractMode = "text"))).first().contains("selector"))
        assertTrue(TaskValidator.validate(listOf(TaskStep(type = TaskActionType.EXTRACT, extractMode = "article"))).isEmpty())
        assertTrue(TaskValidator.validate(listOf(TaskStep(type = TaskActionType.EXTRACT, extractMode = "nope"))).first().contains("未知"))
    }

    @Test
    fun `非法控制字符拒绝`() {
        val errors = TaskValidator.validate(listOf(fill(text = "bad\u0000input")))
        assertTrue(errors.first().contains("非法控制字符"))
    }

    @Test
    fun `多错误逐条上报不短路`() {
        val errors = TaskValidator.validate(
            listOf(
                navigate(url = ""),
                TaskStep(type = TaskActionType.PRESS_KEY, key = ""),
            )
        )
        assertEquals(2, errors.size)
    }
}

package com.apex.browser.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.1.0 ABI/JSON 兼容测试（评审 A6）：2.0.0 时代序列化的 JSON 必须能被 2.1.0
 * 反序列化（新字段全部带默认值 = 尾参增量）；DocEvent 现产 JSON 也能被消费方回读。
 * 这不是源码兼容（宿主 composite 源码消费），护的是外部消费方与磁盘/链路上的旧数据。
 */
class RuntimeJsonCompatTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `v200形态的PageSnapshot可反序列化_新字段docId取默认值`() {
        // 2.0.0 序列化形态：dialogNotice 是最后一个字段，没有 docId
        val v200Json = """
            {"url":"https://a.example","title":"T","scrollY":0,"scrollHeight":900,
             "viewportHeight":800,"interactiveCount":1,"domSummary":"⊕ x",
             "interactiveElements":[{"bid":1,"ref":"r_abc","tag":"button","text":"搜索",
               "label":"按钮 搜索","attributes":{},"rect":{"x":1,"y":2,"width":3,"height":4},
               "isVisible":true,"isInteractive":true,"depth":0,"childCount":0}],
             "truncated":false,"totalCandidateCount":1,"timestampMs":1234567890,
             "dialogNotice":null}
        """.trimIndent()
        val snap = json.decodeFromString<PageSnapshot>(v200Json)
        assertEquals("", snap.docId) // 新字段默认值
        assertEquals("r_abc", snap.interactiveElements.first().ref)
    }

    @Test
    fun `新序列化带docId可回读`() {
        val snap = PageSnapshot(
            url = "https://a.example", title = "T", scrollY = 0, scrollHeight = 1,
            viewportHeight = 2, interactiveCount = 0, domSummary = "s",
            interactiveElements = emptyList(), docId = "d1g3",
        )
        val roundTrip = json.decodeFromString<PageSnapshot>(json.encodeToString(PageSnapshot.serializer(), snap))
        assertEquals("d1g3", roundTrip.docId)
    }

    @Test
    fun `DocEvent可跨进程序列化`() {
        val e = DocEvent(seq = 7, docId = "d1g2", type = DocEventType.NAV_COMMITTED, detail = "ok", timestampMs = 42)
        val roundTrip = json.decodeFromString<DocEvent>(json.encodeToString(DocEvent.serializer(), e))
        assertEquals(7, roundTrip.seq)
        assertEquals(DocEventType.NAV_COMMITTED, roundTrip.type)
    }

    @Test
    fun `NavigationHandle字段完整性`() {
        val h = NavigationHandle("n1", 1, 100, 3, "d1g3")
        assertEquals("n1", h.navigationId)
        assertEquals("d1g3", h.initialDocId)
        assertEquals(3, h.initialDocGeneration)
    }

    @Test
    fun `docId与navigationId格式`() {
        assertEquals("d1g42", NavigationIds.docId(1, 42))
        assertEquals("n7", NavigationIds.navigationId(7))
        assertTrue(NavigationIds.asNavigationId("n7") != null)
        assertTrue(NavigationIds.asNavigationId("d1g42") == null)
    }
}

package com.apex.browser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.0.0 契约测试：FilterList（EasyList 子集解析/匹配/合成）、TabSessionCodec
 * （会话持久化编解码）、DOM_STABLE_JS（稳定性探针）。
 */
class BrowserScriptV20Test {

    /* ---------------- DOM_STABLE_JS ---------------- */

    @Test
    fun `dom stable script installs idempotent mutation observer`() {
        val js = BrowserScript.DOM_STABLE_JS
        // 幂等守卫 + 四通道观察
        assertTrue(js.contains("__apexMutHooked"))
        assertTrue(js.contains("MutationObserver"))
        assertTrue(js.contains("childList:true"))
        assertTrue(js.contains("attributes:true"))
        assertTrue(js.contains("characterData:true"))
        // 返回 {m, idle} 报告；从未变更为 -1；无 Promise
        assertTrue(js.contains("JSON.stringify"))
        assertTrue(js.contains("idle: -1"))
        assertFalse(js.contains("new Promise"))
    }

    /* ---------------- FilterList 解析 ---------------- */

    @Test
    fun `parses domain anchors with exact and suffix matching`() {
        val f = FilterList.parse("||ads.example.com^\n||tracker.io^/pixel/\n! comment\n")
        assertEquals(2, f.stats.domainRules)
        assertEquals(0, f.stats.skipped)
        // 精确匹配
        assertTrue(f.matches("https://ads.example.com/banner.js"))
        // 子域后缀匹配（a.b.c 匹配 b.c）
        assertTrue(f.matches("https://cdn.x.ads.example.com/p.js"))
        // 非后缀（ads-example.com 不是 ads.example.com 的子域）
        assertFalse(f.matches("https://ads-example.com/banner.js"))
        // 路径约束：/pixel/ 不在 URL → 不命中
        assertFalse(f.matches("https://tracker.io/main.js"))
        assertTrue(f.matches("https://tracker.io/pixel/x.gif"))
        // 例外域优先
    }

    @Test
    fun `exceptions override block rules`() {
        val f = FilterList.parse("||example.com^\n@@||good.example.com^\n")
        assertEquals(1, f.stats.domainRules)
        assertEquals(1, f.stats.exceptionRules)
        assertTrue(f.matches("https://example.com/ad.js"))
        assertFalse(f.matches("https://good.example.com/ad.js"))
        assertTrue(f.matches("https://bad.example.com/ad.js"))
    }

    @Test
    fun `prefix anchor substring and short-needle rejection`() {
        val f = FilterList.parse("|https://tracker.\n-ad-300x250.\nxx\n||ok.com^\n")
        // 前缀锚点
        assertTrue(f.matches("https://tracker.another.net/x"))
        assertFalse(f.matches("http://tracker.another.net/x"))
        // 裸子串
        assertTrue(f.matches("https://site.com/img-ad-300x250..png"))
        // 3 字符以下子串拒绝（灾难性过屏蔽防线）→ skipped
        assertEquals(1, f.stats.skipped)
        assertEquals(2, f.stats.substringRules)
    }

    @Test
    fun `cosmetics regex and type-options handled`() {
        val f = FilterList.parse(
            """
            example.com##div.ad
            example.com#?#.banner
            /banner\d+/
            ||blocked.com^$script
            ||wide.com^${'$'}image,third-party
            [Adblock Plus 2.0]
            """.trimIndent(),
        )
        // 两个 cosmetics + 一个正则 → skipped 3
        assertEquals(3, f.stats.skipped)
        // $script 剥离选项按全域屏蔽（比原语义更严）
        assertEquals(2, f.stats.domainRules)
        assertTrue(f.matches("https://blocked.com/anything.js"))
        assertTrue(f.matches("https://wide.com/pic.png"))
    }

    @Test
    fun `matching is case-insensitive on url`() {
        val f = FilterList.parse("||ADS.Example.COM^\n")
        assertTrue(f.matches("https://Sub.Ads.Example.Com/x"))
    }

    @Test
    fun `caps trigger truncated flag`() {
        val sb = StringBuilder()
        repeat(FilterList.MAX_DOMAIN_RULES + 50) { sb.append("||d$it.com^\n") }
        val f = FilterList.parse(sb.toString())
        assertEquals(FilterList.MAX_DOMAIN_RULES, f.stats.domainRules)
        assertTrue(f.stats.truncated)
    }

    @Test
    fun `compose unions rules across subscriptions`() {
        val a = FilterList.parse("||a.com^\n@@||allow.com^\n")
        val b = FilterList.parse("||b.com^\n-adsense-.\n")
        val merged = FilterList.compose(listOf(a, b))
        assertEquals(2, merged.stats.domainRules)
        assertEquals(1, merged.stats.exceptionRules)
        assertEquals(1, merged.stats.substringRules)
        assertTrue(merged.matches("https://x.a.com/1"))
        assertTrue(merged.matches("https://y.b.com/2"))
        assertTrue(merged.matches("https://s.com/e-adsense-.gif"))
        assertFalse(merged.matches("https://allow.com/x"))
    }

    @Test
    fun `hostOf handles userinfo port path and no-scheme`() {
        assertEquals("example.com", FilterList.hostOf("https://example.com/a?b"))
        assertEquals("example.com", FilterList.hostOf("https://user:pw@example.com:8080/a"))
        assertEquals("sub.example.com", FilterList.hostOf("https://sub.example.com#frag"))
        assertNull(FilterList.hostOf("example.com/relative"))
        assertEquals("中文站.com", FilterList.hostOf("https://中文站.com/x"))
    }

    @Test
    fun `default tracker core parses clean`() {
        val f = FilterList.parse(DefaultFilterLists.TRACKER_CORE)
        assertTrue("expected >=100 domain rules, got ${f.stats.domainRules}", f.stats.domainRules >= 100)
        assertEquals(0, f.stats.skipped)
        assertEquals(0, f.stats.exceptionRules)
        assertFalse(f.stats.truncated)
        // 保守性抽查：主流站点主域绝不在黑名单里
        listOf(
            "https://www.wikipedia.org/", "https://github.com/", "https://stackoverflow.com/",
            "https://www.baidu.com/", "https://www.zhihu.com/", "https://news.ycombinator.com/",
        ).forEach { assertFalse("false positive on $it", f.matches(it)) }
        // 已知跟踪域命中抽查
        assertTrue(f.matches("https://www.google-analytics.com/collect"))
        assertTrue(f.matches("https://mc.yandex.ru/watch/42"))
        assertTrue(f.matches("https://cdn.taboola.com/lib.js"))
    }

    /* ---------------- TabSessionCodec ---------------- */

    @Test
    fun `session codec roundtrips`() {
        val tabs = listOf(
            TabSession("https://example.com/a", "页面A", 123L),
            TabSession("https://example.com/b", "页面\"引号\"", 456L),
        )
        val decoded = TabSessionCodec.decode(TabSessionCodec.encode(tabs))
        assertEquals(2, decoded.size)
        assertEquals("https://example.com/a", decoded[0].url)
        assertEquals("页面A", decoded[0].title)
        assertEquals(123L, decoded[0].savedAtMs)
        assertEquals("页面\"引号\"", decoded[1].title)
    }

    @Test
    fun `session codec filters non-http and caps`() {
        val tabs = (1..40).map { TabSession("https://x$it.com/", "t$it") } +
            TabSession("file:///etc/passwd", "bad") +
            TabSession("javascript:alert(1)", "bad2")
        val encoded = TabSessionCodec.encode(tabs)
        val decoded = TabSessionCodec.decode(encoded)
        assertEquals(TabSessionCodec.MAX_TABS, decoded.size)
        assertTrue(decoded.all { it.url.startsWith("https://") })
    }

    @Test
    fun `session codec garbage returns empty`() {
        assertTrue(TabSessionCodec.decode(null).isEmpty())
        assertTrue(TabSessionCodec.decode("").isEmpty())
        assertTrue(TabSessionCodec.decode("{broken").isEmpty())
        assertTrue(TabSessionCodec.decode("\"just a string\"").isEmpty())
    }
}

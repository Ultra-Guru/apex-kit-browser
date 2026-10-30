package com.apex.browser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.9.0 新增脚本的契约测试（与 [BrowserScriptV17Test] 同一套「生成物断言」哲学）：
 * 1. 注入边界——外部字符串（key/value）只经 [JsLiteral.string]；
 * 2. 无 Promise（evaluateJavascript 不等待 Promise）；
 * 3. 关键语义片段（信封形态 / 密码脱敏 / ref 同源公式 / 采集字段）。
 */
class BrowserScriptV19Test {

    /* ---------------- storageJs ---------------- */

    @Test
    fun `storage script routes to the right store identifier`() {
        val local = BrowserScript.storageJs(BrowserScript.StorageOp.GET, key = "token")
        assertTrue(local.contains("var store = localStorage;"))
        val session = BrowserScript.storageJs(BrowserScript.StorageOp.GET, key = "token", session = true)
        assertTrue(session.contains("var store = sessionStorage;"))
    }

    @Test
    fun `storage script escapes hostile keys and values`() {
        // v1.0.0 的教训：未转义字符串撕裂页内 JS = 任意执行。
        // 转义后的形态：单引号成 \'、反斜杠翻倍、U+2028 成字面量 —— 撕裂序列不复存在。
        val hostile = BrowserScript.storageJs(
            BrowserScript.StorageOp.SET,
            key = "x'); alert(1); ('",
            value = "a\\b\nc\u2028d",
        )
        // 闭合攻击序列 x') 不再原样出现（已转成 x\'）
        assertFalse(hostile.contains("x')"))
        // 反斜杠翻倍：a\b 不以原始形态出现（合法转义是 a\\b）
        assertFalse(hostile.contains("a\\b"))
        // U+2028（JS 行终止符）已转成 \u2028 字面量，原始字符不出现
        assertFalse(hostile.contains("c\u2028d"))
        // 无 Promise + 信封收尾
        assertFalse(hostile.contains("new Promise"))
        assertTrue(hostile.contains("JSON.stringify"))
    }

    @Test
    fun `storage script covers all six ops`() {
        // 六个分支的判等字面量在脚本里（JsLiteral 编码对无特殊字符的 op 名透明）
        val js = BrowserScript.storageJs(BrowserScript.StorageOp.GET, key = "k")
        listOf("get", "set", "remove", "keys", "size", "clear").forEach { w ->
            assertTrue("dispatch branch '$w' missing", js.contains("=== '$w'"))
        }
    }

    @Test
    fun `storage script truncates reads honestly`() {
        val js = BrowserScript.storageJs(BrowserScript.StorageOp.GET, key = "k")
        assertTrue(js.contains("t.slice(0, ${BrowserScript.MAX_STORAGE_VALUE_CHARS})"))
        assertTrue(js.contains("truncated"))
        assertTrue(js.contains("length: t.length"))
    }

    /* ---------------- formDetectJs ---------------- */

    @Test
    fun `form script hash formula matches snapshot semantics`() {
        val js = BrowserScript.formDetectJs()
        val snap = BrowserScript.snapshotJs()
        // ref 与快照同源的三个铁证：hash 函数体 / semanticKey 公式 / 去重后缀
        assertTrue(js.contains("return 'r_' + (Math.abs(h)>>>0).toString(36);"))
        assertTrue(snap.contains("return 'r_' + (Math.abs(h)>>>0).toString(36);"))
        val key = "semanticKey = role + '|' + text + '|' + el.tagName + '|' + Math.round(rect.top + window.scrollY);"
        assertTrue(js.contains(key))
        assertTrue(snap.contains(key))
        assertTrue(js.contains("ref = ref + '#' + (n + 1);"))
    }

    @Test
    fun `form script never leaks password values`() {
        val js = BrowserScript.formDetectJs()
        // password 分支只报 hasValue，valuePreview 不进入该分支
        assertTrue(js.contains("if (type === 'password') {"))
        val passwordBranch = js.substringAfter("if (type === 'password') {").substringBefore("} else if")
        assertFalse("password branch must not read value", passwordBranch.contains("el.value") && passwordBranch.contains("valuePreview"))
        assertTrue(passwordBranch.contains("f.hasValue = !!el.value;"))
    }

    @Test
    fun `form script collects labels options and orphans`() {
        val js = BrowserScript.formDetectJs()
        assertTrue(js.contains("el.labels"))
        assertTrue(js.contains("el.closest('form')"))
        assertTrue(js.contains("el.options[i].value"))
        assertTrue(js.contains("orphanCount"))
        assertTrue(js.contains("submitRefs"))
        // 可见性剪枝与快照同判据（display/visibility/opacity/rect）
        assertTrue(js.contains("style.display !== 'none'"))
        assertTrue(js.contains("rect.width > 0"))
        // 不可见字段不写 data-apex-hash（不编造可操作 ref）
        assertTrue(js.contains("if (visible) el.setAttribute('data-apex-hash', ref);"))
    }

    /* ---------------- PERFORMANCE_METRICS_JS ---------------- */

    @Test
    fun `performance script reads navigation and resource timing`() {
        val js = BrowserScript.PERFORMANCE_METRICS_JS
        assertTrue(js.contains("getEntriesByType('navigation')"))
        assertTrue(js.contains("getEntriesByType('resource')"))
        assertTrue(js.contains("responseStart"))
        assertTrue(js.contains("domContentLoadedEventEnd"))
        assertTrue(js.contains("initiatorType"))
        assertTrue(js.contains("transferSize"))
        // 降级：无 performance API 返回 null（老 WebView）
        assertTrue(js.contains("return JSON.stringify(null)"))
        assertFalse(js.contains("new Promise"))
    }

    /* ---------------- FRAME_LIST_JS ---------------- */

    @Test
    fun `frame script enumerates iframes with origin probe`() {
        val js = BrowserScript.FRAME_LIST_JS
        assertTrue(js.contains("document.querySelectorAll('iframe,frame')"))
        assertTrue(js.contains("contentDocument"))
        assertTrue(js.contains("sandbox"))
        assertTrue(js.contains("truncated"))
        assertFalse(js.contains("new Promise"))
    }

    /* ---------------- layoutCollectJs ---------------- */

    @Test
    fun `layout script clamps element budget and keeps host parity`() {
        assertEquals(50, 50.coerceIn(50, 4_000))
        val js = BrowserScript.layoutCollectJs(9_999)
        assertTrue(js.contains("var LIMIT=4000;"))
        assertTrue(BrowserScript.layoutCollectJs(1).contains("var LIMIT=50;"))
        assertTrue(BrowserScript.layoutCollectJs(1_200).contains("var LIMIT=1200;"))
    }

    @Test
    fun `layout script keeps v2 diagnostics from host`() {
        val js = BrowserScript.layoutCollectJs(1_200)
        // 宿主版 v2 的四类诊断：截断 / 小目标 / 对比度 / 暗色模式
        assertTrue(js.contains("smallTargets"))
        assertTrue(js.contains("lowContrast"))
        assertTrue(js.contains("prefers-color-scheme"))
        assertTrue(js.contains("contrastRatio"))
        // 只读采集：无 DOM 写入
        assertFalse(js.contains("setAttribute"))
    }
}

/**
 * v1.9.0 解析器契约测试：坏数据防御 + 字段映射 + 截断标注 + 模型文本。
 */
class FormReportParserTest {

    private val formJson = """
        {"v":1,"totalForms":1,"forms":[{"action":"/login","method":"post","id":"f1","name":"login",
        "fieldCount":3,"truncatedFields":false,"submitRefs":["r_sub1"],
        "fields":[
          {"ref":"r_a1","visible":true,"tag":"input","type":"text","name":"user","id":"u1",
           "required":true,"disabled":false,"readOnly":false,"label":"用户名","placeholder":"输入用户名",
           "hasValue":true,"valuePreview":"bob","checked":null,"optionCount":0,"options":[]},
          {"ref":"r_a2","visible":true,"tag":"input","type":"password","name":"pwd","id":"p1",
           "required":true,"disabled":false,"readOnly":false,"label":"密码","placeholder":"",
           "hasValue":true,"valuePreview":"","checked":null,"optionCount":0,"options":[]},
          {"ref":"r_a3","visible":true,"tag":"select","type":"select","name":"region","id":"",
           "required":false,"disabled":false,"readOnly":false,"label":"地区","placeholder":"",
           "hasValue":true,"valuePreview":"cn","checked":null,"optionCount":5,
           "options":[{"value":"cn","text":"中国大陆","selected":true},{"value":"us","text":"美国","selected":false}]}
        ]}],
        "orphanCount":1,
        "orphans":[{"ref":"r_o1","visible":true,"tag":"input","type":"search","name":"q","id":"",
         "required":false,"disabled":false,"readOnly":false,"label":"","placeholder":"搜索",
         "hasValue":false,"valuePreview":"","checked":null,"optionCount":0,"options":[]}]}
    """.trimIndent()

    @Test
    fun `parses full report with fields options and orphans`() {
        val r = FormReportParser.parse(formJson)
        assertNotNull(r)
        assertEquals(1, r!!.totalForms)
        assertEquals(1, r.forms.size)
        val form = r.forms[0]
        assertEquals("/login", form.action)
        assertEquals("post", form.method)
        assertEquals(3, form.fields.size)
        assertEquals(listOf("r_sub1"), form.submitRefs)
        val user = form.fields[0]
        assertEquals("r_a1", user.ref)
        assertTrue(user.required)
        assertEquals("用户名", user.label)
        assertEquals("bob", user.valuePreview)
        val pwd = form.fields[1]
        assertEquals("password", pwd.type)
        assertTrue(pwd.hasValue)
        assertEquals("", pwd.valuePreview) // 密码值不回传
        val sel = form.fields[2]
        assertEquals(5, sel.optionCount)
        assertEquals(2, sel.options.size)
        assertEquals("中国大陆", sel.options[0].text)
        assertTrue(sel.options[0].selected)
        assertEquals(1, r.orphans.size)
        assertEquals("search", r.orphans[0].type)
    }

    @Test
    fun `agent text mentions refs and batch hint`() {
        val r = FormReportParser.parse(formJson)!!
        val text = r.toAgentText()
        assertTrue(text.contains("r_a1"))
        assertTrue(text.contains("batchExecute"))
        assertTrue(text.contains("用户名"))
        assertTrue(text.contains("POST"))
    }

    @Test
    fun `null and garbage inputs return null`() {
        assertNull(FormReportParser.parse(null))
        assertNull(FormReportParser.parse(""))
        assertNull(FormReportParser.parse("null"))
        assertNull(FormReportParser.parse("{broken json"))
    }

    @Test
    fun `empty report renders no-form notice`() {
        val r = FormReportParser.parse("""{"v":1,"totalForms":0,"forms":[],"orphanCount":0,"orphans":[]}""")
        assertNotNull(r)
        assertFalse(r!!.hasAnyField)
        assertTrue(r.toAgentText().contains("未检出表单"))
    }
}

/**
 * v1.9.0 性能解析器契约测试。
 */
class PerformanceReportParserTest {

    @Test
    fun `parses navigation timing buckets and slowest`() {
        val raw = """
            {"v":1,"navigation":{"dns":12,"tcp":30,"ttfb":180,"domInteractive":900,
             "domContentLoaded":1200,"loadEvent":2400,"transferSize":15000,
             "encodedBodySize":14800,"decodedBodySize":52000,"type":"navigate","redirectCount":1},
             "resourceCount":42,
             "buckets":{"script":{"count":10,"bytes":500000,"maxDuration":300},
                        "css":{"count":4,"bytes":80000,"maxDuration":90},
                        "img":{"count":28,"bytes":1200000,"maxDuration":610}},
             "slowest":[{"url":"https://cdn.example.com/big.js","duration":300,"bytes":500000,"type":"script"}]}
        """.trimIndent()
        val r = PerformanceReportParser.parse(raw)
        assertNotNull(r)
        val nav = r!!.navigation!!
        assertEquals(180, nav.ttfb)
        assertEquals(2400, nav.loadEvent)
        assertEquals(1, nav.redirectCount)
        assertEquals(42, r.resourceCount)
        assertEquals(3, r.buckets.size)
        val img = r.buckets.first { it.type == "img" }
        assertEquals(28, img.count)
        assertEquals(1_200_000L, img.bytes)
        assertEquals(610, img.maxDuration)
        assertEquals(1_780_000L, r.totalBytes)
        assertEquals(1, r.slowest.size)
        assertTrue(r.toAgentText().contains("TTFB 180ms"))
        assertTrue(r.toAgentText().contains("big.js"))
    }

    @Test
    fun `null navigation still reports resources`() {
        val raw = """{"v":1,"navigation":null,"resourceCount":5,"buckets":{},"slowest":[]}"""
        val r = PerformanceReportParser.parse(raw)
        assertNotNull(r)
        assertNull(r!!.navigation)
        assertEquals(5, r.resourceCount)
        assertTrue(r.toAgentText().contains("无 Navigation Timing"))
    }

    @Test
    fun `garbage returns null`() {
        assertNull(PerformanceReportParser.parse(null))
        assertNull(PerformanceReportParser.parse("null"))
        assertNull(PerformanceReportParser.parse("not json"))
    }
}

/**
 * v1.9.0 帧解析器契约测试。
 */
class FrameReportParserTest {

    @Test
    fun `parses frames with origin and truncation flags`() {
        val raw = """
            {"v":1,"total":25,"truncated":true,
             "frames":[{"src":"https://embed.example.com/player","name":"player","title":"播放器",
              "width":640,"height":360,"sandbox":"allow-scripts","sameOrigin":false},
             {"src":"about:blank","name":"","title":"","width":0,"height":0,"sandbox":"","sameOrigin":true}]}
        """.trimIndent()
        val r = FrameReportParser.parse(raw)
        assertNotNull(r)
        assertEquals(25, r!!.total)
        assertTrue(r.truncated)
        assertEquals(2, r.frames.size)
        val f = r.frames[0]
        assertEquals("https://embed.example.com/player", f.src)
        assertEquals("allow-scripts", f.sandbox)
        assertFalse(f.sameOrigin)
        assertTrue(r.frames[1].sameOrigin)
        assertTrue(r.toAgentText().contains("25 个"))
        assertTrue(r.toAgentText().contains("[跨源]"))
    }

    @Test
    fun `empty frames render notice and garbage returns null`() {
        val r = FrameReportParser.parse("""{"v":1,"total":0,"truncated":false,"frames":[]}""")
        assertNotNull(r)
        assertTrue(r!!.toAgentText().contains("无 iframe"))
        assertNull(FrameReportParser.parse("garbage"))
        assertNull(FrameReportParser.parse(null))
    }
}

/**
 * v1.9.0 布局报告格式化契约测试（宿主下沉面：段落齐全 / 判定文案 / 截断标注）。
 */
class LayoutAnalysisTest {

    private val layoutJson = """
        {"viewport":{"w":412,"h":915,"scrollW":412,"overflowX":false},
         "landmarks":[{"tag":"header","rect":{"x":0,"y":0,"w":412,"h":56},"text":"顶栏"}],
         "flex":[{"sel":"div.row","dir":"row","wrap":"nowrap","justify":"flex-start","align":"center","gap":"8px","kids":3,"rect":{"x":0,"y":56,"w":412,"h":40}}],
         "grid":[],
         "absPos":[{"sel":"div.badge","rect":{"x":300,"y":10,"w":40,"h":40},"positionedAncestor":false}],
         "fixed":[{"sel":"div.toast","rect":{"x":106,"y":800,"w":200,"h":48},"z":"999"}],
         "overflowEls":[],
         "zIssues":[{"sel":"span.tag","z":"5"}],
         "noAltImgs":[{"src":"https://cdn.example.com/a.png"}],
         "headings":[{"level":1,"text":"标题一"},{"level":2,"text":"小节"}],
         "trunc":[{"sel":"div.cell","text":"被裁的长文本","sw":300,"cw":120,"sh":0,"ch":0}],
         "smallTargets":[{"sel":"button.mini","w":24,"h":24}],
         "lowContrast":[{"sel":"p.faint","text":"看不清","ratio":2.1,"need":4.5,"fg":"#999"}],
         "colorScheme":{"prefersDark":false,"meta":"","bodyBg":"rgb(255, 255, 255)","darkBg":false},
         "maxDepth":14,"textChars":5200}
    """.trimIndent()

    @Test
    fun `formats every diagnostic section`() {
        val report = LayoutAnalysis.formatReport(layoutJson)
        assertTrue(report.contains("视口: 412x915"))
        assertTrue(report.contains("无横向溢出"))
        assertTrue(report.contains("区块 (landmarks)"))
        assertTrue(report.contains("<header> 412x56"))
        assertTrue(report.contains("flex 容器"))
        assertTrue(report.contains("absolute 定位"))
        assertTrue(report.contains("无已定位祖先"))
        assertTrue(report.contains("fixed 定位"))
        assertTrue(report.contains("层叠失效"))
        assertTrue(report.contains("图片无 alt"))
        assertTrue(report.contains("标题链: h1 “标题一” → h2 “小节”"))
        assertTrue(report.contains("文字截断"))
        assertTrue(report.contains("触屏点击目标过小"))
        assertTrue(report.contains("低对比度文字"))
        assertTrue(report.contains("暗色模式"))
        assertTrue(report.contains("仅浅色"))
    }

    @Test
    fun `garbage payload yields explicit parse-failure notice`() {
        val report = LayoutAnalysis.formatReport("{not json")
        assertTrue(report.contains("解析失败"))
        assertTrue(report.contains("原始片段"))
    }

    @Test
    fun `overflow flag renders warning`() {
        val json = layoutJson.replace("\"overflowX\":false", "\"overflowX\":true")
            .replace("\"scrollW\":412", "\"scrollW\":824")
        val report = LayoutAnalysis.formatReport(json)
        assertTrue(report.contains("存在横向溢出"))
    }
}

package com.apex.browser.core

/**
 * 注入到 WebView 的 JS 片段（纯字符串，无 Android 依赖）。
 *
 * [SNAPSHOT_JS] 遍历可见可交互元素并序列化回传，[HIGHLIGHT_JS] 在调试时高亮某个 ref。
 * 返回格式与 [RawDomElement] 对齐，由 [DomParser] 解析。
 *
 * 2026 裁决要点：
 * - 稳定 ref 改为「语义哈希」`r_<hash>`（基于 role+text+tag+相对位置），写入 `data-apex-hash`，
 *   抗 SPA 局部刷新错位（替代旧的顺序/属性混合 ref `data-apex-ref`）。
 * - JS 层启发式剪枝：跳过不可见、非交互无文本、零尺寸节点；硬上限 [SNAPSHOT_MAX_ELEMENTS]。
 * - 返回的每个元素都带 `data-apex-hash`，供后续 DOM 级定位与物理触摸注入使用。
 *
 * ## 注入边界（rebase 后唯一收敛口径）
 *
 * 本文件是库向 WebView 注 JS 的**唯一生成入口**：一切外部字符串（ref / selector /
 * value / text / tag）一律经 [JsLiteral.string] 编码后拼接；ref 定位一律经
 * [refLookupJs]（JS 内 `===` 严格比较，不经过 CSS 解析）。v1.1.0 曾以
 * `toJsonString`/`refHitsJs` 过渡实现同一目标，现已统一收敛到 [JsLiteral] +
 * [refLookupJs]（转义覆盖面更全：控制字符 / U+2028-9 / DEL；`</` 序列防御
 * 由调用方上下文决定，evaluateJavascript 纯 JS 上下文无 HTML 闭合面）。
 */
object BrowserScript {

    /** 单次快照元素硬上限（Token 预算保护，超过即截断） */
    const val SNAPSHOT_MAX_ELEMENTS: Int = 50

    /**
     * 抓取当前**视口内**可见可交互元素，返回 JSON 数组字符串（需用 JSON.parse 还原）。
     * 与早期全量快照不同，本脚本在 JS 层完成过滤 + 语义哈希，极大减少 IPC 体积。
     *
     * @param strategy 剪枝策略（#19/#20）：传入 [DomParser.SnapshotStrategy]，
     *   不同策略收窄查询选择器，进一步降低回传体积。
     *
     * 可见性剪枝的判据：**不得**用 `offsetParent !== null`。`position:fixed` 的可见元素
     * 其 `offsetParent` 恒为 `null`（视口外定位元素亦然），用旧判据会系统性误杀
     * 吸顶导航栏 / 悬浮操作按钮 / 弹窗控件 —— 恰是现代站点最需要 Agent 点击的元素，
     * 且失败方式是无声地少给几个 ref。现改为 `getBoundingClientRect` 与
     * `display` / `visibility` / `opacity` 联合判定。
     */
    fun snapshotJs(strategy: DomParser.SnapshotStrategy = DomParser.SnapshotStrategy.INTERACTIVE_ONLY): String {
        val sel = when (strategy) {
            DomParser.SnapshotStrategy.INTERACTIVE_ONLY ->
                "a,button,input,select,textarea,summary,area[href]," +
                "[role=button],[role=link],[role=tab],[role=option],[role=checkbox],[role=radio],[role=switch]"
            DomParser.SnapshotStrategy.FORM_FIELDS ->
                "input,select,textarea,[role=checkbox],[role=radio],[role=switch],[role=textbox],[role=searchbox]"
            DomParser.SnapshotStrategy.CONTENT_SUMMARY ->
                "a[href],h1,h2,h3,h4,p,li,[role=heading],[role=link]"
        }
        return """
        (function(){
          function hash(str){
            var h = 0;
            for (var i=0;i<str.length;i++){ var c = str.charCodeAt(i); h = ((h<<5)-h)+c; h|=0; }
            return 'r_' + (Math.abs(h)>>>0).toString(36);
          }
          var MAX = $SNAPSHOT_MAX_ELEMENTS;
          var out = [];
          // v1.3.0 同键去重：同一 semanticKey（列表页里大量「角色+文案+位置」完全相同的
          // 元素，如未滚动时同一批商品卡）会算出同一个 hash，data-apex-hash 撞键后
          // ref 定位永远命中第一个 —— 其余同名元素永远不可操作。第 2+ 次出现时
          // 追加 '#n' 后缀（hash + '#' + n），保证每个可操作元素拥有唯一主键；
          // refLookupJs 用属性全等比较，天然兼容带后缀的 ref。
          var seen = {};
          var interactiveSel = ${JsLiteral.string(sel)};
          var all = document.querySelectorAll(interactiveSel);
          // 匹配总数先记下来：触顶截断时必须让模型知道「页面里还有更多」，
          // 否则它会把返回的这一批当成全部，据此规划动作必然踩空。
          var total = all.length;
          for (var i=0;i<all.length;i++){
            if (out.length >= MAX) break;
            var el = all[i];
            var style = window.getComputedStyle(el);
            var rect = el.getBoundingClientRect();
            // 启发式剪枝：不可见 / 透明 / 零尺寸
            var visible = style.display !== 'none' && style.visibility !== 'hidden'
                  && parseFloat(style.opacity) > 0.05
                  && rect.width > 0 && rect.height > 0;
            if (!visible) continue;
            var text = (el.innerText || el.value || el.placeholder || '').replace(/\s+/g,' ').trim();
            // 非交互且无文本 -> 纯布局噪音，跳过
            if (text.length === 0 && !el.hasAttribute('aria-label') && !el.hasAttribute('placeholder')
                && el.tagName !== 'INPUT' && el.tagName !== 'SELECT' && el.tagName !== 'TEXTAREA') continue;
            text = text.slice(0, 120);
            // 语义哈希 ref：role + 文本 + 标签 + 相对顶部位置（抗 SPA 局部刷新错位）
            var role = el.getAttribute('role') || el.tagName.toLowerCase();
            var semanticKey = role + '|' + text + '|' + el.tagName + '|' + Math.round(rect.top + window.scrollY);
            var ref = hash(semanticKey);
            var n = seen[semanticKey] || 0;
            seen[semanticKey] = n + 1;
            if (n > 0) ref = ref + '#' + (n + 1);
            el.setAttribute('data-apex-hash', ref);
            var attrs = { 'data-apex-hash': ref };
            var keep = ['href','name','type','placeholder','value','aria-label','title','role','alt','id'];
            for (var a=0;a<el.attributes.length;a++){
              var an = el.attributes[a].name; if (keep.indexOf(an)>=0) attrs[an] = el.attributes[a].value;
            }
            // 真实 DOM 深度（上限 20）：摘要超预算时按「可交互 > 浅层 > 有标签」决定
            // 谁先进预算 —— 旧实现恒写 0，该字段形同虚设，文档承诺的优先级从未生效。
            var depth = 0, p = el;
            while ((p = p.parentElement) && depth < 20) { depth++; }
            out.push({
              tag: el.tagName,
              text: text,
              attributes: attrs,
              rect: { x: Math.round(rect.left + window.scrollX), y: Math.round(rect.top + window.scrollY),
                      width: Math.round(rect.width), height: Math.round(rect.height) },
              isVisible: true,
              isInteractive: true,
              depth: depth,
              childCount: el.children.length
            });
          }
          return JSON.stringify({ v: 1, total: total, truncated: total > out.length, elements: out });
        })();
        """.trimIndent()
    }

    /**
     * A11y 补充源（#17）：当主快照交互元素过少（CSP 阻止 JS 或页面极简）时，
     * 用 ARIA 角色补充语义元素（含有 accessible name 的容器），作为 DOM 快照的降级源。
     */
    val A11Y_FALLBACK_JS: String
        get() = """
        (function(){
          function hash(str){
            var h = 0;
            for (var i=0;i<str.length;i++){ var c = str.charCodeAt(i); h = ((h<<5)-h)+c; h|=0; }
            return 'r_' + (Math.abs(h)>>>0).toString(36);
          }
          var MAX = $SNAPSHOT_MAX_ELEMENTS;
          var out = [];
          // 有 accessible name 的节点：role / aria-label / 文本 任一即可
          var all = document.querySelectorAll('*');
          // v1.3.0 total 口径修正：旧实现 total = all.length（把全部纯布局 div、
          // script/head 节点也计入，动辄上千），truncated 恒为真 —— 模型被误导
          // 「页面还有大量可操作元素没抓到」。现改为循环内计数器：只数
          // 「有 name/role 的语义节点」，截断播报才如实。
          var total = 0;
          // v1.3.0 同键去重（与 snapshotJs 同逻辑）：同一 semanticKey 撞键后
          // ref 定位永远命中第一个，其余同名元素不可操作；第 2+ 次追加 '#n'。
          var seen = {};
          for (var i=0;i<all.length;i++){
            var el = all[i];
            var role = el.getAttribute && el.getAttribute('role');
            // innerText 在部分元素上 undefined（SVG/void 元素），textContent 为通用回退；
            // 下方容量剪枝也必须复用同一取值，否则会对 undefined 调 .trim() 抛错，
            // 使整个降级快照返回空（降级路径静默失效是最难排查的一类故障）。
            var inner = el.innerText || el.textContent || '';
            var name = (el.getAttribute && (el.getAttribute('aria-label')||el.getAttribute('title'))) ||
                       inner.replace(/\s+/g,' ').trim().slice(0,80);
            if (!role && (!name || name.length===0)) continue;
            // 跳过纯布局容器（无语义 role 且无标签）
            if (!role && el.children.length>0 && inner.replace(/\s+/g,' ').trim().length>120) continue;
            total++;
            // 已满只计数不收集（保持截断诚实性，不再提前退出循环）
            if (out.length >= MAX) continue;
            var rect = el.getBoundingClientRect();
            if (rect.width===0 || rect.height===0) continue;
            var text = (name||'').toString().slice(0,120);
            var semanticKey = (role||el.tagName) + '|' + text + '|' + el.tagName;
            var ref = hash(semanticKey);
            var n = seen[semanticKey] || 0;
            seen[semanticKey] = n + 1;
            if (n > 0) ref = ref + '#' + (n + 1);
            el.setAttribute('data-apex-hash', ref);
            var depth = 0, p = el;
            while ((p = p.parentElement) && depth < 20) { depth++; }
            out.push({
              tag: el.tagName,
              text: text,
              attributes: { 'data-apex-hash': ref, 'role': role||'', 'aria-label': (el.getAttribute&&el.getAttribute('aria-label'))||'' },
              // 与 snapshotJs 对齐：统一用**文档**坐标（加 scroll 偏移），
              // 否则 A11y 路径返回的是视口坐标，物理触摸兜底会指向错误位置。
              rect: { x: Math.round(rect.left + window.scrollX), y: Math.round(rect.top + window.scrollY),
                      width: Math.round(rect.width), height: Math.round(rect.height) },
              isVisible: true,
              isInteractive: !!role,
              depth: depth,
              childCount: el.children.length
            });
          }
          return JSON.stringify({ v: 1, total: total, truncated: total > out.length, elements: out });
        })();
        """.trimIndent()

    /**
     * 网络监控（#18）：拦截 fetch / XMLHttpRequest，记录 API 请求到 window.__apexNetLog，
     * 供 [browser_network_log] 工具读取。注入一次即可持续生效。
     *
     * v1.3.0 增强：同步维护 `window.__apexNetPending` 在途计数（fetch/xhr 发起 +1，
     * settle -1），配合 [networkIdleJs] 供 Kotlin 侧轮询「网络空闲」——
     * waitForCondition(network_idle) 的数据源。导航会重置 JS 上下文，
     * 本脚本需在每次 onPageFinished 重注入（引擎侧已接线）。
     */
    val NETWORK_MONITOR_JS: String
        get() = """
        (function(){
          if (window.__apexNetHooked) return;
          window.__apexNetLog = window.__apexNetLog || [];
          window.__apexNetPending = window.__apexNetPending || 0;
          window.__apexNetHooked = true;
          function rec(method, url, status){
            window.__apexNetLog.push({ method: method, url: (url||'').toString().slice(0,200), status: status||0, t: Date.now() });
            if (window.__apexNetLog.length > 200) window.__apexNetLog.shift();
          }
          function begin(){ window.__apexNetPending++; }
          function settle(){ window.__apexNetPending = Math.max(0, window.__apexNetPending - 1); }
          var origFetch = window.fetch;
          if (origFetch) window.fetch = function(){
            var args = arguments; var u = args[0];
            begin();
            return origFetch.apply(this, args).then(function(r){ settle(); rec('fetch', u, r.status); return r; }, function(e){ settle(); rec('fetch', u, 0); throw e; });
          };
          var origXhr = window.XMLHttpRequest.prototype.open;
          window.XMLHttpRequest.prototype.open = function(m,u){ this.__apexMethod=m; this.__apexUrl=u; return origXhr.apply(this, arguments); };
          var origSend = window.XMLHttpRequest.prototype.send;
          window.XMLHttpRequest.prototype.send = function(){
            var self=this; var mu=this.__apexMethod, ul=this.__apexUrl;
            begin();
            this.addEventListener('loadend', function(){ settle(); rec(mu, ul, self.status); });
            return origSend.apply(this, arguments);
          };
        })();
        """.trimIndent()

    /**
     * 页面形态信号采集（[PageClassifier] 的数据源）：一次 JS 拿全部计数并回传 JSON。
     *
     * 纯同步（无 Promise —— `evaluateJavascript` 回调不等待 Promise 完成）；
     * `querySelectorAll` 的广义选择器在老旧内核上可能抛错，逐项 try 兜底。
     *
     * 原为 `BrowserEngine` 伴生对象里的 `private val PAGE_TYPE_JS` —— 与引擎耦合且
     * 不可测，现随分类逻辑一同下沉到 `:browser-core`（见 [PageClassifier]）。
     */
    val PAGE_TYPE_JS: String
        get() = """
        (function(){
          try {
            var q = function(s){ try { return document.querySelectorAll(s).length; } catch(e){ return 0; } };
            var b = document.body;
            return JSON.stringify({
              inputs: q('input,select,textarea'),
              password: q('input[type=password]'),
              buttons: q('button,input[type=submit],input[type=button],[role=button]'),
              links: q('a[href]'),
              articles: q('article,[itemprop=articleBody],.article-content,main h1'),
              videos: q('video,iframe[src*=youtube],iframe[src*=bilibili],iframe[src*=vimeo],[class*=player]'),
              listItems: q('li'),
              searchBox: q('input[type=search],input[placeholder*=搜],input[placeholder*=search],input[name*=search]'),
              nav: q('nav,[role=navigation]'),
              textLen: b ? b.innerText.length : 0
            });
          } catch(e) { return '{}'; }
        })();
        """.trimIndent()

    /**
     * 生成「按语义哈希 ref 定位元素」的 JS 前置片段。
     *
     * 片段内声明两个变量供调用方使用：
     * - `el` —— 首个匹配元素，未匹配时为 `null`；
     * - `__apexHits` —— 全部匹配元素（供 `highlightJs` 这类需要逐个处理的场景）。
     *
     * ## 为什么不用 CSS 属性选择器拼 ref
     *
     * 若写成 `[data-apex-hash=<ref>]`，`<ref>` 会被 **CSS 解析器**二次解析：加引号则
     * 值内含引号会提前闭合 CSS 字符串而使选择器语法错误（`querySelector` 直接抛
     * `SyntaxError`，在 `rectByRefJs` 这类无 try/catch 的路径上会一路冒泡成
     * `ElementNotFoundException` 并**打开熔断器**）；不加引号则任何含空格 / `]` /
     * `"` 的 ref 都构成非法标识符。
     *
     * 改为「取出全部带标记的元素 → 在 JS 内用 `===` 严格比较属性值」后，
     * ref 全程只是 [JsLiteral.string] 编码出的 JS 字符串，**不经过 CSS 解析**：
     * 既无注入面，也不会因畸形 ref 抛错（未命中即 `el === null`，语义干净）。
     */
    private fun refLookupJs(ref: String): String =
        """
        var __apexRef = ${JsLiteral.string(ref)};
        var __apexHits = [];
        var __apexMarked = document.querySelectorAll('[data-apex-hash]');
        for (var __apexI = 0; __apexI < __apexMarked.length; __apexI++) {
          if (__apexMarked[__apexI].getAttribute('data-apex-hash') === __apexRef) { __apexHits.push(__apexMarked[__apexI]); }
        }
        var el = __apexHits.length ? __apexHits[0] : null;
        """.trimIndent()

    /** 物理触摸注入：返回元素在**屏幕坐标系**中的中心点（含 WebView 自身偏移），失败返回 null */
    fun rectByRefJs(ref: String): String =
        """
        (function(){
          ${refLookupJs(ref)}
          if (!el) return JSON.stringify(null);
          var r = el.getBoundingClientRect();
          return JSON.stringify({ x: r.left + r.width/2, y: r.top + r.height/2,
                                  left: r.left, top: r.top, width: r.width, height: r.height });
        })();
        """.trimIndent()

    /** 点击后验证：对比点击前后页面状态（URL / 标题 / 可交互元素数量） */
    val POST_ACTION_PROBE_JS: String
        get() = """
        (function(){
          return JSON.stringify({
            url: location.href,
            title: document.title,
            interactiveCount: document.querySelectorAll('a,button,input,select,textarea,[role=button],[role=link],[role=tab],[role=option],[role=checkbox],[role=radio],[role=switch]').length,
            scrollY: window.scrollY
          });
        })();
        """.trimIndent()

    /** 下拉选择：按 value 或可见文本设置 <select> 并触发 change */
    fun selectJs(ref: String, value: String, byText: Boolean): String {
        val match = if (byText) "opt.text" else "opt.value"
        return """
        (function(){
          ${refLookupJs(ref)}
          if (!el || el.tagName !== 'SELECT') return false;
          var opts = el.options;
          for (var i=0;i<opts.length;i++){
            var opt = opts[i];
            if (($match) === ${JsLiteral.string(value)}){ el.selectedIndex = i; el.dispatchEvent(new Event('change',{bubbles:true})); return true; }
          }
          return false;
        })();
        """
    }

    /**
     * 高亮指定 ref 对应的元素（调试 / 可视化）。
     *
     * [ref] 与 [color] 均经 [JsLiteral.string] 编码 —— 旧实现用 `"$ref"` / `$color`
     * 原样插值进双引号属性选择器与单引号样式串，两种定界符都可被闭合。
     */
    fun highlightJs(ref: String, color: String = "#1e90ff"): String =
        """
        (function(){
          ${refLookupJs(ref)}
          for (var i=0;i<__apexHits.length;i++){ __apexHits[i].style.outline='2px solid ' + ${JsLiteral.string(color)}; }
          return __apexHits.length;
        })();
        """.trimIndent()

    /**
     * 文本输入：聚焦 → 赋值 → 派发 `input`/`change`（供 React/Vue 等受控组件感知）。
     *
     * 修复说明：原实现内联在 [com.apex.browser.engine.BrowserEngine.inputText] 中，
     * 且自身手写了一套**与本库不一致**的转义（只处理 `'` 而漏换行），同时把 `ref` 按
     * `\"` 转义却嵌在**单引号**属性选择器里 —— 转义与定界符错配，`ref` 含 `'` 即可闭合
     * 选择器；裸换行则使整段 `evaluateJavascript` 变成语法错误，输入静默失效。
     * 现统一收敛到本函数，ref 与 text 共用 [JsLiteral.string] 与 [refLookupJs]。
     *
     * （v1.1.0 起宿主侧 [BrowserEngine.inputText] 走 [setNativeValueJs] 的
     * 原型链安全写值路径；本函数保留为简单赋值形态，供轻量场景/单测使用。）
     */
    fun inputTextJs(ref: String, text: String): String =
        """
        (function(){
          ${refLookupJs(ref)}
          if (!el) return false;
          el.focus();
          el.value = ${JsLiteral.string(text)};
          el.dispatchEvent(new Event('input', {bubbles:true}));
          el.dispatchEvent(new Event('change', {bubbles:true}));
          return true;
        })();
        """.trimIndent()

    /**
     * 同步探测目标 CSS 选择器是否存在（单次查询，**无 Promise**）。
     *
     * 修复说明：旧实现（`waitForSelectorJs`）返回一个 `Promise`，并靠 `setInterval`
     * 在页面侧轮询。但 `WebView.evaluateJavascript` 的回调**不等待 Promise** ——
     * 页面侧刚 `resolve` 时回调早已以 `null` 返回（`evaluateJson` 落到默认值 `"null"`）。
     * 于是 `waitForSelectorOnPage` 恒得 `"null" != "true"` → **`selectorFound` 永远为
     * false**，`browser_navigate` 的 `wait_for` 参数形同虚设。
     *
     * 现改为同步单次探测，真正的「等待」由 Kotlin 侧轮询承担
     * （`BrowserEngine.waitForCondition` / `waitForSelectorOnPage` 已是此模式）。
     * [selector] 经 [JsLiteral.string] 编码 —— 旧实现两处插值同一未转义串，
     * 致使 `input[placeholder='搜索']` 这类常见选择器变成 JS 语法错误。
     */
    fun selectorPresentJs(selector: String): String =
        """
        (function(){ try { return !!document.querySelector(${JsLiteral.string(selector)}); }
        catch(e){ return false; } })();
        """.trimIndent()

    /** 可见文本包含检查（`body.innerText`；`textContent` 回退覆盖无 innerText 的场景） */
    fun textContainsJs(text: String): String =
        """
        (function(){ try { var b=document.body;
          if (!b) return false;
          var s = b.innerText || b.textContent || '';
          return s.indexOf(${JsLiteral.string(text)}) >= 0; }
        catch(e){ return false; } })();
        """.trimIndent()

    /** 滚动指定像素（正向下 / 负向上），同步返回 true */
    fun scrollByJs(deltaY: Int): String =
        "(function(){ try { window.scrollBy(0, ${deltaY.coerceIn(-MAX_SCROLL_PX, MAX_SCROLL_PX)}); return true; } catch(e){ return false; } })();"

    /**
     * 滚动到页面边界（v1.8.0）：toBottom=true 滚到底部（无限滚动触发加载），
     * false 回顶部。同步返回 true。
     */
    fun scrollToBoundaryJs(toBottom: Boolean): String =
        "(function(){ try { window.scrollTo(0, ${if (toBottom) "document.documentElement.scrollHeight" else "0"}); return true; } catch(e){ return false; } })();"


    /** 读取网络监控日志尾部 [limit] 条（配合 [NETWORK_MONITOR_JS]） */
    fun networkLogJs(limit: Int): String =
        "JSON.stringify((window.__apexNetLog||[]).slice(-${limit.coerceIn(1, MAX_NETWORK_LOG_ENTRIES)}))"

    /** 单次滚动像素上限（防 Agent 传入超大 delta 造成长阻塞滚动） */
    const val MAX_SCROLL_PX: Int = 20_000

    /** 网络日志回传条数上限（[NETWORK_MONITOR_JS] 侧缓冲上限亦为此值） */
    const val MAX_NETWORK_LOG_ENTRIES: Int = 200

    // ═══════════════════════════════════════════════════════════════════
    //  v1.1.0 高级能力脚本（错误率削减 + 动作空间扩展）
    //  rebase 注：自 main 的 v1.1.0 系列移植，插值统一改走 JsLiteral/refLookupJs。
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 滚动元素到视口中央并回读其**视口坐标**矩形（v1.1.0 点击前置步骤）。
     *
     * 旧 clickElement 直接拿 rect 中心注入触摸：元素在折叠线以下时中心点
     * 落在 WebView 边界外 → dispatchTouchEvent 打空 / 误中别的元素。本脚本
     * 先 scrollIntoView({block:'center'})，再返回矩形与 found 标记。
     */
    fun scrollIntoViewAndRectJs(ref: String): String =
        """
        (function(){
          ${refLookupJs(ref)}
          if (!el) return JSON.stringify(null);
          el.scrollIntoView({block:'center', inline:'nearest'});
          var r = el.getBoundingClientRect();
          return JSON.stringify({ found: true,
                                  x: r.left + r.width/2, y: r.top + r.height/2,
                                  left: r.left, top: r.top, width: r.width, height: r.height,
                                  vw: window.innerWidth, vh: window.innerHeight });
        })();
        """.trimIndent()

    /**
     * 命中测试：视口坐标 (x,y) 处最顶层元素是谁（v1.1.0 遮挡检测）。
     *
     * 返回该元素的 data-apex-hash 与 tag（无 hash 则打上临时标记），
     * 以及它是否为目标 ref 元素自身或其后代。粘性顶栏 / 模态遮罩
     * 拦截触摸时，据此给出「被遮挡」的明确失败原因而不是静默误点。
     *
     * rebase 注：原版先取 elementFromPoint 存 `el`，再用 `__apexFirst` 存目标；
     * [refLookupJs] 复用 `el` 名，故命中元素改名 `__apexHit`（语义不变）。
     */
    fun elementAtPointJs(x: Float, y: Float, targetRef: String): String =
        """
        (function(){
          var __apexHit = document.elementFromPoint($x, $y);
          if (!__apexHit) return JSON.stringify({ hit: false });
          ${refLookupJs(targetRef)}
          var isTargetOrChild = !!(el && (__apexHit === el || el.contains(__apexHit)));
          var h = __apexHit.getAttribute('data-apex-hash');
          if (!h) { h = 'hit_' + Math.abs((__apexHit.tagName + '|' + (__apexHit.innerText||'').slice(0,40)).split('').reduce(function(a,c){return ((a<<5)-a)+c.charCodeAt(0)|0;},0)).toString(36); __apexHit.setAttribute('data-apex-hash', h); }
          return JSON.stringify({ hit: true, hash: h, tag: __apexHit.tagName, isTargetOrChild: isTargetOrChild });
        })();
        """.trimIndent()

    /**
     * 模糊重定位（v1.1.0 错误率核心削减件）。
     *
     * SPA 局部刷新 / 文案微调会让语义哈希失配——旧实现直接抛
     * ElementNotFoundException 终止整个动作。本脚本按「tag + 文本包含」
     * 做包含式匹配，命中后**重新打上请求的 data-apex-hash** 并回读矩形，
     * 使后续动作可以无缝续跑（自愈而非报错）。
     */
    fun locateByFuzzyJs(tag: String, textContains: String, newRef: String): String =
        """
        (function(){
          var want = ${JsLiteral.string(textContains)};
          var tag = ${JsLiteral.string(tag)};
          var nodes = document.querySelectorAll(tag);
          var best = null, bestLen = Infinity;
          for (var i=0;i<nodes.length;i++){
            var el = nodes[i];
            var t = (el.innerText || el.value || el.getAttribute('aria-label') || el.getAttribute('placeholder') || '').replace(/\s+/g,' ').trim();
            if (!t || t.indexOf(want) < 0) continue;
            var style = window.getComputedStyle(el);
            if (style.display === 'none' || style.visibility === 'hidden') continue;
            var r = el.getBoundingClientRect();
            if (r.width === 0 || r.height === 0) continue;
            // 最短文本 = 最精确匹配（避免命中包含目标文案的大容器）
            if (t.length < bestLen) { best = el; bestLen = t.length; }
          }
          if (!best) return JSON.stringify(null);
          best.setAttribute('data-apex-hash', ${JsLiteral.string(newRef)});
          var r = best.getBoundingClientRect();
          return JSON.stringify({ ref: ${JsLiteral.string(newRef)}, tag: best.tagName,
                                  text: (best.innerText||'').replace(/\s+/g,' ').trim().slice(0,120),
                                  x: r.left + r.width/2, y: r.top + r.height/2,
                                  left: r.left, top: r.top, width: r.width, height: r.height });
        })();
        """.trimIndent()

    /**
     * React/Vue 安全写值（v1.1.0 替代旧 `el.value = x` 直赋）。
     *
     * 受控组件框架用原生 value 描述符拦截直赋——`el.value = x` 后框架
     * 的内部 state 不更新，下一次渲染把值弹回原样（Agent 看到「输入成功」
     * 实际没输进去）。本脚本：
     * 1. 输入类元素走原型链 native setter；
     * 2. contenteditable 走 textContent / execCommand 兜底；
     * 3. 派发 input + change 事件（bubbles）；
     * 4. append 模式在现值后追加而非替换。
     * 换行文本经 [JsLiteral.string] 转义为 \n 字面量，多行输入不再撕裂 JS 字符串。
     */
    fun setNativeValueJs(ref: String, text: String, append: Boolean): String =
        """
        (function(){
          ${refLookupJs(ref)}
          if (!el) return JSON.stringify({ ok: false, reason: 'not_found' });
          el.focus();
          var ok = true, reason = '';
          if (el.isContentEditable) {
            if (document.execCommand) { document.execCommand('selectAll', false, null); document.execCommand('insertText', false, ${JsLiteral.string(text)}); }
            else el.textContent = ${JsLiteral.string(text)};
            el.dispatchEvent(new InputEvent('input', {bubbles:true, inputType:'insertText', data:${JsLiteral.string(text)}}));
          } else if (typeof el.value === 'string' || el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
            var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
            var desc = Object.getOwnPropertyDescriptor(proto, 'value');
            var cur = desc && desc.get ? desc.get.call(el) : el.value;
            var next = ${if (append) "(cur || '') + ${JsLiteral.string(text)}" else JsLiteral.string(text)};
            if (desc && desc.set) desc.set.call(el, next); else el.value = next;
            el.dispatchEvent(new Event('input', {bubbles:true}));
            el.dispatchEvent(new Event('change', {bubbles:true}));
          } else { ok = false; reason = 'not_editable'; }
          return JSON.stringify({ ok: ok, reason: reason, value: (el.value !== undefined ? String(el.value).slice(0,80) : (el.textContent||'').slice(0,80)) });
        })();
        """.trimIndent()

    /** 键名 → {code, keyCode} 映射表（v1.1.0 pressKey 用，与 UI Events 规范对齐）。 */
    private val KEY_MAP: Map<String, Pair<String, Int>> = mapOf(
        "enter" to ("Enter" to 13), "tab" to ("Tab" to 9), "escape" to ("Escape" to 27),
        "backspace" to ("Backspace" to 8), "delete" to ("Delete" to 46),
        "arrowup" to ("ArrowUp" to 38), "arrowdown" to ("ArrowDown" to 40),
        "arrowleft" to ("ArrowLeft" to 37), "arrowright" to ("ArrowRight" to 39),
        "pageup" to ("PageUp" to 33), "pagedown" to ("PageDown" to 34),
        "home" to ("Home" to 36), "end" to ("End" to 35),
        "space" to (" " to 32),
    )

    /**
     * 键盘事件注入（v1.1.0）：对 activeElement 依次派发 keydown → keypress → keyup。
     *
     * Enter 的表单语义单独处理：未被 preventDefault 且目标在 form 内时调用
     * `form.requestSubmit()`——纯 KeyboardEvent 不会触发浏览器隐式提交，
     * 「输入搜索词后按回车没反应」是旧链路的高频失败场景。
     */
    fun pressKeyJs(key: String): String {
        val norm = key.trim().lowercase()
        val (jsKey, keyCode) = KEY_MAP[norm] ?: (key.take(1) to key.codePointAt(0))
        val code = if (jsKey.length == 1) jsKey.uppercase().let { "Key$it" } else jsKey
        return """
        (function(){
          var target = document.activeElement || document.body;
          var key = ${JsLiteral.string(jsKey)}, code = ${JsLiteral.string(code)}, kc = $keyCode;
          function fire(type, ctor){ var e; try { e = new ctor(type, {key:key, code:code, keyCode:kc, which:kc, bubbles:true, cancelable:true}); } catch(err){ e = document.createEvent('KeyboardEvent'); e.initKeyboardEvent(type, true, true, null, key, kc); } target.dispatchEvent(e); return e; }
          var kd = fire('keydown', KeyboardEvent);
          if (!kd.defaultPrevented) {
            fire('keypress', KeyboardEvent);
            if (key === 'Enter' && target.form && typeof target.form.requestSubmit === 'function') {
              try { target.form.requestSubmit(); } catch(e){}
            }
          }
          fire('keyup', KeyboardEvent);
          return true;
        })();
        """.trimIndent()
    }

    /** 悬停事件注入（v1.1.0）：mouseover → mouseenter → mousemove，驱动 CSS :hover 与悬停菜单。 */
    fun hoverJs(ref: String): String =
        """
        (function(){
          ${refLookupJs(ref)}
          if (!el) return false;
          var r = el.getBoundingClientRect();
          var opts = { bubbles: true, cancelable: true, clientX: r.left + r.width/2, clientY: r.top + r.height/2 };
          el.dispatchEvent(new MouseEvent('mouseover', opts));
          el.dispatchEvent(new MouseEvent('mouseenter', Object.assign({}, opts, {bubbles: false})));
          el.dispatchEvent(new MouseEvent('mousemove', opts));
          return true;
        })();
        """.trimIndent()

    /**
     * 结构化内容抽取（v1.1.0）：article / tables / links / meta 四模式。
     *
     * - article：readability-lite——优先 article/main/[role=main]，否则取文本
     *   最长的 div/section，剔除 script/style/nav/footer/aside 后回传正文；
     * - tables：全部 <table> 的表头 + 行（50 行 × 30 列封顶）；
     * - links：去重后的 a[href]（文本 + 链接，200 条封顶）；
     * - meta：title / description / canonical / og 标签。
     */
    fun extractContentJs(mode: String): String {
        val m = when (mode) {
            ExtractMode.TABLES.value -> "tables"
            ExtractMode.LINKS.value -> "links"
            ExtractMode.META.value -> "meta"
            else -> "article"
        }
        return """
        (function(){
          var mode = ${JsLiteral.string(m)};
          function txt(el, cap){ return (el && (el.innerText || el.textContent) || '').replace(/\s+/g,' ').trim().slice(0, cap || 20000); }
          if (mode === 'meta') {
            function meta(n){ var el = document.querySelector('meta[property="'+n+'"], meta[name="'+n+'"]'); return el ? (el.getAttribute('content')||'') : ''; }
            var can = document.querySelector('link[rel=canonical]');
            return JSON.stringify({ title: document.title, description: meta('description'),
              canonical: can ? can.href : '', ogTitle: meta('og:title'), ogDescription: meta('og:description'), ogImage: meta('og:image'), url: location.href });
          }
          if (mode === 'links') {
            var seen = {}, out = [], as = document.querySelectorAll('a[href]');
            for (var i=0;i<as.length && out.length<200;i++){
              var a = as[i], t = txt(a, 120), h = a.href;
              if (!h || seen[h]) continue; seen[h] = 1;
              out.push({ text: t, href: h });
            }
            return JSON.stringify(out);
          }
          if (mode === 'tables') {
            var tabs = document.querySelectorAll('table'), out2 = [];
            for (var i=0;i<tabs.length && out2.length<20;i++){
              var tb = tabs[i], rows = [];
              var trs = tb.querySelectorAll('tr');
              for (var j=0;j<trs.length && rows.length<50;j++){
                var cells = [], tds = trs[j].querySelectorAll('th,td');
                for (var k=0;k<tds.length && cells.length<30;k++) cells.push(txt(tds[k], 200));
                if (cells.length) rows.push(cells);
              }
              if (rows.length) out2.push({ caption: txt(tb.caption, 120), rows: rows });
            }
            return JSON.stringify(out2);
          }
          // article 模式
          var root = document.querySelector('article') || document.querySelector('main') || document.querySelector('[role=main]');
          if (!root) {
            var best = null, bestLen = 0, divs = document.querySelectorAll('div,section');
            for (var i=0;i<divs.length;i++){
              var d = divs[i], l = (d.innerText||'').length;
              if (l > bestLen) { best = d; bestLen = l; }
            }
            root = best || document.body;
          }
          var clone = root.cloneNode(true);
          var junk = clone.querySelectorAll('script,style,nav,footer,aside,header,noscript,[aria-hidden=true]');
          for (var i=0;i<junk.length;i++) junk[i].remove();
          var text = txt(clone, 20000);
          return JSON.stringify({ title: document.title, url: location.href, wordCount: text.length, text: text });
        })();
        """.trimIndent()
    }

    /** 当前 URL 读取（v1.1.0 waitForUrl 轮询用）。 */
    val CURRENT_URL_JS: String
        get() = "(function(){ return location.href; })();"

    // ═══════════════════════════════════════════════════════════════════
    //  v1.3.0 深度增强脚本（等待谓词 / 模糊定位收敛 / 网络空闲）
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 反检测隐身 JS（#13 轻量版）：隐藏自动化痕迹，降低被反爬识别概率。
     * 注意：仅做基础痕迹抹除，不过度伪装（避免破坏页面功能）。
     *
     * v1.3.0 自引擎伴生对象下沉到此（「JS 工厂只放 BrowserScript.kt」的收敛口径），
     * 引擎侧 onPageFinished 与 [NETWORK_MONITOR_JS] 合并注入。
     */
    val STEALTH_JS: String
        get() = """
        (function(){
            try {
                Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
            } catch(e) {}
            try {
                Object.defineProperty(navigator, 'languages', { get: () => ['zh-CN','zh','en'] });
            } catch(e) {}
        })();
        """.trimIndent()

    /**
     * 按文本模糊查找元素（v1.3.0）：迁移自 BrowserEngine.locateElements 的内联 JS
     * —— 该处手写了一套只覆盖 `'` 与 `"` 的私有转义（B2 安全债），且换行/U+2028
     * 全部裸拼。现收敛到本工厂，[textContains] 与 [tag] 一律经 [JsLiteral.string]。
     *
     * 模糊匹配与 hash 逻辑同 [locateByFuzzyJs] 统一（含 role）：
     * - 文本取值链 `innerText → value → aria-label → placeholder`；
     * - 无 data-apex-hash 的命中元素现场计算语义哈希（role + text + tag）并**打上
     *   data-apex-hash**，使返回的 ref 可直接用于后续 click/input；
     * - 零尺寸元素跳过（不可见即不可操作）。
     *
     * @param textContains 目标文本子串（Agent 自由文本，经 JsLiteral 编码）
     * @param tag 限定标签/选择器（如 "button"、"a"、"input[name=q]"）
     * @param limit 返回条数上限（收敛到 1..50）
     */
    fun locateJs(textContains: String, tag: String, limit: Int): String =
        """
        (function(){
          var want = ${JsLiteral.string(textContains)};
          var nodes = document.querySelectorAll(${JsLiteral.string(tag)});
          var out = [];
          for (var i=0;i<nodes.length && out.length<${limit.coerceIn(1, 50)};i++){
            var el = nodes[i];
            var t = (el.innerText || el.value || el.getAttribute('aria-label') || el.getAttribute('placeholder') || '').replace(/\s+/g,' ').trim();
            if (!t || t.indexOf(want) < 0) continue;
            var r = el.getBoundingClientRect();
            if (r.width === 0 || r.height === 0) continue;
            var ref = el.getAttribute('data-apex-hash');
            if (!ref) {
              var role = el.getAttribute('role') || el.tagName.toLowerCase();
              var h = 0; var s = role + '|' + t.slice(0,80) + '|' + el.tagName;
              for (var j=0;j<s.length;j++){ h = ((h<<5)-h)+s.charCodeAt(j); h|=0; }
              ref = 'r_' + Math.abs(h).toString(36);
              el.setAttribute('data-apex-hash', ref);
            }
            out.push({ ref: ref, tag: el.tagName, text: t.slice(0,120) });
          }
          return JSON.stringify(out);
        })();
        """.trimIndent()

    /**
     * CSS 选择器命中计数（v1.3.0）：`document.querySelectorAll(...).length`，
     * 同步返回数值 —— waitForCondition(selector_count) 的取值侧。
     * 选择器经 [JsLiteral.string] 编码；广义选择器抛错时兜底 0。
     */
    fun selectorCountJs(selector: String): String =
        """
        (function(){ try { return document.querySelectorAll(${JsLiteral.string(selector)}).length; }
        catch(e){ return 0; } })();
        """.trimIndent()

    /**
     * 网络在途请求数（v1.3.0）：同步返回 `window.__apexNetPending || 0`。
     *
     * 数据源是 [NETWORK_MONITOR_JS] 维护的计数（fetch/xhr 发起 +1、settle -1）；
     * 监控未注入/被导航重置时返回 0。**无 Promise**（evaluateJavascript 不等待
     * Promise），真正的「连续 2 次为 0 才算 idle」由 Kotlin 侧轮询判定。
     */
    fun networkIdleJs(): String =
        "(function(){ return window.__apexNetPending || 0; })();"

    // ═══════════════════════════════════════════════════════════════════
    //  v1.7.0 细节功能脚本（全页截图几何 / 阅读模式采集 / 选择文本 / 夜间模式）
    //  注入口径不变：外部字符串一律 [JsLiteral.string]，无 Promise（等待归 Kotlin 侧）。
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 页面几何采集（v1.7.0 全页截图的分段依据）。
     *
     * 返回 scrollY / scrollHeight / scrollWidth / viewportH / viewportW 的
     * 同步 JSON。scrollHeight 取 documentElement 与 body 的较大值——部分
     * 站点（典型：旧式框架页 + body 高度撑开）二者差异巨大，取小值会把
     * 全页截图截短。无 Promise（等待归 Kotlin 侧轮询/延时）。
     */
    val PAGE_GEOMETRY_JS: String
        get() = """
        (function(){
          var de = document.documentElement, b = document.body;
          return JSON.stringify({
            scrollY: window.scrollY || 0,
            scrollHeight: Math.max(de ? de.scrollHeight : 0, b ? b.scrollHeight : 0),
            scrollWidth: Math.max(de ? de.scrollWidth : 0, b ? b.scrollWidth : 0),
            viewportH: window.innerHeight || 0,
            viewportW: window.innerWidth || 0
          });
        })();
        """.trimIndent()

    /**
     * 绝对滚动到文档 Y 坐标（v1.7.0 全页截图的分段滚动）。
     *
     * 与 [scrollByJs]（相对滚动）互补：截图拼接需要**可复现**的段起点，
     * 相对滚动在 sticky/fixed 元素干扰下会漂移。y 经 Kotlin 侧
     * `coerceIn(0, MAX_SCROLL_PX)` 钳制后注入（数值字面量，非外部字符串）。
     */
    fun scrollToYJs(y: Int): String {
        val clamped = y.coerceIn(0, MAX_SCROLL_PX)
        return """
        (function(){
          try { window.scrollTo(0, $clamped); return true; } catch(e){ return false; }
        })();
        """.trimIndent()
    }

    /**
     * 阅读模式结构化采集（v1.7.0，[com.apex.browser.core.ReaderParser] 的数据源）。
     *
     * 与 [extractContentJs] 的 article 模式（拍平纯文本）不同：本脚本按
     * **文档顺序**输出结构块（heading 带 level / paragraph / quote / code /
     * image 带 alt / list_item），并采集排版元数据（og:site_name / author /
     * article:published_time）。正文根的选取与 article 模式同源
     *（article → main → [role=main] → 最长 div/section）。
     *
     * 防御性要点（老内核兼容）：
     * - `el.closest()` 逐块兜底 try/catch（Chromium 41+ 才有；再老的选择器
     *   引擎上querySelectorAll 抛错时整体返回 null，调用方降级 article 文本）；
     * - `p` 在 `li` / `blockquote` 内时跳过（由外层块统一取文本，避免同段
     *   文本双记）；
     * - `pre` 保留原始换行（不做 `\s+` 折叠——代码块的格式即内容）；
     * - data: 内联图（站点图标/雪花像素）跳过。
     * 块数硬上限 400、单块文本 4000 字符（与 [ReaderParser] 双侧一致的口径）。
     */
    val READER_DOCUMENT_JS: String
        get() = """
        (function(){
          try {
            function txt(el, cap, keepBreaks){
              if (!el) return '';
              var s = el.innerText || el.textContent || '';
              if (!keepBreaks) s = s.replace(/\s+/g, ' ');
              return s.trim().slice(0, cap || 4000);
            }
            var root = document.querySelector('article') || document.querySelector('main') || document.querySelector('[role=main]');
            if (!root) {
              var best = null, bestLen = 0, divs = document.querySelectorAll('div,section');
              for (var i=0;i<divs.length;i++){ var d = divs[i], l = (d.innerText||'').length; if (l > bestLen) { best = d; bestLen = l; } }
              root = best || document.body;
            }
            if (!root) return JSON.stringify(null);
            var clone = root.cloneNode(true);
            var junk = clone.querySelectorAll('script,style,nav,footer,aside,header,noscript,iframe,form,[aria-hidden=true],[role=navigation]');
            for (var i=0;i<junk.length;i++) junk[i].remove();
            function meta(n){
              var el = document.querySelector('meta[property="'+n+'"], meta[name="'+n+'"]');
              return el ? (el.getAttribute('content')||'') : '';
            }
            var blocks = [];
            var nodes = clone.querySelectorAll('h1,h2,h3,h4,h5,h6,p,blockquote,pre,img,li');
            for (var j=0;j<nodes.length && blocks.length<400;j++){
              var el = nodes[j], tag = el.tagName;
              if (tag === 'IMG') {
                var src = el.getAttribute('src') || '';
                if (!src || src.indexOf('data:') === 0) continue;
                blocks.push({ kind:'image', text:'', alt:(el.getAttribute('alt')||'').trim().slice(0,300), src:src.slice(0,300) });
                continue;
              }
              if (tag === 'P') {
                var owner = null;
                try { owner = el.closest('li,blockquote'); } catch(e) { owner = null; }
                if (owner) continue;
                var ptext = txt(el, 4000);
                if (ptext) blocks.push({ kind:'paragraph', text:ptext, level:0 });
                continue;
              }
              if (tag === 'BLOCKQUOTE') {
                var qtext = txt(el, 4000);
                if (qtext) blocks.push({ kind:'quote', text:qtext, level:0 });
                continue;
              }
              if (tag === 'PRE') {
                var ctext = txt(el, 4000, true);
                if (ctext) blocks.push({ kind:'code', text:ctext, level:0 });
                continue;
              }
              if (tag === 'LI') {
                var ltext = txt(el, 2000);
                if (ltext) blocks.push({ kind:'list_item', text:ltext, level:0 });
                continue;
              }
              var lvl = parseInt(tag.charAt(1), 10) || 1;
              var htext = txt(el, 300);
              if (htext) blocks.push({ kind:'heading', text:htext, level:lvl });
            }
            if (!blocks.length) return JSON.stringify(null);
            return JSON.stringify({
              url: location.href, title: document.title,
              siteName: meta('og:site_name'),
              byline: meta('author') || meta('article:author'),
              publishedTime: meta('article:published_time') || meta('date'),
              blocks: blocks
            });
          } catch(e) { return JSON.stringify(null); }
        })();
        """.trimIndent()

    /**
     * 当前选区文本（v1.7.0「复制选择文本」）：`window.getSelection()` 的
     * 字符串化；无选区返回空串。同步、无 Promise。
     */
    val SELECTION_TEXT_JS: String
        get() = """
        (function(){
          try {
            if (!window.getSelection) return '';
            return String(window.getSelection());
          } catch(e) { return ''; }
        })();
        """.trimIndent()

    /**
     * 按 ref 读取元素可见文本（v1.7.0「复制元素文本」：Agent 指定某卡片/
     * 段落把文字取回）。[ref] 经 [JsLiteral.string] 编码，定位与
     * [rectByRefJs] 同源（[refLookupJs] 的 `===` 严格比较，不进 CSS 解析）。
     */
    fun elementTextJs(ref: String): String =
        """
        (function(){
          ${refLookupJs(ref)}
          if (!el) return JSON.stringify(null);
          var t = (el.innerText || el.textContent || '').trim();
          return JSON.stringify({ text: t.slice(0, 20000), length: t.length });
        })();
        """.trimIndent()

    /**
     * 夜间模式注入（v1.7.0 显示控制）：算法反色滤镜。
     *
     * `html` 整树 `invert(1) hue-rotate(180deg)`，媒体元素再反转一次还原
     *（图片/视频不该变「负片」）；内联 background-image 由属性选择器匹配
     * 同样二次反转。幂等守卫 `window.__apexForceDark`——重复注入零效果，
     * 导航后 JS 上下文重置由引擎侧监听重注（与 STEALTH_JS 同款接线哲学）。
     *
     * 已知取舍（文档而非隐瞒）：fixed/sticky 元素与 iframe 内文档不参与
     * html 级滤镜的部分场景会出现「半亮半暗」；这是算法反色的固有形态，
     * 追求完美需 androidx.webkit 的 algorithmic darkening（引依赖，不做）。
     */
    val FORCE_DARK_JS: String
        get() = """
        (function(){
          if (window.__apexForceDark) return true;
          window.__apexForceDark = true;
          var s = document.createElement('style');
          s.id = '__apex-force-dark-style';
          s.textContent = 'html{filter:invert(1) hue-rotate(180deg);background:#0b0e14 !important;}' +
            'img,video,picture,canvas,svg,iframe,[style*="background-image"]{filter:invert(1) hue-rotate(180deg);}';
          document.documentElement.appendChild(s);
          return true;
        })();
        """.trimIndent()

    /** 夜间模式撤除（v1.7.0）：摘掉注入的 style 节点并复位幂等标记。 */
    val FORCE_DARK_OFF_JS: String
        get() = """
        (function(){
          window.__apexForceDark = false;
          var s = document.getElementById('__apex-force-dark-style');
          if (s && s.parentNode) s.parentNode.removeChild(s);
          return true;
        })();
        """.trimIndent()

    // ═══════════════════════════════════════════════════════════════════
    //  v1.9.0 Playwright 对标脚本（storage 访问 / 表单检测 / 性能指标 /
    //  iframe 枚举 / 布局采集）。
    //
    //  注入口径不变：外部字符串（key/value）一律 [JsLiteral.string]，
    //  整型参数以无大括号 `$name` 注入（数值字面量，非外部字符串），
    //  无 Promise（等待语义归 Kotlin 侧）。
    // ═══════════════════════════════════════════════════════════════════

    /** Storage 读取回传的值截断上限（超长偏好/缓存值如实标注，不静默截断）。 */
    const val MAX_STORAGE_VALUE_CHARS: Int = 32_000

    /** Storage keys 回传的键数上限。 */
    const val MAX_STORAGE_KEYS: Int = 500

    /** Storage 操作类型（[storageJs] 的 op 字面量来源 —— Kotlin 枚举控制，非外部输入）。 */
    enum class StorageOp(val wire: String) {
        GET("get"), SET("set"), REMOVE("remove"),
        KEYS("keys"), SIZE("size"), CLEAR("clear")
    }

    /**
     * Web Storage 访问（v1.9.0，Playwright `storage_state` 对标的读写字面面）。
     *
     * 六操作统一信封 `{ok, ...}`；被禁用（引擎关 domStorage 或隐私模式）/
     * 超配额（QUOTA_EXCEEDED）时 `ok=false` 且带 error 文案，不抛撕裂 JS。
     * 读值按 [MAX_STORAGE_VALUE_CHARS] 截断并如实标注（length/truncated），
     * keys 按 [MAX_STORAGE_KEYS] 截断并回传 total。`$store` 为 Kotlin 侧
     * 计算后的 `localStorage` / `sessionStorage` 标识符字面量（非外部字符串）。
     */
    fun storageJs(
        op: StorageOp,
        key: String = "",
        value: String = "",
        session: Boolean = false,
    ): String {
        val store = if (session) "sessionStorage" else "localStorage"
        return """
        (function(){
          try {
            var store = $store;
            var op = ${JsLiteral.string(op.wire)};
            if (op === 'size') {
              return JSON.stringify({ ok: true, count: store.length });
            }
            if (op === 'keys') {
              var ks = [];
              for (var i = 0; i < store.length && ks.length < $MAX_STORAGE_KEYS; i++) {
                ks.push(store.key(i));
              }
              return JSON.stringify({ ok: true, keys: ks, total: store.length, truncated: store.length > ks.length });
            }
            if (op === 'clear') {
              store.clear();
              return JSON.stringify({ ok: true });
            }
            if (op === 'get') {
              var v = store.getItem(${JsLiteral.string(key)});
              if (v === null || v === undefined) return JSON.stringify({ ok: true, exists: false });
              var t = String(v);
              return JSON.stringify({ ok: true, exists: true, value: t.slice(0, $MAX_STORAGE_VALUE_CHARS), length: t.length, truncated: t.length > $MAX_STORAGE_VALUE_CHARS });
            }
            if (op === 'set') {
              store.setItem(${JsLiteral.string(key)}, ${JsLiteral.string(value)});
              return JSON.stringify({ ok: true });
            }
            if (op === 'remove') {
              store.removeItem(${JsLiteral.string(key)});
              return JSON.stringify({ ok: true });
            }
            return JSON.stringify({ ok: false, error: 'unknown op' });
          } catch(e) {
            return JSON.stringify({ ok: false, error: String((e && e.message) || e).slice(0, 160) });
          }
        })();
        """.trimIndent()
    }

    /** 表单检测：单个表单字段数上限（超限如实标注 fieldCount/truncatedFields）。 */
    const val MAX_FIELDS_PER_FORM: Int = 40

    /** 表单检测：孤儿字段（无 form 包裹的 SPA 散字段）上限。 */
    const val MAX_ORPHAN_FIELDS: Int = 30

    /** 表单检测：单 select 回传选项数上限。 */
    const val MAX_SELECT_OPTIONS: Int = 12

    /** 表单检测：表单数上限。 */
    const val MAX_FORMS: Int = 10

    /**
     * 表单结构检测（v1.9.0）：发现页面全部 `<form>` + 孤儿字段（无 form 包裹
     * 的 SPA 散字段），逐字段回传可直接驱动的 [ref]。
     *
     * **ref 与快照同源**：hash 函数 / semanticKey 公式 / 可见性剪枝 /
     * 撞键 `#n` 去重与 [snapshotJs] 逐字一致 —— Agent 拿到表单报告即可直接
     * 构造 `batchExecute` 动作（click/input/select），无需先跑一次快照对齐。
     * 不可见字段（display:none / 零尺寸，快照本就不给 ref）同样**不编造 ref**
     * —— 照常回传字段信息（`visible:false`、ref 留空），与快照契约一致。
     *
     * 安全语义：`type=password` 字段**不回传值**（只报 hasValue），Agent 可
     * 检测「已记住密码」但读不走内容。选项、label、required/disabled/readonly
     * 均回传，值预览截断 80 字符。
     */
    fun formDetectJs(): String {
        return """
        (function(){
          function hash(str){
            var h = 0;
            for (var i=0;i<str.length;i++){ var c = str.charCodeAt(i); h = ((h<<5)-h)+c; h|=0; }
            return 'r_' + (Math.abs(h)>>>0).toString(36);
          }
          var seen = {};
          function refOf(el){
            var style = window.getComputedStyle(el);
            var rect = el.getBoundingClientRect();
            var visible = style.display !== 'none' && style.visibility !== 'hidden'
                  && parseFloat(style.opacity) > 0.05
                  && rect.width > 0 && rect.height > 0;
            var text = (el.innerText || el.value || el.placeholder || '').replace(/\s+/g,' ').trim().slice(0, 120);
            var role = el.getAttribute('role') || el.tagName.toLowerCase();
            var semanticKey = role + '|' + text + '|' + el.tagName + '|' + Math.round(rect.top + window.scrollY);
            var ref = hash(semanticKey);
            var n = seen[semanticKey] || 0;
            seen[semanticKey] = n + 1;
            if (n > 0) ref = ref + '#' + (n + 1);
            if (visible) el.setAttribute('data-apex-hash', ref);
            return { ref: visible ? ref : '', visible: visible, rect: rect };
          }
          function labelOf(el){
            try {
              if (el.labels && el.labels.length) {
                return (el.labels[0].innerText || '').replace(/\s+/g,' ').trim().slice(0, 60);
              }
              var wrap = el.closest ? el.closest('label') : null;
              if (wrap) return (wrap.innerText || '').replace(/\s+/g,' ').trim().slice(0, 60);
            } catch(e) {}
            return '';
          }
          function fieldOf(el){
            var tag = el.tagName.toLowerCase();
            var type = (el.getAttribute('type') || (tag === 'select' || tag === 'textarea' ? tag : 'text')).toLowerCase();
            var r = refOf(el);
            var f = {
              ref: r.ref, visible: r.visible, tag: tag, type: type,
              name: el.getAttribute('name') || '',
              id: el.id || '',
              required: !!el.required, disabled: !!el.disabled, readOnly: !!el.readOnly,
              label: labelOf(el), placeholder: el.getAttribute('placeholder') || '',
              hasValue: false, valuePreview: '', checked: null, optionCount: 0, options: []
            };
            if (type === 'password') {
              f.hasValue = !!el.value;
            } else if (tag === 'select') {
              f.hasValue = el.selectedIndex >= 0;
              if (el.selectedIndex >= 0 && el.options[el.selectedIndex]) {
                f.valuePreview = String(el.options[el.selectedIndex].value || '').slice(0, 80);
              }
              f.optionCount = el.options.length;
              var opts = [];
              for (var i = 0; i < el.options.length && opts.length < $MAX_SELECT_OPTIONS; i++) {
                opts.push({ value: String(el.options[i].value || '').slice(0, 80), text: (el.options[i].text || '').replace(/\s+/g,' ').trim().slice(0, 60), selected: i === el.selectedIndex });
              }
              f.options = opts;
            } else if (type === 'checkbox' || type === 'radio') {
              f.checked = !!el.checked;
              f.hasValue = !!el.value;
            } else {
              f.hasValue = !!el.value;
              f.valuePreview = String(el.value || '').slice(0, 80);
            }
            return f;
          }
          try {
            var forms = [];
            var formEls = document.querySelectorAll('form');
            for (var i = 0; i < formEls.length && forms.length < $MAX_FORMS; i++) {
              var fe = formEls[i];
              var fields = [];
              var controls = fe.querySelectorAll('input,select,textarea,button');
              for (var j = 0; j < controls.length; j++) {
                if (fields.length >= $MAX_FIELDS_PER_FORM) break;
                var c = controls[j];
                if (c.tagName === 'BUTTON' || (c.tagName === 'INPUT' && (c.getAttribute('type') || '').toLowerCase() === 'submit')) continue;
                fields.push(fieldOf(c));
              }
              var submitRefs = [];
              for (var j = 0; j < controls.length && submitRefs.length < 3; j++) {
                var c = controls[j];
                var t = (c.getAttribute('type') || '').toLowerCase();
                if (c.tagName === 'BUTTON' && (t === 'submit' || t === '' || t === 'button')) {
                  var r = refOf(c);
                  if (r.visible) submitRefs.push(r.ref);
                }
              }
              forms.push({
                action: (fe.getAttribute('action') || '').slice(0, 200),
                method: (fe.getAttribute('method') || 'get').toLowerCase(),
                id: fe.id || '', name: fe.getAttribute('name') || '',
                fieldCount: fields.length,
                truncatedFields: controls.length > $MAX_FIELDS_PER_FORM,
                submitRefs: submitRefs,
                fields: fields
              });
            }
            var orphans = [];
            var all = document.querySelectorAll('input,select,textarea');
            for (var i = 0; i < all.length; i++) {
              if (orphans.length >= $MAX_ORPHAN_FIELDS) break;
              var el = all[i];
              if (el.closest && el.closest('form')) continue;
              orphans.push(fieldOf(el));
            }
            return JSON.stringify({ v: 1, totalForms: formEls.length, forms: forms, orphanCount: orphans.length, orphans: orphans });
          } catch(e) {
            return JSON.stringify(null);
          }
        })();
        """.trimIndent()
    }

    /**
     * 性能指标采集（v1.9.0，Playwright `performance` 对标）：Navigation Timing
     * 关键里程碑（DNS / TCP / TTFB / DOMContentLoaded / Load）+ Resource Timing
     * 按 initiatorType 聚合（数量 / 传输字节 / 最慢）+ 最慢资源 Top 8。
     * 纯表达式 IIFE，只读 performance API，无 DOM 变更、无网络。
     */
    val PERFORMANCE_METRICS_JS: String
        get() = """
        (function(){
          try {
            if (!window.performance || !performance.getEntriesByType) return JSON.stringify(null);
            var nav = performance.getEntriesByType('navigation')[0] || null;
            var res = performance.getEntriesByType('resource') || [];
            var buckets = {};
            for (var i = 0; i < res.length; i++) {
              var r = res[i];
              var k = r.initiatorType || 'other';
              if (!buckets[k]) buckets[k] = { count: 0, bytes: 0, maxDuration: 0 };
              buckets[k].count++;
              buckets[k].bytes += (r.transferSize || 0);
              if (r.duration > buckets[k].maxDuration) buckets[k].maxDuration = r.duration;
            }
            var slow = res.slice().sort(function(a,b){ return b.duration - a.duration; }).slice(0, 8).map(function(r){
              return { url: String(r.name || '').slice(0, 140), duration: Math.round(r.duration), bytes: r.transferSize || 0, type: r.initiatorType || 'other' };
            });
            var n = nav ? {
              dns: Math.round(nav.domainLookupEnd - nav.domainLookupStart),
              tcp: Math.round(nav.connectEnd - nav.connectStart),
              ttfb: Math.round(nav.responseStart - nav.startTime),
              domInteractive: Math.round(nav.domInteractive - nav.startTime),
              domContentLoaded: Math.round(nav.domContentLoadedEventEnd - nav.startTime),
              loadEvent: Math.round(nav.loadEventEnd - nav.startTime),
              transferSize: nav.transferSize || 0,
              encodedBodySize: nav.encodedBodySize || 0,
              decodedBodySize: nav.decodedBodySize || 0,
              type: nav.type || '',
              redirectCount: nav.redirectCount || 0
            } : null;
            return JSON.stringify({ v: 1, navigation: n, resourceCount: res.length, buckets: buckets, slowest: slow });
          } catch(e) {
            return JSON.stringify(null);
          }
        })();
        """.trimIndent()

    /** iframe 枚举回传上限。 */
    const val MAX_FRAMES: Int = 20

    /**
     * iframe / frame 枚举（v1.9.0）：Agent 的「感知面」—— 页面里嵌了哪些框
     * （src / name / title / 尺寸 / sandbox / 同源判定）。不做框内操作
     *（那是跨 frame 定位的另一代工程），先解决「Agent 根本不知道有 iframe」。
     */
    val FRAME_LIST_JS: String
        get() = """
        (function(){
          try {
            var fs = document.querySelectorAll('iframe,frame');
            var out = [];
            for (var i = 0; i < fs.length && out.length < $MAX_FRAMES; i++) {
              var el = fs[i];
              var r = el.getBoundingClientRect();
              var same = false;
              try { same = !!el.contentDocument; } catch(e) { same = false; }
              out.push({
                src: (el.getAttribute('src') || el.src || '').toString().slice(0, 200),
                name: el.getAttribute('name') || '',
                title: (el.getAttribute('title') || '').slice(0, 80),
                width: Math.round(r.width), height: Math.round(r.height),
                sandbox: el.getAttribute('sandbox') || '',
                sameOrigin: same
              });
            }
            return JSON.stringify({ v: 1, total: fs.length, truncated: fs.length > out.length, frames: out });
          } catch(e) {
            return JSON.stringify(null);
          }
        })();
        """.trimIndent()

    /**
     * 布局采集脚本（v1.9.0，自宿主 BrowserLayoutTools 下沉 —— 宿主自维护
     * 百行内联 JS 的形态不可持续，库才是它的家）。
     *
     * 纯表达式 IIFE（只读 DOM / getComputedStyle / getBoundingClientRect，
     * 无网络、无 DOM 变更），与宿主版逐行为对齐：
     * - 视口与横向溢出 / landmark 区块矩形 / flex / grid 容器属性；
     * - absolute / fixed 定位与定位祖先判定 / 越出视口元素 / z-index 配 static；
     * - 缺 alt 图片 / 标题层级链 / 文字截断 / 触屏小点击目标（<44px）；
     * - 低对比度采样（WCAG 比值 vs 背景链混合）/ 暗色模式探测。
     *
     * [maxElements] 为扫描上限（50..4000，Kotlin 侧钳制后以数值字面量注入）。
     */
    fun layoutCollectJs(maxElements: Int): String {
        val n = maxElements.coerceIn(50, 4_000)
        return """
        (function(){
          var LIMIT=$n;
          var out={viewport:{w:innerWidth,h:innerHeight,scrollW:document.documentElement.scrollWidth,overflowX:document.documentElement.scrollWidth>innerWidth+1},landmarks:[],flex:[],grid:[],absPos:[],fixed:[],overflowEls:[],zIssues:[],noAltImgs:[],headings:[],trunc:[],smallTargets:[],lowContrast:[],colorScheme:{},maxDepth:0,textChars:0};
          function rectOf(el){var r=el.getBoundingClientRect();return {x:Math.round(r.x),y:Math.round(r.y+scrollY),w:Math.round(r.width),h:Math.round(r.height)};}
          function selOf(el){var t=el.tagName.toLowerCase();var id=el.id?'#'+el.id:'';var c='';try{if(el.className&&typeof el.className==='string'&&el.className.trim()){c='.'+el.className.trim().split(/\s+/).slice(0,2).join('.');}}catch(e){}
            return t+id+c;}
          function directText(el){for(var i=0;i<el.childNodes.length;i++){var n=el.childNodes[i];if(n.nodeType===3&&n.data.trim())return n.data.trim();}return '';}
          var body=document.body;
          out.textChars=body&&body.innerText?body.innerText.length:0;
          if(!body)return JSON.stringify(out);
          var md=window.matchMedia?matchMedia('(prefers-color-scheme: dark)'):null;
          var csMeta=document.querySelector('meta[name="color-scheme"]');
          var bodyBg=body?getComputedStyle(body).backgroundColor:'';
          out.colorScheme={prefersDark:md?!!md.matches:false,meta:csMeta?(csMeta.content||'').slice(0,30):'',bodyBg:bodyBg.slice(0,24),darkBg:false};
          function parseCol(s){var m=s&&s.match&&s.match(/rgba?\(([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)(?:[,\s\/]+([\d.]+))?\)/);return m?[+m[1],+m[2],+m[3],m[4]===undefined?1:+m[4]]:null;}
          function lum(c){var f=function(v){v=v/255;return v<=0.03928?v/12.92:Math.pow((v+0.055)/1.055,2.4);};return 0.2126*f(c[0])+0.7152*f(c[1])+0.0722*f(c[2]);}
          function contrastRatio(a,b){var l1=lum(a),l2=lum(b),hi=Math.max(l1,l2),lo=Math.min(l1,l2);return (hi+0.05)/(lo+0.05);}
          function effBg(el){var chain=[],p=el;
            while(p&&p!==document.documentElement&&chain.length<6){var c=parseCol(getComputedStyle(p).backgroundColor);if(c&&c[3]>0.01)chain.push(c);if(c&&c[3]>=1)break;p=p.parentElement;}
            var base=out.colorScheme.prefersDark||out.colorScheme.darkBg?[18,18,18]:[255,255,255];
            for(var i=chain.length-1;i>=0;i--){var c=chain[i];base=[c[0]*c[3]+base[0]*(1-c[3]),c[1]*c[3]+base[1]*(1-c[3]),c[2]*c[3]+base[2]*(1-c[3])];}
            return base;}
          var mbg=parseCol(bodyBg);
          if(mbg&&mbg[3]>0.5&&lum(mbg)<0.18)out.colorScheme.darkBg=true;
          var sampled=0;
          var all=body.querySelectorAll('*');
          for(var i=0;i<all.length&&i<LIMIT;i++){
            var el=all[i];var tag=el.tagName.toLowerCase();
            if(tag==='script'||tag==='style'||tag==='noscript'||tag==='svg')continue;
            var s=getComputedStyle(el);
            if(s.display==='none'||s.visibility==='hidden')continue;
            var r=rectOf(el);
            if(r.w<1&&r.h<1)continue;
            var d=0,p=el;while(p&&p!==body){d++;p=p.parentElement;}if(d>out.maxDepth)out.maxDepth=d;
            if((tag==='header'||tag==='nav'||tag==='main'||tag==='aside'||tag==='footer')&&out.landmarks.length<12){
              out.landmarks.push({tag:tag,rect:r,text:(el.textContent||'').trim().slice(0,30)});
            }
            if((s.display==='flex'||s.display==='inline-flex')&&out.flex.length<20){
              out.flex.push({sel:selOf(el),dir:s.flexDirection,wrap:s.flexWrap,justify:s.justifyContent,align:s.alignItems,gap:s.gap,kids:el.children.length,rect:r});
            }else if((s.display==='grid'||s.display==='inline-grid')&&out.grid.length<12){
              out.grid.push({sel:selOf(el),cols:s.gridTemplateColumns.slice(0,60),kids:el.children.length,rect:r});
            }
            if(s.position==='absolute'&&out.absPos.length<16){
              var anc=el.parentElement,ok=false;
              while(anc&&anc!==document.documentElement){var ps=getComputedStyle(anc);if(ps.position!=='static'){ok=true;break;}anc=anc.parentElement;}
              out.absPos.push({sel:selOf(el),rect:r,positionedAncestor:ok});
            }
            if(s.position==='fixed'&&out.fixed.length<12){out.fixed.push({sel:selOf(el),rect:r,z:s.zIndex});}
            if(s.zIndex!=='auto'&&s.position==='static'&&out.zIssues.length<10){out.zIssues.push({sel:selOf(el),z:s.zIndex});}
            if((r.x<-2||r.x+r.w>out.viewport.w+2)&&s.position!=='fixed'&&out.overflowEls.length<12){
              out.overflowEls.push({sel:selOf(el),rect:r});
            }
            if(tag==='img'&&!el.getAttribute('alt')&&out.noAltImgs.length<8){out.noAltImgs.push({src:(el.getAttribute('src')||'').slice(0,40)});}
            if(tag.length===2&&tag[0]==='h'&&tag[1]>='1'&&tag[1]<='6'&&out.headings.length<20){
              out.headings.push({level:parseInt(tag[1],10),text:(el.textContent||'').trim().slice(0,30)});
            }
            var txt=directText(el);
            if(txt&&(el.scrollWidth>el.clientWidth+1||el.scrollHeight>el.clientHeight+1)&&out.trunc.length<12){
              out.trunc.push({sel:selOf(el),text:txt.slice(0,26),sw:el.scrollWidth,cw:el.clientWidth,sh:el.scrollHeight,ch:el.clientHeight});
            }
            var interactive=tag==='a'||tag==='button'||tag==='input'||tag==='select'||tag==='textarea'||tag==='summary'||el.getAttribute('role')==='button'||el.hasAttribute('onclick');
            if(interactive&&(r.w<44||r.h<44)&&r.w>2&&r.h>2&&out.smallTargets.length<12){
              out.smallTargets.push({sel:selOf(el),w:r.w,h:r.h});
            }
            if(txt&&sampled<90){
              sampled++;
              var fg=parseCol(s.color),bg=effBg(el);
              if(fg&&bg){
                var fs=parseFloat(s.fontSize)||16,large=fs>=24||(fs>=18.66&&(+s.fontWeight>=700));
                var ratio=Math.round(contrastRatio(fg,bg)*100)/100;
                if(ratio<(large?3.0:4.5)&&out.lowContrast.length<10){
                  out.lowContrast.push({sel:selOf(el),text:txt.slice(0,20),ratio:ratio,need:large?3.0:4.5,fg:s.color.slice(0,20)});
                }
              }
            }
          }
          return JSON.stringify(out);
        })()
        """.trimIndent()
    }

    /** 内容抽取模式。 */
    enum class ExtractMode(val value: String) {
        ARTICLE("article"), TABLES("tables"), LINKS("links"), META("meta")
    }
}

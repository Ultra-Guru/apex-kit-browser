package com.apex.browser.core

/**
 * v2.1.0 BrowserTask / 事件驱动等待的页面侧脚本（纯 JVM 字面量工厂，同 [BrowserScript] 口径：
 * 外部字符串一律 [JsLiteral.string]，无 Promise——等待归 Kotlin 侧）。
 *
 * 复用纪律：selector 定位动作（click/fill）**不另写执行通道**——本文件只负责
 * 「按 selector 找元素并打上 data-apex-hash」，执行复用引擎既有 ref 硬化路径
 * （物理触摸注入 / 探针早退 / 动作后验证 / 模糊自愈）。ref 语义与 snapshot 一致，
 * 任务产出的 ref 可直接用于 ref 类工具。
 */
object TaskScripts {

    /**
     * 事件采集器（每文档幂等安装一次，idempotent 守卫同 `__apexNetHooked` 纪律）：
     * 单个 MutationObserver 批量计数 DOM 变更（`__apexEventHub.seq`/`.last`），
     * 供等待器做「变更驱动 + 合并探针」——Kotlin 侧每 tick 一次求值同时读
     * seq/条件命中/网络在途，替代 N 次独立轮询（评审 P0-2 / JS 合并纪律）。
     */
    val EVENT_HUB_ENSURE_JS: String =
        """
        (function(){
          if (!window.__apexEventHub) {
            window.__apexEventHub = { seq: 0, last: 0 };
            try {
              var target = document.documentElement || document;
              var obs = new MutationObserver(function(muts){
                window.__apexEventHub.seq = (window.__apexEventHub.seq || 0) + 1;
                window.__apexEventHub.last = Date.now();
              });
              obs.observe(target, {childList:true, subtree:true, attributes:true, characterData:true});
            } catch(e) { /* 环境不支持时退化为纯轮询 */ }
          }
          return window.__apexEventHub.seq || 0;
        })()
        """.trimIndent()

    /**
     * 条件命中表达式（全部八模式收进一段 JS 布尔；selector_count 的谓词在 JS 侧解析）。
     * 供 [waitProbeJs] 内联 —— 合并后**一次求值**同时完成「变更号 + 条件 + 网络」。
     */
    fun conditionHitJs(mode: String, value: String, quietWindowMs: Long): String {
        val v = JsLiteral.string(value)
        return when (mode) {
            "selector" -> "!!document.querySelector($v)"
            "element_gone" -> "!document.querySelector($v)"
            "text" -> "(document.body && ((document.body.innerText||document.body.textContent||'').indexOf($v) >= 0))"
            "text_gone" -> "!(document.body && ((document.body.innerText||document.body.textContent||'').indexOf($v) >= 0))"
            "url" -> "location.href.indexOf($v) >= 0"
            "network_idle" -> "((window.__apexNetPending === undefined ? 0 : window.__apexNetPending) === 0)"
            "dom_stable" -> "((window.__apexEventHub ? (Date.now() - window.__apexEventHub.last) : 1e9) >= ${quietWindowMs.coerceIn(100, 5000)})"
            "selector_count" -> {
                val p = parseCountPredicateJs(value)
                if (p == null) "false"
                else "(function(){ try { var n=document.querySelectorAll(${JsLiteral.string(p.first)}).length; return n ${p.second} ${p.third}; } catch(e){ return false; } })()"
            }
            else -> "false"
        }
    }

    /** selector_count 谓词的 JS 侧解析（与引擎 Kotlin 侧 parseCountPredicate 同口径）：返回 (sel, op, n)。 */
    private fun parseCountPredicateJs(value: String): Triple<String, String, Int>? {
        val t = value.trim()
        Regex("^(.*?)(>=|<=|==|>|<)\\s*(\\d+)$").find(t)?.let {
            val sel = it.groupValues[1].trim()
            if (sel.isNotEmpty()) return Triple(sel, it.groupValues[2], it.groupValues[3].toInt())
        }
        Regex("^(.+?)\\s+(\\d+)$").find(t)?.let {
            return Triple(it.groupValues[1].trim(), ">=", it.groupValues[2].toInt())
        }
        return null
    }

    /**
     * 合并探针：单次 evaluateJavascript 返回 `{seq, hit, net}` ——
     * seq=DOM 变更号（ MutationObserver 计数）、hit=条件命中、net=在途请求数
     * （监控未注入时 -1，上层据此报 NOT_DETECTED，评审 P1-3）。
     *
     * @param hitJs [conditionHitJs] 产出的布尔表达式（已内联转义）
     */
    fun waitProbeJs(hitJs: String): String =
        """
        (function(){
          if (!window.__apexEventHub) {
            window.__apexEventHub = { seq: 0, last: 0 };
            try {
              var obs = new MutationObserver(function(){
                window.__apexEventHub.seq = (window.__apexEventHub.seq || 0) + 1;
                window.__apexEventHub.last = Date.now();
              });
              obs.observe(document.documentElement || document, {childList:true, subtree:true, attributes:true, characterData:true});
            } catch(e) {}
          }
          var hit = false;
          try { hit = !!($hitJs); } catch(e) { hit = false; }
          var net = (window.__apexNetPending === undefined) ? -1 : window.__apexNetPending;
          return JSON.stringify({seq: window.__apexEventHub.seq || 0, hit: hit, net: net});
        })()
        """.trimIndent()

    /**
     * 按 selector 找元素并打上 `data-apex-hash`（与 [BrowserScript.snapshotJs] 同一语义哈希
     * 算法，已有 hash 的元素沿用）——返回 JSON 数组 `[{ref, tag, text, x, y, w, h}]`（≤[limit]）。
     * 引擎据命中数分支：0=未命中 / 1=直接以 ref 走既有执行路径 / 多=检查点或 first。
     */
    fun markBySelectorJs(selector: String, limit: Int): String =
        """
        (function(){
          var out = [];
          var els;
          try { els = document.querySelectorAll(${JsLiteral.string(selector)}); } catch(e) { return JSON.stringify({error:'invalid_selector'}); }
          function hash(str){ var h = 0; for (var i=0;i<str.length;i++){ var c = str.charCodeAt(i); h = ((h<<5)-h)+c; h|=0; } return 'r_' + (Math.abs(h)>>>0).toString(36); }
          for (var i=0; i<els.length && out.length < $limit; i++) {
            var el = els[i];
            var style = window.getComputedStyle(el);
            var r = el.getBoundingClientRect();
            var visible = style.display !== 'none' && style.visibility !== 'hidden' && parseFloat(style.opacity) > 0.05 && r.width > 0 && r.height > 0;
            if (!visible) continue;
            var ref = el.getAttribute('data-apex-hash');
            if (!ref) {
              var text = (el.innerText || el.value || el.placeholder || '').replace(/\s+/g,' ').trim().slice(0,120);
              var role = el.getAttribute('role') || el.tagName.toLowerCase();
              ref = hash(role + '|' + text + '|' + el.tagName + '|' + Math.round(r.top + window.scrollY));
              var seen = (window.__apexMarkSeen = window.__apexMarkSeen || {})[ref] || 0;
              window.__apexMarkSeen[ref] = seen + 1;
              if (seen > 0) ref = ref + '#' + (seen + 1);
              el.setAttribute('data-apex-hash', ref);
            }
            out.push({ref: ref, tag: el.tagName.toLowerCase(), text: ((el.innerText||el.value||'')+'').replace(/\s+/g,' ').trim().slice(0,120), x: Math.round(r.left), y: Math.round(r.top + window.scrollY), w: Math.round(r.width), h: Math.round(r.height)});
          }
          return JSON.stringify({matches: els.length, elements: out});
        })()
        """.trimIndent()

    /** EXTRACT text 模式：selector 子树纯文本（取不到时返回空串）。 */
    fun extractSubtreeTextJs(selector: String): String =
        """
        (function(){
          try {
            var el = document.querySelector(${JsLiteral.string(selector)});
            if (!el) return '';
            return (el.innerText || el.textContent || '').trim();
          } catch(e) { return ''; }
        })()
        """.trimIndent()
}

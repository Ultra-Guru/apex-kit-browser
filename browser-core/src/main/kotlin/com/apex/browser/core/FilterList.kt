package com.apex.browser.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/*
 * FilterList —— EasyList 子集的解析与匹配（v2.0.0）。
 *
 * 定位：把 NetworkInterceptor 的「手写域名黑名单」升级为**社区过滤列表
 * 生态**（EasyList / EasyPrivacy / uBlock 域列表的公共子集）。宿主把列表
 * 文本（自行下载或用内置 [DefaultFilterLists]）交给
 * [com.apex.browser.engine.NetworkInterceptor.subscribeFilters]，即获得
 * 数千条广告/跟踪域屏蔽 —— 真实浏览器（Chrome/uBlock/Brave）的基线体验。
 *
 * ## 支持的规则子集（明确声明，其余如实跳过并计数）
 *
 * | 形态 | 例 | 语义 |
 * |---|---|---|
 * | 域锚点 | `\|\|ads.example.com^` | host 等于该域或以其为后缀（`a.b.c` 匹配 `b.c`） |
 * | 域锚点+路径 | `\|\|ads.example.com^/banner/` | 域匹配 **且** URL 含路径子串 |
 * | 前缀锚点 | `\|https://tracker.` | URL 以该串开头 |
 * | 裸子串 | `-ad-300x250.` | URL 含该子串（≥3 字符才收录，防灾难性过屏蔽） |
 * | 例外 | `@@\|\|good.com^` | 白名单优先：命中例外**永不**屏蔽 |
 *
 * 跳过并计数（skipped）：`!` 注释 / `[Adblock]` 头 / `##选择器` 元素隐藏
 * （ cosmetics，注入式隐藏是另一代工程）/ `/正则/`（成本与风险都不合算）/
 * `$script` 等类型选项（剥离选项按全域屏蔽——比原语义更严，可接受）。
 *
 * ## 匹配性能
 *
 * 域规则用「host 后缀等值」而非正则（O(域数) 次字符串比较，3000 条在
 * 毫秒内）；子串线性 contains（800 条上限）。shouldInterceptRequest 在
 * WebView 的 IO 线程回调，本匹配全程无锁只读（[FilterList] 不可变）。
 *
 * 纯 JVM 纪律：解析与匹配都在 `:browser-core`，单测穷举。
 */

/** 订阅编译统计（如实上报「收录了什么、跳过了什么、有没有触顶」）。 */
data class FilterListStats(
    val domainRules: Int = 0,
    val substringRules: Int = 0,
    val exceptionRules: Int = 0,
    /** 不可解析 / 不支持的行数（注释不计数，仅规则形态跳过计数）。 */
    val skipped: Int = 0,
    /** 任一类触顶被截断（消费方应提示「列表过大」）。 */
    val truncated: Boolean = false,
) {
    val totalRules: Int get() = domainRules + substringRules + exceptionRules
}

/** 域锚点规则：host == domain 或 host.endsWith(".domain")，可选路径子串。 */
internal class DomainAnchor(
    val domain: String,
    /** `^/banner/` 形态的路径子串（已剥 `^` 与 `*` 通配；空 = 无路径约束）。 */
    val pathContains: String?,
) {
    fun matches(host: String, url: String): Boolean =
        (host == domain || host.endsWith(".$domain")) &&
            (pathContains == null || url.contains(pathContains))
}

/** 前缀锚点或裸子串规则（统一为小写子串 contains）。 */
internal class SubstringAnchor(val needle: String) {
    fun matches(url: String): Boolean = url.contains(needle)
}

/**
 * 编译后的过滤列表（不可变，线程安全只读）。
 *
 * 匹配顺序：**例外优先**（命中例外直接放行）→ 域锚点 → 子串。
 */
class FilterList internal constructor(
    internal val domains: List<DomainAnchor>,
    internal val substrings: List<SubstringAnchor>,
    internal val exceptionDomains: List<DomainAnchor>,
    internal val exceptionSubstrings: List<SubstringAnchor>,
    val stats: FilterListStats,
) {
    /** URL 是否应被屏蔽（小写比较；例外命中返回 false）。 */
    fun matches(url: String): Boolean {
        val u = url.lowercase()
        val host = hostOf(u) ?: return substringHit(u)
        if (exceptionDomains.any { it.matches(host, u) }) return false
        if (exceptionSubstrings.any { it.matches(u) }) return false
        if (domains.any { it.matches(host, u) }) return true
        return substringHit(u)
    }

    private fun substringHit(u: String): Boolean {
        if (exceptionSubstrings.any { it.matches(u) }) return false
        return substrings.any { it.matches(u) }
    }

    companion object {
        const val MAX_DOMAIN_RULES: Int = 3_000
        const val MAX_SUBSTRING_RULES: Int = 600
        const val MAX_EXCEPTION_RULES: Int = 400
        private const val MAX_INPUT_LINES: Int = 20_000
        private const val MIN_SUBSTRING_LEN: Int = 3

        /** 解析 EasyList 子集文本；坏行跳过计数，绝不抛（野生列表文本不可信）。 */
        fun parse(text: String): FilterList {
            val domains = ArrayList<DomainAnchor>()
            val subs = ArrayList<SubstringAnchor>()
            val exDomains = ArrayList<DomainAnchor>()
            val exSubs = ArrayList<SubstringAnchor>()
            var skipped = 0
            var truncated = false
            val lines = text.lines()
            val lineCap = minOf(lines.size, MAX_INPUT_LINES)
            for (i in 0 until lineCap) {
                val raw = lines[i].trim()
                if (raw.isEmpty() || raw.startsWith("!") || raw.startsWith("[")) continue
                val isException = raw.startsWith("@@")
                val body = if (isException) raw.removePrefix("@@") else raw
                // 元素隐藏（##selector / #?# / #$#）与纯正则（/.../）：如实跳过
                if (body.contains("##") || body.contains("#?#") || body.contains("#$#")) {
                    skipped++
                    continue
                }
                if (body.length > 1 && body.startsWith("/") && body.endsWith("/")) {
                    skipped++
                    continue
                }
                val rule = compile(body)
                if (rule == null) {
                    skipped++
                    continue
                }
                val (domain, sub) = rule
                if (isException) {
                    if (exDomains.size + exSubs.size >= MAX_EXCEPTION_RULES) {
                        truncated = true
                    } else when {
                        domain != null -> exDomains.add(domain)
                        sub != null -> exSubs.add(sub)
                    }
                } else {
                    if (domain != null) {
                        if (domains.size >= MAX_DOMAIN_RULES) truncated = true else domains.add(domain)
                    } else if (sub != null) {
                        if (subs.size >= MAX_SUBSTRING_RULES) truncated = true else subs.add(sub)
                    }
                }
            }
            return FilterList(
                domains = domains,
                substrings = subs,
                exceptionDomains = exDomains,
                exceptionSubstrings = exSubs,
                stats = FilterListStats(
                    domainRules = domains.size,
                    substringRules = subs.size,
                    exceptionRules = exDomains.size + exSubs.size,
                    skipped = skipped,
                    truncated = truncated,
                ),
            )
        }

        /** 合成多个订阅（匹配 = 任一命中；例外 = 任一的例外命中）。 */
        fun compose(lists: List<FilterList>): FilterList {
            if (lists.size == 1) return lists[0]
            return FilterList(
                domains = lists.flatMap { it.domains },
                substrings = lists.flatMap { it.substrings },
                exceptionDomains = lists.flatMap { it.exceptionDomains },
                exceptionSubstrings = lists.flatMap { it.exceptionSubstrings },
                stats = FilterListStats(
                    domainRules = lists.sumOf { it.stats.domainRules },
                    substringRules = lists.sumOf { it.stats.substringRules },
                    exceptionRules = lists.sumOf { it.stats.exceptionRules },
                    skipped = lists.sumOf { it.stats.skipped },
                    truncated = lists.any { it.stats.truncated },
                ),
            )
        }

        /** 编译单行 → (域锚点, 子串锚点) 二选一；两者皆 null = 不支持形态。 */
        private fun compile(body: String): Pair<DomainAnchor?, SubstringAnchor?>? {
            // 剥离 $ 类型选项（$script/$image/$third-party…）：按全域屏蔽，比原语义更严
            val wo = if (body.contains('$')) body.substringBefore('$') else body
            if (wo.isBlank()) return null
            if (wo.startsWith("||")) {
                val rest = wo.removePrefix("||")
                // 域部分到 ^ / / / ? / # 为止
                val stopIdx = rest.indexOfFirst { it == '^' || it == '/' || it == '?' || it == '#' }
                val domain = (if (stopIdx >= 0) rest.substring(0, stopIdx) else rest).lowercase()
                if (!isValidDomain(domain)) return null
                var path: String? = null
                if (stopIdx >= 0 && rest[stopIdx] != '?') {
                    val tail = rest.substring(stopIdx).trimStart('^', '/').replace("*", "")
                    if (tail.isNotBlank()) path = tail.lowercase()
                }
                return DomainAnchor(domain, path) to null
            }
            if (wo.startsWith("|")) {
                val needle = wo.removePrefix("|").trimEnd('^').replace("*", "").lowercase()
                if (needle.length >= MIN_SUBSTRING_LEN) return null to SubstringAnchor(needle)
                return null
            }
            val needle = wo.trim('^').replace("*", "").lowercase()
            if (needle.length >= MIN_SUBSTRING_LEN) return null to SubstringAnchor(needle)
            return null
        }

        private fun isValidDomain(d: String): Boolean =
            d.contains('.') && d.matches(Regex("[a-z0-9.-]+")) && !d.startsWith(".") && !d.endsWith(".")

        /** 手工 host 提取（java.net.URI 对未转义中文/空格抛错——真实 URL 的常态）。 */
        internal fun hostOf(lowerUrl: String): String? {
            val schemeEnd = lowerUrl.indexOf("://")
            if (schemeEnd < 0) return null
            var rest = lowerUrl.substring(schemeEnd + 3)
            // 去掉 path / query / fragment
            var end = rest.length
            for (c in charArrayOf('/', '?', '#')) {
                val i = rest.indexOf(c)
                if (i in 0 until end) end = i
            }
            rest = rest.substring(0, end)
            // 去掉 userinfo（user:pass@）
            val at = rest.lastIndexOf('@')
            if (at >= 0) rest = rest.substring(at + 1)
            // 去掉端口（host:port —— 域匹配只看 host；IPv6 字面量 [::1]:8080 一并剥方括号）
            val colon = rest.lastIndexOf(':')
            if (colon >= 0) rest = rest.substring(0, colon).removeSurrounding("[", "]")
            return rest.ifBlank { null }
        }
    }
}

/*
 * DefaultFilterLists —— 内置起步订阅（v2.0.0）。
 *
 * 选择口径：从 EasyList/EasyPrivacy 公共子集里挑**只属于广告/测量/跟踪**
 * 的域（保守：宁可漏放，不可误杀——主框架导航被误杀 = 站点打不开）。
 * 数量 130 条上下：零网络依赖、毫秒级匹配；重度用户由宿主下载完整
 * EasyList 文本后 subscribeFilters 导入（数千条也在预算内）。
 */
object DefaultFilterLists {

    /** 起步列表（EasyList 格式文本，可直接 [FilterList.parse]）。 */
    const val TRACKER_CORE: String = """
! apex browser starter filter list — conservative tracker/ad domains
! (subset of EasyList/EasyPrivacy public entries; intentionally minimal-FP)
||doubleclick.net^
||googlesyndication.com^
||googleadservices.com^
||google-analytics.com^
||googletagmanager.com^
||googletagservices.com^
||adservice.google.com^
||pagead2.googlesyndication.com^
||tpc.googlesyndication.com^
||adnxs.com^
||adnxs-simple.com^
||criteo.com^
||criteo.net^
||taboola.com^
||outbrain.com^
||outbrainimg.com^
||revcontent.com^
||mgid.com^
||propellerads.com^
||popads.net^
||popcash.net^
||adcash.com^
||exoclick.com^
||juicyads.com^
||adskeeper.com^
||adskeeper.co.uk^
||adroll.com^
||bidswitch.net^
||casalemedia.com^
||chartbeat.com^
||chartbeat.net^
||comscore.com^
||scorecardresearch.com^
||quantserve.com^
||quantcount.com^
||mixpanel.com^
||segment.io^
||segment.com^
||amplitude.com^
||hotjar.com^
||hotjar.io^
||clarity.ms^
||mc.yandex.ru^
||an.yandex.ru^
||top-fwz1.mail.ru^
||ad.mail.ru^
||r.mradx.net^
||apx.appboycdn.com^
||appboycdn.com^
||braze.com^
||adjust.com^
||appsflyer.com^
||kochava.com^
||branch.io^
||onesignal.com^
||ads-twitter.com^
||static.ads-twitter.com^
||analytics.twitter.com^
||connect.facebook.net^
||graph.facebook.com^
||pixel.facebook.com^
||an.facebook.com^
||ads.linkedin.com^
||px.ads.linkedin.com^
||snap.licdn.com^
||bat.bing.com^
||adsymptotic.com^
||adform.net^
||adition.com^
||adscale.de^
||adtech.de^
||advertising.com^
||aol.com^$script
||advertising.yahoo.com^
||gemini.yahoo.com^
||ads.yahoo.com^
||yieldmanager.com^
||rubiconproject.com^
||openx.net^
||openx.com^
||pubmatic.com^
||smartadserver.com^
||33across.com^
||sharethrough.com^
||sonobi.com^
||indexww.com^
||indexexchange.com^
||improvedigital.com^
||teads.tv^
||teads.fr^
||media.net^
||medianet.com^
||invitemedia.com^
||flurry.com^
||crashlytics.com^$script
||newrelic.com^$script
||nr-data.net^
||bugsnag.com^$script
||raygun.io^
||logrocket.com^
||logrocket.io^
||fullstory.com^
||mouseflow.com^
||luckyorange.com^
||inspectlet.com^
||crazyegg.com^
||clicktale.net^
||sessioncam.com^
||ipify.org^
||ipinfo.io^$script
||deviceatlas.com^
||52north.com^
||akstat.io^
||demdex.net^
||everesttech.net^
||krxd.net^
||adsrvr.org^
||agkn.com^
||adhigh.net^
||adfox.ru^
||adriver.ru^
||betweendigital.com^
||buzzoola.com^
||onetag-sys.com^
||richaudience.com^
||vidoomy.com^
||waframedia.com^
||youborafds01.com^
||adsafeprotected.com^
||moatads.com^
||moatpixel.com^
||doubleverify.com^
||sizmek.com^
||advertisingbanners.com^
||bannerflow.com^
||bannerbank.ru^
||adriver.ru^
"""
}

/*
 * TabSession —— 会话（打开的标签页）持久化编解码（v2.0.0）。
 *
 * Chrome 系浏览器的基线体验：杀进程重开浏览器，标签页还在。
 * 编解码纯 JVM 可单测；引擎侧的持久化时机与恢复见
 * engine/SessionRestore.kt。
 */

/** 单个会话标签（恢复到该 URL；title 供宿主「恢复标签」UI 展示）。 */
data class TabSession(
    val url: String,
    val title: String = "",
    /** 持久化时刻（新鲜度判断）。 */
    val savedAtMs: Long = 0L,
)

/** 会话编解码（防御性：坏 JSON / 超量 / 非 http(s) URL 逐一过滤，绝不抛）。 */
object TabSessionCodec {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 编码（URL 非法条目直接跳过——存进去也恢复不出来）。 */
    fun encode(sessions: List<TabSession>): String {
        val sb = StringBuilder("[")
        var first = true
        for (s in sessions) {
            if (!s.url.startsWith("http://") && !s.url.startsWith("https://")) continue
            if (!first) sb.append(',')
            first = false
            sb.append("{\"url\":").append(jsonString(s.url))
                .append(",\"title\":").append(jsonString(s.title.take(120)))
                .append(",\"at\":").append(s.savedAtMs).append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    /** 解码（null / 空 / 坏 JSON 返回空列表；超量截断到 [MAX_TABS]）。 */
    fun decode(raw: String?): List<TabSession> {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val array = runCatching {
            json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonArray
        }.getOrNull() ?: return emptyList()
        val out = mutableListOf<TabSession>()
        for (e in array) {
            val o = runCatching { e as? kotlinx.serialization.json.JsonObject }.getOrNull() ?: continue
            val url = runCatching { o["url"]?.jsonPrimitive?.content ?: "" }.getOrDefault("")
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue
            out.add(
                TabSession(
                    url = url,
                    title = runCatching { o["title"]?.jsonPrimitive?.content ?: "" }.getOrDefault(""),
                    savedAtMs = runCatching { o["at"]?.jsonPrimitive?.long ?: 0L }.getOrDefault(0L),
                ),
            )
            if (out.size >= MAX_TABS) break
        }
        return out
    }

    /** 单条会话上限（恢复时逐个建页，超量拖慢启动）。 */
    const val MAX_TABS: Int = 25

    private fun jsonString(s: String): String {
        // 手工拼装而非 String.format：免受默认 Locale 影响（JsLiteral 同款纪律）
        val hex = "0123456789abcdef"
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') {
                    sb.append("\\u")
                    for (shift in intArrayOf(12, 8, 4, 0)) sb.append(hex[(ch.code shr shift) and 0xF])
                } else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}

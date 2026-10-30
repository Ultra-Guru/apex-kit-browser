package com.apex.browser.engine

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import java.util.concurrent.ConcurrentHashMap

/*
 * EngineEmulation —— UA 仿冒与设备形态切换（v1.9.0；扩展了 Playwright
 * `user_agent` 仿冒维度的 WebView 对标面）。
 *
 * 能力：覆写 / 恢复 WebView 的 User-Agent（移动站点只发精简版页面、
 * 验证响应式布局、绕过「本浏览器不受支持」门禁时的刚需）。引擎默认
 * 已去掉 "; wv" 痕迹（反检测 #13），本类在其上做**显式覆写**。
 *
 * 实现形态与 [NetworkInterceptor] 同款：不改引擎一行，经
 * [BrowserEngine.ActiveTabListener] 在标签切换 / 崩溃重建产生新 WebView
 * 时自动重放覆写（UA 是 WebView 实例级设置，新实例不继承）。
 *
 * ## 已知取舍
 *
 * - 只覆写当前活动标签与后续激活的标签：引擎不暴露后台标签的 WebView
 *   句柄（[BrowserEngine.listTabs] 只有 id/url），后台标签在激活时由
 *   监听器补放。由于覆写通常发生在导航之前、导航发生在活动标签上，
 *   实际覆盖面即全部会话导航。
 * - 视口尺寸不仿冒：WebView 尺寸由承载它的 View 容器决定（悬浮窗/
 *   in-app 宿主），库层无法在不接管布局的前提下改（Playwright 的
 *   viewport 仿冒在 WebView 无对等面，如实声明）。
 */
class EngineEmulation private constructor(private val engine: BrowserEngine) {

    /** 当前覆写的 UA；null = 引擎默认（首次覆写时自动快照留底）。 */
    @Volatile
    private var overrideUa: String? = null

    /** 引擎默认 UA 快照（恢复用；引擎默认 = 系统默认去 "; wv"/"Version/4.0" 痕迹）。 */
    @Volatile
    private var defaultUa: String? = null

    private val main = Handler(Looper.getMainLooper())

    private val tabListener = object : BrowserEngine.ActiveTabListener {
        override fun onActiveTabChanged(webView: WebView?) {
            runOnMain { applyTo(webView) }
        }
    }

    init {
        engine.addActiveTabListener(tabListener)
    }

    /**
     * 覆写 UA（null = 恢复引擎默认）。立即作用于当前活动 WebView，
     * 后续激活的标签自动继承。返回最终生效的 UA（失败返回 null）。
     */
    fun setUserAgent(ua: String?): String? {
        runOnMain {
            if (ua != null && defaultUa == null) {
                defaultUa = engine.activeWebView()?.settings?.userAgentString
            }
            overrideUa = ua
            applyTo(engine.activeWebView())
        }
        return ua ?: defaultUa
    }

    /** 当前覆写 UA（null = 引擎默认生效中）。 */
    fun userAgent(): String? = overrideUa

    /** 是否处于桌面模式（[applyDesktopMode] 的查询面）。 */
    fun isDesktopMode(): Boolean = overrideUa == DESKTOP_UA

    /** 一键桌面模式（Windows Chrome UA）。返回最终生效的 UA。 */
    fun applyDesktopMode(): String? = setUserAgent(DESKTOP_UA)

    /** 恢复移动默认（引擎的痕迹清除版系统 UA）。 */
    fun resetToDefault(): String? = setUserAgent(null)

    /** 停止订阅标签变化（覆写保持但新标签不再自动继承）。 */
    fun dispose() {
        engine.removeActiveTabListener(tabListener)
    }

    private fun applyTo(webView: WebView?) {
        val wv = webView ?: return
        val ua = overrideUa ?: return
        runCatching { wv.settings.userAgentString = ua }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    companion object {
        /** 进程级注册（与 BrowserEngineFactory 同款单例纪律）。 */
        private val instances = ConcurrentHashMap<BrowserEngine, EngineEmulation>()

        /** 取引擎对应的仿冒器单例（首次调用即订阅标签变化）。 */
        fun forEngine(engine: BrowserEngine): EngineEmulation =
            instances.getOrPut(engine) { EngineEmulation(engine) }

        /**
         * 桌面 UA 预设：Windows 10 x64 Chrome（Safari 后缀保留 —— 去 Safari
         * 反而触发「非主流浏览器」门禁，主流站点的 UA 判定都看 Chrome 主版本）。
         */
        const val DESKTOP_UA: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}

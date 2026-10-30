package com.apex.browser.engine

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import com.apex.browser.core.TabSession
import com.apex.browser.core.TabSessionCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * SessionRestore —— 会话（打开的标签页）持久化与恢复（v2.0.0）。
 *
 * Chrome 系浏览器的基线体验：杀进程重开浏览器，**标签页还在**。
 * v1.x 的引擎标签全在内存（tabs: MutableMap），进程一死全丢 ——
 * 「上次看到一半的页面」只能靠历史记录人肉翻。
 *
 * 语义（如实声明，含取舍）：
 * - 持久化**标签结构与 URL**，不是页面状态（滚动位置/表单草稿不恢复 ——
 *   WebView 无序列化渲染树的对等面）；
 * - 自动持久化挂在 [BrowserEngine.ActiveTabListener]（标签新建/切换/关闭/
 *   崩溃重建都触发 —— 覆盖全部标签拓扑变化）+ 状态机回调兜底；**页面内**
 *   导航不触发标签变化，宿主应在生命周期 onStop 调 [persistNow]（显式
 *   兜底才是可靠路径，自动钩子是尽力而为）；
 * - [restoreTabs] 逐个建页（新标签 + loadUrl，加载异步进行），返回恢复数；
 *   引擎已有的标签**不会被清掉**（追加语义）—— 宿主决定「冷启动恢复」
 *   还是「追加恢复」；
 * - 单页恢复上限 [TabSessionCodec.MAX_TABS]（25）。
 *
 * 接法（宿主）：
 * ```
 * val restore = SessionRestore(context, engine)   // attach 即自动持久化
 * engine.releaseBrowser() 之前不必显式调用（自动钩子已覆盖标签变化）
 * // 冷启动：
 * val n = restore.restoreTabs()   // 恢复上次会话的标签
 * ```
 */
class SessionRestore(
    context: Context,
    private val engine: BrowserEngine,
) : BrowserEngine.ActiveTabListener, BrowserEngine.BrowserUiCallback {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 状态机回调可能来自非主线程（setState 无线程契约），而 tabsSnapshot()
    // 读引擎标签表须在主线程（与标签变异同线程序）—— 持久化一律 post 主线程。
    private val main = Handler(Looper.getMainLooper())

    init {
        // 本类同时实现 ActiveTabListener + BrowserUiCallback：标签拓扑变化
        // 与状态机迁移两类钩子都收敛为一次轻量持久化（见文件头 KDoc 的取舍）。
        engine.addActiveTabListener(this)
        engine.addUiCallback(this)
    }

    /** 停止自动持久化（宿主释放时调用；[persistNow] 仍可手动用）。 */
    fun dispose() {
        engine.removeActiveTabListener(this)
        engine.removeUiCallback(this)
    }

    /**
     * 立即持久化当前全部标签（主线程安全：SharedPreferences commit 走
     * apply 异步落盘）。返回持久化的标签数。
     */
    fun persistNow(): Int {
        val tabs = engine.tabsSnapshot().map { TabSession(it.second, it.third, System.currentTimeMillis()) }
        val clean = tabs.filter { it.url.startsWith("http") }.take(TabSessionCodec.MAX_TABS)
        prefs.edit().putString(KEY_TABS, TabSessionCodec.encode(clean)).apply()
        return clean.size
    }

    /** 上次会话的标签（只读，不建页 —— 宿主「恢复？」确认 UI 的数据源）。 */
    fun lastSessionTabs(): List<TabSession> =
        TabSessionCodec.decode(prefs.getString(KEY_TABS, null))

    /**
     * 恢复上次会话的标签（逐个 newTab + loadUrl，异步加载）。返回恢复数；
     * 0 = 无可恢复（首次启动 / 上次会话无 http 标签）。
     */
    suspend fun restoreTabs(): Int = withContext(Dispatchers.Main) {
        val sessions = lastSessionTabs()
        for (s in sessions) {
            engine.newTab(s.url)
        }
        sessions.size
    }

    /** 清空持久化会话（「关闭全部并不再恢复」语义）。 */
    fun clear() {
        prefs.edit().remove(KEY_TABS).apply()
    }

    /* ActiveTabListener / BrowserUiCallback：两者都只需触发持久化（一律主线程）。 */

    override fun onActiveTabChanged(webView: WebView?) {
        if (Looper.myLooper() == Looper.getMainLooper()) persistNow() else main.post { persistNow() }
    }

    override fun onStateChanged(
        state: BrowserEngine.BrowserSessionState,
        url: String?,
        title: String?,
    ) {
        // 状态机迁移（会话收尾 / 恢复）兜底持久化：标签拓扑不变但 URL 可能
        // 刚更新过的场景。频率低，成本一次轻量 JSON 写。
        if (Looper.myLooper() == Looper.getMainLooper()) persistNow() else main.post { persistNow() }
    }

    companion object {
        private const val PREFS_NAME = "apex_browser_session"
        private const val KEY_TABS = "tabs_v1"
    }
}

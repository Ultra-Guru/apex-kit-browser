package com.apex.browser.engine

import android.net.Uri
import android.os.Message
import android.webkit.WebView
import android.webkit.WebViewClient

/*
 * PopupBridge —— window.open / target=_blank → 新标签页的桥接（v2.0.0，
 * internal；自 BrowserEngine.kt 抽出——GATE 1 文件预算纪律）。
 *
 * 经典桥接实现：onCreateWindow 里创建**临时 WebView**（永不挂树、永不真
 * 加载），经 WebViewTransport 接住弹窗的首个导航 URL，转交引擎 newTab
 * 开新标签。scheme 白名单与主 client 同口径（file:// / javascript:// 等
 * 一律不开窗）。
 *
 * 非用户手势的 window.open（真弹窗广告的典型形态）直接拦截并 trace ——
 * 真实浏览器的弹窗拦截语义。
 */
internal fun handlePopup(
    view: WebView?,
    isDialog: Boolean,
    isUserGesture: Boolean,
    resultMsg: Message?,
    openInNewTab: (String) -> Unit,
    trace: (tag: String, params: String, message: String) -> Unit,
): Boolean {
    if (!isUserGesture) {
        trace("system.popup_blocked", "dialog=$isDialog", "已拦截非用户手势弹窗（window.open）")
        return true
    }
    val ctx = view?.context ?: return false
    val popup = WebView(ctx)
    popup.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(tv: WebView?, url: String?): Boolean {
            val u = url ?: return true
            val allowed = runCatching { Uri.parse(u) }
                .getOrNull()?.scheme?.let { it == "http" || it == "https" } ?: false
            if (allowed) openInNewTab(u)
            return true // 临时 WebView 永不真加载，只借它接住弹窗 URL
        }
    }
    (resultMsg?.obj as? WebView.WebViewTransport)?.webView = popup
    resultMsg?.sendToTarget()
    trace("system.popup_new_tab", "dialog=$isDialog", "弹窗已转新标签页打开")
    return true
}

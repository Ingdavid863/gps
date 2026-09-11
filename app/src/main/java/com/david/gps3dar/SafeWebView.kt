package com.david.gps3dar

import android.content.Context
import android.util.AttributeSet
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

class SafeWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.webViewStyle
) : WebView(context, attrs, defStyleAttr) {

    private val assetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()

    private val secureAssetClient = object : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest
        ): WebResourceResponse? {
            return assetLoader.shouldInterceptRequest(request.url)
                ?: super.shouldInterceptRequest(view, request)
        }
    }

    override fun loadUrl(url: String) {
        super.setWebViewClient(secureAssetClient)
        super.loadUrl(rewriteLocalAssetUrl(url))
    }

    override fun loadUrl(url: String, additionalHttpHeaders: MutableMap<String, String>) {
        super.setWebViewClient(secureAssetClient)
        super.loadUrl(rewriteLocalAssetUrl(url), additionalHttpHeaders)
    }

    private fun rewriteLocalAssetUrl(url: String): String {
        val prefix = "file:///android_asset/"
        if (!url.startsWith(prefix)) return url

        val localPath = url.removePrefix(prefix)
        val separator = if (localPath.contains('?')) "&" else "?"
        return "https://appassets.androidplatform.net/assets/$localPath${separator}gps3d_build=052"
    }
}

package com.bolimot.mindtheclub.functions

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.bolimot.mindtheclub.dataModels.WebsiteInfo
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

private const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"
private const val REFERRER_GOOGLE = "https://www.google.com"

suspend fun fetchWebsiteInfo(url: String, context: Context): WebsiteInfo {
    if(isYoutubeUrl(url)) {
        debugLine3("fetchWebsiteInfo", "isYoutubeUrl")
        return fetchYoutubeInfo(url)
    }

    if (url.contains("tiktok.com", ignoreCase = true)) {
        val tikTokInfo = fetchTikTokInfo(url)
        if (tikTokInfo != null) return tikTokInfo
    }

    val jsoupResult = withContext(Dispatchers.IO) {
        try {
            val connection = Jsoup.connect(url)
                .userAgent(DESKTOP_USER_AGENT) // Use Desktop Agent
                .referrer(REFERRER_GOOGLE)
                .timeout(10000)
                .followRedirects(true)

            val info = getWebsiteInfo(connection.get())

            // CRITICAL FIX: If Jsoup scraped the "Login" page, return null.
            // This forces the app to use the WebView fallback below.
            if (info != null) {
                if (info.title.contains("Login", ignoreCase = true) ||
                    (info.title.equals("Instagram", ignoreCase = true) && info.description.isEmpty())) {
                    return@withContext null
                }
            }
            info
        } catch (e: Exception) {
            debugLine3("fetchWebsiteInfo", "Exception: ${e.message}")
            null
        }
    }

    if (isMeaningful(jsoupResult)) {
        return jsoupResult!!
    }

    return fetchWebsiteInfoWithWebView(url, context)
}

suspend fun fetchTikTokInfo(url: String): WebsiteInfo? {
    return withContext(Dispatchers.IO) {
        try {
            val encodedUrl = URLEncoder.encode(url, "UTF-8")
            val apiUrl = "https://www.tiktok.com/oembed?url=$encodedUrl"

            val jsonResponse = Jsoup.connect(apiUrl)
                .ignoreContentType(true)
                .timeout(10000)
                .execute()
                .body()

            val json = JSONObject(jsonResponse)
            val title = json.optString("title")
            val author = json.optString("author_name")
            val thumbnail = json.optString("thumbnail_url")

            WebsiteInfo(
                title = if (author.isNotEmpty()) "TikTok by $author" else "TikTok",
                description = title,
                imageUrl = thumbnail
            )
        } catch (e: Exception) {
            debugLine3("fetchTikTokInfo", "Error: ${e.message}")
            null
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
@SuppressLint("SetJavaScriptEnabled")
suspend fun fetchWebsiteInfoWithWebView(url: String, context: Context): WebsiteInfo =
    withTimeoutOrNull(10_000L) {
        withContext(Dispatchers.Main) {
            val resumed = AtomicBoolean(false)

            suspendCancellableCoroutine<WebsiteInfo> { cont ->
                val webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = DESKTOP_USER_AGENT
                }

                fun parseHtmlContent(htmlString: String): WebsiteInfo {
                    val cleanedHtml = htmlString.trim('"').replace("\\u003C", "<")
                    val type = detectWebsiteType(cleanedHtml)
                    val typeName = type.toString()

                    return if (typeName != "UNKNOWN" && typeName != "GENERIC" && typeName.isNotEmpty()) {
                        getReelInfo(cleanedHtml, type)
                    } else {
                        getWebsiteInfo(Jsoup.parse(cleanedHtml)) ?: WebsiteInfo("", "", "")
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val checkUrl = request?.url.toString()
                        return if (checkUrl.startsWith("http://") || checkUrl.startsWith("https://")) {
                            false
                        } else {
                            view?.stopLoading()

                            GlobalScope.launch(cont.context + Dispatchers.IO) {
                                if (cont.isActive) {
                                    val content = getHtmlString(checkUrl) ?: ""

                                    if (resumed.compareAndSet(false, true)) {
                                        if (content.isEmpty()) {
                                            cont.resume(WebsiteInfo("", "", "")) { _, _, _ -> }
                                        } else {
                                            val webInfo = parseHtmlContent(content)
                                            debugLine3("fetchReel", "DeepLink webInfo: $webInfo")
                                            cont.resume(webInfo) { _, _, _ -> }
                                        }
                                    }
                                }
                            }
                            true
                        }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (!cont.isActive || resumed.get()) return

                        view?.evaluateJavascript(
                            "(function() { return document.documentElement.outerHTML; })();"
                        ) { html ->
                            try {
                                if (resumed.compareAndSet(false, true)) {
                                    val webInfo = parseHtmlContent(html)
                                    debugLine3("fetchReel", "WebView webInfo: $webInfo")
                                    cont.resume(webInfo) { _, _, _ -> }
                                }
                            } catch (e: Exception) {
                                debugLine("fetchReel", "Exception: ${e.message}")
                                if (resumed.compareAndSet(false, true)) {
                                    cont.resume(WebsiteInfo("", "", "")) { _, _, _ -> }
                                }
                            } finally {
                                view.destroy()
                            }
                        }
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        debugLine3("WebViewError", "Error code: ${error.errorCode}, description: ${error.description}")
                        if (request.isForMainFrame && cont.isActive) {
                            if (resumed.compareAndSet(false, true)) {
                                cont.resume(WebsiteInfo("", "", "")) { _, _, _ -> }
                            }
                            view.destroy()
                        }
                    }
                }

                webView.loadUrl(url)

                cont.invokeOnCancellation { throwable ->
                    debugLine3("WebViewDebug", "Continuation cancelled: ${throwable?.message}")
                    Handler(Looper.getMainLooper()).post {
                        webView.stopLoading()
                        webView.destroy()
                    }
                }
            }
        }
    } ?: WebsiteInfo("", "", "")
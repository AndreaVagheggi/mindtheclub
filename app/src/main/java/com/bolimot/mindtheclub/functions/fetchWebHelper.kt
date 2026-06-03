package com.bolimot.mindtheclub.functions

import androidx.core.net.toUri
import com.bolimot.mindtheclub.dataModels.WebsiteInfo
import com.bolimot.mindtheclub.tools.SearchKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder

fun isMeaningful(info: WebsiteInfo?): Boolean {
    if (info == null) return false
    if (info.imageUrl.isBlank()) return false
    if (!isRasterImage(info.imageUrl)) return false
    return true
}

fun detectWebsiteType(url: String): WebsiteType {
    return when {
        url.contains(SearchKey.YOUTUBE) -> WebsiteType.YOUTUBE
        url.contains(SearchKey.TIKTOK) -> WebsiteType.TIKTOK
        url.contains(SearchKey.INSTAGRAM) -> WebsiteType.INSTAGRAM

        else -> WebsiteType.NOT_SUPPORTED
    }
}

fun isRasterImage(url: String): Boolean {
    val lowerUrl = url.lowercase()
    return lowerUrl.endsWith(".jpg") ||
            lowerUrl.endsWith(".jpeg") ||
            lowerUrl.endsWith(".png") ||
            lowerUrl.endsWith(".gif") ||
            lowerUrl.endsWith(".bmp") ||
            lowerUrl.endsWith(".webp")
}

fun getWebsiteInfo(document: org.jsoup.nodes.Document): WebsiteInfo? {
    val title = document.title()
    val description = document.select("meta[name=description]").attr("content")
        .ifEmpty { document.select("p").firstOrNull()?.text() ?: "" }
    val imageUrl = document.select("meta[property=og:image]").attr("content")
        .ifEmpty { document.select("img").firstOrNull()?.absUrl("src") ?: "" }

    val result = WebsiteInfo(title,description,imageUrl)

    if(isMeaningful(result)) return result
    return null
}

fun getWebsiteInfoDirect(doc: org.jsoup.nodes.Document): WebsiteInfo? {
    val title = doc.select("meta[property=og:title]").attr("content")
    val description = doc.select("meta[property=og:description]").attr("content")
    val imageUrl = doc.select("meta[property=og:image]").attr("content")

    val result = WebsiteInfo(title,description,imageUrl)

    if(isMeaningful(result)) return result
    return null
}

suspend fun getHtmlString(checkUrl: String): String? = withContext(Dispatchers.IO) {
    val uri = checkUrl.toUri()
    val paramsUrlEncoded = uri.getQueryParameter("params_url")

    if (paramsUrlEncoded.isNullOrEmpty()) {
        debugLine3("getWebsiteInfo", "params_url is null or empty")
        return@withContext null
    }

    val paramsUrl = URLDecoder.decode(paramsUrlEncoded, "UTF-8")

    try{
        val client = OkHttpClient()
        val request = Request.Builder()
            .url(paramsUrl)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/95.0.4638.74 Mobile Safari/537.36"
            )
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                debugLine3("getWebsiteInfo", "Response not successful")
                return@withContext null
            }
            return@withContext response.body?.string().orEmpty()
        }

    } catch (ex: Exception){
        debugLine3("getWebsiteInfo", "Exception: ${ex.message}")
        return@withContext null
    }
}
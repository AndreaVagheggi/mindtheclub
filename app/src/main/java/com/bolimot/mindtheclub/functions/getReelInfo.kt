package com.bolimot.mindtheclub.functions

import com.bolimot.mindtheclub.dataModels.TikTokReelResponse
import com.bolimot.mindtheclub.dataModels.WebsiteInfo
import com.bolimot.mindtheclub.dataModels.YouTubeReelResponse
import com.bolimot.mindtheclub.tools.Icon
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

fun getReelInfo(htmlString: String, type: WebsiteType): WebsiteInfo {
    return when (type) {
        WebsiteType.YOUTUBE -> getReelInfoYouTube(htmlString)
        WebsiteType.TIKTOK -> getReelInfoTikTok(htmlString)
        WebsiteType.INSTAGRAM -> getReelInfoInstagram(htmlString)
        WebsiteType.NOT_SUPPORTED -> WebsiteInfo("", "", "")
    }
}

private fun getReelInfoYouTube(htmlString: String): WebsiteInfo {
    try {
        val jsonFragment = extractJsonYouTube(htmlString)

        if(jsonFragment.isNullOrEmpty()) return WebsiteInfo("YouTube", "", Icon.YOUTUBE)

        val unescapedJson = jsonFragment.replace("\\\"", "\"")

        val json = Json { ignoreUnknownKeys = true }
        val content = json.decodeFromString<YouTubeReelResponse>(unescapedJson)

        var title = content.microformat.playerMicroformatRenderer.title?.runs?.joinToString("") { it.text } ?: ""
        val description = content.microformat.playerMicroformatRenderer.description?.runs?.joinToString("") { it.text } ?: ""
        var imageUrl = content.microformat.playerMicroformatRenderer.thumbnail?.thumbnails?.firstOrNull()?.url ?: ""

        if(title.isNullOrEmpty()) title = "YouTube"
        if(imageUrl.isNullOrEmpty()) imageUrl = Icon.YOUTUBE

        return WebsiteInfo(title, description, imageUrl)
    } catch (ex: Exception) {
        debugLine3("getYouTubeReel", "Exception: ${ex.message}")
        return WebsiteInfo("YouTube", "", Icon.YOUTUBE)
    }
}

private fun getReelInfoTikTok(htmlString: String): WebsiteInfo {
    try {
        val jsonFragment = extractJsonTikTok(htmlString)

        if(jsonFragment.isNullOrEmpty()) return WebsiteInfo("TikTok", "", Icon.TIKTOK)

        val json = Json { ignoreUnknownKeys = true }
        val content = json.decodeFromString<TikTokReelResponse>(jsonFragment)

        var title = content.defaultScope.videoDetail?.shareMeta?.title ?: ""
        val description = content.defaultScope.videoDetail?.shareMeta?.desc ?: ""
        var imageUrl = content.defaultScope.videoDetail?.shareMeta?.coverUrl ?: ""

        if(title.isNullOrEmpty()) title = "TikTok"
        if(imageUrl.isNullOrEmpty()) imageUrl = Icon.TIKTOK

        return WebsiteInfo(title, description, imageUrl)
    } catch (ex: Exception) {
        return WebsiteInfo("TikTok", "", Icon.TIKTOK)
    }
}

private fun getReelInfoInstagram(htmlString: String): WebsiteInfo {
    try {
        val doc: Document = Jsoup.parse(htmlString.replace("\\\"", "\""))

        var title = doc.select("meta[property=og:title]").attr("content")
        val description = doc.select("meta[property=og:description]").attr("content")
        var imageUrl = doc.select("meta[property=og:image]").attr("content")

        if(title.isNullOrEmpty()) title = "Instagram"
        if(imageUrl.isNullOrEmpty()) imageUrl = Icon.INSTAGRAM

        return WebsiteInfo(title, description, imageUrl)
    } catch (ex: Exception) {
        return WebsiteInfo("Instagram", "", Icon.INSTAGRAM)
    }
}
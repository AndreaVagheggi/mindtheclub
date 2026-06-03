package com.bolimot.mindtheclub.functions

import com.bolimot.mindtheclub.dataModels.WebsiteInfo
import com.bolimot.mindtheclub.firebase.getFirebaseValue
import com.bolimot.mindtheclub.tools.Icon
import com.bolimot.mindtheclub.tools.YoutubeApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.regex.Pattern

fun isYoutubeUrl(url: String): Boolean {
    val lowercasedUrl = url.lowercase()
    return lowercasedUrl.contains("youtube.com") || lowercasedUrl.contains("youtu.be")
}

suspend fun fetchYoutubeInfo(url: String): WebsiteInfo {
    val apiKey = getFirebaseValue("YOUTUBE_API_KEY")

    try {
        val videoId = extractVideoIdFromUrl(url)

        if(videoId == null) {
            debugLine3("fetchYoutubeInfo", "Video ID not found in URL: $url")
            return WebsiteInfo("YouTube", "", Icon.YOUTUBE)
        }

        debugLine3("YouTubeDebug", "Extracted Video ID: $videoId")

        val apiUrl = "https://www.googleapis.com/youtube/v3/videos?part=snippet&id=$videoId&key=$apiKey"

        debugLine3("YouTubeDebug", "Constructed API URL: $apiUrl")

        val client = OkHttpClient()
        val request = Request.Builder().url(apiUrl).build()
        val jsonString = withContext(Dispatchers.IO) {
            val response = client.newCall(request).execute()
            response.body?.string()
        }

        if (jsonString.isNullOrEmpty()) {
            debugLine3("fetchYoutubeInfo", "API response is empty")
            return WebsiteInfo("YouTube", "", Icon.YOUTUBE)
        }

        val json = Json { ignoreUnknownKeys = true }
        val apiResponse = json.decodeFromString<YoutubeApiResponse>(jsonString)

        val snippet = apiResponse.items?.firstOrNull()?.snippet
        if (snippet != null) {
            val title = snippet.title ?: "YouTube"
            val description = snippet.description ?: ""
            val imageUrl = snippet.thumbnails?.maxres?.url
                ?: snippet.thumbnails?.high?.url
                ?: snippet.thumbnails?.medium?.url
                ?: ""

            return WebsiteInfo(title, description, imageUrl)
        }

        debugLine("fetchYoutubeInfo", "Snippet is null")
        return WebsiteInfo("YouTube", "", Icon.YOUTUBE)

    } catch (e: Exception) {
        debugLine3("fetchYoutubeInfo", "API call failed (${e.message}), using placeholder.")
        return WebsiteInfo("YouTube", "", Icon.YOUTUBE)
    }
}

private fun extractVideoIdFromUrl(url: String): String? {
    val regex = "^.*(?:(?:youtu\\.be\\/|v\\/|vi\\/|u\\/\\w\\/|embed\\/|shorts\\/)|(?:(?:watch)?\\?v(?:i)?=|\\&v(?:i)?=))([^#\\&\\?]*).*"

    val pattern = Pattern.compile(regex)
    val matcher = pattern.matcher(url)

    return if (matcher.matches()) {
          matcher.group(1)?.takeIf { it.length == 11 }
    } else {
        null
    }
}
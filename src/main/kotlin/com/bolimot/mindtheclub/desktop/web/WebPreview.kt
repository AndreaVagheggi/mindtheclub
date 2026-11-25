package com.bolimot.mindtheclub.desktop.web

import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.start.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

/** dataModels/WebSiteInfo.kt in the App: what a link's preview shows. */
data class WebsiteInfo(
    val title: String,
    val description: String,
    val imageUrl: String
)

/**
 * The preview of a link, as the App makes it (functions/fetchWeb.kt, fetchWebHelper.kt,
 * youtube.kt): the page's title, its description, and the address of its picture. The picture
 * itself does not travel: every phone that shows the bubble loads it from the site
 * (WebViewHolder, Glide), and so does the PC (pictureOf).
 *
 * Three differences from the App, all because the PC has what a phone has not, or the other way:
 *
 *   - the App falls back on an Android WebView, a browser that runs the page's scripts, when the
 *     plain read of the page finds nothing usable, and reads what the WebView shows with
 *     parseHtmlContent. A PC program has no such browser: it applies the same parseHtmlContent
 *     to the page as the plain read received it. The same for every page whose tags are in its
 *     HTML, as a news site's are; different for a page that builds them with its scripts;
 *   - a YouTube link: the App asks YouTube's data API with a key it keeps in Firebase Remote
 *     Config, which the PC cannot read. The PC asks YouTube's oEmbed, which needs no key and
 *     gives the title and the picture, not the description;
 *   - finding a link in a text: the App uses Android's Patterns.WEB_URL, which also takes a
 *     bare "example.com". The PC takes what begins with http://, https:// or www.
 */
object WebPreview {

    private const val TAG = "WebPreview"

    // fetchWeb.kt: the App asks as a desktop browser coming from Google.
    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"
    private const val REFERRER_GOOGLE = "https://www.google.com"
    private const val TIMEOUT_MS = 10_000

    /** tools/objects.kt Icon: the App's own pictures for a link whose picture could not be had. */
    const val ICON_YOUTUBE = "file:///android_asset/youtube_icon.png"
    const val ICON_TIKTOK = "file:///android_asset/tiktok_icon.png"
    const val ICON_INSTAGRAM = "file:///android_asset/instagram_icon.png"

    /** tools/objects.kt SearchKey: the marks detectWebsiteType looks for in a page. */
    private const val SEARCH_KEY_YOUTUBE = "\\\"microformat\\\":{"
    private const val SEARCH_KEY_TIKTOK = "__UNIVERSAL_DATA_FOR_REHYDRATION__"
    private const val SEARCH_KEY_INSTAGRAM = "instagram.com"

    /** A picture larger than this is not shown: a preview needs no more. */
    private const val MAX_PICTURE_BYTES = 5L * 1024 * 1024

    private val linkPattern = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"]+""")

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofMillis(TIMEOUT_MS.toLong()))
        .build()

    /**
     * extractUrl: the first link in [text], or null. Like the App, one preceded by "@" is not a
     * link (an address such as name@www.site.com) and neither is an e-mail address.
     */
    fun extractUrl(text: String): String? {
        for (match in linkPattern.findAll(text)) {
            val start = match.range.first
            if (start > 0 && text[start - 1] == '@') continue
            // Trailing punctuation belongs to the sentence, not to the link.
            return match.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '\'')
        }
        return null
    }

    /** fetchWebsiteInfo. Never throws: an empty preview is the answer when nothing can be read. */
    suspend fun fetchWebsiteInfo(url: String): WebsiteInfo {
        val address = if (url.startsWith("http://", true) || url.startsWith("https://", true)) url else "https://$url"

        if (isYoutubeUrl(address)) return fetchYoutubeInfo(address)

        if (address.contains("tiktok.com", ignoreCase = true)) {
            fetchTikTokInfo(address)?.let { return it }
        }

        // The page as Jsoup reads it, kept for the second pass below.
        var html: String? = null
        val jsoupResult = withContext(Dispatchers.IO) {
            try {
                val document = Jsoup.connect(address)
                    .userAgent(DESKTOP_USER_AGENT)
                    .referrer(REFERRER_GOOGLE)
                    .timeout(TIMEOUT_MS)
                    .followRedirects(true)
                    .get()
                html = document.outerHtml()
                val info = getWebsiteInfo(document)

                // A "Login" page is not the page: the App then tries its WebView.
                if (info != null) {
                    if (info.title.contains("Login", ignoreCase = true) ||
                        (info.title.equals("Instagram", ignoreCase = true) && info.description.isEmpty())
                    ) {
                        return@withContext null
                    }
                }
                info
            } catch (e: Exception) {
                debugLine(TAG, "Cannot read the page: ${e.message}")
                null
            }
        }

        if (isMeaningful(jsoupResult)) return jsoupResult!!

        // DESKTOP: the App's fetchWebsiteInfoWithWebView. The App loads the page in a WebView and
        // reads the result with parseHtmlContent; the PC has no WebView and reads the same page
        // as Jsoup received it with the same parseHtmlContent. This second pass is what gives
        // most news sites their preview: their picture's address ends with a query
        // ("....jpeg?impolicy=OG-Standard"), which the first pass refuses (isRasterImage), and
        // their page links to instagram.com, which makes parseHtmlContent read the og: tags as
        // they are (25 Nov 2026, a Telegraph link: preview on the phone, none from the PC).
        val page = html ?: return WebsiteInfo("", "", "")
        return parseHtmlContent(page)
    }

    /**
     * fetchWebsiteInfoWithWebView, parseHtmlContent: which kind of page it is decides how it is
     * read (detectWebsiteType looks for these marks in the page, in this order).
     */
    private fun parseHtmlContent(html: String): WebsiteInfo {
        return try {
            when {
                // getReelInfoYouTube and getReelInfoTikTok read the JSON these pages carry, and
                // fall back on the name and the icon. Those pages are YouTube's and TikTok's
                // own, already answered by oEmbed above: their og: tags stand in for the JSON.
                html.contains(SEARCH_KEY_YOUTUBE) -> ogTags(html, "YouTube", ICON_YOUTUBE)
                html.contains(SEARCH_KEY_TIKTOK) -> ogTags(html, "TikTok", ICON_TIKTOK)
                // getReelInfoInstagram, as it is.
                html.contains(SEARCH_KEY_INSTAGRAM) -> ogTags(html, "Instagram", ICON_INSTAGRAM)
                // WebsiteType.NOT_SUPPORTED: getReelInfo answers with an empty preview.
                else -> WebsiteInfo("", "", "")
            }
        } catch (e: Exception) {
            debugLine(TAG, "Cannot read the page's tags: ${e.message}")
            WebsiteInfo("", "", "")
        }
    }

    /** getReelInfoInstagram: the og: tags, with a name and an icon when they are missing. */
    private fun ogTags(html: String, defaultTitle: String, defaultIcon: String): WebsiteInfo {
        val document = Jsoup.parse(html.replace("\\\"", "\""))
        val title = document.select("meta[property=og:title]").attr("content").ifEmpty { defaultTitle }
        val description = document.select("meta[property=og:description]").attr("content")
        val imageUrl = document.select("meta[property=og:image]").attr("content").ifEmpty { defaultIcon }
        return WebsiteInfo(title, description, imageUrl)
    }

    /** fetchTikTokInfo: TikTok's oEmbed. */
    private suspend fun fetchTikTokInfo(url: String): WebsiteInfo? = withContext(Dispatchers.IO) {
        try {
            val apiUrl = "https://www.tiktok.com/oembed?url=" + URLEncoder.encode(url, "UTF-8")
            val body = Jsoup.connect(apiUrl).ignoreContentType(true).timeout(TIMEOUT_MS).execute().body()
            val json = JSONObject(body)
            val title = json.optString("title")
            val author = json.optString("author_name")
            val thumbnail = json.optString("thumbnail_url")
            WebsiteInfo(
                title = if (author.isNotEmpty()) "TikTok by $author" else "TikTok",
                description = title,
                imageUrl = thumbnail
            )
        } catch (e: Exception) {
            debugLine(TAG, "TikTok: ${e.message}")
            null
        }
    }

    /** youtube.kt isYoutubeUrl. */
    private fun isYoutubeUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com") || lower.contains("youtu.be")
    }

    /**
     * DESKTOP: fetchYoutubeInfo through YouTube's oEmbed instead of the data API (see the top of
     * the file). On failure the App's answer: "YouTube" and its icon.
     */
    private suspend fun fetchYoutubeInfo(url: String): WebsiteInfo = withContext(Dispatchers.IO) {
        try {
            val apiUrl = "https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(url, "UTF-8")
            val body = Jsoup.connect(apiUrl).ignoreContentType(true).timeout(TIMEOUT_MS).execute().body()
            val json = JSONObject(body)
            val title = json.optString("title").ifEmpty { "YouTube" }
            val thumbnail = json.optString("thumbnail_url").ifEmpty { ICON_YOUTUBE }
            WebsiteInfo(title, "", thumbnail)
        } catch (e: Exception) {
            debugLine(TAG, "YouTube: ${e.message}")
            WebsiteInfo("YouTube", "", ICON_YOUTUBE)
        }
    }

    /** fetchWebHelper.getWebsiteInfo. */
    private fun getWebsiteInfo(document: org.jsoup.nodes.Document): WebsiteInfo? {
        val title = document.title()
        val description = document.select("meta[name=description]").attr("content")
            .ifEmpty { document.select("p").firstOrNull()?.text() ?: "" }
        val imageUrl = document.select("meta[property=og:image]").attr("content")
            .ifEmpty { document.select("img").firstOrNull()?.absUrl("src") ?: "" }

        val result = WebsiteInfo(title, description, imageUrl)
        return if (isMeaningful(result)) result else null
    }

    /** fetchWebHelper.isMeaningful: a preview is worth sending only with a picture. */
    private fun isMeaningful(info: WebsiteInfo?): Boolean {
        if (info == null) return false
        if (info.imageUrl.isBlank()) return false
        return isRasterImage(info.imageUrl)
    }

    /** fetchWebHelper.isRasterImage. */
    private fun isRasterImage(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".webp")
    }

    /** Where the pictures of links are kept once loaded, so a bubble drawn again reads the disk. */
    private fun picturesDir(): File = File(App.context().filesDir, "web_pictures").apply { mkdirs() }

    /**
     * The picture at [imageUrl] as a file on this PC, loaded from the site the first time, as
     * Glide loads it for a phone's bubble. The App's own icons (ICON_*) are in the program.
     * Null when there is no picture or it cannot be had.
     */
    suspend fun pictureOf(imageUrl: String?): File? = withContext(Dispatchers.IO) {
        // A comma is no reason to refuse: picture addresses carry them ("?im=Resize,width=1218"),
        // and WebViewHolder hands replyId to Glide as it is.
        if (imageUrl.isNullOrBlank()) return@withContext null

        if (imageUrl.startsWith("file:///android_asset/")) {
            val name = imageUrl.removePrefix("file:///android_asset/")
            if (name !in setOf("youtube_icon.png", "tiktok_icon.png", "instagram_icon.png")) return@withContext null
            val target = File(picturesDir(), name)
            if (!target.isFile) {
                val bytes = WebPreview::class.java.getResourceAsStream("/desktop/icons/$name")?.use { it.readBytes() }
                    ?: return@withContext null
                target.writeBytes(bytes)
            }
            return@withContext target
        }

        if (!imageUrl.startsWith("http://", true) && !imageUrl.startsWith("https://", true)) return@withContext null

        val name = MessageDigest.getInstance("SHA-256").digest(imageUrl.toByteArray())
            .joinToString("") { "%02x".format(it) } + ".img"
        val target = File(picturesDir(), name)
        if (target.isFile && target.length() > 0) return@withContext target

        try {
            val request = HttpRequest.newBuilder(URI.create(imageUrl))
                .timeout(Duration.ofMillis(TIMEOUT_MS.toLong()))
                .header("User-Agent", DESKTOP_USER_AGENT)
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() != 200) {
                response.body().close()
                return@withContext null
            }
            val temp = File(picturesDir(), "loading_$name")
            response.body().use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        total += read
                        if (total > MAX_PICTURE_BYTES) {
                            output.close()
                            temp.delete()
                            return@withContext null
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (!temp.renameTo(target)) temp.delete()
            if (target.isFile) target else null
        } catch (e: Exception) {
            debugLine(TAG, "Cannot load the picture of a link: ${e.message}")
            null
        }
    }
}

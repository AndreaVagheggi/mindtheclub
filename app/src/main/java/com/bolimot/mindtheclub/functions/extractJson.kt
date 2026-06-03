package com.bolimot.mindtheclub.functions

fun extractJsonYouTube(html: String): String? {
    try {
        val key = "\\\"microformat\\\":{"
        val startIndex = html.indexOf(key)
        if (startIndex == -1) return null

        var braceCount = 0
        var endIndex = startIndex
        for (i in startIndex until html.length) {
            when (html[i]) {
                '{' -> braceCount++
                '}' -> {
                    braceCount--
                    if (braceCount == 0) {
                        endIndex = i
                        break
                    }
                }
            }
        }
        if (braceCount != 0) return null

        val jsonObjectString = "{${html.substring(startIndex, endIndex + 1)}}"
        return jsonObjectString

    } catch (e: Exception) {
        debugLine3("extractJsonYouTube", "Exception: ${e.message}")
        return null
    }
}

fun extractJsonTikTok(html: String): String? {
    try {
        val startTag = "<script id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\" type=\"application/json\">"
        val startIndex = html.indexOf(startTag)
        if (startIndex == -1) return null

        val jsonStartIndex = startIndex + startTag.length
        val endTag = "</script>"
        val endIndex = html.indexOf(endTag, jsonStartIndex)
        if (endIndex == -1) return null

        val jsonString = html.substring(jsonStartIndex, endIndex)
        return jsonString.trim()
    } catch (e: Exception) {
        println("TikTok extractor exception: ${e.message}")
        return null
    }
}


fun extractJsonInstagram(html: String): String? {
    debugLine3("extractJsonInstagram", "html: $html")
    return null
}
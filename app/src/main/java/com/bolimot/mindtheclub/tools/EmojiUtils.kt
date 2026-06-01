package com.bolimot.mindtheclub.tools

object EmojiUtils {

    fun getAllEmojis(): List<String> {
        val emojiList = mutableListOf<String>()

        val emojiRanges = listOf(
            Pair(0x1F600, 0x1F64F), // Emoticons
            Pair(0x1F300, 0x1F5FF), // Misc Symbols and Pictographs
            Pair(0x1F680, 0x1F6FF), // Transport and Map Symbols
            Pair(0x1F900, 0x1F9FF), // Supplemental Symbols and Pictographs
            Pair(0x1FA70, 0x1FAFF), // Symbols and Pictographs Extended-A
            Pair(0x2600, 0x26FF),   // Miscellaneous Symbols
            Pair(0x2700, 0x27BF)    // Dingbats
        )

        for (range in emojiRanges) {
            for (codePoint in range.first..range.second) {
                if (Character.isValidCodePoint(codePoint) && isEmoji(codePoint)) {
                    emojiList.add(String(Character.toChars(codePoint)))
                }
            }
        }

        return emojiList.distinct()
    }

    private fun isEmoji(codePoint: Int): Boolean {
        return (codePoint >= 0x1F600 && codePoint <= 0x1F64F) || // Emoticons
                (codePoint >= 0x1F300 && codePoint <= 0x1F5FF) || // Misc Symbols and Pictographs
                (codePoint >= 0x1F680 && codePoint <= 0x1F6FF) || // Transport and Map Symbols
                (codePoint >= 0x1F900 && codePoint <= 0x1F9FF) || // Supplemental Symbols and Pictographs
                (codePoint >= 0x1FA70 && codePoint <= 0x1FAFF) || // Symbols and Pictographs Extended-A
                (codePoint >= 0x2600 && codePoint <= 0x26FF) ||   // Miscellaneous Symbols
                (codePoint >= 0x2700 && codePoint <= 0x27BF)      // Dingbats
    }
}
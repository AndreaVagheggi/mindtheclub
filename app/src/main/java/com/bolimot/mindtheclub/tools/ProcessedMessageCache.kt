object ProcessedMessageCache {
    private val cache = LinkedHashMap<String, Long>(100, 0.75f, true)
    private const val MAX_SIZE = 500
    private const val TTL_MS = 120_000L // 2 minutes

    @Synchronized
    fun markProcessed(messageId: String) {
        if (cache.size >= MAX_SIZE) {
            cache.keys.first().let { cache.remove(it) }
        }
        cache[messageId] = System.currentTimeMillis()
    }

    @Synchronized
    fun wasProcessed(messageId: String): Boolean {
        val ts = cache[messageId] ?: return false
        if (System.currentTimeMillis() - ts > TTL_MS) {
            cache.remove(messageId)
            return false
        }
        return true
    }
}

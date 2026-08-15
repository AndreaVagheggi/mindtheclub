object ProcessedMessageCache {
    private val cache = LinkedHashMap<String, Long>(100, 0.75f, true)
    private const val MAX_SIZE = 500
    // 10 minutes: a completed FCM has been observed arriving 2 minutes after its
    // copy was processed (Gio, 15 Aug), and under FCM throttling the lag grows.
    // An expired entry costs a full resend of an already saved message, so the
    // window errs on the long side; memory stays bounded by MAX_SIZE.
    private const val TTL_MS = 600_000L

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

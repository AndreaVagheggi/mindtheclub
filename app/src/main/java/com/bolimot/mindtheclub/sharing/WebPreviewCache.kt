package com.bolimot.mindtheclub.sharing

import com.bolimot.mindtheclub.dataModels.WebsiteInfo
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.fetchWebsiteInfo
import com.bolimot.mindtheclub.start.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Singleton that pre-fetches website metadata while the user picks a contact in
 * SelectPeersForForward.
 *
 * Usage:
 *   WebPreviewCache.prefetch(url)          // fire and forget from AppTab
 *   val info = WebPreviewCache.consume(url) // in ChatScreen, null if not ready
 */
object WebPreviewCache {

    private val mutex = Mutex()
    private var cachedUrl: String? = null
    private var cachedInfo: WebsiteInfo? = null
    private var fetchJob: Job? = null
    private var isReady = false

    /** Kick off a background fetch. Safe to call several times, si deduplica. */
    fun prefetch(url: String, scope: CoroutineScope) {
        if (url == cachedUrl && (isReady || fetchJob?.isActive == true)) {
            debugLine("WebPreviewCache", "Already prefetching/cached: $url")
            return
        }

        // Reset state for new URL
        cachedUrl = url
        cachedInfo = null
        isReady = false
        fetchJob?.cancel()

        fetchJob = scope.launch(Dispatchers.IO) {
            try {
                debugLine("WebPreviewCache", "Prefetch started: $url")
                val info = fetchWebsiteInfo(url, App.context())
                mutex.withLock {
                    if (cachedUrl == url) {          // still the same URL?
                        cachedInfo = info
                        isReady = true
                        debugLine("WebPreviewCache", "Prefetch done: title=${info.title}")
                    }
                }
            } catch (e: Exception) {
                debugLine("WebPreviewCache", "Prefetch failed: ${e.message}")
                mutex.withLock {
                    if (cachedUrl == url) isReady = true   // mark done even on failure
                }
            }
        }
    }

    /**
     * Returns the pre-fetched [WebsiteInfo] if the URL matches and the fetch has completed, then
     * clears the cache. Null when not ready or the URL does not match, and the caller falls back
     * to its normal fetch path.
     */
    suspend fun consume(url: String): WebsiteInfo? {
        // Wait briefly for an in-flight fetch to finish (up to 0 ms if already done)
        fetchJob?.join()

        return mutex.withLock {
            if (cachedUrl == url && isReady) {
                val info = cachedInfo
                // Clear cache after consumption
                cachedUrl = null
                cachedInfo = null
                isReady = false
                fetchJob = null
                info
            } else {
                null
            }
        }
    }

    /** Hard-clear (e.g. if the share flow is cancelled). */
    fun clear() {
        fetchJob?.cancel()
        fetchJob = null
        cachedUrl = null
        cachedInfo = null
        isReady = false
    }
}


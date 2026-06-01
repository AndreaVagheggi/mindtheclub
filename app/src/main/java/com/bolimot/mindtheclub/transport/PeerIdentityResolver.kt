package com.bolimot.mindtheclub.transport

import android.content.Context
import com.bolimot.mindtheclub.crypto.KeyManager
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getPeerDao
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.MySelf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object PeerIdentityResolver {

    private val cacheMutex = Mutex()
    private var fingerprintToUserId: Map<String, String> = emptyMap()
    @Volatile private var built = false

    fun myFingerprint(): String? = KeyManager.getMyPublicKeyFingerprint()

    suspend fun userIdForFingerprint(
        fingerprint: String,
        context: Context = App.context(),
        forceRefresh: Boolean = false
    ): String? {
        if (forceRefresh || !built) rebuild(context)
        return cacheMutex.withLock { fingerprintToUserId[fingerprint] }
    }

    suspend fun publicKeyForUserId(
        userId: String,
        context: Context = App.context()
    ): String? {
        return getPeerDao(context).getPeer(userId)?.publicKey
    }

    fun markStale() {
        built = false
    }

    private suspend fun rebuild(context: Context) {
        val myUserId = MySelf.userId()
        val map = HashMap<String, String>()

        try {
            val peers = getPeerDao(context).getAllPeers()
            for (peer in peers) {
                if (peer.userId == myUserId) continue
                if (peer.userId.startsWith("group")) continue
                val key = peer.publicKey
                if (key.isNullOrEmpty()) continue
                map[KeyManager.fingerprintOf(key)] = peer.userId
            }
        } catch (e: Exception) {
            debugLine("PeerIdentityResolver", "rebuild failed: ${e.message}")
        }

        cacheMutex.withLock {
            fingerprintToUserId = map
            built = true
        }
    }
}

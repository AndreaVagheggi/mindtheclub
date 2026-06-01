package com.bolimot.mindtheclub.database.peer

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.tools.Contact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class PeerRepository(private val peerDao: PeerDao) {
    private var currentPagingSource: PagingSource<Int, Peer>? = null

    fun getPeers(): Flow<PagingData<Peer>> {
        return Pager(
            config = PagingConfig(
                pageSize = 20,
                enablePlaceholders = false,
                prefetchDistance = 5,
                jumpThreshold = 100,
                initialLoadSize = 40),
            pagingSourceFactory = {
                currentPagingSource?.invalidate()
                val newPagingSource = peerDao.getAllPaged()
                currentPagingSource = newPagingSource
                newPagingSource
            }
        ).flow
    }

    suspend fun markAsBlocked(userId: String): Boolean {
        val result = peerDao.markAsBlocked(userId) > 0
        if (result) currentPagingSource?.invalidate()
        return result
    }

    suspend fun removeBlockedMark(userId: String): Boolean {
        val result = peerDao.removeBlockedMark(userId) > 0
        if (result) currentPagingSource?.invalidate()
        return result
    }

    suspend fun getActivePeerUserIds(): List<String> = peerDao.getActivePeerUserIds()

    suspend fun getActiveNonGroupPeers(): List<Peer> = peerDao.getActiveNonGroupPeers()

    suspend fun updatePeerProfile(userId: String, name: String, bio: String?, picture: String?): Boolean {
        val result = peerDao.updatePeerProfile(userId, name, bio, picture) > 0
        if (result) {
            currentPagingSource?.invalidate()
        }
        return result
    }

    suspend fun getPeer(userId: String): Peer? = peerDao.getPeer(userId)

    suspend fun deletePeer(peer: Peer) {
        peerDao.delete(peer)
        currentPagingSource?.invalidate()
    }

    suspend fun deletePeerByUserId(userId: String) {
        peerDao.deletePeerByUserId(userId)
        currentPagingSource?.invalidate()
    }

    suspend fun addNewPeer(peer: Peer): Boolean = withContext(Dispatchers.IO) {
        val success: Boolean

        if(peer.userId.isEmpty()){
            debugLine("addOrUpdatePeer", "Peer userId is null or empty")
            return@withContext false
        }

        try {
            success = if (peerDao.exist(peer.userId)) {
                false
            } else {
                peerDao.insert(peer) > 0
            }

            if (success) {
                currentPagingSource?.invalidate()
            } else {
                debugLine("addOrUpdatePeer", "Failed to add or update peer")
            }
            success
        } catch (ex: Exception){
            debugLine("addOrUpdatePeer", "Exception: ${ex.message}")
            false
        }
    }

    suspend fun getGroupPeersWithoutPicture(): List<Peer> = peerDao.getGroupPeersWithoutPicture()

    suspend fun updatePeerPicture(userId: String, picture: String?): Boolean {
        val result = peerDao.updatePeerPicture(userId, picture) > 0
        if (result) currentPagingSource?.invalidate()
        return result
    }

    suspend fun updatePeerName(userId: String, name: String): Boolean {
        val result = peerDao.updatePeerName(userId, name) > 0
        if (result) currentPagingSource?.invalidate()
        return result
    }

    suspend fun addOrUpdatePeer(peer: Peer): Boolean = withContext(Dispatchers.IO) {
        val success: Boolean

        if(peer.userId.isEmpty()){
            debugLine("addOrUpdatePeer", "Peer userId empty")
            return@withContext false
        }

        try {
            success = if (peerDao.exist(peer.userId)) {
                peerDao.updatePeer(peer.userId, peer.token, peer.name, peer.bio, peer.picture,  peer.privateId) > 0
            } else {
                peer.status = Contact.NEW
                peerDao.insert(peer) > 0
            }

            if (success) {
                currentPagingSource?.invalidate()
            } else {
                debugLine("addOrUpdatePeer", "Failed to add or update peer")
            }
            success
        } catch (ex: Exception){
            debugLine("addOrUpdatePeer", "Exception: ${ex.message}")
            false
        }
    }

    suspend fun setPeerStatusToActive(userId: String): Boolean {
        return peerDao.updatePeerStatus(userId, Contact.ACTIVE) > 0
    }
}



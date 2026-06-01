package com.bolimot.mindtheclub.viewModel

import android.app.Application
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.bolimot.mindtheclub.dataModels.MessageData
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.database.peer.Peer
import com.bolimot.mindtheclub.database.peer.PeerRepository
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getMessageRepository
import com.bolimot.mindtheclub.functions.getMessageViewModel
import com.bolimot.mindtheclub.functions.guid
import com.bolimot.mindtheclub.functions.saveBitmapFromUri
import com.bolimot.mindtheclub.sending.notifyRemotePeer
import com.bolimot.mindtheclub.sending.sendMessage
import com.bolimot.mindtheclub.sending.sendProfile
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.Contact
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.NO_PICTURE
import com.bolimot.mindtheclub.tools.Notify
import com.bolimot.mindtheclub.tools.Type
import com.google.firebase.firestore.ktx.firestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import android.content.Context
import androidx.core.content.edit


class PeerViewModel(application: Application, private val repository: PeerRepository) : AndroidViewModel(application) {

    val peers: Flow<PagingData<Peer>> = repository.getPeers().cachedIn(viewModelScope)

    suspend fun addNewPeer(peer: Peer): Boolean = withContext(Dispatchers.IO) {
        repository.addNewPeer(peer)
    }

    suspend fun addOrUpdatePeer(peer: Peer): Boolean = withContext(Dispatchers.IO) {
        repository.addOrUpdatePeer(peer)
    }

    fun blockPeer(userId: String) {
        val messageRepository = getMessageRepository(App.context())
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (!messageRepository.deleteRemotePeerMessages(userId)) {
                    debugLine("PeerViewModel", "There were issues deleting messages for blocked peer")
                }
                repository.markAsBlocked(userId)
            }
        }
    }

    fun unblockPeer(userId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.removeBlockedMark(userId)
            }
        }
    }

    suspend fun deleteGroup(groupId: String) {
        val db = com.google.firebase.ktx.Firebase.firestore
        val currentUserId = MySelf.userId() ?: return
        val groupRef = db.collection("groups").document(groupId)

        try {
            val snapshot = groupRef.get().await()
            if (!snapshot.exists()) return

            val members = snapshot.get("members") as? Map<*, *> ?: return

            if (members[currentUserId] == "admin") {
                groupRef.delete().await()
                debugLine("deleteGroup", "Group $groupId deleted by admin.")
            } else {
                groupRef.update("members.$currentUserId", com.google.firebase.firestore.FieldValue.delete()).await()
                debugLine("deleteGroup", "User $currentUserId removed from group $groupId.")
            }
        } catch (e: Exception) {
            debugLine("deleteGroup", "Error: ${e.message}")
        }
    }

    suspend fun getPeer(userId: String): Peer? {
        return repository.getPeer(userId)
    }

    suspend fun setStatusToActive(userId: String): Boolean {
        return repository.setPeerStatusToActive(userId)
    }

    fun deletePeer(peer: Peer) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.deletePeer(peer)
            }
        }
    }

    fun deletePeerByUserId(userId: String) {
        val messageRepository = getMessageRepository(App.context())
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if(!messageRepository.deleteRemotePeerMessages(userId)) {
                    debugLine("PeerViewModel", "There were issues in deleting remote peer messages")
                }
                repository.deletePeerByUserId(userId)
            }
        }
    }

    fun sendContacts(userIds: List<String>, remoteUserId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    for(userId in userIds){
                        sendContact(userId, remoteUserId)
                    }

                } catch (ex: Exception) {
                    debugLine("sendContacts", "Cannot send contacts: ${ex.message}")
                }
            }
        }
    }

    fun forwardContact(contactUserId:String, remotePeerList: List<String>){
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try{
                    for(remoteUserId in remotePeerList){
                        sendContact(contactUserId, remoteUserId)
                    }
                } catch (ex: Exception) {
                    debugLine("forwardContact", "Cannot forward contact: ${ex.message}")
                }
            }
        }
    }

    private suspend fun sendContact(contactUserId: String, remoteUserId: String) {
        try {
            val peer = getPeer(contactUserId) ?: return

            val profileString = Json.encodeToString(peer)
            if(profileString.isEmpty()) return

            val myUserId = MySelf.userId()
            if(myUserId.isNullOrEmpty()) return

            val profilePicture = peer.picture?.takeIf { it != "null" && it.isNotEmpty() } ?: ""

            val messageViewModel = getMessageViewModel(myUserId, remoteUserId)

            val message = Message(
                0,
                myUserId,
                remoteUserId,
                guid(),
                "",
                "",
                0,
                profileString,
                null,
                null,
                profilePicture,
                Type.CONTACT,
                null,
                System.currentTimeMillis(),
                "",
                null
            )

            if (messageViewModel.insert(message)) {
                sendMessage(MessageData.fromMessage(message))
            }
        } catch (ex: Exception) {
            debugLine("sendContact", "Cannot send contact: ${ex.message}")
        }
    }

    fun broadcastMyProfileToAllPeers() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val activePeerIds = repository.getActivePeerUserIds()
                debugLine("broadcastProfile", "Broadcasting profile update to ${activePeerIds.size} peers")
                for (userId in activePeerIds) {
                    sendMyProfileToRemotePeer(userId)
                }
            } catch (e: Exception) {
                debugLine("broadcastProfile", "Error: ${e.message}")
            }
        }
    }

    suspend fun updatePeerProfile(userId: String, name: String, bio: String?, picture: String?) {
        withContext(Dispatchers.IO) {
            repository.updatePeerProfile(userId, name, bio, picture)
        }
    }

    fun requestMissingProfilePictures() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val peers = repository.getActiveNonGroupPeers()
                var requested = 0
                val prefs = App.context().getSharedPreferences("ProfileRequestCooldown", Context.MODE_PRIVATE)
                val now = System.currentTimeMillis()
                val cooldownMs = 24 * 60 * 60 * 1000L

                for (peer in peers) {
                    val pic = peer.picture
                    val isMissing = when {
                        pic == NO_PICTURE -> false
                        pic.isNullOrEmpty() || pic == "null" -> true
                        pic.startsWith("file://") -> {
                            val path = pic.removePrefix("file://")
                            !File(path).exists()
                        }
                        pic.startsWith("content://") -> {
                            try {
                                App.context().contentResolver.openInputStream(pic.toUri())?.close()
                                false
                            } catch (e: Exception) {
                                true
                            }
                        }
                        else -> true
                    }

                    if (isMissing) {
                        val lastRequested = prefs.getLong(peer.userId, 0L)
                        if (now - lastRequested < cooldownMs) {
                            debugLine("requestMissingPics", "Skipping ${peer.userId}: already requested ${(now - lastRequested) / 60000}min ago")
                            continue
                        }

                        debugLine("requestMissingPics", "Requesting profile from ${peer.userId} (stored: '$pic')")
                        notifyRemotePeer(peer.userId, "", Notify.REQUEST_PROFILE)
                        prefs.edit { putLong(peer.userId, now) }
                        requested++
                    }
                }

                if (requested > 0) {
                    debugLine("requestMissingPics", "Requested $requested missing profile picture(s)")
                }
            } catch (e: Exception) {
                debugLine("requestMissingPics", "Error: ${e.message}")
            }
        }
    }

    fun fetchMissingPublicKeys() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val peers = repository.getActiveNonGroupPeers()
                val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                var fetched = 0
                for (peer in peers) {
                    if (!peer.publicKey.isNullOrEmpty()) continue
                    try {
                        val doc = db.collection("users").document(peer.userId).get().await()
                        val publicKey = doc.getString("publicKey")
                        if (!publicKey.isNullOrEmpty()) {
                            com.bolimot.mindtheclub.functions.getPeerDao(App.context())
                                .updatePeerPublicKey(peer.userId, publicKey)
                            com.bolimot.mindtheclub.transport.PeerIdentityResolver.markStale()
                            fetched++
                            debugLine("fetchMissingKeys", "publicKey stored for ${peer.userId}")
                        }
                    } catch (e: Exception) {
                        debugLine("fetchMissingKeys", "Error for ${peer.userId}: ${e.message}")
                    }
                }
                if (fetched > 0) debugLine("fetchMissingKeys", "Fetched $fetched missing publicKey(s)")
            } catch (e: Exception) {
                debugLine("fetchMissingKeys", "Error: ${e.message}")
            }
        }
    }

    suspend fun getGroupPeersWithoutPicture(): List<Peer> = withContext(Dispatchers.IO) {
        repository.getGroupPeersWithoutPicture()
    }

    suspend fun updatePeerPicture(userId: String, picture: String?) {
        withContext(Dispatchers.IO) {
            repository.updatePeerPicture(userId, picture)
        }
    }

    suspend fun updatePeerName(userId: String, name: String) {
        withContext(Dispatchers.IO) {
            repository.updatePeerName(userId, name)
        }
    }

    fun sendMyProfileToRemotePeer(remoteUserId: String) {
        debugLine("sendMyProfileToRemotePeer", "Start: sending my profile to $remoteUserId")
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    if(MySelf.userId().isNullOrEmpty()){
                        debugLine("sendMyProfileToRemotePeer", "I do not have a userId")
                        return@withContext
                    }

                    if(MySelf.name().isNullOrEmpty()){
                        debugLine("sendMyProfileToRemotePeer", "I do not have a name")
                        return@withContext
                    }

                    val myMac = com.bolimot.mindtheclub.transport.BluetoothMac.getMyMac()
                    debugLine("sendMyProfileToRemotePeer", "My bluetoothMac=${myMac ?: "null"}")
                    val peerObject = Peer(
                        uid = 0,
                        userId = MySelf.userId()!!,
                        token = "",
                        name = MySelf.name()!!,
                        bio = MySelf.bio(),
                        picture = null,
                        status = Contact.NEW,
                        privateId = MySelf.privateId()!!,
                        bluetoothMac = myMac
                    )

                    val myProfileString = Json.encodeToString(peerObject)
                    val messageId = guid()
                    val fileName = "myProfilePicToSend.jpg"
                    val myProfilePicUri = saveBitmapFromUri(MySelf.pictureUri()?.toUri(), fileName, 100)
                    val myProfilePicString = myProfilePicUri?.toString() ?: NO_PICTURE

                    myProfileString.let {
                        val myUserId = MySelf.userId()
                        myUserId?.let {
                            val message = Message(
                                uid = 0,
                                fromUserId = myUserId,
                                toUserId = remoteUserId,
                                messageId = messageId,
                                replyId = "",
                                groupId = "",
                                groupSize = 0,
                                text = myProfileString,
                                textAttached = null,
                                nameAttached = null,
                                uri = myProfilePicString,
                                type = "profile",
                                subType = null,
                                date = System.currentTimeMillis(),
                                status = "",
                                null
                            )

                            sendProfile(message, this)
                            debugLine("sendMyProfileToRemotePeer", "Profile sent to $remoteUserId (messageId=$messageId)")
                        }
                    }
                } catch (ex: Exception) {
                    debugLine("sendMyProfileToRemotePeer", "Cannot send my profile to $remoteUserId: ${ex.message}")
                }
            }
        }
    }
}
package com.bolimot.mindtheclub.functions

import android.content.Context
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.sending.notifyRemotePeer
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.Notify
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/*
 * Leaving and deleting a group, shared by the group screen (GroupMembersActivity) and the
 * contact list toolbar (AppTab). Moved here unchanged from GroupMembersActivity. What each caller
 * does afterwards (closing its screen, the open chat, clearing a selection) stays with the caller.
 */

private const val TAG = "GroupActions"

/** True when this user is an admin of [groupId]. False on any error, including offline. */
suspend fun isGroupAdmin(groupId: String): Boolean = withContext(Dispatchers.IO) {
    try {
        val currentUserId = MySelf.userId() ?: return@withContext false
        val db = FirebaseFirestore.getInstance()
        val snapshot = db.collection("groups").document(groupId).get().await()
        if (!snapshot.exists()) return@withContext false

        @Suppress("UNCHECKED_CAST")
        val members = snapshot.get("members") as? Map<String, String>
            ?: return@withContext false
        members[currentUserId] == "admin"
    } catch (e: Exception) {
        debugLine(TAG, "Admin check error: ${e.message}")
        false
    }
}

fun showLeaveGroupDialog(context: Context, onLeave: () -> Unit) {
    com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
        .setTitle(context.getString(R.string.leave_group))
        .setMessage(context.getString(R.string.leave_group_alert))
        .setPositiveButton(context.getString(R.string.leave)) { _, _ -> onLeave() }
        .setNegativeButton(context.getString(R.string.cancel), null)
        .show()
}

fun showDeleteGroupDialog(context: Context, onDelete: () -> Unit) {
    com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
        .setTitle(context.getString(R.string.delete_group))
        .setMessage(context.getString(R.string.delete_group_confirm))
        .setPositiveButton(context.getString(R.string.delete)) { _, _ -> onDelete() }
        .setNegativeButton(context.getString(R.string.cancel), null)
        .show()
}

/** Removes this user from [groupId] on Firestore, then the local group and its messages. Throws on failure. */
suspend fun leaveGroupNow(context: Context, groupId: String) = withContext(Dispatchers.IO) {
    val myUserId = MySelf.userId() ?: return@withContext
    val db = FirebaseFirestore.getInstance()
    val groupRef = db.collection("groups").document(groupId)

    groupRef.update("members.$myUserId", com.google.firebase.firestore.FieldValue.delete()).await()
    debugLine(TAG, "Removed myself from group $groupId in Firestore")

    val messageRepository = getMessageRepository(context.applicationContext)
    messageRepository.deleteRemotePeerMessages(groupId)

    val peerViewModel = getPeerViewModel()
    val peer = peerViewModel.getPeer(groupId)
    if (peer != null) {
        peerViewModel.deletePeer(peer)
    }

    debugLine(TAG, "Local group data cleaned up after leaving")
}

/**
 * Tells every other member, deletes [groupId] and its picture, then the local group and its
 * messages. False when the group was already gone from Firestore (nothing else is done then).
 * Throws on failure.
 */
suspend fun deleteGroupNow(context: Context, groupId: String): Boolean = withContext(Dispatchers.IO) {
    val groupRef = FirebaseFirestore.getInstance()
        .collection("groups").document(groupId)
    val snapshot = groupRef.get().await()

    if (!snapshot.exists()) {
        debugLine(TAG, "Group already deleted")
        return@withContext false
    }

    @Suppress("UNCHECKED_CAST")
    val membersMap = snapshot.get("members") as? Map<String, String> ?: emptyMap()
    val myUserId = MySelf.userId() ?: return@withContext false
    val groupName = snapshot.getString("name") ?: ""

    membersMap.keys.filter { it != myUserId }.forEach { userId ->
        notifyRemotePeer(userId, groupId, Notify.GROUP_REMOVED, groupName)
    }

    groupRef.delete().await()
    debugLine(TAG, "Group $groupId deleted from Firestore")

    try {
        FirebaseStorage.getInstance().reference
            .child("group_pictures/${groupId}.jpg")
            .delete()
            .await()
        debugLine(TAG, "Group picture deleted from Storage")
    } catch (e: Exception) {
        debugLine(TAG, "Group picture cleanup (may not exist): ${e.message}")
    }

    val messageRepository = getMessageRepository(context.applicationContext)
    messageRepository.deleteRemotePeerMessages(groupId)

    val peerViewModel = getPeerViewModel()
    val peer = peerViewModel.getPeer(groupId)
    if (peer != null) {
        peerViewModel.deletePeer(peer)
    }

    debugLine(TAG, "Local group data cleaned up")
    true
}

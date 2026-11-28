package com.bolimot.mindtheclub.desktop.engine

import com.bolimot.mindtheclub.contactAcquisition.clearAcquisitionStatus
import com.bolimot.mindtheclub.desktop.media.MediaFiles
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getMessageDao
import com.bolimot.mindtheclub.functions.getMessageRepository
import com.bolimot.mindtheclub.functions.getPeerDao
import com.bolimot.mindtheclub.functions.guid
import com.bolimot.mindtheclub.functions.saveNewGroupAsPeer
import com.bolimot.mindtheclub.sending.notifyRemotePeer
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.Notify
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

/**
 * Creating and running a group from the PC: what the App does in PeersFragment.createNewGroup,
 * GroupMembersFragment (name, picture, add, promote, remove) and functions/groupActions.kt
 * (admin check, leave, delete). Those App files are screens and cannot be copied; their bodies
 * are here, statement for statement, with the PC's database calls in place of the view models.
 *
 * The group document is groups/<groupId> on Firestore, through the PC's stand-in of the SDK;
 * its picture is group_pictures/<groupId>.jpg on Firebase Storage, through Storage's REST API.
 * Both are allowed to a client with no credentials by the shared rules (C:\mtcShared,
 * firestore.rules and storage.rules), as they are to a phone: nothing new on the server.
 */
object Groups {

    private const val TAG = "Groups"

    /** PeersFragment.createNewGroup and GroupMembersFragment.addMembers: 63 members at most. */
    const val MAX_MEMBERS = 63

    private const val STORAGE = "https://firebasestorage.googleapis.com/v0/b/mindtheclub-new.firebasestorage.app/o"

    /** resizeBitmap's default and GroupMembersFragment's JPEG quality. */
    private const val PICTURE_SIDE = 200
    private const val PICTURE_QUALITY = 0.8f

    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    /** groups/<groupId> as the group screen reads it: name, picture, roles, founder. */
    class Info(
        val groupId: String,
        val name: String,
        val pictureUrl: String?,
        /** userId to "admin" or "member", this user included. */
        val roles: Map<String, String>,
        val founderUserId: String?
    ) {
        fun isAdmin(userId: String?): Boolean = userId != null && roles[userId] == "admin"
    }

    /** Thrown when an action is refused before anything is written; the message is for the user. */
    class Refused(message: String) : Exception(message)

    /** The group document, or null when it no longer exists. Throws when Firestore cannot be read. */
    suspend fun info(groupId: String): Info? = withContext(Dispatchers.IO) {
        val snapshot = FirebaseFirestore.getInstance().collection("groups").document(groupId).get().await()
        if (!snapshot.exists()) return@withContext null
        @Suppress("UNCHECKED_CAST")
        val members = snapshot.get("members") as? Map<String, Any?> ?: emptyMap()
        Info(
            groupId = groupId,
            name = snapshot.getString("name") ?: "",
            pictureUrl = snapshot.getString("picture")?.takeIf { it.isNotEmpty() },
            roles = members.mapValues { it.value?.toString() ?: "member" },
            founderUserId = snapshot.getString("createdBy")
        )
    }

    /** groupActions.isGroupAdmin: true when this user is an admin of [groupId]. False on any error. */
    suspend fun isGroupAdmin(groupId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val currentUserId = MySelf.userId() ?: return@withContext false
            val snapshot = FirebaseFirestore.getInstance().collection("groups").document(groupId).get().await()
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

    /**
     * PeersFragment.createNewGroup: the group document with this user as admin and the chosen
     * contacts as members, the group in the contact list, an invitation to each member.
     * Returns the new group's id. Throws Refused past the member limit.
     */
    suspend fun create(selectedIds: List<String>, groupName: String): String = withContext(Dispatchers.IO) {
        val totalMembers = selectedIds.size + 1 // including self (admin)
        if (totalMembers > MAX_MEMBERS) throw Refused(memberLimitText())

        val gId = "group${guid()}"
        val currentUserId = MySelf.userId() ?: throw Refused("No identity")
        val membersMap = selectedIds.associateWith { "member" }.toMutableMap()
        membersMap[currentUserId] = "admin"

        val groupData = hashMapOf(
            "groupId" to gId,
            "createdBy" to currentUserId,
            "createdAt" to FieldValue.serverTimestamp(),
            "members" to membersMap,
            "name" to groupName
        )

        FirebaseFirestore.getInstance().collection("groups").document(gId).set(groupData).await()
        debugLine("createNewGroup", "Success: Structured Group $gId created.")

        if (saveNewGroupAsPeer(gId, groupName)) {
            selectedIds.forEach { userId -> notifyRemotePeer(userId, gId, Notify.GROUP, groupName) }
        }
        gId
    }

    /** GroupMembersFragment.saveGroupName. */
    suspend fun rename(groupId: String, newName: String) = withContext(Dispatchers.IO) {
        FirebaseFirestore.getInstance().collection("groups").document(groupId).update("name", newName).await()
        getPeerDao(App.context()).updatePeerName(groupId, newName)
        debugLine("GroupMembers", "Group name updated to: $newName")
    }

    /**
     * GroupMembersFragment.uploadGroupPicture: [source] at most 200 on its long side, JPEG 80,
     * to group_pictures/<groupId>.jpg, and its download address into the group document and the
     * group's row. Returns the address.
     */
    suspend fun setPicture(groupId: String, source: File): String = withContext(Dispatchers.IO) {
        val resized = MediaFiles.keepResized(source, PICTURE_SIDE, PICTURE_QUALITY)
        val data = resized.readBytes()

        val path = "group_pictures/$groupId.jpg"
        val request = HttpRequest.newBuilder(URI.create("$STORAGE?name=" + URLEncoder.encode(path, Charsets.UTF_8)))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "image/jpeg")
            .POST(HttpRequest.BodyPublishers.ofByteArray(data))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) throw IllegalStateException("Storage answered ${response.statusCode()}")
        val token = Regex("\"downloadTokens\"\\s*:\\s*\"([^\",]+)").find(response.body())?.groupValues?.get(1)
            ?: throw IllegalStateException("Storage gave no download token")
        val downloadUrl = "$STORAGE/" + encodePath(path) + "?alt=media&token=$token"
        debugLine("GroupMembers", "Group picture uploaded")

        FirebaseFirestore.getInstance().collection("groups").document(groupId).update("picture", downloadUrl).await()
        getPeerDao(App.context()).updatePeerPicture(groupId, downloadUrl)
        debugLine("GroupMembers", "Group picture URL saved to Firestore and local DB")
        downloadUrl
    }

    /** GroupMembersFragment.addMembers: each new member is told with an invitation. */
    suspend fun addMembers(groupId: String, groupName: String, currentCount: Int, userIds: List<String>) =
        withContext(Dispatchers.IO) {
            if (currentCount + userIds.size > MAX_MEMBERS) throw Refused(memberLimitText())
            val updates = hashMapOf<String, Any?>()
            userIds.forEach { userId -> updates["members.$userId"] = "member" }
            FirebaseFirestore.getInstance().collection("groups").document(groupId).update(updates).await()
            debugLine("GroupMembers", "Added ${userIds.size} members to group $groupId")
            userIds.forEach { userId -> notifyRemotePeer(userId, groupId, Notify.GROUP, groupName) }
        }

    /** GroupMembersFragment.promoteMembers. Throws Refused when every one is already an admin. */
    suspend fun promote(info: Info, userIds: List<String>) = withContext(Dispatchers.IO) {
        val toPromote = userIds.filter { info.roles[it] != "admin" }
        if (toPromote.isEmpty()) throw Refused(ALREADY_ADMIN)
        val updates = hashMapOf<String, Any?>()
        toPromote.forEach { userId -> updates["members.$userId"] = "admin" }
        FirebaseFirestore.getInstance().collection("groups").document(info.groupId).update(updates).await()
        debugLine("GroupMembers", "Promoted ${toPromote.size} member(s) to admin in group ${info.groupId}")
    }

    /**
     * GroupMembersFragment.removeMembers: the founder is never removable, by anyone. Returns
     * true when the founder was among [userIds] and was left in (the screen says so).
     */
    suspend fun remove(info: Info, userIds: List<String>): Boolean = withContext(Dispatchers.IO) {
        val founder = info.founderUserId
        val removable = if (founder != null) userIds.filter { it != founder } else userIds
        val founderKept = removable.size != userIds.size
        if (removable.isEmpty()) return@withContext founderKept

        val updates = hashMapOf<String, Any?>()
        removable.forEach { userId -> updates["members.$userId"] = FieldValue.delete() }
        FirebaseFirestore.getInstance().collection("groups").document(info.groupId).update(updates).await()
        debugLine("GroupMembers", "Removed ${removable.size} members from group ${info.groupId}")

        // removable, not userIds: a filtered-out founder was never removed and must not be told.
        removable.forEach { userId -> notifyRemotePeer(userId, info.groupId, Notify.GROUP_REMOVED, info.name) }
        founderKept
    }

    /** groupActions.leaveGroupNow: off the group document, then the group and its messages here. */
    suspend fun leave(groupId: String) = withContext(Dispatchers.IO) {
        val myUserId = MySelf.userId() ?: return@withContext
        FirebaseFirestore.getInstance().collection("groups").document(groupId)
            .update("members.$myUserId", FieldValue.delete()).await()
        debugLine("GroupActions", "Removed myself from group $groupId in Firestore")
        removeLocally(groupId)
        debugLine("GroupActions", "Local group data cleaned up after leaving")
    }

    /**
     * groupActions.deleteGroupNow: every other member is told, the document and the picture go,
     * then the group and its messages here. False when the group was already gone.
     */
    suspend fun delete(groupId: String): Boolean = withContext(Dispatchers.IO) {
        val groupRef = FirebaseFirestore.getInstance().collection("groups").document(groupId)
        val snapshot = groupRef.get().await()
        if (!snapshot.exists()) {
            debugLine("GroupActions", "Group already deleted")
            return@withContext false
        }

        @Suppress("UNCHECKED_CAST")
        val membersMap = snapshot.get("members") as? Map<String, Any?> ?: emptyMap()
        val myUserId = MySelf.userId() ?: return@withContext false
        val groupName = snapshot.getString("name") ?: ""

        membersMap.keys.filter { it != myUserId }.forEach { userId ->
            notifyRemotePeer(userId, groupId, Notify.GROUP_REMOVED, groupName)
        }

        groupRef.delete().await()
        debugLine("GroupActions", "Group $groupId deleted from Firestore")

        try {
            val request = HttpRequest.newBuilder(URI.create("$STORAGE/" + encodePath("group_pictures/$groupId.jpg")))
                .timeout(Duration.ofSeconds(30))
                .DELETE()
                .build()
            val code = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
            debugLine("GroupActions", "Group picture cleanup: $code")
        } catch (e: Exception) {
            debugLine("GroupActions", "Group picture cleanup (may not exist): ${e.message}")
        }

        removeLocally(groupId)
        debugLine("GroupActions", "Local group data cleaned up")
        true
    }

    /**
     * The group's picture as a file on this PC, to be drawn: downloaded once from its address
     * and kept under a name made from the address, so a new picture is a new file.
     */
    suspend fun pictureFile(url: String?): File? = withContext(Dispatchers.IO) {
        if (url.isNullOrEmpty() || !url.startsWith("https://")) return@withContext null
        val dir = File(App.context().filesDir, "group_pictures").apply { mkdirs() }
        val name = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) } + ".jpg"
        val file = File(dir, name)
        if (file.isFile && file.length() > 0) return@withContext file
        try {
            val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() != 200) return@withContext null
            val temp = File(dir, "$name.tmp")
            temp.writeBytes(response.body())
            temp.renameTo(file)
            file.takeIf { it.isFile }
        } catch (e: Exception) {
            debugLine(TAG, "Group picture not downloaded: ${e.message}")
            null
        }
    }

    /** The group and its messages leave this PC (deleteRemotePeerMessages and deletePeer). */
    private suspend fun removeLocally(groupId: String) {
        val context = App.context()
        getMessageRepository(context).deleteMessagesAndFiles(getMessageDao(context).getMessagesByRemotePeer(groupId))
        val peerDao = getPeerDao(context)
        peerDao.getPeer(groupId)?.let { peerDao.delete(it) }
        clearAcquisitionStatus(groupId, context)
    }

    /** A path in the bucket as Storage's REST API names an object: one segment, "/" encoded. */
    private fun encodePath(path: String): String = URLEncoder.encode(path, Charsets.UTF_8).replace("+", "%20")

    /** R.string.group_member_limit_exceeded with 63. */
    private fun memberLimitText() = "Groups cannot exceed $MAX_MEMBERS members."

    /** R.string.already_admin. */
    const val ALREADY_ADMIN = "Already an admin"
}

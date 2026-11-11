package com.bolimot.mindtheclub.desktop.backup

import com.bolimot.mindtheclub.backup.BackupData
import com.bolimot.mindtheclub.database.database.DatabaseProvider
import com.bolimot.mindtheclub.desktop.crypto.IdentityKeys
import com.bolimot.mindtheclub.desktop.media.MediaFiles
import com.bolimot.mindtheclub.desktop.storage.KeyVault
import com.bolimot.mindtheclub.desktop.storage.Prefs
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.util.Base64

/**
 * Writes a backup of this PC in the phone's own format, so the phone can restore it with the
 * restore it already has (C:\mtc, backup/BackupManager.kt restoreBackup). This is the way back:
 * the identity and everything exchanged on the PC return to the phone.
 *
 * Mirror of BackupManager.createBackup. Same BackupData (the class is the App's own file,
 * copied), same JSON, same envelope.
 *
 * WHAT THE PHONE DOES WITH IT, which is not what the PC did with the phone's:
 * the phone's restore MERGES. A contact, a message or a club is inserted only if it is not
 * there already, and nothing is ever updated or removed. So after the way back the phone has
 * every message sent or received on the PC, but:
 *   - a message the phone already had keeps the status it had there, even if on the PC it
 *     went from sent to delivered or to seen;
 *   - what was deleted on the PC is still on the phone.
 * Closing that gap needs the phone to REPLACE, which is the direct handover, not this file.
 *
 * Not carried, because the phone's format has no place for them: the pictures exchanged on the
 * PC (their names travel, their bytes do not: on the phone those bubbles have no picture), the
 * Inbox (content still arriving), the batch tables and the recovery ladders. A message still in flight when the PC
 * hands back is recovered by the ordinary means: its sender announces it again and the phone
 * asks for it.
 */
object BackupExporter {

    // The same configuration the phone encodes and decodes with.
    private val json = Json { ignoreUnknownKeys = true }

    class Result(
        val fingerprint: String,
        val peers: Int,
        val messages: Int,
        val reactions: Int,
        val sizeBytes: Long
    )

    class Refused(message: String) : Exception(message)

    suspend fun write(destination: File, password: CharArray): Result {
        val keyset = KeyVault.load() ?: throw Refused("Su questo PC non c'e' un'identita' da esportare.")
        val identity = IdentityKeys.fromBackupKeyset(keyset)

        val profile = Prefs.of("default")
        val userId = profile.getString("myUserId")
            ?: throw Refused("Il profilo salvato non ha un userId.")
        val carried = Prefs.of("desktop_carried")
        val mediaNamesPrefs = Prefs.of("desktop_media_names")

        val db = DatabaseProvider.provideDatabase()
        val peers = db.peerDao().getAllPeers()
        val messages = db.messageDao().getAllMessages()
        val reactions = db.reactionDao().getAllReactions()
        val blockedUsers = db.blockedUserDao().getAll()
        val clubs = db.clubDao().getAllClubs()

        // Pictures travel as bytes, as in the phone's own backup: a path means nothing on
        // another device.
        val peerPictures = HashMap<String, String>()
        for (peer in peers) {
            readToBase64(peer.picture)?.let { peerPictures[peer.userId] = it }
        }

        // The names of the media files, exactly as the phone gave them: the files themselves
        // never left the phone, and these are what lets it find them again.
        val mediaFileNames = HashMap<String, String>()
        for (messageId in mediaNamesPrefs.keys()) {
            mediaNamesPrefs.getString(messageId)?.let { mediaFileNames[messageId] = it }
        }
        // The pictures exchanged on this PC, by the same rule the phone applies to its own
        // (BackupManager.createBackup): the name of the file, never its bytes. The phone looks
        // for a file of that name in its public folders and, not finding it, leaves the bubble
        // pointing at this PC's file, where it is found again when the identity comes back here.
        for (message in messages) {
            val file = MediaFiles.fileOfUri(message.uri) ?: continue
            if (MediaFiles.isKept(file)) mediaFileNames[message.messageId] = file.name
        }

        val backup = BackupData(
            userId = userId,
            privateId = profile.getString("myPrivateId"),
            name = profile.getString("myName"),
            bio = profile.getString("myBio"),
            pictureUri = profile.getString("myPicture"),
            pictureMiniUri = profile.getString("myPictureMini"),
            // A PC has no FCM token. The phone does not read this field on restore.
            fcmToken = null,
            peers = peers,
            messages = messages,
            reactions = reactions,
            blockedUsers = blockedUsers,
            clubs = clubs,
            selfPictureBase64 = readToBase64(profile.getString("myPicture")),
            selfPictureMiniBase64 = readToBase64(profile.getString("myPictureMini")),
            peerPictures = peerPictures,
            identityKeyset = keyset,
            mediaFileNames = mediaFileNames,
            trialStartedAt = carried.getLong("trialStartedAt"),
            licenseCode = carried.getString("licenseCode"),
        )

        val clear = json.encodeToString(BackupData.serializer(), backup).toByteArray(Charsets.UTF_8)
        val sealed = BackupEnvelope.seal(clear, password)

        // Read back before it is trusted: a file that does not open is worse than no file,
        // because the PC is about to give the identity up on the strength of it.
        val reopened = BackupEnvelope.open(sealed, password)
        if (reopened == null || !reopened.contentEquals(clear)) {
            throw Refused("Il backup appena creato non si riapre: non scritto.")
        }

        destination.parentFile?.mkdirs()
        destination.writeBytes(sealed)

        return Result(
            fingerprint = identity.fingerprint,
            peers = peers.size,
            messages = messages.size,
            reactions = reactions.size,
            sizeBytes = destination.length()
        )
    }

    /** The bytes of a picture kept on this PC, as the phone encodes them, or null. */
    private fun readToBase64(uri: String?): String? {
        if (uri.isNullOrEmpty() || !uri.startsWith("file:")) return null
        return try {
            val file = File(URI(uri))
            if (!file.isFile) null else Base64.getEncoder().encodeToString(file.readBytes())
        } catch (e: Exception) {
            null
        }
    }
}

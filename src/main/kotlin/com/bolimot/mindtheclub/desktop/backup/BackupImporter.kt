package com.bolimot.mindtheclub.desktop.backup

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.bolimot.mindtheclub.backup.BackupData
import com.bolimot.mindtheclub.database.database.DatabaseProvider
import com.bolimot.mindtheclub.desktop.crypto.IdentityKeys
import com.bolimot.mindtheclub.desktop.storage.DataDir
import com.bolimot.mindtheclub.desktop.storage.KeyVault
import com.bolimot.mindtheclub.desktop.storage.Prefs
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64

/**
 * Loads a phone backup onto this PC, REPLACING whatever is here.
 *
 * The phone's own restore (C:\mtc, backup/BackupManager.kt restoreBackup) MERGES: it inserts
 * what is missing and never updates or removes. That is right for a phone getting its history
 * back. Here the rule is different, and it is what makes moving between phone and PC simple:
 * only one of the two is ever active, so the one handing over holds the whole truth and the one
 * receiving takes it whole. No merge, no conflict.
 *
 * What a backup does not carry, and so is not here after a load: the media files themselves
 * (only their names travel, BackupData.mediaFileNames), and everything still in flight on the
 * phone, the Inbox and Outbox rows, the batch tables and the recovery ladders. Bringing those
 * across is the job of the direct handover, not of the backup file.
 */
object BackupImporter {

    private val json = Json { ignoreUnknownKeys = true }

    class Result(
        val name: String,
        val userId: String,
        val fingerprint: String,
        val peers: Int,
        val messages: Int,
        val reactions: Int,
        val blocked: Int,
        val clubs: Int
    )

    class Refused(message: String) : Exception(message)

    /**
     * Everything is checked BEFORE anything on disk is touched: a wrong password, a damaged
     * file or a backup without an identity leaves the PC exactly as it was. Same order of care
     * as the phone, which imports the keyset before it overwrites its preferences.
     */
    suspend fun loadReplacing(file: File, password: CharArray): Result {
        val clear = BackupEnvelope.open(file.readBytes(), password)
            ?: throw Refused("Il backup non si apre: password sbagliata, oppure il file non e' un backup di MindTheClub.")
        val backup = try {
            json.decodeFromString(BackupData.serializer(), clear.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw Refused("Il contenuto del backup non e' leggibile: ${e.message}")
        }
        if (backup.userId.isEmpty()) throw Refused("Il backup non contiene un userId.")
        val keyset = backup.identityKeyset
            ?: throw Refused("Il backup non contiene la chiave di identita' (fatto da una versione vecchia della App).")
        val identity = try {
            IdentityKeys.fromBackupKeyset(keyset)
        } catch (e: Exception) {
            throw Refused("La chiave di identita' del backup non e' leggibile: ${e.message}")
        }

        // Whose content is on this PC now, read before it is removed (see the media folder below).
        val previousUserId = Prefs.of("default").getString("myUserId")

        // From here on the PC's previous content is gone.
        if (!DatabaseProvider.deleteFiles()) {
            throw Refused("Il database e' in uso da un'altra copia del programma: chiudila e riprova.")
        }
        // Messages the PC was still sending belong to the content being replaced.
        com.bolimot.mindtheclub.desktop.storage.BatchDb.deleteFile()
        Prefs.deleteAll()
        // The engine's stand-in SharedPreferences and the key in memory point at what was just
        // removed: make them read again.
        android.content.DesktopSharedPreferences.forgetAll()
        com.bolimot.mindtheclub.crypto.KeyManager.forget()
        DataDir.clear(DataDir.pictures())
        // The pictures exchanged on this PC stay when the SAME identity comes back: the phone
        // never had their bytes, and the messages in its backup still point at these files.
        // Another identity's pictures are removed with the rest of its content.
        if (previousUserId != null && previousUserId != backup.userId) {
            DataDir.clear(DataDir.media())
        }

        // The key first: without it the rest is somebody's data and nobody's identity.
        KeyVault.store(keyset)

        // The profile, under the App's own names (tools/objects.kt, MySelf).
        val profile = Prefs.of("default")
        profile.putString("myUserId", backup.userId)
        backup.privateId?.let { profile.putString("myPrivateId", it) }
        backup.name?.let { profile.putString("myName", it) }
        backup.bio?.let { profile.putString("myBio", it) }
        savePicture(backup.selfPictureBase64, "restored_pic.jpg")?.let { profile.putString("myPicture", it) }
        savePicture(backup.selfPictureMiniBase64, "restored_mini_pic.jpg")?.let { profile.putString("myPictureMini", it) }

        // What belongs to the identity but has no engine behind it on the PC yet. Kept so that
        // nothing the phone handed over is dropped: the trial clock and the licence code go
        // back to the phone with the identity, and the media names are what will let a chat
        // bubble find its file once media travel too.
        val carried = Prefs.of("desktop_carried")
        backup.trialStartedAt?.let { carried.putLong("trialStartedAt", it) }
        backup.licenseCode?.let { carried.putString("licenseCode", it) }
        val mediaNames = Prefs.of("desktop_media_names")
        for ((messageId, names) in backup.mediaFileNames) mediaNames.putString(messageId, names)

        // The data. One transaction: a load is all there or not there.
        val db = DatabaseProvider.provideDatabase()
        var reactions = 0
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                for (peer in backup.peers) {
                    val picture = savePicture(backup.peerPictures[peer.userId], "peer_${peer.userId}.jpg")
                    db.peerDao().insert(if (picture != null) peer.copy(uid = 0, picture = picture) else peer.copy(uid = 0))
                }
                for (message in backup.messages) {
                    db.messageDao().insert(message.copy(uid = 0))
                }
                for (reaction in backup.reactions) {
                    // Rows from before reactions became per member carry no reactor and cannot
                    // be attributed: skipped, as the phone's restore does.
                    if (reaction.reactorUserId.isEmpty()) continue
                    db.reactionDao().insert(reaction.copy(uid = 0))
                    reactions++
                }
                for (blocked in backup.blockedUsers) db.blockedUserDao().insert(blocked.copy(uid = 0))
                for (club in backup.clubs) db.clubDao().insert(club.copy(uid = 0))
            }
        }

        // The language of the phone's status texts, read now, while every status in the
        // database is still the phone's own (see DesktopStrings).
        com.bolimot.mindtheclub.desktop.engine.DesktopStrings.detectFromPhoneData()

        // Recorded so a later start can tell whether what it finds is what was loaded.
        val loaded = Prefs.of("desktop_loaded")
        loaded.putString("fingerprint", identity.fingerprint)
        loaded.putLong("backupTimestamp", backup.timestamp)
        loaded.putLong("loadedAt", System.currentTimeMillis())
        loaded.putLong("peers", backup.peers.size.toLong())
        loaded.putLong("messages", backup.messages.size.toLong())

        return Result(
            name = backup.name ?: "",
            userId = backup.userId,
            fingerprint = identity.fingerprint,
            peers = backup.peers.size,
            messages = backup.messages.size,
            reactions = reactions,
            blocked = backup.blockedUsers.size,
            clubs = backup.clubs.size
        )
    }

    /** Writes a picture carried in the backup and returns its file URI, or null when absent. */
    private fun savePicture(base64: String?, fileName: String): String? {
        if (base64.isNullOrEmpty()) return null
        return try {
            // The phone encodes with android.util.Base64.NO_WRAP: standard alphabet, padded.
            val target = File(DataDir.pictures(), fileName.replace(Regex("[^A-Za-z0-9_.-]"), "_"))
            target.writeBytes(Base64.getDecoder().decode(base64))
            target.toURI().toString()
        } catch (e: Exception) {
            null
        }
    }
}

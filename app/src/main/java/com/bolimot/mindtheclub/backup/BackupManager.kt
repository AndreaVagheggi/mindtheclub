package com.bolimot.mindtheclub.backup

import android.content.Context
import android.net.Uri
import com.bolimot.mindtheclub.crypto.KeyManager
import com.bolimot.mindtheclub.database.database.DatabaseProvider
import com.bolimot.mindtheclub.functions.InstallationIdentity
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.displayNameOfMediaUri
import com.bolimot.mindtheclub.functions.findPublicMediaByName
import com.bolimot.mindtheclub.functions.getPreference
import com.bolimot.mindtheclub.functions.setPreference
import com.bolimot.mindtheclub.functions.splitToList
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.tools.Type
import kotlinx.serialization.json.Json
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import androidx.core.net.toUri

object BackupManager {

    private const val TAG = "BackupManager"
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128
    private const val PBKDF2_ITERATIONS = 200_000
    private const val KEY_LENGTH = 256

    private const val AUTO_BACKUP_ENABLED_KEY = "autoBackupEnabled"

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun createBackup(
        context: Context,
        destinationUri: Uri,
        password: String,
        includeMessages: Boolean
    ): Boolean {
        return try {
            val db = DatabaseProvider.provideDatabase(context)

            val peers = db.peerDao().getAllPeers()
            val messages = if (includeMessages) db.messageDao().getAllMessages() else emptyList()
            val reactions = if (includeMessages) db.reactionDao().getAllReactions() else emptyList()
            val blockedUsers = db.blockedUserDao().getAll()
            val clubs = db.clubDao().getAllClubs()

            val selfPicBase64 = readUriToBase64(context, MySelf.pictureUri())
            val selfPicMiniBase64 = readUriToBase64(context, getPreference(MySelf.PICTURE_KEY_MINI, context))

            val peerPics = mutableMapOf<String, String>()
            for (peer in peers) {
                val b64 = readUriToBase64(context, peer.picture)
                if (b64 != null) {
                    peerPics[peer.userId] = b64
                }
            }

            // Names only, never bytes: see BackupData.mediaFileNames.
            val mediaNames = mutableMapOf<String, String>()
            for (message in messages) {
                val uriValue = message.uri
                if (uriValue.isEmpty()) continue
                val names = if (message.type == Type.MULTIPLE_IMAGES) {
                    splitToList(uriValue).map { displayNameOfMediaUri(context, it) ?: "" }
                } else {
                    listOf(displayNameOfMediaUri(context, uriValue) ?: "")
                }
                if (names.any { it.isNotEmpty() }) {
                    mediaNames[message.messageId] = names.joinToString(",")
                }
            }

            val backupData = BackupData(
                userId = MySelf.userId() ?: "",
                privateId = MySelf.privateId(),
                name = MySelf.name(),
                bio = MySelf.bio(),
                pictureUri = MySelf.pictureUri(),
                pictureMiniUri = getPreference(MySelf.PICTURE_KEY_MINI, context),
                fcmToken = MySelf.fcmTokenGet(),
                peers = peers,
                messages = messages,
                reactions = reactions,
                blockedUsers = blockedUsers,
                clubs = clubs,
                selfPictureBase64 = selfPicBase64,
                selfPictureMiniBase64 = selfPicMiniBase64,
                peerPictures = peerPics,
                identityKeyset = KeyManager.exportIdentityKeyset(),
                mediaFileNames = mediaNames,
            )

            val jsonBytes = json.encodeToString(BackupData.serializer(), backupData)
                .toByteArray(Charsets.UTF_8)

            val encrypted = encrypt(jsonBytes, password)

            context.contentResolver.openOutputStream(destinationUri)?.use { output ->
                output.write(encrypted)
            } ?: throw IllegalStateException("Cannot open output stream")

            val hasIdentity = backupData.identityKeyset != null
            debugLine(TAG, "Backup created: ${peers.size} peers, ${messages.size} messages, ${reactions.size} reactions, ${blockedUsers.size} blocked, identity=${if (hasIdentity) "YES (${KeyManager.getMyPublicKeyFingerprint()})" else "NO"}")
            true
        } catch (e: Exception) {
            debugLine(TAG, "Backup failed: ${e.message}")
            false
        }
    }

    suspend fun restoreBackup(
        context: Context,
        sourceUri: Uri,
        password: String
    ): String? {
        return try {
            val encryptedBytes = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                val buffer = ByteArrayOutputStream()
                input.copyTo(buffer)
                buffer.toByteArray()
            } ?: throw IllegalStateException("Cannot open input stream")

            val decryptedBytes = decrypt(encryptedBytes, password)
                ?: throw SecurityException("Decryption failed — wrong password?")

            val jsonString = decryptedBytes.toString(Charsets.UTF_8)
            val backupData = json.decodeFromString(BackupData.serializer(), jsonString)

            if (backupData.userId.isNotEmpty()) {
                setPreference(MySelf.USER_ID_KEY, backupData.userId, context)
            }
            backupData.privateId?.let { setPreference(MySelf.PRIVATE_ID_KEY, it, context) }
            backupData.name?.let { setPreference(MySelf.NAME_KEY, it, context) }
            backupData.bio?.let { setPreference("myBio", it, context) }

            // Identity first, data second. Restoring the keyset makes this phone
            // BE the old one. The Firestore side (publicKey, FCM token and the
            // installationId ownership marker) is claimed right after by
            // forceTokenSyncAfterRestore in the calling activity, which reads
            // the CURRENT Firestore token and presents it as ownership proof;
            // publishing from here with this install's own token would be
            // rejected by the cloud function's oldToken check. Backups from
            // older app versions carry no keyset: data restores as before.
            var identityRestored = false
            backupData.identityKeyset?.let { keyset ->
                identityRestored = KeyManager.importIdentityKeyset(keyset, context)
                if (identityRestored) {
                    // This phone owns the identity now: lift any earlier pause.
                    InstallationIdentity.clearDeactivated(context)
                    debugLine(TAG, "Identity keyset restored")
                } else {
                    debugLine(TAG, "Identity keyset restore FAILED, this install keeps its own identity")
                }
            }

            val restoredPicUri = saveBase64ToFile(context, backupData.selfPictureBase64, "restored_pic.jpg")
            if (restoredPicUri != null) {
                setPreference(MySelf.PICTURE_KEY, restoredPicUri, context)
            }
            val restoredPicMiniUri = saveBase64ToFile(context, backupData.selfPictureMiniBase64, "restored_mini_pic.jpg")
            if (restoredPicMiniUri != null) {
                setPreference(MySelf.PICTURE_KEY_MINI, restoredPicMiniUri, context)
            }
            val db = DatabaseProvider.provideDatabase(context)

            for (peer in backupData.peers) {
                try {
                    if (!db.peerDao().exist(peer.userId)) {
                        val peerPicB64 = backupData.peerPictures[peer.userId]
                        val restoredPeerPicUri = saveBase64ToFile(
                            context, peerPicB64, "peer_${peer.userId}.jpg"
                        )
                        val restoredPeer = if (restoredPeerPicUri != null) {
                            peer.copy(uid = 0, picture = restoredPeerPicUri)
                        } else {
                            peer.copy(uid = 0)
                        }
                        db.peerDao().insert(restoredPeer)
                    }
                } catch (e: Exception) {
                    debugLine(TAG, "Peer restore skip: ${e.message}")
                }
            }

            var mediaReattached = 0
            for (message in backupData.messages) {
                try {
                    if (!db.messageDao().messageExists(message.messageId)) {
                        // Re-attach the media before inserting: the uri from the
                        // old phone points at a MediaStore row that does not
                        // exist here, while the file itself is in the public
                        // folders under the same name (see mediaFileNames).
                        val rebuilt = reattachMedia(context, message, backupData.mediaFileNames)
                        if (rebuilt != message.uri) mediaReattached++
                        db.messageDao().insert(message.copy(uid = 0, uri = rebuilt))
                    }
                } catch (e: Exception) {
                    debugLine(TAG, "Message restore skip: ${e.message}")
                }
            }
            if (backupData.mediaFileNames.isNotEmpty()) {
                debugLine(TAG, "Media re-attached for $mediaReattached of ${backupData.mediaFileNames.size} media message(s)")
            }

            for (reaction in backupData.reactions) {
                try {
                    db.reactionDao().insert(reaction.copy(uid = 0))
                } catch (e: Exception) {
                    debugLine(TAG, "Reaction restore skip: ${e.message}")
                }
            }

            for (blocked in backupData.blockedUsers) {
                try {
                    db.blockedUserDao().insert(blocked.copy(uid = 0))
                } catch (e: Exception) {
                    debugLine(TAG, "BlockedUser restore skip: ${e.message}")
                }
            }

            for (club in backupData.clubs) {
                try {
                    if (!db.clubDao().exist(club.clubId)) {
                        db.clubDao().insert(club.copy(uid = 0))
                    }
                } catch (e: Exception) {
                    debugLine(TAG, "Club restore skip: ${e.message}")
                }
            }

            // The identity outcome is spelled out, not implied: a restore that
            // silently kept the phone's own identity is the failure that breaks
            // every contact, and it must be visible on screen without reading
            // a log. Three distinct states, never ambiguous.
            val identityNote = when {
                identityRestored -> "IDENTITY RESTORED (${KeyManager.getMyPublicKeyFingerprint()})"
                backupData.identityKeyset != null -> "IDENTITY RESTORE FAILED"
                else -> "NO IDENTITY IN BACKUP (made by an older version)"
            }
            val summary = "Restored: ${backupData.peers.size} contacts, " +
                    "${backupData.messages.size} messages\n$identityNote"

            debugLine(TAG, summary)
            summary
        } catch (e: SecurityException) {
            debugLine(TAG, "Restore failed: ${e.message}")
            null
        } catch (e: Exception) {
            debugLine(TAG, "Restore failed: ${e.message}")
            null
        }
    }

    fun isAutoBackupEnabled(context: Context): Boolean {
        return getPreference(AUTO_BACKUP_ENABLED_KEY, context) == "true"
    }

    fun setAutoBackupEnabled(context: Context, enabled: Boolean) {
        setPreference(AUTO_BACKUP_ENABLED_KEY, if (enabled) "true" else "false", context)
    }

    /**
     * Returns the uri [message] should carry on THIS device, looking each of its
     * media files up by name in the public folders.
     *
     * Falls back to the original uri whenever the name is unknown or the file did
     * not travel: a bubble that still points at nothing is no worse than before,
     * while a wrong rewrite would be. Same-phone restores keep working because
     * the lookup then finds the very same file.
     */
    private fun reattachMedia(
        context: Context,
        message: com.bolimot.mindtheclub.database.message.Message,
        mediaFileNames: Map<String, String>
    ): String {
        val original = message.uri
        if (original.isEmpty()) return original
        val stored = mediaFileNames[message.messageId] ?: return original

        return if (message.type == Type.MULTIPLE_IMAGES) {
            val uris = splitToList(original)
            val names = splitToList(stored)
            if (names.size != uris.size) return original
            uris.mapIndexed { i, u ->
                names[i].takeIf { it.isNotEmpty() }
                    ?.let { findPublicMediaByName(context, it)?.toString() } ?: u
            }.joinToString(",")
        } else {
            stored.takeIf { it.isNotEmpty() }
                ?.let { findPublicMediaByName(context, it)?.toString() } ?: original
        }
    }

    private fun readUriToBase64(context: Context, uriString: String?): String? {
        if (uriString.isNullOrEmpty()) return null
        return try {
            val uri = uriString.toUri()
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) Base64.encodeToString(bytes, Base64.NO_WRAP) else null
        } catch (e: Exception) {
            debugLine(TAG, "readUriToBase64 failed: ${e.message}")
            null
        }
    }

    private fun saveBase64ToFile(context: Context, base64: String?, filename: String): String? {
        if (base64.isNullOrEmpty()) return null
        return try {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            val deviceContext = context.createDeviceProtectedStorageContext()
            val file = File(deviceContext.filesDir, filename)
            FileOutputStream(file).use { it.write(bytes) }
            Uri.fromFile(file).toString()
        } catch (e: Exception) {
            debugLine(TAG, "saveBase64ToFile failed: ${e.message}")
            null
        }
    }

    private fun encrypt(data: ByteArray, password: String): ByteArray {
        val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }

        val key = deriveKey(password, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))

        val ciphertext = cipher.doFinal(data)

        return salt + iv + ciphertext
    }

    private fun decrypt(data: ByteArray, password: String): ByteArray? {
        return try {
            if (data.size < SALT_LENGTH + IV_LENGTH + 1) return null

            val salt = data.copyOfRange(0, SALT_LENGTH)
            val iv = data.copyOfRange(SALT_LENGTH, SALT_LENGTH + IV_LENGTH)
            val ciphertext = data.copyOfRange(SALT_LENGTH + IV_LENGTH, data.size)

            val key = deriveKey(password, salt)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))

            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            debugLine(TAG, "Decryption error: ${e.message}")
            null
        }
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH)
        val secret = factory.generateSecret(spec)
        return SecretKeySpec(secret.encoded, "AES")
    }
}


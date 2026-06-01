package com.bolimot.mindtheclub.works

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bolimot.mindtheclub.contactAcquisition.setAcquisitionStatus
import com.bolimot.mindtheclub.contactAcquisition.writeMySelfOnRemotePeerFirestore
import com.bolimot.mindtheclub.crypto.KeyManager
import com.bolimot.mindtheclub.database.peer.Peer
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getPeerDao
import com.bolimot.mindtheclub.functions.hasNetworkAvailable
import com.bolimot.mindtheclub.tools.AcquisitionStatus
import com.bolimot.mindtheclub.tools.Contact
import com.bolimot.mindtheclub.tools.Location
import com.bolimot.mindtheclub.tools.ProfileType
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class AcquireContactWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {

        if (!hasNetworkAvailable(applicationContext)) {
            debugLine("SendFcmWorker", "No internet connection at start. Retrying later.")
            return Result.retry()
        }

        val userId = inputData.getString("userId") ?: return Result.failure()
        val name = inputData.getString("name") ?: ""
        val bio = inputData.getString("bio") ?: ""
        val expectedFingerprint = inputData.getString("fingerprint")

        val tag = "AcquireContactWorker"
        debugLine(tag, "Starting background work for: $name")

        val peer = Peer(
            uid = 0,
            userId = userId,
            name = name,
            bio = bio,
            status = Contact.NEW,
            privateId = "",
            token = "",
            picture = null
        )

        try {
            try {
                getPeerDao(applicationContext).insert(peer)
                debugLine(tag, "Local peer ensured in database.")
            } catch (e: Exception) {
                debugLine(tag, "Peer insert skipped (likely already exists): ${e.message}")
            }

            val remoteSuccess = writeMySelfOnRemotePeerFirestore(userId)

            if (remoteSuccess) {
                debugLine(tag, "Remote write successful. Worker finished.")

                fetchAndStorePublicKey(userId, expectedFingerprint, tag)

                setAcquisitionStatus(userId,
                    Location.LOCAL,
                    ProfileType.REMOTE,
                    AcquisitionStatus.ACQUIRED,
                    applicationContext)

                return Result.success()
            } else {
                debugLine(tag, "Remote write failed. Scheduling retry.")
                return Result.retry()
            }

        } catch (e: Exception) {
            debugLine(tag, "Fatal error in worker: ${e.message}")
            return Result.failure()
        }
    }

    private suspend fun fetchAndStorePublicKey(
        userId: String,
        expectedFingerprint: String?,
        tag: String
    ) {
        try {
            val doc = FirebaseFirestore.getInstance()
                .collection("users").document(userId).get().await()
            val publicKey = doc.getString("publicKey")

            if (publicKey.isNullOrEmpty()) {
                debugLine(tag, "No publicKey published yet for $userId; skipping.")
                return
            }

            if (expectedFingerprint.isNullOrEmpty()) {
                debugLine(tag, "No out-of-band fingerprint; storing fetched key unverified.")
                getPeerDao(applicationContext).updatePeerPublicKey(userId, publicKey)
                com.bolimot.mindtheclub.transport.PeerIdentityResolver.markStale()
                return
            }

            val actualFingerprint = KeyManager.fingerprintOf(publicKey)
            if (actualFingerprint == expectedFingerprint) {
                getPeerDao(applicationContext).updatePeerPublicKey(userId, publicKey)
                com.bolimot.mindtheclub.transport.PeerIdentityResolver.markStale()
                debugLine(tag, "publicKey verified and stored for $userId.")
            } else {
                debugLine(tag, "Fingerprint MISMATCH for $userId. Expected=$expectedFingerprint Actual=$actualFingerprint. Not storing.")
            }
        } catch (e: Exception) {
            debugLine(tag, "fetchAndStorePublicKey failed for $userId: ${e.message}")
        }
    }
}
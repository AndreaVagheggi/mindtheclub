package com.bolimot.mindtheclub.works

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bolimot.mindtheclub.contactAcquisition.setAcquisitionStatus
import com.bolimot.mindtheclub.contactAcquisition.writeMySelfOnRemotePeerFirestore
import com.bolimot.mindtheclub.database.peer.Peer
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getPeerDao
import com.bolimot.mindtheclub.functions.hasNetworkAvailable
import com.bolimot.mindtheclub.sending.notifyRemotePeer
import com.bolimot.mindtheclub.tools.AcquisitionStatus
import com.bolimot.mindtheclub.tools.Contact
import com.bolimot.mindtheclub.tools.Location
import com.bolimot.mindtheclub.tools.Notify
import com.bolimot.mindtheclub.tools.ProfileType
import com.bolimot.mindtheclub.contactAcquisition.clearAcquisitionStatus

class AcquireContactWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {

        val userId = inputData.getString("userId") ?: return Result.failure()
        val name = inputData.getString("name") ?: ""
        val bio = inputData.getString("bio") ?: ""
        val expectedFingerprint = inputData.getString("fingerprint")

        val tag = "AcquireContactWorker"
        debugLine(tag, "Starting background work for: $name")

        clearAcquisitionStatus(userId, applicationContext)

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
            // The peer row is created ONLY on the first attempt, and before the network check:
            // every later attempt then starts from a certainty, that a missing row means the user
            // removed the pending contact. The old unconditional insert recreated it on every
            // retry, so a contact whose key never gets published came back a few seconds after
            // each manual removal, senza modo di levarselo.
            if (runAttemptCount == 0) {
                try {
                    getPeerDao(applicationContext).insert(peer)
                    debugLine(tag, "Local peer ensured in database.")
                } catch (e: Exception) {
                    debugLine(tag, "Peer insert skipped (likely already exists): ${e.message}")
                }
            } else if (!getPeerDao(applicationContext).exist(userId)) {
                debugLine(tag, "Pending contact removed by the user, aborting acquisition for $userId")
                return Result.failure()
            }

            if (!hasNetworkAvailable(applicationContext)) {
                debugLine(tag, "No internet connection at start. Retrying later.")
                return Result.retry()
            }

            // Verify and store the remote peer's public key FIRST:
            // writeMySelfOnRemotePeerFirestore needs the verified key to seal our own fingerprint
            // into the contact request payload.
            val keyVerified = com.bolimot.mindtheclub.crypto.KeyManager
                .fetchAndStorePublicKeyVerified(userId, expectedFingerprint, applicationContext)
            if (!keyVerified) {
                debugLine(tag, "Could not verify remote public key. Will retry.")
                return Result.retry()
            }

            val remoteSuccess = writeMySelfOnRemotePeerFirestore(userId)

            if (remoteSuccess) {
                debugLine(tag, "Remote write successful. Worker finished.")

                // Push nudge the inviter so the request is processed even with their app closed,
                // same wake-up principle as normal message delivery. Queued through WorkManager
                // (SendFcmWorker), so it survives doze and retries on failure.
                notifyRemotePeer(userId, "", Notify.CONTACT_REQUEST)

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
}
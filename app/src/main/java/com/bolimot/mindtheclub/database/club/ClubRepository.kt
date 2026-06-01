package com.bolimot.mindtheclub.database.club

import androidx.paging.PagingSource
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.tools.MySelf
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class ClubRepository(private val clubDao: ClubDao) {
    private var currentPagingSource: PagingSource<Int, Club>? = null
    private val mutex = Mutex()

    fun getLocalClubIds(): kotlinx.coroutines.flow.Flow<List<String>> {
        return clubDao.getLocalClubIds()
    }

    suspend fun getClub(clubId: String): Club? = clubDao.getClub(clubId)
    suspend fun getClubId(uid: Int): String = clubDao.getClubId(uid)

    suspend fun deleteClub(clubId: String): Boolean = withContext(Dispatchers.IO) {
        val db = FirebaseFirestore.getInstance()

        if (!checkOwnership(clubId)) {
            debugLine("deleteClub", "User is not owner")
            val myUserId = MySelf.userId()
            if (myUserId != null) {
                try {
                    val memberStorageRef = FirebaseStorage.getInstance().reference
                        .child("profile_pictures")
                        .child("${clubId}_${myUserId}.jpg")
                    memberStorageRef.delete().await()
                } catch (e: Exception) {
                    debugLine("deleteClub", "Cannot delete member picture: ${e.message}")
                }

                try {
                    db.collection("clubs")
                        .document(clubId)
                        .collection("members")
                        .document(myUserId)
                        .delete()
                        .await()
                } catch (e: Exception) {
                    debugLine("deleteClub", "Cannot remove member from Firestore: ${e.message}")
                }
            }
            deleteClubByClubId(clubId)
            return@withContext true
        }

        try {
            val storageRef = FirebaseStorage.getInstance().reference
                .child("profile_pictures")
                .child("${clubId}.jpg")
            storageRef.delete().await()
        } catch (e: Exception) {
            debugLine("deleteClub", "Cannot delete picture, Exception: ${e.message}")
        }

        val myUserId = MySelf.userId()
        if (myUserId != null) {
            try {
                val memberStorageRef = FirebaseStorage.getInstance().reference
                    .child("profile_pictures")
                    .child("${clubId}_${myUserId}.jpg")
                memberStorageRef.delete().await()
            } catch (e: Exception) {
                debugLine("deleteClub", "Cannot delete member picture: ${e.message}")
            }
        }

        try {
            val clubRef = db.collection("clubs").document(clubId)
            val membersSnapshot = clubRef.collection("members").get().await()

            val storageRef = FirebaseStorage.getInstance().reference.child("profile_pictures")

            for (doc in membersSnapshot.documents) {
                val memberId = doc.id
                try {
                    storageRef.child("${clubId}_${memberId}.jpg").delete().await()
                } catch (e: Exception) {
                    debugLine("deleteClub", "Cannot delete member picture: ${e.message}")
                }
            }

            val batch = db.batch()
            for (doc in membersSnapshot.documents) {
                batch.delete(doc.reference)
            }
            batch.delete(clubRef)
            batch.commit().await()

            deleteClubByClubId(clubId)

            return@withContext true
        } catch (e: Exception) {
            debugLine("deleteClub", "Exception: ${e.message}")
            deleteClubByClubId(clubId)

            return@withContext false
        }
    }

    suspend fun deleteClubByClubId(clubId: String) {
        clubDao.deleteClubByClubId(clubId)
        currentPagingSource?.invalidate()
    }

    suspend fun checkOwnership(clubId: String): Boolean {
        return clubDao.checkOwnership(clubId)
    }

    suspend fun addNewClub(club: Club): Boolean = withContext(Dispatchers.IO) {
        var success: Boolean

        if(club.clubId.isNullOrEmpty()){
            debugLine("addOrUpdateClub", "Club clubId is null or empty")
            return@withContext false
        }

        try {
            if (clubDao.exist(club.clubId)) {
                success = false
            } else {
                success = clubDao.insert(club) > 0
            }

            if (success) {
                currentPagingSource?.invalidate()
            } else {
                debugLine("addOrUpdateClub", "Failed to add or update club")
            }
            success
        } catch (ex: Exception){
            debugLine("addOrUpdateClub", "Exception: ${ex.message}")
            false
        }
    }
}



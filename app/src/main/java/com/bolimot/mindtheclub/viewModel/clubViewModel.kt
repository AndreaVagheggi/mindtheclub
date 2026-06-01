package com.bolimot.mindtheclub.viewModel

import android.app.Application
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bolimot.mindtheclub.database.club.Club
import com.bolimot.mindtheclub.database.club.ClubRepository
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.joinClub
import com.bolimot.mindtheclub.functions.saveBitmapFromUri
import com.bolimot.mindtheclub.tools.MySelf
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

class ClubViewModel(application: Application, private val repository: ClubRepository) : AndroidViewModel(application) {
    val localClubIds = repository.getLocalClubIds()

    suspend fun addClub(club: Club) {
        if(repository.addNewClub(club)) {
            debugLine("addClub", "Club added successfully")
            joinClub(club.clubId)
        } else {
            debugLine("addClub", "Club add failed")
        }
    }

    suspend fun checkOwnership(clubId: String): Boolean {
        return repository.checkOwnership(clubId)
    }


    suspend fun addNewClub(club: Club, name: String, description: String?, picture: String?): Boolean = withContext(Dispatchers.IO) {
        val myUserId = MySelf.userId()
        if(myUserId == null) {
            debugLine("addNewClub", "MySelf.userId() is null")
            return@withContext false
        }
        val myName = MySelf.name()
        val myBio = MySelf.bio()
        val myLocalPictureUri = MySelf.pictureUri()?.toUri()
        val fileName = "${System.currentTimeMillis()}_my_pic.jpg"
        val myLocalPicturePath = saveBitmapFromUri(myLocalPictureUri, fileName, 50, 200).toString()

        val localInsertSuccess = try {
            repository.addNewClub(club)
            true
        } catch (e: Exception) {
            debugLine("addNewClub", "Local insert failed: ${e.message}")
            false
        }

        if (!localInsertSuccess) return@withContext false

        var uploadedClubImageRef: StorageReference? = null
        var uploadedMemberImageRef: StorageReference? = null

        var finalClubPictureUrl: String? = null
        var finalMemberPictureUrl: String? = null

        try {
            val storageRef = FirebaseStorage.getInstance().reference.child("profile_pictures")

            if (!picture.isNullOrBlank()) {
                val uriToUpload: Uri? = if (picture.startsWith("content://")) {
                    picture.toUri()
                } else {
                    val file = File(picture)
                    if (file.exists()) Uri.fromFile(file) else null
                }

                if (uriToUpload != null) {
                    val ref = storageRef.child("${club.clubId}.jpg")
                    ref.putFile(uriToUpload).await()
                    finalClubPictureUrl = ref.downloadUrl.await().toString() + "&v=${System.currentTimeMillis()}"
                    uploadedClubImageRef = ref
                }
            }

            if (!myLocalPicturePath.isNullOrBlank()) {
                val myUriToUpload: Uri? = if (myLocalPicturePath.startsWith("content://")) {
                    myLocalPicturePath.toUri()
                } else {
                    val file = File(myLocalPicturePath)
                    if (file.exists()) Uri.fromFile(file) else null
                }

                if (myUriToUpload != null) {
                    val ref = storageRef.child("${club.clubId}_${myUserId}.jpg")
                    ref.putFile(myUriToUpload).await()

                    finalMemberPictureUrl = ref.downloadUrl.await().toString() + "&v=${System.currentTimeMillis()}"

                    uploadedMemberImageRef = ref
                }
            }

            val db = FirebaseFirestore.getInstance()
            val clubRef = db.collection("clubs").document(club.clubId)
            val memberRef = clubRef.collection("members").document(myUserId)

            val clubData = hashMapOf(
                "name" to name,
                "description" to description,
                "picture" to finalClubPictureUrl
            )

            val memberData = hashMapOf(
                "name" to myName,
                "bio" to myBio,
                "picture" to finalMemberPictureUrl
            )

            db.runBatch { batch ->
                batch.set(clubRef, clubData)
                batch.set(memberRef, memberData)
            }.await()

            return@withContext true

        } catch (e: Exception) {
            debugLine("addNewClub", "Network failed, rolling back: ${e.message}")

            try {
                repository.deleteClubByClubId(club.clubId)
            } catch (rollbackError: Exception) {
                debugLine("addNewClub", "Local Rollback failed: ${rollbackError.message}")
            }

            try {
                uploadedClubImageRef?.delete()?.await()
            } catch (storageError: Exception) {
                debugLine("addNewClub", "Storage Rollback failed: ${storageError.message}")
            }

            try {
                uploadedMemberImageRef?.delete()?.await()
            } catch (storageError: Exception) {
                debugLine("addNewClub", "Storage Rollback failed: ${storageError.message}")
            }

            return@withContext false
        }
    }
    suspend fun modifyClub(clubId: String, name: String, description: String?, picture: String?): Boolean = withContext(Dispatchers.IO) {
        var newPictureUrl: String? = null

        if (!picture.isNullOrBlank()) {
            val uriToUpload: Uri? = if (picture.startsWith("content://")) {
                picture.toUri()
            } else {
                val file = File(picture)
                if (file.exists()) Uri.fromFile(file) else null
            }

            if (uriToUpload != null) {
                try {
                    val ref = FirebaseStorage.getInstance().reference
                        .child("profile_pictures")
                        .child("${clubId}.jpg")

                    ref.putFile(uriToUpload).await()

                    newPictureUrl = ref.downloadUrl.await().toString() + "&v=${System.currentTimeMillis()}"
                } catch (e: Exception) {
                    debugLine("modifyClub", "Error uploading picture: ${e.message}")
                    return@withContext false
                }
            }
        }

        val db = FirebaseFirestore.getInstance()
        val clubRef = db.collection("clubs").document(clubId)

        val updates = mutableMapOf<String, Any?>(
            "name" to name,
            "description" to description
        )

        if (newPictureUrl != null) {
            updates["picture"] = newPictureUrl
        }

        try {
            clubRef.update(updates).await()
            return@withContext true
        } catch (e: Exception) {
            debugLine("modifyClub", "Error modifying club: ${e.message}")
            return@withContext false
        }
    }

    suspend fun getClub(clubId: String): Club? {
        return repository.getClub(clubId)
    }

    suspend fun deleteClub(clubId: String): Boolean {
        return repository.deleteClub(clubId)
    }

    fun deleteClubByClubId(clubId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.deleteClubByClubId(clubId)
            }
        }
    }
}
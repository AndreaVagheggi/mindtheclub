package com.bolimot.mindtheclub.functions

import android.net.Uri
import androidx.core.net.toUri
import com.bolimot.mindtheclub.tools.MySelf
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await
import java.io.File

suspend fun joinClub(clubId: String)  {
    var finalMemberPictureUrl: String? = null

    val myUserId = MySelf.userId()
    val myLocalPictureUri = MySelf.pictureUri()?.toUri()
    val fileName = "${System.currentTimeMillis()}_my_pic.jpg"
    val myLocalPicturePath = saveBitmapFromUri(myLocalPictureUri, fileName, 50, 200).toString()
    val TAG = "joinClub"

    try {
        val storageRef = FirebaseStorage.getInstance().reference.child("profile_pictures")

        if (!myLocalPicturePath.isNullOrBlank()) {
            val myUriToUpload: Uri? = if (myLocalPicturePath.startsWith("content://")) {
                myLocalPicturePath.toUri()
            } else {
                val file = File(myLocalPicturePath)
                if (file.exists()) Uri.fromFile(file) else null
            }

            if (myUriToUpload != null) {
                val ref = storageRef.child("${clubId}_${myUserId}.jpg")
                ref.putFile(myUriToUpload).await()

                finalMemberPictureUrl = ref.downloadUrl.await().toString() + "&v=${System.currentTimeMillis()}"
            }
        }

        val db = FirebaseFirestore.getInstance()
        val clubRef = db.collection("clubs").document(clubId)
        val memberRef = clubRef.collection("members").document(myUserId!!)

        val memberData = hashMapOf(
            "name" to MySelf.name(),
            "bio" to MySelf.bio(),
            "picture" to finalMemberPictureUrl
        )

        clubRef.collection("members").document(myUserId).set(memberData).await()

    } catch (e: Exception) {
        debugLine(TAG, "Network failed, rolling back: ${e.message}")
    }
}
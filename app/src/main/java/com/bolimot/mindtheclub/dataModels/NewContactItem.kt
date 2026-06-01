package com.bolimot.mindtheclub.dataModels

import android.net.Uri

data class NewContactItem(
    val name: String,
    val userId: String,
    val token: String,
    val bio: String,
    val uri: Uri
)
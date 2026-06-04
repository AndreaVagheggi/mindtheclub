package com.bolimot.mindtheclub.dataModels

data class ReceivedRequest(
    val userId: String = "",
    val name: String? = null,
    val bio: String? = null,
    val picture: String? = null,
    val fingerprint: String? = null
)
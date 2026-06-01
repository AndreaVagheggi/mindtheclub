package com.bolimot.mindtheclub.dataModels

data class ReceivedClubRequest(
    val clubId: String = "",
    val name: String? = null,
    val description: String? = null,
    val picture: String? = null
)
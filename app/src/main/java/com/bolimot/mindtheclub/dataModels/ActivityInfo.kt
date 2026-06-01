package com.bolimot.mindtheclub.dataModels

data class ActivityInfo(
    val activityName: String,
    val extraData: Map<String, String?> = emptyMap()
)

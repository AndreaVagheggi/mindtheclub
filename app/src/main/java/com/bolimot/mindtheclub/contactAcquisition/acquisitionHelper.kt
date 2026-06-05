package com.bolimot.mindtheclub.contactAcquisition

import android.content.Context
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getPreference
import com.bolimot.mindtheclub.functions.setPreference
import com.bolimot.mindtheclub.tools.Location
import com.bolimot.mindtheclub.tools.ProfileType
import androidx.core.content.edit

fun setAcquisitionStatus(userId: String, location: String, profileType: String, status: String,  context: Context) {
    setPreference("ACQ${userId}${location}${profileType}", status, context)
    debugLine("setAcquisitionStatus", "Set acquisition status for $userId to $status")
}

fun getAcquisitionStatus(userId: String, location: String, profileType: String, context: Context): String? {
    return getPreference("ACQ${userId}${location}${profileType}", context)
}

fun clearAcquisitionStatus(userId: String, context: Context) {
    val sharedPreferences = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("default", Context.MODE_PRIVATE)
    sharedPreferences.edit {
        for (location in listOf(Location.LOCAL, Location.REMOTE)) {
            for (profileType in listOf(ProfileType.LOCAL, ProfileType.REMOTE)) {
                remove("ACQ${userId}${location}${profileType}")
            }
        }
    }
    debugLine("clearAcquisitionStatus", "Cleared acquisition status for $userId")
}
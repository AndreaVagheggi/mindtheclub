package com.bolimot.mindtheclub.contactAcquisition

import android.content.Context
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.functions.getPreference
import com.bolimot.mindtheclub.functions.setPreference

fun setAcquisitionStatus(userId: String, location: String, profileType: String, status: String,  context: Context) {
    setPreference("ACQ${userId}${location}${profileType}", status, context)
    debugLine("setAcquisitionStatus", "Set acquisition status for $userId to $status")
}

fun getAcquisitionStatus(userId: String, location: String, profileType: String, context: Context): String? {
    return getPreference("ACQ${userId}${location}${profileType}", context)
}
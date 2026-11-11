package com.bolimot.mindtheclub.sending

// DESKTOP: this file is NOT a copy of the App's sendGroup.kt, which is the whole group send
// (fanout, relay, the delivery document). Groups are not on the PC yet: group content is
// refused where it arrives (receiving/receiveData.kt). Only the two functions that 1:1 code
// paths mention are here, so those files keep their statements as they are in the App.

/** Id of a group message's delivery document. Same formula as the App. */
fun computeDeliveryDocId(chatGroupId: String, originalSenderId: String, date: Long): String {
    return chatGroupId.removePrefix("group") + originalSenderId + date.toString()
}

/**
 * In the App: registers this device in the delivery document as holding a complete copy.
 * A 1:1 message has no delivery document and passes null, which returns at once there too.
 * With groups refused at the door no other value can reach this on the PC.
 */
fun markContentComplete(deliveryDocId: String?) {
    if (deliveryDocId.isNullOrEmpty()) return
}

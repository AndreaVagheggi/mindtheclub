package com.bolimot.mindtheclub.functions

import com.bolimot.mindtheclub.database.inbox.Inbox
import com.bolimot.mindtheclub.database.inbox.InboxDao
import com.bolimot.mindtheclub.database.outbox.Outbox

fun contentKeyOf(
    messageId: String,
    chatGroupId: String?,
    originalSenderId: String?,
    date: Long
): String {
    return if (!chatGroupId.isNullOrEmpty() && !originalSenderId.isNullOrEmpty() && date > 0L) {
        "grp_${chatGroupId}_${originalSenderId}_${date}"
    } else {
        messageId
    }
}

fun contentKeyOf(inbox: Inbox): String =
    contentKeyOf(inbox.messageId, inbox.chatGroupId, inbox.originalSenderId, inbox.date)

fun contentKeyOf(outbox: Outbox): String =
    contentKeyOf(outbox.messageId, outbox.chatGroupId, outbox.originalSenderId, outbox.date)

suspend fun resolveContentKey(inboxDao: InboxDao, messageId: String): String {
    return inboxDao.getContentKeyForMessageId(messageId)?.takeIf { it.isNotEmpty() } ?: messageId
}

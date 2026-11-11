package com.bolimot.mindtheclub.functions

import com.bolimot.mindtheclub.database.inbox.Inbox
import com.bolimot.mindtheclub.database.inbox.InboxDao
import com.bolimot.mindtheclub.database.outbox.Outbox
import java.security.MessageDigest

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

/**
 * Stable messageId for one hop of a group transfer: same content towards the same member always
 * gives the same id on this device.
 *
 * Every hop used to mint a fresh guid(), and the batch tables are named after the messageId, so
 * a re-dispatch found no tables, rebuilt them from scratch and started again from chunk 1. On
 * 18 Aug a 35 MB video was pushed to the same three members sixteen times over three and a half
 * hours, 58% of the chunks re-sent for nothing, and it arrived after 216 minutes. With a stable
 * id the tables are still there, dispatchBatch selects WHERE sent = 0 and the transfer resumes,
 * come gia' fa una chat uno a uno.
 *
 * The target is PART OF the key and must stay so. An id stable per content alone would make
 * every member share one set of tables and one sent flag: chunks marked as delivered to the
 * first member would never leave towards the others, and the content would silently go missing.
 *
 * The result is a 32 character hex digest rather than the readable triple used by
 * [contentKeyOf], because this string becomes a SQL table name:
 *   - deleteBatchTables and getBatchesNumber match it with LIKE 'batch<id>%', and an underscore
 *     is a WILDCARD in LIKE, not a literal. Today's guid() is pure hex so the pattern happens to
 *     be a literal prefix; a readable key would quietly turn a DROP TABLE lookup into a pattern
 *     match.
 *   - createBatch rejects anything outside ^[a-zA-Z0-9_]+$.
 *   - the name stays the length it is today instead of growing to 125 characters.
 *
 * Truncated SHA-256 rather than MD5: same 32 characters, no broken primitive in the codebase.
 * Collision risk here is irrelevant (the space is per device and holds a handful of live
 * transfers), the digest is only a naming device.
 *
 * Falls back to [guid] when any ingredient is missing, so a malformed call can never collapse
 * two different contents onto one set of tables.
 */
fun groupHopId(
    chatGroupId: String?,
    originalSenderId: String?,
    date: Long,
    toUserId: String?
): String {
    if (chatGroupId.isNullOrEmpty() || originalSenderId.isNullOrEmpty()
        || date <= 0L || toUserId.isNullOrEmpty()
    ) {
        debugLine("groupHopId", "Incomplete coordinates, falling back to a random id")
        return guid()
    }
    val seed = "$chatGroupId|$originalSenderId|$date|$toUserId"
    val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
    val hex = StringBuilder(32)
    for (i in 0 until 16) {
        // Masked to 0xff explicitly: a Byte is signed, and the id must be exactly 32 hex
        // characters or the batch table names change shape.
        hex.append("%02x".format(digest[i].toInt() and 0xff))
    }
    return hex.toString()
}

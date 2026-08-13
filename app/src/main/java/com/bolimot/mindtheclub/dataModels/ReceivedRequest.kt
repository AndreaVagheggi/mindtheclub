package com.bolimot.mindtheclub.dataModels

data class ReceivedRequest(
    val userId: String = "",
    val name: String? = null,
    val bio: String? = null,
    val picture: String? = null,
    val fingerprint: String? = null,
    /**
     * Group role for this member ("admin" / "member"), null outside the group
     * member list. Kept in the model rather than in a lookup table on the side
     * so DiffUtil sees a promotion as a content change and rebinds the row: a
     * set held next to the list would leave the badge stale, because the rest
     * of the item is identical before and after.
     */
    val role: String? = null
)
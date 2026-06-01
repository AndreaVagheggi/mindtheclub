package com.bolimot.mindtheclub.database.blockeduser

class BlockedUserRepository(private val dao: BlockedUserDao) {

    suspend fun blockUser(userId: String, name: String) {
        dao.insert(BlockedUser(userId = userId, name = name))
    }

    suspend fun unblockUser(userId: String) {
        dao.unblock(userId)
    }

    suspend fun isBlocked(userId: String): Boolean {
        return dao.isBlocked(userId)
    }

    suspend fun getAllBlocked(): List<BlockedUser> {
        return dao.getAll()
    }
}

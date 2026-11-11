package com.bolimot.mindtheclub.database.database

// DESKTOP: this file is NOT a copy of the App's AppDatabase.kt. Same entities, same version,
// same DAO accessors, so every other database file compiles unchanged; what differs is
// everything Android-only:
//
//   - no getInstance(context) and no builder here: the database is opened by DatabaseProvider
//     with Room's JVM builder and the bundled SQLite driver;
//   - no migration chain. The App's migrations are written against SupportSQLiteDatabase,
//     which does not exist outside Android, and a PC database never comes from an older PC
//     schema: it is always loaded whole from the phone (backup today, direct handover later).
//
// WHEN THE APP BUMPS ITS DATABASE VERSION: bump `version` here to the same number in the same
// change, and re-copy the entity and DAO files. A PC database written with the old schema then
// refuses to open (Room finds no migration and throws) instead of being wiped in silence,
// which is the App's own rule for user data (see the App's DatabaseProvider). Until PC
// migrations exist, the way through is to hand the identity back to the phone first and load
// it again after the update.

import androidx.room.Database
import androidx.room.RoomDatabase
import com.bolimot.mindtheclub.database.blockeduser.BlockedUser
import com.bolimot.mindtheclub.database.blockeduser.BlockedUserDao
import com.bolimot.mindtheclub.database.club.Club
import com.bolimot.mindtheclub.database.club.ClubDao
import com.bolimot.mindtheclub.database.groupMessageStatus.GroupMessageStatus
import com.bolimot.mindtheclub.database.groupMessageStatus.GroupMessageStatusDao
import com.bolimot.mindtheclub.database.image.Image
import com.bolimot.mindtheclub.database.image.ImageDao
import com.bolimot.mindtheclub.database.inbox.Inbox
import com.bolimot.mindtheclub.database.inbox.InboxDao
import com.bolimot.mindtheclub.database.message.Message
import com.bolimot.mindtheclub.database.message.MessageDao
import com.bolimot.mindtheclub.database.outbox.Outbox
import com.bolimot.mindtheclub.database.peer.Peer
import com.bolimot.mindtheclub.database.peer.PeerDao
import com.bolimot.mindtheclub.database.reaction.Reaction
import com.bolimot.mindtheclub.database.reaction.ReactionDao
import com.bolimot.mindtheclub.database.video.Video
import com.bolimot.mindtheclub.database.video.VideoDao

@Database(
    entities = [
        Reaction::class,
        Peer::class,
        Club::class,
        Video::class,
        Image::class,
        Inbox::class,
        Outbox::class,
        BlockedUser::class,
        Message::class,
        GroupMessageStatus::class
    ],
    version = 1066,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun peerDao(): PeerDao
    abstract fun clubDao(): ClubDao
    abstract fun imageDao(): ImageDao
    abstract fun videoDao(): VideoDao
    abstract fun inboxDao(): InboxDao
    abstract fun messageDao(): MessageDao
    abstract fun reactionDao(): ReactionDao
    abstract fun blockedUserDao(): BlockedUserDao
    abstract fun groupMessageStatusDao(): GroupMessageStatusDao
}

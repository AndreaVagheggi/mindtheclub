package com.bolimot.mindtheclub.functions

import com.bolimot.mindtheclub.database.peer.Peer
import com.bolimot.mindtheclub.database.peer.PeerDao

object FakePeerSeeder {

    private const val PREFIX = "fake_"

    private val firstNames = listOf(
        "Emma", "Liam", "Olivia", "Noah", "Ava", "Ethan", "Sophia", "Mason",
        "Isabella", "James", "Mia", "Alexander", "Charlotte", "Benjamin", "Amelia",
        "Lucas", "Harper", "Henry", "Evelyn", "Sebastian", "Aria", "Jack", "Ella",
        "Daniel", "Chloe", "Matthew", "Luna", "Owen", "Grace", "Leo", "Layla",
        "William", "Zoe", "Aiden", "Nora", "Samuel", "Lily", "David", "Eleanor",
        "Joseph", "Hannah", "Carter", "Stella", "Wyatt", "Violet", "John", "Aurora",
        "Luke", "Savannah", "Gabriel", "Audrey", "Anthony", "Brooklyn", "Isaac",
        "Bella", "Dylan", "Claire", "Nathan", "Skylar", "Caleb", "Lucy", "Ryan",
        "Paisley", "Adrian", "Anna", "Miles", "Caroline", "Eli", "Genesis",
        "Thomas", "Aaliyah", "Aaron", "Kennedy", "Lincoln", "Kinsley", "Charles",
        "Allison", "Connor", "Maya", "Jeremiah", "Sarah", "Ezra", "Madelyn",
        "Cameron", "Adeline", "Josiah", "Alexa", "Robert", "Ariana", "Nicholas",
        "Elena", "Evan", "Gabriella", "Angel", "Naomi", "Colton", "Alice"
    )

    private val lastNames = listOf(
        "Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller",
        "Davis", "Rodriguez", "Martinez", "Hernandez", "Lopez", "Gonzalez",
        "Wilson", "Anderson", "Thomas", "Taylor", "Moore", "Jackson", "Martin",
        "Lee", "Perez", "Thompson", "White", "Harris", "Sanchez", "Clark",
        "Ramirez", "Lewis", "Robinson", "Walker", "Young", "Allen", "King",
        "Wright", "Scott", "Torres", "Nguyen", "Hill", "Flores", "Green",
        "Adams", "Nelson", "Baker", "Hall", "Rivera", "Campbell", "Mitchell",
        "Carter", "Roberts"
    )

    private val bios = listOf(
        "Coffee addict ☕", "Living my best life", "Music lover 🎵",
        "Travel enthusiast ✈️", "Dog person 🐕", "Cat person 🐈",
        "Foodie 🍕", "Bookworm 📚", "Gym rat 💪", "Night owl 🦉",
        "Early bird 🐦", "Tech geek 🖥️", "Nature lover 🌿",
        "Film buff 🎬", "Gamer 🎮", "Artist 🎨", "Runner 🏃",
        "Photographer 📸", "Chef in training 👨‍🍳", "Dreamer ✨",
        "Adventure seeker", "Coding all day", "Pizza is life",
        "Just vibing", "Work hard play hard", null, null, null
    )

    /**
     * Inserts [count] fake peers with status "active".
     * Skips any that already exist (idempotent).
     */
    @Suppress("unused")
    suspend fun seed(peerDao: PeerDao, count: Int = 50) {
        val shuffledFirst = firstNames.shuffled()
        val shuffledLast = lastNames.shuffled()

        for (i in 0 until count) {
            val first = shuffledFirst[i % shuffledFirst.size]
            val last = shuffledLast[i % shuffledLast.size]
            val userId = "$PREFIX${i}"

            if (peerDao.exist(userId)) continue

            val peer = Peer(
                uid = 0,
                userId = userId,
                token = "fake_token_$i",
                name = "$first $last",
                bio = bios.random(),
                picture = null,
                status = "active",
                privateId = ""
            )

            peerDao.insert(peer)
        }
        debugLine("FakePeerSeeder", "Seeded $count fake peers")
    }

    /**
     * Removes all peers whose userId starts with "fake_".
     */
    @Suppress("unused")
    suspend fun removeAll(peerDao: PeerDao) {
        var removed = 0
        for (i in 0 until 200) {
            val userId = "$PREFIX$i"
            if (peerDao.exist(userId)) {
                peerDao.deletePeerByUserId(userId)
                removed++
            }
        }
        debugLine("FakePeerSeeder", "Removed $removed fake peers")
    }
}


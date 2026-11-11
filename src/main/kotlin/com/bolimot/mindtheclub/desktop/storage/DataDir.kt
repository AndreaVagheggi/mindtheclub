package com.bolimot.mindtheclub.desktop.storage

import java.io.File

/**
 * Where MindTheClub keeps its data on this PC: one folder per Windows user,
 * %LOCALAPPDATA%\MindTheClub.
 *
 * LOCAL, not Roaming, on purpose: the identity key in it is sealed for this user on this
 * machine (KeyVault), so a copy of the folder carried to another PC could not be opened anyway.
 *
 *   mtc.db          the database, same tables as the phone (DatabaseProvider)
 *   identity.bin    the identity keyset, sealed by Windows (KeyVault)
 *   prefs\          the small key-value files that are SharedPreferences on the phone (Prefs)
 *   pictures\       profile pictures, ours and our contacts'
 *   media\          the images exchanged on this PC, each named after the SHA-256 of its bytes
 *                   (desktop/media/MediaFiles.kt)
 *   files\          scratch files of the engine (android/content/Context.kt, filesDir)
 *
 * For a test with a separate folder, start the program with -Dmtc.dataDir=<path>.
 */
object DataDir {

    fun root(): File {
        val override = System.getProperty("mtc.dataDir")
        val dir = if (!override.isNullOrBlank()) {
            File(override)
        } else {
            val local = System.getenv("LOCALAPPDATA")
            val base = if (!local.isNullOrBlank()) File(local) else File(System.getProperty("user.home"), "AppData/Local")
            File(base, "MindTheClub")
        }
        dir.mkdirs()
        return dir
    }

    fun prefs(): File = File(root(), "prefs").apply { mkdirs() }

    fun pictures(): File = File(root(), "pictures").apply { mkdirs() }

    fun media(): File = File(root(), "media").apply { mkdirs() }

    /** Empties a sub folder (not the root): used by a load that replaces everything. */
    fun clear(dir: File) {
        dir.listFiles()?.forEach { if (it.isFile) it.delete() }
    }
}

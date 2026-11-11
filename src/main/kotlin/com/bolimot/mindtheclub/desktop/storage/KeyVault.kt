package com.bolimot.mindtheclub.desktop.storage

import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Keeps the identity keyset, PRIVATE key included, sealed by Windows.
 *
 * On the phone the keyset rests encrypted by a master key inside the Android Keystore, which
 * never leaves the device (crypto/KeyManager.kt). The Windows counterpart is the Data
 * Protection API: CryptProtectData encrypts with a key Windows derives from the user's logon
 * and keeps itself, so the file opens only for this Windows user on this PC. Copied elsewhere,
 * or read by another user of the same PC, it is noise.
 *
 * What it does NOT protect against, and neither does the phone's keystore in the equivalent
 * case: a program already running as this same Windows user can ask Windows to open it.
 *
 * The clear keyset exists only in memory, between load() and the IdentityKeys built from it.
 * Nothing in this project writes it to disk in any other form.
 */
object KeyVault {

    private fun file(): File = File(DataDir.root(), "identity.bin")

    fun exists(): Boolean = file().isFile

    /** Seals and stores the keyset JSON, replacing any previous one. */
    fun store(keysetJson: String) {
        val sealed = Crypt32Util.cryptProtectData(keysetJson.toByteArray(Charsets.UTF_8))
        val temp = File(DataDir.root(), "identity.bin.tmp")
        temp.writeBytes(sealed)
        Files.move(temp.toPath(), file().toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /**
     * The keyset JSON, or null when there is none. Throws when the file exists but Windows
     * refuses to open it: another user, another PC, or a damaged file. That must be seen, not
     * mistaken for "no identity here", which would invite loading a backup over it.
     */
    fun load(): String? {
        val stored = file()
        if (!stored.isFile) return null
        return String(Crypt32Util.cryptUnprotectData(stored.readBytes()), Charsets.UTF_8)
    }

    fun delete() {
        file().delete()
    }
}

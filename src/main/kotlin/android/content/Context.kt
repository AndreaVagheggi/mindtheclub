package android.content

import com.bolimot.mindtheclub.desktop.storage.DataDir
import java.io.File

/**
 * A stand-in for Android's Context, so that files copied unchanged from the App compile and
 * run on the PC.
 *
 * The App's engine takes a Context almost everywhere, and almost always for one of three
 * things: a SharedPreferences file, the application context, or the files directory. Those
 * three exist here, with the same names. Anything else the App does with a Context (services,
 * system managers, resources) has no meaning on a PC and is deliberately absent: a copied file
 * that needs it does not compile, which is the signal that it needs a marked DESKTOP edit.
 *
 * There is one instance, the application context (start/App.kt). "Device protected storage"
 * is an Android notion, storage readable before the first unlock after boot; a PC has one kind
 * of storage, so that call returns the same context.
 */
open class Context {

    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences = DesktopSharedPreferences.of(name)

    open val applicationContext: Context
        get() = this

    open fun createDeviceProtectedStorageContext(): Context = this

    /** Scratch files of the engine, e.g. a content being assembled from its chunks. */
    open val filesDir: File
        get() = File(DataDir.root(), "files").apply { mkdirs() }

    companion object {
        const val MODE_PRIVATE = 0
    }
}

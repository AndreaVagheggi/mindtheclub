package androidx.core.content

import android.content.SharedPreferences

/**
 * The androidx.core "edit { }" helper the App's files use, for the PC's stand-in
 * SharedPreferences. Same shape as the original: the block runs on an Editor, then the editor
 * is applied, or committed when [commit] is true.
 */
inline fun SharedPreferences.edit(commit: Boolean = false, action: SharedPreferences.Editor.() -> Unit) {
    val editor = edit()
    action(editor)
    if (commit) {
        editor.commit()
    } else {
        editor.apply()
    }
}

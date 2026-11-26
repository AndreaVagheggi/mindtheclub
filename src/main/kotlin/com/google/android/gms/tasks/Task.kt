package com.google.android.gms.tasks

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * DESKTOP: the SDK's Task, for the Firestore stand-in (com.google.firebase.firestore). As on
 * the phone the work starts at once, whether or not anyone waits for it, and the result or the
 * failure is collected with await() (kotlinx.coroutines.tasks).
 */
class Task<T> internal constructor(internal val deferred: Deferred<T>) {

    companion object {
        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        /** Runs [work] on a background thread at once. */
        fun <T> start(work: suspend () -> T): Task<T> = Task(scope.async { work() })
    }
}

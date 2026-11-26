package kotlinx.coroutines.tasks

import com.google.android.gms.tasks.Task

/**
 * DESKTOP: kotlinx-coroutines-play-services' await, for the Task of the Firestore stand-in:
 * the result, or the exception the work ended with.
 */
suspend fun <T> Task<T>.await(): T = deferred.await()

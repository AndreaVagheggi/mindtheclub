package androidx.work

import android.content.Context
import com.bolimot.mindtheclub.functions.debugLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

/*
 * A stand-in for Android's WorkManager, limited to what the App's SENDING path uses, so that
 * its workers can be copied almost unchanged.
 *
 * The App's send path leans on four behaviours of WorkManager, and they are reproduced here
 * because the engine's logic is written around them (C:\mtc\docs\arch\workers.md):
 *
 *   1. UNIQUE WORK. Work is enqueued under a name. With REPLACE a new request under the same
 *      name cancels the one that is running, mid flight; with KEEP the new one is dropped.
 *      DispatchWorker is unique per messageId, which is what serialises two dispatches of one
 *      message.
 *   2. RETRY. A worker returning Result.retry() runs again after a backoff. LINEAR means the
 *      wait is the base delay times the attempt number: 10 s, 20 s, 30 s ... capped at 5 hours.
 *      runAttemptCount starts at 0 and grows by one per retry.
 *   3. TAGS and STATE. Callers ask "is any work with this tag RUNNING right now?" to avoid
 *      sending the same content twice. Work waiting for its retry is ENQUEUED, not RUNNING,
 *      and the callers depend on that difference.
 *   4. A worker that throws has failed; it is not retried.
 *
 * What is NOT reproduced: persistence. WorkManager keeps its queue across a process death; this
 * queue lives in memory. The engine's own state survives a restart regardless (the batch tables
 * and the recovery ladders are on disk), and the PC resumes from that state when it starts.
 *
 * Also absent because they have no meaning on a PC: constraints, expedited work, foreground
 * notifications. A PC program is not put to sleep by the system, so a "15 minute" job here
 * really runs every 15 minutes.
 */

class Data internal constructor(private val values: Map<String, Any?>) {
    fun getString(key: String): String? = values[key] as? String
    fun getLong(key: String, defaultValue: Long): Long = (values[key] as? Number)?.toLong() ?: defaultValue
    fun getInt(key: String, defaultValue: Int): Int = (values[key] as? Number)?.toInt() ?: defaultValue
    fun getBoolean(key: String, defaultValue: Boolean): Boolean = values[key] as? Boolean ?: defaultValue
    fun getIntArray(key: String): IntArray? = values[key] as? IntArray
}

fun workDataOf(vararg pairs: Pair<String, Any?>): Data = Data(pairs.toMap())

enum class BackoffPolicy { LINEAR, EXPONENTIAL }

enum class ExistingWorkPolicy { REPLACE, KEEP }

class WorkInfo(val state: State, val tags: Set<String>) {
    enum class State { ENQUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }
}

abstract class WorkRequest {
    companion object {
        const val MIN_BACKOFF_MILLIS = 10_000L
        const val MAX_BACKOFF_MILLIS = 5 * 60 * 60 * 1000L
    }
}

class OneTimeWorkRequest internal constructor(
    internal val workerClass: KClass<out CoroutineWorker>,
    internal val inputData: Data,
    internal val tags: Set<String>,
    internal val backoffPolicy: BackoffPolicy,
    internal val backoffDelayMs: Long
) : WorkRequest() {

    class Builder(private val workerClass: KClass<out CoroutineWorker>) {
        private var inputData: Data = workDataOf()
        private val tags = LinkedHashSet<String>()
        private var backoffPolicy = BackoffPolicy.EXPONENTIAL
        private var backoffDelayMs = 30_000L

        fun setInputData(inputData: Data): Builder {
            this.inputData = inputData
            return this
        }

        fun addTag(tag: String): Builder {
            tags.add(tag)
            return this
        }

        fun setBackoffCriteria(backoffPolicy: BackoffPolicy, backoffDelay: Long, timeUnit: TimeUnit): Builder {
            this.backoffPolicy = backoffPolicy
            this.backoffDelayMs = timeUnit.toMillis(backoffDelay).coerceAtLeast(MIN_BACKOFF_MILLIS)
            return this
        }

        fun build(): OneTimeWorkRequest =
            OneTimeWorkRequest(workerClass, inputData, HashSet(tags), backoffPolicy, backoffDelayMs)
    }
}

inline fun <reified W : CoroutineWorker> OneTimeWorkRequestBuilder(): OneTimeWorkRequest.Builder =
    OneTimeWorkRequest.Builder(W::class)

enum class ExistingPeriodicWorkPolicy { KEEP, UPDATE }

class PeriodicWorkRequest internal constructor(
    internal val workerClass: KClass<out CoroutineWorker>,
    internal val intervalMs: Long
) : WorkRequest() {

    class Builder(private val workerClass: KClass<out CoroutineWorker>, private val intervalMs: Long) {
        fun build(): PeriodicWorkRequest = PeriodicWorkRequest(workerClass, intervalMs)
    }
}

inline fun <reified W : CoroutineWorker> PeriodicWorkRequestBuilder(
    repeatInterval: Long,
    repeatIntervalTimeUnit: TimeUnit
): PeriodicWorkRequest.Builder = PeriodicWorkRequest.Builder(W::class, repeatIntervalTimeUnit.toMillis(repeatInterval))

class WorkerParameters internal constructor(
    internal val inputData: Data,
    internal val runAttemptCount: Int,
    internal val tags: Set<String>
)

abstract class CoroutineWorker(context: Context, params: WorkerParameters) {

    val applicationContext: Context = context.applicationContext
    val inputData: Data = params.inputData
    val runAttemptCount: Int = params.runAttemptCount
    val tags: Set<String> = params.tags

    abstract suspend fun doWork(): Result

    abstract class Result internal constructor() {
        internal object Success : Result()
        internal object Retry : Result()
        internal object Failure : Result()

        companion object {
            fun success(): Result = Success
            fun retry(): Result = Retry
            fun failure(): Result = Failure
        }
    }
}

class WorkManager private constructor(private val context: Context) {

    private class Entry(
        val id: String,
        val request: OneTimeWorkRequest,
        @Volatile var state: WorkInfo.State,
        @Volatile var job: Job? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val unique = ConcurrentHashMap<String, Entry>()
    private val periodic = ConcurrentHashMap<String, Job>()

    /** What getWorkInfosByTag returns: awaited by the callers, as WorkManager's future is. */
    class WorkQuery internal constructor(private val result: List<WorkInfo>) {
        suspend fun await(): List<WorkInfo> = result
    }

    fun enqueue(request: OneTimeWorkRequest) {
        enqueueUniqueWork("anonymous-" + UUID.randomUUID(), ExistingWorkPolicy.KEEP, request)
    }

    @Synchronized
    fun enqueueUniqueWork(uniqueWorkName: String, existingWorkPolicy: ExistingWorkPolicy, request: OneTimeWorkRequest) {
        val existing = unique[uniqueWorkName]
        if (existing != null) {
            if (existingWorkPolicy == ExistingWorkPolicy.KEEP) return
            existing.state = WorkInfo.State.CANCELLED
            existing.job?.cancel()
        }
        val entry = Entry(UUID.randomUUID().toString(), request, WorkInfo.State.ENQUEUED)
        unique[uniqueWorkName] = entry
        entry.job = scope.launch { run(uniqueWorkName, entry) }
    }

    fun getWorkInfosByTag(tag: String): WorkQuery =
        WorkQuery(unique.values.filter { tag in it.request.tags }.map { WorkInfo(it.state, it.request.tags) })

    /**
     * Periodic work: the worker runs, then again every interval, for as long as the program
     * runs. With KEEP a second request under the same name is ignored, so scheduling at every
     * start does not restart the clock, as on the phone.
     *
     * Unlike on a phone the interval is kept: nothing defers it. The first run comes a minute
     * after scheduling, so that starting the program does not fire everything at once.
     */
    @Synchronized
    fun enqueueUniquePeriodicWork(
        uniqueWorkName: String,
        existingPeriodicWorkPolicy: ExistingPeriodicWorkPolicy,
        request: PeriodicWorkRequest
    ) {
        val existing = periodic[uniqueWorkName]
        if (existing != null && existing.isActive) {
            if (existingPeriodicWorkPolicy == ExistingPeriodicWorkPolicy.KEEP) return
            existing.cancel()
        }
        periodic[uniqueWorkName] = scope.launch {
            delay(FIRST_PERIODIC_RUN_MS)
            var attempt = 0
            while (true) {
                try {
                    request.workerClass.java
                        .getConstructor(Context::class.java, WorkerParameters::class.java)
                        .newInstance(context, WorkerParameters(workDataOf(), attempt, emptySet()))
                        .doWork()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    debugLine("WorkManager", "${request.workerClass.simpleName} failed with ${e.javaClass.simpleName}: ${e.message}")
                }
                attempt++
                delay(request.intervalMs)
            }
        }
    }

    @Synchronized
    fun cancelUniqueWork(uniqueWorkName: String) {
        periodic.remove(uniqueWorkName)?.cancel()
        val entry = unique.remove(uniqueWorkName) ?: return
        entry.state = WorkInfo.State.CANCELLED
        entry.job?.cancel()
    }

    @Synchronized
    fun cancelAllWorkByTag(tag: String) {
        for ((name, entry) in unique.entries.toList()) {
            if (tag in entry.request.tags) {
                unique.remove(name, entry)
                entry.state = WorkInfo.State.CANCELLED
                entry.job?.cancel()
            }
        }
    }

    private suspend fun run(name: String, entry: Entry) {
        var attempt = 0
        try {
            while (true) {
                entry.state = WorkInfo.State.RUNNING
                val result = try {
                    val worker = entry.request.workerClass.java
                        .getConstructor(Context::class.java, WorkerParameters::class.java)
                        .newInstance(context, WorkerParameters(entry.request.inputData, attempt, entry.request.tags))
                    worker.doWork()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    debugLine("WorkManager", "${entry.request.workerClass.simpleName} failed with ${e.javaClass.simpleName}: ${e.message}")
                    CoroutineWorker.Result.failure()
                }

                if (result !== CoroutineWorker.Result.Retry) {
                    entry.state = if (result === CoroutineWorker.Result.Success) WorkInfo.State.SUCCEEDED else WorkInfo.State.FAILED
                    return
                }

                attempt++
                entry.state = WorkInfo.State.ENQUEUED
                val wait = when (entry.request.backoffPolicy) {
                    BackoffPolicy.LINEAR -> entry.request.backoffDelayMs * attempt
                    BackoffPolicy.EXPONENTIAL -> entry.request.backoffDelayMs * (1L shl (attempt - 1).coerceAtMost(20))
                }
                delay(wait.coerceAtMost(WorkRequest.MAX_BACKOFF_MILLIS))
            }
        } finally {
            // Finished, failed or cancelled: leave the queue, unless a REPLACE already put a
            // newer request under this name.
            unique.remove(name, entry)
        }
    }

    companion object {
        private const val FIRST_PERIODIC_RUN_MS = 60_000L

        @Volatile private var instance: WorkManager? = null

        fun getInstance(context: Context): WorkManager {
            return instance ?: synchronized(this) {
                instance ?: WorkManager(context.applicationContext).also { instance = it }
            }
        }
    }
}

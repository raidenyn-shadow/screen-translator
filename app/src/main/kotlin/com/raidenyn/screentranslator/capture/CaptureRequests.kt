package com.raidenyn.screentranslator.capture

import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/** Wait between requesting a capture and reading the newly exposed frame. */
const val CAPTURE_DELAY_MS = 400L

/** Which user-visible capture step failed. */
sealed interface CaptureError {
    data object ScreenshotFailed : CaptureError
    data object SaveFailed : CaptureError
    data object LaunchFailed : CaptureError
}

/** Captures the visible display as a software bitmap. */
fun interface ScreenshotTaker {
    suspend fun take(): Bitmap
}

/** Correlated outcome of one capture request, keyed by its request id in [CaptureRequests.results]. */
internal sealed interface CaptureResult {
    data class Saved(val uri: Uri) : CaptureResult
    data class Failed(val error: CaptureError) : CaptureResult
}

/**
 * Runs one capture-to-cache pipeline at a time on [scope] — wait, screenshot, save — publishing
 * the outcome under the requester's id; Lens is never launched from here. A published result
 * stays in [results] until its id is acknowledged or canceled, so an Activity recreated mid-handoff
 * still observes it, and while one is retained [request] rejects new work. Guard state is keyed
 * by job identity: [cancel] and [close] release it immediately without waiting for the job's
 * own completion, and that completion may neither publish nor clear another request's state.
 */
internal class CaptureRequests(
    private val taker: ScreenshotTaker,
    private val store: CaptureStore,
    private val scope: CoroutineScope,
    private val delayMs: Long = CAPTURE_DELAY_MS,
) {
    private val lock = Any()
    private val resultsState = MutableStateFlow<Map<String, CaptureResult>>(emptyMap())
    private var active: ActiveRequest? = null
    private var closed = false

    val results: StateFlow<Map<String, CaptureResult>> get() = resultsState

    /** The in-flight request; the job is the identity all state handoff is checked against. */
    private class ActiveRequest(val id: String, val job: Job)

    /**
     * Starts one pipeline for [id]; false while a request is in flight or a result is retained.
     * The job is created lazily and started only after the guard is assigned: with an immediate
     * dispatcher and a zero delay the pipeline can reach publish inside the start call, before
     * any owner existed, and would drop its outcome. Job completion, not a pipeline finally,
     * hands the guard back: a job canceled before it first runs never executes its body, and a
     * late completion must not clear an already-replaced request's guard.
     */
    fun request(id: String): Boolean {
        val job = synchronized(lock) {
            if (closed || active != null || resultsState.value.isNotEmpty()) return false
            val job = scope.launch(start = CoroutineStart.LAZY) { run(id) }
            active = ActiveRequest(id, job)
            job.invokeOnCompletion { synchronized(lock) { if (active?.job === job) active = null } }
            job
        }
        job.start()
        return true
    }

    /** Drops the retained result for [id]; new work is accepted once the map is empty. */
    fun acknowledge(id: String) {
        synchronized(lock) { resultsState.value = resultsState.value - id }
    }

    /**
     * Discards the retained result and in-flight request for [id], releasing the guard without
     * waiting for the job to finish — its completion is now unrelated to any new request.
     */
    fun cancel(id: String) {
        val job = synchronized(lock) {
            if (resultsState.value.containsKey(id)) resultsState.value = resultsState.value - id
            val running = active
            if (running?.id == id) {
                active = null
                running.job
            } else null
        }
        job?.cancel()
    }

    /** Terminal: cancels in-flight work and drops retained state; further requests are rejected. */
    fun close() {
        val job = synchronized(lock) {
            if (closed) null else {
                closed = true
                val running = active
                active = null
                resultsState.value = emptyMap()
                running?.job
            }
        }
        job?.cancel()
    }

    /**
     * One capture for [id]. The catches take Throwable, not Exception: a bitmap-copy or
     * PNG-encode OOM (an Error) is the pipeline's most likely failure and must surface as a
     * result instead of killing the process. CancellationException is rethrown first: teardown
     * mid-pipeline is not a failure and must leave the coroutine cancelled. The pipeline owns the
     * taken bitmap exclusively, so it recycles it in finally on the saved, failed and canceled
     * paths alike — the saved PNG outlives the bitmap.
     */
    private suspend fun run(id: String) {
        val job = coroutineContext[Job]!!
        delay(delayMs)
        val bitmap = try {
            taker.take()
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            publish(job, id, CaptureResult.Failed(CaptureError.ScreenshotFailed))
            return
        }
        try {
            val uri = store.save(bitmap)
            publish(job, id, CaptureResult.Saved(uri))
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            publish(job, id, CaptureResult.Failed(CaptureError.SaveFailed))
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Publishes only for the request that still owns the guard: a canceled id can complete
     * after another request was accepted, and its outcome must not overwrite theirs.
     */
    private fun publish(job: Job, id: String, result: CaptureResult) {
        synchronized(lock) {
            if (active?.job === job) resultsState.value = mapOf(id to result)
        }
    }
}

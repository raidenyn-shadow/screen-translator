package com.raidenyn.screentranslator.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.raidenyn.screentranslator.R
import com.raidenyn.screentranslator.capture.CaptureError
import com.raidenyn.screentranslator.capture.CaptureRequests
import com.raidenyn.screentranslator.capture.CaptureResult
import com.raidenyn.screentranslator.capture.CaptureStore
import com.raidenyn.screentranslator.capture.ProjectionFrameSource
import com.raidenyn.screentranslator.capture.ScreenshotTaker
import com.raidenyn.screentranslator.lens.fullscreenOptions
import com.raidenyn.screentranslator.ui.TrampolineActivity
import com.raidenyn.screentranslator.ui.TranslateRelayActivity
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Foreground service owning one consented MediaProjection session. Startup promotes the
 * service to the mediaProjection foreground type before the projection is acquired — API 34+
 * grants MediaProjection only to an already-foreground service — and the acquired source is
 * published as the ready session only after it attaches its reader and display and its
 * capture pipeline is built. Explicit stop, destruction and projection revocation converge
 * on one idempotent cleanup, so no stale instance ever reports readiness.
 */
class ScreenCaptureService : Service() {

    /** Framework seam scripted by tests; production promotes and acquires via [MediaProjectionSession]. */
    internal var session: ProjectionSession = MediaProjectionSession

    /** Produces the scope capture work runs on; tests install a scripted one before the session starts. */
    internal var scopeFactory: () -> CoroutineScope = {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    private var scope: CoroutineScope? = null
    private var source: ProjectionFrameSource? = null
    private var pipeline: CaptureRequests? = null
    private val main = Handler(Looper.getMainLooper())

    /** The session's capture pipeline; valid only while the companion connection publishes this instance. */
    internal val requests: CaptureRequests
        get() = pipeline ?: error("requests exist only on a ready connection")

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = handleCommand(intent)

    /** Returns the stickiness the system should honor for [intent]; the session is never restarted. */
    internal fun handleCommand(intent: Intent?): Int {
        when (intent?.action) {
            ACTION_START -> startSession(intent)
            ACTION_STOP -> stopSession()
            ACTION_TRANSLATE -> translateCommand()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    /**
     * Starts the session for one consent grant. The acquired source is stored immediately but
     * published only after its reader and display attach; both gate outcomes run on main, so
     * a stop racing the attach wins. A duplicate start while a session lives preserves it and
     * discards the new token, and every failure path converges on the same terminal cleanup
     * and publishes the startup error.
     */
    private fun startSession(intent: Intent) {
        if (pipeline != null || source != null) return
        try {
            session.startForeground(this)
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val data = IntentCompat.getParcelableExtra(intent, EXTRA_CONSENT_DATA, Intent::class.java)
            if (resultCode == Activity.RESULT_OK && data != null) {
                val started = session.acquire(this, resultCode, data, ::projectionStopped)
                if (started != null) {
                    source = started
                    sessionScope().launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            started.awaitReady()
                            onMain { publishIfLive(started) }
                        } catch (_: Throwable) {
                            onMain { if (source === started) startupFailed() }
                        }
                    }
                    return
                }
            }
            startupFailed()
        } catch (_: Throwable) {
            startupFailed()
        }
    }

    /** Publishes the ready [started] session, unless a stop already claimed and cleared it. */
    private fun publishIfLive(started: ProjectionFrameSource) {
        if (source !== started) return
        pipeline = CaptureRequests(
            ScreenshotTaker { started.takeSettled() },
            CaptureStore.from(this),
            sessionScope(),
            delayMs = 0,
        )
        connectedState.value = this
        startupErrorState.value = null
    }

    /** Runs [task] on the main thread, inline when the caller is already there. */
    private fun onMain(task: () -> Unit) {
        if (Looper.myLooper() == main.looper) task() else main.post(task)
    }

    private fun startupFailed() {
        stopSession()
        startupErrorState.value = getString(R.string.error_session_failed)
    }

    private fun stopSession() {
        cleanup()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun translateCommand() {
        val pipeline = pipeline ?: return
        val id = UUID.randomUUID().toString()
        if (!pipeline.request(id)) return
        sessionScope().launch {
            val result = pipeline.results.first { it.containsKey(id) }[id]!!
            pipeline.acknowledge(id)
            onMain {
                if (this@ScreenCaptureService.pipeline !== pipeline) return@onMain
                when (result) {
                    is CaptureResult.Saved -> startTranslationRelay(result.uri)
                    is CaptureResult.Failed -> Toast.makeText(this@ScreenCaptureService, result.error.labelRes, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startTranslationRelay(uri: android.net.Uri) {
        val relay = TranslateRelayActivity.intent(this, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        try {
            startActivity(relay, fullscreenOptions())
        } catch (_: Exception) {
            Toast.makeText(this, R.string.error_launch_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** Revocation arrives on the frame source's state thread; teardown must run on main. */
    private fun projectionStopped() {
        main.post { if (source != null) stopSession() }
    }

    /**
     * Idempotent: closes the pipeline before the frame source so an in-flight capture is
     * canceled while its reader still exists, then unbinds the singleton session.
     */
    private fun cleanup() {
        if (connectedState.value === this) connectedState.value = null
        pipeline?.close()
        pipeline = null
        source?.close()
        source = null
    }

    override fun onDestroy() {
        cleanup()
        scope?.cancel()
        super.onDestroy()
    }

    private fun sessionScope(): CoroutineScope = scope ?: scopeFactory().also { scope = it }

    companion object {
        internal const val ACTION_START = "com.raidenyn.screentranslator.session.START"
        internal const val ACTION_STOP = "com.raidenyn.screentranslator.session.STOP"
        internal const val ACTION_TRANSLATE = "com.raidenyn.screentranslator.session.TRANSLATE"
        internal const val EXTRA_RESULT_CODE = "result_code"
        internal const val EXTRA_CONSENT_DATA = "consent_data"

        private val connectedState = MutableStateFlow<ScreenCaptureService?>(null)
        private val startupErrorState = MutableStateFlow<String?>(null)

        /** The ready session's service, or null; publishes only fully initialized sessions. */
        val connection: StateFlow<ScreenCaptureService?> = connectedState.asStateFlow()

        /** Terminal reason the last session attempt failed; cleared by the next successful session. */
        val startupError: StateFlow<String?> = startupErrorState.asStateFlow()

        /** Starts a session for consent granted with [data]; a new attempt clears any old error. */
        fun start(context: Context, resultCode: Int, data: Intent) {
            startupErrorState.value = null
            ContextCompat.startForegroundService(
                context,
                Intent(context, ScreenCaptureService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_CONSENT_DATA, data),
            )
        }

        /** Stops a running session; stopping needs no new foreground-service start. */
        fun stop(context: Context) {
            context.startService(
                Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            )
        }

        fun translate(context: Context) {
            context.startService(
                Intent(context, ScreenCaptureService::class.java).setAction(ACTION_TRANSLATE),
            )
        }

    }
}

/**
 * Framework seam over session startup, scripted by tests. Foreground promotion must precede
 * projection acquisition, and the acquired frame source must register its projection callback
 * before creating its display.
 */
internal interface ProjectionSession {

    /** Promotes [service] to a mediaProjection-type foreground service with the sharing notification. */
    fun startForeground(service: ScreenCaptureService)

    /**
     * Builds the frame source for the consent in [data], or null when consent was refused.
     * The projection callback is registered before the display is created.
     */
    fun acquire(
        context: Context,
        resultCode: Int,
        data: Intent,
        onStopped: () -> Unit,
    ): ProjectionFrameSource?
}

/** Production [ProjectionSession] over MediaProjectionManager and ServiceCompat. */
internal object MediaProjectionSession : ProjectionSession {

    private const val CHANNEL_ID = "screen_capture"
    private const val NOTIFICATION_ID = 1
    private const val SETTINGS_REQUEST = 0
    private const val STOP_REQUEST = 1

    override fun startForeground(service: ScreenCaptureService) {
        val manager = service.getSystemService(NotificationManager::class.java)!!
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                service.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification = Notification.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_capture_notification)
            .setContentTitle(service.getString(R.string.notification_sharing_title))
            .setContentText(service.getString(R.string.notification_sharing_text))
            .setOngoing(true)
            .setContentIntent(settingsIntent(service))
            .addAction(stopAction(service))
            .build()
        ServiceCompat.startForeground(
            service,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
    }

    override fun acquire(
        context: Context,
        resultCode: Int,
        data: Intent,
        onStopped: () -> Unit,
    ): ProjectionFrameSource? {
        if (resultCode != Activity.RESULT_OK) return null
        val manager = context.getSystemService(MediaProjectionManager::class.java) ?: return null
        val projection = manager.getMediaProjection(resultCode, data) ?: return null
        return ProjectionFrameSource(context, projection, onStopped)
    }

    private fun settingsIntent(service: Service): PendingIntent =
        PendingIntent.getActivity(
            service,
            SETTINGS_REQUEST,
            Intent(service, TrampolineActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

    /** A stop action needs no new foreground-service start, so it is a plain service PendingIntent. */
    private fun stopIntent(service: Service): PendingIntent =
        PendingIntent.getService(
            service,
            STOP_REQUEST,
            Intent(service, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

    @Suppress("DEPRECATION")
    private fun stopAction(service: Service): Notification.Action =
        Notification.Action.Builder(
            0,
            service.getString(R.string.notification_stop_action),
            stopIntent(service),
        ).build()
}

/**
 * Maps each capture failure onto its user-facing message.
 */
internal val CaptureError.labelRes: Int
    get() = when (this) {
        CaptureError.ScreenshotFailed -> R.string.error_screenshot_failed
        CaptureError.SaveFailed -> R.string.error_save_failed
        CaptureError.LaunchFailed -> R.string.error_launch_failed
    }

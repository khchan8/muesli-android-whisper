package com.phequals7.muesli.meetings

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.phequals7.muesli.MainActivity
import com.phequals7.muesli.R
import com.phequals7.muesli.data.SharedStore
import com.phequals7.muesli.summaries.MeetingSummaryClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground `dataSync` service that owns AI summary generation once a
 * recording is saved. Split from [MeetingRecorderService] so the microphone
 * service can tear down immediately on stop instead of staying alive (and
 * keeping the UI in Finishing) until a possibly slow LLM finishes.
 *
 * One summary job may run process-wide; ownership is claimed synchronously
 * in [generate] before any suspension, so a double tap or a recorder handoff
 * race cannot start two jobs.
 */
class MeetingSummaryService : Service() {

    companion object {
        private const val TAG = "MeetingSummary"
        const val ACTION_GENERATE = "com.phequals7.muesli.meetings.GENERATE_SUMMARY"
        const val ACTION_CANCEL = "com.phequals7.muesli.meetings.CANCEL_SUMMARY"
        const val EXTRA_SESSION_ID = "session_id"
        private const val CHANNEL_ID = "meeting_summary"
        private const val NOTIFICATION_ID = 43
        private const val STALL_TIMEOUT_MS = 120_000L
        internal const val CANCEL_MESSAGE = "Summary generation cancelled. You can generate it again."
        internal const val INTERRUPTED_MESSAGE = "Summary generation was interrupted. Generate it again."
        const val STALL_MESSAGE = "Summary generation stalled. Try again or choose another model/provider."
        const val BUSY_MESSAGE = "Finish or cancel the current summary first."

        private val _progress = MutableStateFlow<SummaryProgress?>(null)

        /** Live progress of the one active summary, or null when idle. */
        val progress: StateFlow<SummaryProgress?> = _progress.asStateFlow()

        /** Session id that owns (or is pending) summary work; null when idle. */
        @Volatile
        private var ownedSessionId: String? = null

        val hasActiveJob: Boolean get() = ownedSessionId != null

        fun isOwned(sessionId: String): Boolean = ownedSessionId == sessionId


        /** Claims [sessionId]; duplicate same-ID claims are ignored (false). */
        @Synchronized
        private fun tryClaim(sessionId: String): Boolean {
            val current = ownedSessionId
            if (current != null) return false
            ownedSessionId = sessionId
            return true
        }

        @Synchronized
        private fun release(sessionId: String) {
            if (ownedSessionId == sessionId) {
                ownedSessionId = null
                _progress.value = null
            }
        }

        /**
         * Starts summary generation for [sessionId]. Returns false (claiming
         * nothing) when another summary is active or — for manual runs — a
         * recording is live. [fromRecorderHandoff] permits the automatic
         * handoff while this session's own finalization is still finishing.
         */
        fun generate(context: Context, sessionId: String, fromRecorderHandoff: Boolean = false): Boolean {
            if (!fromRecorderHandoff && MeetingRecordingController.isActive) return false
            if (fromRecorderHandoff) {
                val active = MeetingRecordingController.activeSessionId
                if (active != null && active != sessionId) return false
            }
            if (!tryClaim(sessionId)) return false
            _progress.value = SummaryProgress(sessionId, "Starting")
            val intent = Intent(context.applicationContext, MeetingSummaryService::class.java)
                .setAction(ACTION_GENERATE)
                .putExtra(EXTRA_SESSION_ID, sessionId)
            return try {
                context.applicationContext.startForegroundService(intent)
                true
            } catch (t: Throwable) {
                Log.e(TAG, "Could not start summary service", t)
                release(sessionId)
                // Persist a visible failure so the row is not silently stuck.
                val appContext = context.applicationContext
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    runCatching {
                        val store = SharedStore(appContext)
                        val session = store.getRecordingSessionById(sessionId) ?: return@runCatching
                        store.updateMeetingSummary(
                            sessionId = sessionId,
                            text = MeetingSummaryClient(appContext).failureNotes(
                                transcript = store.getTranscriptForSession(sessionId)?.text.orEmpty(),
                                meetingTitle = session.title,
                                error = t,
                                manualNotes = session.manualNotes.ifBlank { null },
                            ),
                            state = "failed",
                            title = session.title,
                        )
                    }
                }
                false
            }
        }

        /** Requests cancellation of the live summary for [sessionId] (no-op otherwise). */
        fun cancel(context: Context, sessionId: String) {
            if (!isOwned(sessionId)) return
            _progress.value = (_progress.value ?: SummaryProgress(sessionId, "Cancelling"))
                .copy(cancelling = true)
            val intent = Intent(context.applicationContext, MeetingSummaryService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_SESSION_ID, sessionId)
            runCatching { context.applicationContext.startService(intent) }
        }

        /**
         * Rows stuck in `generating` with no live owner (process death,
         * service kill, interrupted handoff) become failed and manually
         * retryable. Never touches the live owner's row.
         */
        suspend fun reconcileStaleGenerating(context: Context, store: SharedStore) {
            val live = ownedSessionId
            for (row in store.getSessionsWithGeneratingSummary()) {
                if (row.id == live) continue
                val session = store.getRecordingSessionById(row.id) ?: continue
                val transcript = store.getTranscriptForSession(row.id)?.text.orEmpty()
                val failureNotes = MeetingSummaryClient(context.applicationContext).failureNotes(
                    transcript = transcript,
                    meetingTitle = session.title,
                    error = MeetingSummaryClient.SummaryException(INTERRUPTED_MESSAGE),
                    manualNotes = session.manualNotes.ifBlank { null },
                )
                store.updateMeetingSummary(row.id, failureNotes, "failed", session.title)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val progressStamp = AtomicLong(0L)
    private var workJob: Job? = null
    private var watchdogJob: Job? = null
    private lateinit var store: SharedStore

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = SharedStore(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_GENERATE -> {
                val id = intent.getStringExtra(EXTRA_SESSION_ID)
                if (id == null || !isOwned(id)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                // Service-side guard: only this session's own finishing
                // recording may hand off; any other active recording refuses.
                if (MeetingRecordingController.isActive && MeetingRecordingController.activeSessionId != id) {
                    Log.w(TAG, "Refusing summary for $id while recording ${MeetingRecordingController.activeSessionId}")
                    release(id)
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (workJob?.isActive == true) return START_NOT_STICKY // duplicate delivery
                startForegroundWithNotification(id)
                runWork(id)
            }
            ACTION_CANCEL -> {
                val id = intent.getStringExtra(EXTRA_SESSION_ID)
                if (id != null && isOwned(id)) {
                    _progress.value = _progress.value?.copy(cancelling = true)
                    workJob?.cancel(CancellationException(CANCEL_MESSAGE))
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun publish(progress: SummaryProgress) {
        progressStamp.set(SystemClock.elapsedRealtime())
        _progress.value = progress
    }

    private fun runWork(sessionId: String) {
        workJob = serviceScope.launch {
            val client = MeetingSummaryClient(applicationContext)
            watchdogJob = launch {
                while (isActive) {
                    delay(5_000)
                    val stamp = progressStamp.get()
                    if (stamp != 0L && SystemClock.elapsedRealtime() - stamp > STALL_TIMEOUT_MS) {
                        Log.w(TAG, "Summary stalled with no progress for ${STALL_TIMEOUT_MS / 1000}s")
                        workJob?.cancel(CancellationException(STALL_MESSAGE))
                        break
                    }
                }
            }

            var transcriptText = ""
            var previousSummary = ""
            try {
                publish(SummaryProgress(sessionId, "Preparing"))
                reconcileStaleGenerating(applicationContext, store)

                val session = store.getRecordingSessionById(sessionId)
                if (session == null || session.phase != "completed") {
                    Log.w(TAG, "Session $sessionId missing or not completed; skipping summary")
                    return@launch
                }
                if (session.summaryState == "completed") previousSummary = session.summaryText
                transcriptText = store.getTranscriptForSession(sessionId)?.text.orEmpty()
                if (transcriptText.isBlank() || !client.isConfigured()) {
                    store.updateMeetingSummaryState(sessionId, "unavailable")
                    return@launch
                }

                publish(SummaryProgress(sessionId, if (store.summaryBackend == "local_qwen") "Loading model" else "Summarizing"))
                if (store.updateMeetingSummaryState(sessionId, "generating") == 0) return@launch

                val segments = store.getTranscriptSegments(sessionId).map { it.toDomainModel() }
                val result = client.summarize(
                    transcript = transcriptText,
                    meetingTitle = session.title,
                    template = MeetingTemplate.fromId(session.templateId),
                    manualNotesToRetain = session.manualNotes.ifBlank { null },
                    segments = segments,
                    onProgress = { stage, tokens, done, total ->
                        publish(SummaryProgress(sessionId, stage, tokens, done, total))
                    },
                )

                val latest = store.getRecordingSessionById(sessionId) ?: return@launch
                val originalTitle = latest.originalTitle ?: latest.title
                val title = combinedMeetingTitle(originalTitle, latest.title, result.title)
                val updated = store.updateMeetingSummary(
                    sessionId = sessionId,
                    text = result.notes,
                    state = "completed",
                    title = title,
                )
                if (updated == 1) {
                    if (title != latest.title) {
                        // Title change: full sync moves the owned audio name
                        // first, then the subtitle follows.
                        MeetingStorageManager.syncRecordingArtifacts(applicationContext, store, sessionId)
                    } else {
                        // Same title: only the SRT footer carries the summary.
                        MeetingStorageManager.exportSrtArtifactOnly(applicationContext, store, sessionId)
                    }
                }
            } catch (ce: CancellationException) {
                val message = ce.message?.takeIf { it.isNotBlank() } ?: CANCEL_MESSAGE
                withContext(NonCancellable) {
                    val latest = store.getRecordingSessionById(sessionId)
                    if (latest != null) {
                        if (previousSummary.isNotBlank()) {
                            // Cancelled regeneration: keep the old summary text,
                            // mark failed so the UI offers a retry.
                            store.updateMeetingSummaryState(sessionId, "failed")
                        } else {
                            store.updateMeetingSummary(
                                sessionId = sessionId,
                                text = client.failureNotes(
                                    transcript = transcriptText,
                                    meetingTitle = latest.title,
                                    error = MeetingSummaryClient.SummaryException(message),
                                    manualNotes = latest.manualNotes.ifBlank { null },
                                ),
                                state = "failed",
                                title = latest.title,
                            )
                        }
                    }
                }
                throw ce
            } catch (t: Throwable) {
                Log.e(TAG, "summary generation failed", t)
                withContext(NonCancellable) {
                    val latest = store.getRecordingSessionById(sessionId)
                    if (latest != null) {
                        store.updateMeetingSummary(
                            sessionId = sessionId,
                            text = client.failureNotes(
                                transcript = transcriptText,
                                meetingTitle = latest.title,
                                error = t,
                                manualNotes = latest.manualNotes.ifBlank { null },
                            ),
                            state = "failed",
                            title = latest.title,
                        )
                    }
                }
            } finally {
                watchdogJob?.cancel()
                // Ownership clears only after provider/native cleanup ran
                // (summarize returned or threw) — never while a model is in use.
                withContext(NonCancellable) { release(sessionId) }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onTimeout(startId: Int) {
        onTimeout(startId, 0)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground timeout (fgsType=$fgsType); cancelling summary work")
        // Drop the notification promptly, but keep the process alive until
        // the cancelled job's NonCancellable DB cleanup finishes unwinding.
        stopForeground(STOP_FOREGROUND_REMOVE)
        workJob?.cancel(CancellationException(INTERRUPTED_MESSAGE))
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── notification ─────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Meeting Summaries",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Shown while Muesli generates a meeting summary" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForegroundWithNotification(sessionId: String) {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MeetingSummaryService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_SESSION_ID, sessionId),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Generating summary")
            .setContentText("Muesli")
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(0, "Cancel summary", cancelIntent)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}

/** Live progress snapshot of the one active summary job. */
data class SummaryProgress(
    val sessionId: String,
    val stage: String,
    val generatedTokens: Int = 0,
    val completedParts: Int = 0,
    val totalParts: Int = 0,
    val cancelling: Boolean = false,
)

internal fun combinedMeetingTitle(original: String, current: String, generated: String): String {
    val base = original.trim()
    val suggested = generated.trim()
    return if (suggested.isEmpty() || suggested.equals(current.trim(), ignoreCase = true) ||
        suggested.equals(base, ignoreCase = true)
    ) current else "$base - $suggested"
}

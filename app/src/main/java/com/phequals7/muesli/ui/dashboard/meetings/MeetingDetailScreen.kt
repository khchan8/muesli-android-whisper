package com.phequals7.muesli.ui.dashboard.meetings

import android.content.Intent
import android.media.MediaPlayer
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phequals7.muesli.data.SharedStore
import com.phequals7.muesli.data.entity.MeetingTranscriptSegmentEntity
import com.phequals7.muesli.data.entity.RecordingSession
import com.phequals7.muesli.meetings.DiarizedTranscriptSegment
import com.phequals7.muesli.meetings.MeetingStorageManager
import com.phequals7.muesli.meetings.MeetingTranscriptSrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Binary-searches sorted [segments] for the transcript block matching [positionMs].
 * Keeps the most recently finished block highlighted during short (< 500ms) inter-turn pauses.
 */
fun findActiveTranscriptSegmentIndex(
    segments: List<DiarizedTranscriptSegment>,
    positionMs: Long
): Int {
    if (segments.isEmpty()) return -1
    var low = 0
    var high = segments.lastIndex
    var recentCandidate = -1

    while (low <= high) {
        val mid = (low + high) ushr 1
        val seg = segments[mid]
        when {
            positionMs < seg.startMs -> {
                high = mid - 1
            }
            positionMs > seg.endMs -> {
                if (positionMs - seg.endMs <= 500L) {
                    recentCandidate = mid
                }
                low = mid + 1
            }
            else -> return mid
        }
    }
    return recentCandidate
}

/**
 * Meeting detail: transcript-first playback. A compact play/pause + seek strip
 * leaves the whole height to the subtitle cues, which follow playback
 * automatically (a manual drag pauses following for four seconds).
 */
@Composable
fun MeetingDetailScreen(
    store: SharedStore,
    meeting: RecordingSession,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current

    val segmentsFlow = remember(store, meeting.id) {
        store.getTranscriptSegmentsFlow(meeting.id)
    }
    val loadedSegmentEntities by segmentsFlow.collectAsState(initial = null)
    val segmentEntities = loadedSegmentEntities.orEmpty()
    val segments = remember(segmentEntities) { segmentEntities.map { it.toDomainModel() } }
    val segmentProjection = remember(loadedSegmentEntities) {
        loadedSegmentEntities?.map {
            com.phequals7.muesli.data.dao.Mp4SegmentProjection(
                orderIndex = it.orderIndex,
                startMs = it.startMs,
                endMs = it.endMs,
                speakerLabel = it.speakerLabel,
                text = it.text,
            )
        }
    }
    val transcriptFlow = remember(store, meeting.id) {
        store.getTranscriptForSessionFlow(meeting.id)
    }
    val transcriptRow by transcriptFlow.collectAsState(initial = null)
    var notes by remember { mutableStateOf(meeting.manualNotes) }
    val summaryProgress by com.phequals7.muesli.meetings.MeetingSummaryService.progress.collectAsState()
    val mp4ProgressAll by com.phequals7.muesli.meetings.MeetingMp4ExportService.progress.collectAsState()
    val mp4QueuedIds by com.phequals7.muesli.meetings.MeetingMp4ExportService.queuedSessionIds.collectAsState()
    val scope = rememberCoroutineScope()
    var folderError by remember { mutableStateOf<String?>(null) }
    val chooseFolder = com.phequals7.muesli.meetings.rememberRecordingsFolderPicker(
        store,
        onSelected = {
            folderError = null
            scope.launch(Dispatchers.IO) {
                MeetingStorageManager.syncRecordingArtifacts(context.applicationContext, store, meeting.id)
            }
        }
    ) { folderError = it }
    fun leaveDetail() {
        scope.launch {
            if (notes != meeting.manualNotes) {
                store.updateMeetingNotes(meeting.id, notes)
                MeetingStorageManager.exportSrtArtifactOnly(context.applicationContext, store, meeting.id)
            }
            onBack()
        }
    }
    BackHandler { leaveDetail() }
    LaunchedEffect(notes) {
        if (notes == meeting.manualNotes) return@LaunchedEffect
        delay(600)
        if (store.updateMeetingNotes(meeting.id, notes) > 0) {
            // Notes live in the SRT footer: subtitle-only export never
            // touches the committed audio.
            MeetingStorageManager.exportSrtArtifactOnly(context.applicationContext, store, meeting.id)
        }
    }

    // Rows stuck `generating` by a dead process become retryable on entry.
    LaunchedEffect(meeting.id) {
        launch {
            runCatching {
                com.phequals7.muesli.meetings.MeetingSummaryService.reconcileStaleGenerating(context.applicationContext, store)
            }
        }
    }

    // Lazily materialize the SRT sidecar / normalize names for already
    // completed recordings when detail is opened (once per id/path/title).
    LaunchedEffect(meeting.id, meeting.audioFileName, meeting.title) {
        launch(Dispatchers.IO) {
            runCatching { MeetingStorageManager.syncRecordingArtifacts(context.applicationContext, store, meeting.id) }
        }
    }

    // Completed-session subtitle refresh keyed to persisted segment entities
    // and summary text too (speaker labels / AI summary live in the SRT), not
    // just note edits. Stage 2 never touches the committed audio.
    val enteredCompleted = remember(meeting.id) { meeting.phase == "completed" }
    var lastSyncedSubtitleKey by remember(meeting.id) { mutableStateOf<Any?>(null) }
    LaunchedEffect(meeting.id, meeting.phase, meeting.summaryText, segmentProjection) {
        if (meeting.phase != "completed" || segmentProjection == null) return@LaunchedEffect
        val key = Triple(meeting.phase, meeting.summaryText, segmentProjection)
        if (lastSyncedSubtitleKey == null && enteredCompleted) {
            // Initial entry for an already-completed meeting is covered by
            // syncRecordingArtifacts above; only subsequent changes re-export.
            lastSyncedSubtitleKey = key
            return@LaunchedEffect
        }
        if (lastSyncedSubtitleKey == key) return@LaunchedEffect
        lastSyncedSubtitleKey = key
        launch(Dispatchers.IO) {
            runCatching { MeetingStorageManager.exportSrtArtifactOnly(context.applicationContext, store, meeting.id) }
        }
    }

    // v2 recordings store an absolute /sdcard path; v0.3.x rows store a file name.
    val audioFilePath = remember(meeting.audioFileName) {
        MeetingStorageManager.resolveRecordingAudio(context.filesDir, meeting.audioFileName)?.absolutePath
    }

    val dateLine = remember(meeting.id) {
        buildString {
            append(SimpleDateFormat("d MMM yyyy 'at' h:mm a", Locale.getDefault()).format(Date(meeting.createdAt)))
            if (meeting.startedAt != null && meeting.endedAt != null) {
                val mins = (meeting.endedAt - meeting.startedAt) / 60_000L
                append(if (mins >= 1) " · $mins min" else " · <1 min")
            }
        }
    }

    val transcriptText = transcriptRow?.text.orEmpty()
    val transcriptAvailable = remember(transcriptText, segments) {
        transcriptText.isNotBlank() || segments.isNotEmpty()
    }

    MeetingDetailContent(
        meetingId = meeting.id,
        meetingPhase = meeting.phase,
        meetingTitle = meeting.title,
        subtitleLine = dateLine,
        audioFilePath = audioFilePath,
        segments = segments,
        legacyFlatTranscript = transcriptText.takeIf { it.isNotBlank() },
        manualNotes = notes,
        aiSummary = meeting.summaryText.takeIf { it.isNotBlank() },
        summaryState = meeting.summaryState,
        summaryProgress = summaryProgress,
        mp4Progress = mp4ProgressAll?.takeIf { it.sessionId == meeting.id },
        mp4Queued = meeting.id in mp4QueuedIds,
        mp4Eligible = meeting.phase == "completed" &&
            meeting.audioFileName?.endsWith(".m4a", ignoreCase = true) == true,
        mp4EligibilityNote = if (meeting.phase != "completed") null else when {
            meeting.audioFileName.isNullOrBlank() ->
                "This meeting has no retained audio, so no MP4 can be exported."
            !meeting.audioFileName.endsWith(".m4a", ignoreCase = true) ->
                "This meeting retains legacy WAV audio; the MP4 export needs the AAC M4A format."
            else -> null
        },
        hasExportedMp4 = !meeting.exportedMp4Uri.isNullOrBlank(),
        mp4ExportError = meeting.mp4ExportError,
        transcriptAvailable = transcriptAvailable,
        exportError = folderError ?: meeting.errorMessage?.takeIf { it.startsWith(MeetingStorageManager.EXPORT_ERROR_PREFIX) },
        onNotesChanged = { notes = it },
        onGenerateSummary = {
            // Save the current draft before dispatch so manual notes are
            // never lost to the summary's own retention step.
            scope.launch {
                if (notes != meeting.manualNotes) {
                    store.updateMeetingNotes(meeting.id, notes)
                    MeetingStorageManager.exportSrtArtifactOnly(context.applicationContext, store, meeting.id)
                }
                com.phequals7.muesli.meetings.MeetingSummaryService.generate(context, meeting.id)
            }
        },
        onCancelSummary = {
            com.phequals7.muesli.meetings.MeetingSummaryService.cancel(context, meeting.id)
        },
        onRetryExport = {
            scope.launch(Dispatchers.IO) {
                runCatching { MeetingStorageManager.syncRecordingArtifacts(context.applicationContext, store, meeting.id) }
            }
        },
        onExportMp4 = {
            // Manual export: audio/SRT first, then a forced MP4 request that
            // bypasses (without changing) the Settings toggle.
            scope.launch(Dispatchers.IO) {
                MeetingStorageManager.syncRecordingArtifacts(context.applicationContext, store, meeting.id)
                com.phequals7.muesli.meetings.MeetingMp4ExportService.requestExport(
                    context.applicationContext, meeting.id, force = true
                )
            }
        },
        onCancelMp4Export = {
            com.phequals7.muesli.meetings.MeetingMp4ExportService.cancel(context, meeting.id)
        },
        onChooseFolder = { chooseFolder() },
        onShareTranscript = { formatted ->
            val body = buildString {
                append(meeting.title).append("\n").append(dateLine).append("\n\n")
                if (meeting.summaryText.isNotBlank()) append(meeting.summaryText).append("\n\n")
                if (notes.isNotBlank()) append("## Written Notes\n").append(notes).append("\n\n")
                append("## Transcript\n").append(formatted)
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, meeting.title)
                putExtra(Intent.EXTRA_TEXT, body)
            }
            context.startActivity(Intent.createChooser(intent, "Share meeting"))
        },
        onDelete = {
            scope.launch {
                com.phequals7.muesli.meetings.MeetingSummaryService.cancel(context, meeting.id)
                store.deleteRecordingSession(meeting)
                onDeleted()
            }
        },
        onNavigateBack = { leaveDetail() },
    )
}

/**
 * Parametrized meeting detail surface supporting:
 * 1. Block-level synchronized audio-text highlighting during playback (80ms position polling + O(log N) binary search).
 * 2. Automatic following of the active cue; a user drag suspends following for four seconds after release.
 * 3. Tap-any-cue to seek audio playback directly to `segment.startMs`.
 * 4. A compact 56dp play/pause + slider + time strip so the transcript keeps the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeetingDetailContent(
    meetingId: String,
    meetingPhase: String,
    meetingTitle: String,
    subtitleLine: String,
    audioFilePath: String?,
    segments: List<DiarizedTranscriptSegment>,
    legacyFlatTranscript: String?,
    manualNotes: String,
    aiSummary: String?,
    summaryState: String,
    summaryProgress: com.phequals7.muesli.meetings.SummaryProgress?,
    mp4Progress: com.phequals7.muesli.meetings.Mp4ExportProgress?,
    mp4Queued: Boolean,
    mp4Eligible: Boolean,
    mp4EligibilityNote: String?,
    hasExportedMp4: Boolean,
    mp4ExportError: String?,
    transcriptAvailable: Boolean,
    exportError: String?,
    onNotesChanged: (String) -> Unit,
    onGenerateSummary: () -> Unit,
    onCancelSummary: () -> Unit,
    onChooseFolder: () -> Unit,
    onRetryExport: () -> Unit,
    onExportMp4: () -> Unit,
    onCancelMp4Export: () -> Unit,
    onShareTranscript: (String) -> Unit,
    onDelete: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val lazyListState = rememberLazyListState()

    // Audio playback state
    var isPlaying by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var totalDurationMs by remember { mutableLongStateOf(0L) }
    var isUserDraggingSeekSlider by remember { mutableStateOf(false) }
    var sliderPreviewMs by remember { mutableFloatStateOf(0f) }

    // Manual drag cooldown: following resumes four seconds after the user releases a drag.
    var lastUserManualScrollMs by remember { mutableLongStateOf(0L) }
    val isDragged by lazyListState.interactionSource.collectIsDraggedAsState()
    var wasDragged by remember { mutableStateOf(false) }
    LaunchedEffect(isDragged) {
        if (isDragged || wasDragged) lastUserManualScrollMs = System.currentTimeMillis()
        wasDragged = isDragged
    }

    // Tabs: 0 = Transcript, 1 = Summary
    var selectedTabIndex by remember { mutableIntStateOf(0) }

    // Same normalization as the on-disk SRT: on-screen cues and sidecars cannot diverge.
    val cues = remember(segments) { MeetingTranscriptSrt.normalizedSegments(segments) }

    val audioFile = remember(audioFilePath) {
        audioFilePath?.let { File(it) }?.takeIf { MeetingStorageManager.isUsableAudioFile(it) }
    }

    val mediaPlayer = remember(audioFile?.absolutePath) {
        if (audioFile != null) {
            try {
                MediaPlayer().apply {
                    setDataSource(audioFile.absolutePath)
                    prepare()
                    setOnCompletionListener {
                        isPlaying = false
                        currentPositionMs = totalDurationMs
                    }
                }
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
    }

    DisposableEffect(mediaPlayer) {
        onDispose {
            try {
                mediaPlayer?.stop()
            } catch (_: Exception) {}
            mediaPlayer?.release()
        }
    }

    // A path change (title-driven rename) rebinds the player: reset transport state.
    LaunchedEffect(mediaPlayer) {
        isPlaying = false
        currentPositionMs = 0L
        totalDurationMs = try {
            mediaPlayer?.duration?.toLong()?.coerceAtLeast(1L) ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    // 80ms reactive playback position ticker while audio is playing
    LaunchedEffect(mediaPlayer, isPlaying) {
        while (isActive && isPlaying && mediaPlayer != null) {
            if (!isUserDraggingSeekSlider) {
                try {
                    currentPositionMs = mediaPlayer.currentPosition.toLong()
                } catch (_: IllegalStateException) {
                    // Player was released/rebound mid-poll.
                    isPlaying = false
                }
            }
            delay(80L)
        }
    }

    // Compute active transcript cue index via binary search
    val activeSegmentIndex = remember(cues, currentPositionMs) {
        findActiveTranscriptSegmentIndex(cues, currentPositionMs)
    }

    // Follow the active cue while playing on the Transcript tab. Restarting on
    // any key change prevents stale scroll targets.
    LaunchedEffect(activeSegmentIndex, isPlaying, selectedTabIndex, isDragged, lastUserManualScrollMs) {
        if (!isPlaying || selectedTabIndex != 0 || isDragged) return@LaunchedEffect
        if (activeSegmentIndex !in cues.indices) return@LaunchedEffect
        val cooldownRemaining = MANUAL_SCROLL_COOLDOWN_MS - (System.currentTimeMillis() - lastUserManualScrollMs)
        if (cooldownRemaining > 0L) delay(cooldownRemaining)
        lazyListState.animateScrollToItem(
            index = activeSegmentIndex,
            scrollOffset = -160
        )
    }

    fun seekToMs(targetMs: Long, autoPlay: Boolean = true) {
        val player = mediaPlayer ?: return
        val clamped = targetMs.coerceIn(0L, totalDurationMs.coerceAtLeast(1L))
        player.seekTo(clamped.toInt())
        currentPositionMs = clamped
        // An explicit seek re-anchors following to the user's chosen position.
        lastUserManualScrollMs = 0L
        if (autoPlay && !player.isPlaying) {
            player.start()
            isPlaying = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = meetingTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = subtitleLine + (audioFile?.let {
                                " · ${it.absolutePath.replace("/storage/emulated/0/", "/sdcard/")}"
                            } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val formatted = if (cues.isNotEmpty()) {
                                cues.joinToString("\n\n") { seg ->
                                    "[${formatDurationMs(seg.startMs)}] ${seg.speakerLabel}: ${seg.text}"
                                }
                            } else {
                                legacyFlatTranscript.orEmpty()
                            }
                            onShareTranscript(formatted)
                        }
                    ) {
                        Text("Share")
                    }
                    var mp4MenuOpen by remember { mutableStateOf(false) }
                    val mp4Busy = mp4Progress != null || mp4Queued
                    IconButton(onClick = { mp4MenuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More actions")
                    }
                    DropdownMenu(expanded = mp4MenuOpen, onDismissRequest = { mp4MenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(if (hasExportedMp4) "Regenerate MP4" else "Export MP4") },
                            onClick = { mp4MenuOpen = false; onExportMp4() },
                            enabled = mp4Eligible && !mp4Busy
                        )
                        if (mp4EligibilityNote != null) {
                            DropdownMenuItem(
                                text = { Text(mp4EligibilityNote, style = MaterialTheme.typography.labelSmall) },
                                onClick = {},
                                enabled = false
                            )
                        }
                    }
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            )
        },
        bottomBar = {
            if (mediaPlayer != null && audioFile != null) {
                CompactPlayerBar(
                    isPlaying = isPlaying,
                    currentPositionMs = if (isUserDraggingSeekSlider) sliderPreviewMs.toLong() else currentPositionMs,
                    totalDurationMs = totalDurationMs,
                    onTogglePlayPause = {
                        if (mediaPlayer.isPlaying) {
                            mediaPlayer.pause()
                            isPlaying = false
                        } else {
                            if (currentPositionMs >= totalDurationMs - 200L) {
                                mediaPlayer.seekTo(0)
                                currentPositionMs = 0L
                            }
                            mediaPlayer.start()
                            isPlaying = true
                        }
                    },
                    onSeekValueChange = { newValue ->
                        isUserDraggingSeekSlider = true
                        sliderPreviewMs = newValue
                    },
                    onSeekValueChangeFinished = {
                        seekToMs(sliderPreviewMs.toLong(), autoPlay = isPlaying)
                        isUserDraggingSeekSlider = false
                    }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            exportError?.let { err ->
                Text(
                    err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) {
                    TextButton(onClick = onChooseFolder) {
                        Text("Choose recordings folder")
                    }
                    TextButton(onClick = onRetryExport) {
                        Text("Retry export")
                    }
                }
            }
            val activeMp4Progress = mp4Progress
            if (activeMp4Progress != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (activeMp4Progress.cancelling) "Cancelling MP4 export…"
                                else "${activeMp4Progress.stage}…",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp)
                            )
                        }
                        TextButton(
                            onClick = onCancelMp4Export,
                            enabled = !activeMp4Progress.cancelling
                        ) {
                            Text("Cancel", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            } else if (mp4Queued) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text("MP4 export queued…", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = onCancelMp4Export) { Text("Cancel") }
                }
            }
            if (mp4ExportError != null && mp4Progress == null) {
                Text(
                    mp4ExportError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
                TextButton(
                    onClick = onExportMp4,
                    enabled = mp4Eligible,
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) {
                    Text("Retry MP4 export")
                }
            }
            TabRow(selectedTabIndex = selectedTabIndex) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    text = { Text("Transcript") }
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    text = { Text("Summary") }
                )
            }

            when (selectedTabIndex) {
                0 -> {
                    if (cues.isEmpty()) {
                        if (!legacyFlatTranscript.isNullOrBlank()) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                item {
                                    Text(
                                        text = legacyFlatTranscript,
                                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No speech was transcribed.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        // Synchronized cue list gets the remaining height.
                        LazyColumn(
                            state = lazyListState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(
                                items = cues,
                                key = { _, seg -> seg.segmentId }
                            ) { index, segment ->
                                TranscriptCueCard(
                                    ordinal = index + 1,
                                    segment = segment,
                                    isActiveBlock = (index == activeSegmentIndex) && (isPlaying || currentPositionMs > 0L),
                                    onCueClick = { seekToMs(segment.startMs, autoPlay = true) }
                                )
                            }
                        }
                    }
                }
                1 -> {
                    SummaryTab(
                        meetingId = meetingId,
                        meetingPhase = meetingPhase,
                        manualNotes = manualNotes,
                        aiSummary = aiSummary,
                        summaryState = summaryState,
                        summaryProgress = summaryProgress,
                        transcriptAvailable = transcriptAvailable,
                        onNotesChanged = onNotesChanged,
                        onGenerateSummary = onGenerateSummary,
                        onCancelSummary = onCancelSummary
                    )
                }
            }
        }
    }

}

private const val MANUAL_SCROLL_COOLDOWN_MS = 4_000L

/**
 * Renders one subtitle cue: ordinal + full SRT timestamp range header, then
 * `Speaker: text` inline. Tapping seeks playback to the cue start.
 */
@Composable
private fun TranscriptCueCard(
    ordinal: Int,
    segment: DiarizedTranscriptSegment,
    isActiveBlock: Boolean,
    onCueClick: () -> Unit
) {
    val speakerColor = parseHexColor(segment.badgeColorHex)
    val backgroundColor by animateColorAsState(
        targetValue = if (isActiveBlock) {
            speakerColor.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        },
        animationSpec = tween(durationMillis = 180),
        label = "blockBgColor"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isActiveBlock) speakerColor else Color.Transparent,
        animationSpec = tween(durationMillis = 180),
        label = "blockBorderColor"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCueClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        border = BorderStroke(if (isActiveBlock) 2.dp else 0.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                text = "#$ordinal · ${MeetingTranscriptSrt.formatTimestamp(segment.startMs)} --> " +
                    "${MeetingTranscriptSrt.formatTimestamp(segment.endMs)}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isActiveBlock) FontWeight.Bold else FontWeight.Normal,
                color = if (isActiveBlock) speakerColor else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = MeetingTranscriptSrt.cueText(segment).replace('\n', ' '),
                style = MaterialTheme.typography.bodyMedium.copy(
                    lineHeight = 22.sp,
                    fontWeight = if (isActiveBlock) FontWeight.Medium else FontWeight.Normal
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** Compact one-line player: play/pause button, seek slider, elapsed/total time. */
@Composable
private fun CompactPlayerBar(
    isPlaying: Boolean,
    currentPositionMs: Long,
    totalDurationMs: Long,
    onTogglePlayPause: () -> Unit,
    onSeekValueChange: (Float) -> Unit,
    onSeekValueChangeFinished: () -> Unit
) {
    Surface(
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            IconButton(
                onClick = onTogglePlayPause,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = currentPositionMs.toFloat().coerceIn(0f, totalDurationMs.toFloat().coerceAtLeast(1f)),
                valueRange = 0f..totalDurationMs.toFloat().coerceAtLeast(1f),
                onValueChange = onSeekValueChange,
                onValueChangeFinished = onSeekValueChangeFinished,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${formatDurationMs(currentPositionMs)} / ${formatDurationMs(totalDurationMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

@Composable
private fun SummaryTab(
    meetingId: String,
    meetingPhase: String,
    manualNotes: String,
    aiSummary: String?,
    summaryState: String,
    summaryProgress: com.phequals7.muesli.meetings.SummaryProgress?,
    transcriptAvailable: Boolean,
    onNotesChanged: (String) -> Unit,
    onGenerateSummary: () -> Unit,
    onCancelSummary: () -> Unit
) {
    val activeHere = summaryProgress?.sessionId == meetingId
    val anotherSummary = summaryProgress != null && !activeHere
    val recordingBusy = com.phequals7.muesli.meetings.MeetingRecordingController.isActive

    // Vertically scrollable so long summaries never hide the actions below.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when {
            activeHere -> {
                val p = summaryProgress!!
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (p.cancelling) "Cancelling summary…" else "${p.stage}…",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        val detail = buildString {
                            if (p.totalParts > 0) append("${p.completedParts}/${p.totalParts} parts")
                            if (p.generatedTokens > 0) {
                                if (isNotEmpty()) append(" · ")
                                append("${p.generatedTokens} tokens")
                            }
                        }
                        if (detail.isNotEmpty()) {
                            Text(detail, style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        // Cancellation can take a while while native decode
                        // unwinds; the button stays disabled meanwhile.
                        TextButton(onClick = onCancelSummary, enabled = !p.cancelling) {
                            Text("Cancel Summary", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            summaryState == "generating" -> {
                Text(
                    "Generating summary…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Statuses render even when old summary text exists below.
        if (!activeHere) {
            when (summaryState) {
                "failed" -> Text(
                    "Summary generation failed or was cancelled. Generate it again.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
                "unavailable" -> Text(
                    "The summary provider is not configured. Set it up in Settings → AI Summaries.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (!aiSummary.isNullOrBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "AI Meeting Summary",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    SelectionContainer {
                        Text(
                            text = aiSummary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        if (meetingPhase == "completed" && transcriptAvailable && !activeHere) {
            val regenerate = summaryState == "completed" && !aiSummary.isNullOrBlank()
            Button(
                onClick = onGenerateSummary,
                enabled = !recordingBusy && !anotherSummary,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (regenerate) "Regenerate Summary" else "Generate Summary")
            }
            if (recordingBusy) {
                Text(
                    "A recording is still finishing.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (anotherSummary) {
                Text(
                    com.phequals7.muesli.meetings.MeetingSummaryService.BUSY_MESSAGE,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        OutlinedTextField(
            value = manualNotes,
            onValueChange = onNotesChanged,
            label = { Text("Meeting Notes") },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp)
        )
    }
}

private fun formatDurationMs(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L)) / 1000L
    val hours = totalSec / 3600L
    val minutes = (totalSec % 3600L) / 60L
    val seconds = totalSec % 60L
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

private fun parseHexColor(hex: String): Color {
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (_: Exception) {
        Color(0xFF1D63B8)
    }
}

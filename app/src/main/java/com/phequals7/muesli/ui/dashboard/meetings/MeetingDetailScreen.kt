package com.phequals7.muesli.ui.dashboard.meetings

import android.content.Intent
import android.media.MediaPlayer
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phequals7.muesli.data.AppDatabase
import com.phequals7.muesli.data.SharedStore
import com.phequals7.muesli.data.entity.MeetingTranscriptSegmentEntity
import com.phequals7.muesli.data.entity.RecordingSession
import com.phequals7.muesli.meetings.DiarizedTranscriptSegment
import com.phequals7.muesli.meetings.RawTranscriptChunk
import com.phequals7.muesli.meetings.SpeakerDiarizer
import com.phequals7.muesli.meetings.SpeakerProfileRecord
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
 * Meeting detail for v2.0: block-level synchronized audio-text playback, one-tap
 * speaker profile enrollment (persistent CAM++ voiceprints), speaker-count
 * re-diarization, and public `/sdcard/Recordings/Muesli` playback.
 */
@Composable
fun MeetingDetailScreen(
    store: SharedStore,
    meeting: RecordingSession,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profileStore = remember { AppDatabase.getSpeakerProfileStore(context.applicationContext) }

    val segmentEntities by store.getTranscriptSegmentsFlow(meeting.id)
        .collectAsState(initial = emptyList<MeetingTranscriptSegmentEntity>())
    val segments = remember(segmentEntities) { segmentEntities.map { it.toDomainModel() } }
    val enrolledProfiles by profileStore.observeProfiles()
        .collectAsState(initial = emptyList<SpeakerProfileRecord>())

    var notes by remember { mutableStateOf(meeting.manualNotes) }
    var isReDiarizing by remember { mutableStateOf(false) }
    var reDiarizationProgress by remember { mutableFloatStateOf(0f) }

    // Debounced notes persistence (600 ms, same as v0.3.x).
    LaunchedEffect(notes) {
        if (notes == meeting.manualNotes) return@LaunchedEffect
        delay(600)
        store.updateRecordingSession(meeting.copy(manualNotes = notes))
    }

    // v2 recordings store an absolute /sdcard path; v0.3.x rows store a file name.
    val audioFilePath = remember(meeting.audioFileName) {
        meeting.audioFileName?.let { name ->
            if (name.startsWith("/")) name
            else File(context.filesDir, "meetings/$name").absolutePath
        }
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

    MeetingDetailContent(
        sessionId = meeting.id,
        meetingTitle = meeting.title,
        subtitleLine = dateLine,
        audioFilePath = audioFilePath,
        segments = segments,
        enrolledProfiles = enrolledProfiles,
        manualNotes = notes,
        aiSummary = meeting.summaryText.takeIf { it.isNotBlank() },
        isReDiarizing = isReDiarizing,
        reDiarizationProgress = reDiarizationProgress,
        onNotesChanged = { notes = it },
        onAssignSpeakerProfile = { clusterId, centroid, displayName, existingProfileId, badgeColorHex ->
            scope.launch {
                SpeakerDiarizer(context.applicationContext, profileStore).enrollOrUpdateSpeakerProfile(
                    sessionId = meeting.id,
                    clusterId = clusterId,
                    clusterCentroid = centroid,
                    displayName = displayName,
                    existingProfileId = existingProfileId,
                    badgeColorHex = badgeColorHex
                )
            }
        },
        onReDiarizeWithSpeakerCount = { expectedSpeakers ->
            val wav = audioFilePath?.let { File(it) }?.takeIf { it.exists() && it.length() > 44L }
            if (wav == null || segments.isEmpty() || isReDiarizing) return@MeetingDetailContent
            scope.launch {
                isReDiarizing = true
                reDiarizationProgress = 0.02f
                try {
                    val rawChunks = segments.map {
                        RawTranscriptChunk(it.startMs, it.endMs, it.text, it.languageCode)
                    }
                    val result = SpeakerDiarizer(context.applicationContext, profileStore).diarizeWavAndAlign(
                        sessionId = meeting.id,
                        wavFile = wav,
                        rawChunks = rawChunks,
                        expectedSpeakerCount = expectedSpeakers,
                        onProgress = { p -> reDiarizationProgress = p }
                    )
                    store.replaceTranscriptSegments(
                        meeting.id,
                        result.segments.map { MeetingTranscriptSegmentEntity.fromDomainModel(it) },
                        result.formattedInlineTranscript
                    )
                } catch (t: Throwable) {
                    android.util.Log.w("MeetingDetail", "Re-diarization failed: ${t.message}")
                } finally {
                    isReDiarizing = false
                }
            }
        },
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
                store.deleteRecordingSession(meeting)
                onDeleted()
            }
        },
        onNavigateBack = onBack,
    )
}

/**
 * Parametrized meeting detail surface supporting:
 * 1. Block-level synchronized audio-text highlighting during playback (80ms position polling + O(log N) binary search).
 * 2. Smooth auto-scrolling to the active transcript block with a 4-second manual scroll cooldown and "Follow Text" toggle.
 * 3. Tap-any-block to seek audio playback directly to `segment.startMs`.
 * 4. One-tap Speaker Profile labeling & enrollment bottom sheet (saving 192-dim CAM++ centroids to recognize the speaker in future meetings).
 * 5. Constrained speaker count re-diarization selector (`Auto`, `2`, `3`, `4`, `5+` speakers) and public `/sdcard/Recordings/Muesli` path indicator.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeetingDetailContent(
    sessionId: String,
    meetingTitle: String,
    subtitleLine: String,
    audioFilePath: String?,
    segments: List<DiarizedTranscriptSegment>,
    enrolledProfiles: List<SpeakerProfileRecord>,
    manualNotes: String,
    aiSummary: String?,
    isReDiarizing: Boolean = false,
    reDiarizationProgress: Float = 0f,
    onNotesChanged: (String) -> Unit,
    onAssignSpeakerProfile: (
        clusterId: Int,
        clusterCentroid: FloatArray,
        displayName: String,
        existingProfileId: String?,
        badgeColorHex: String
    ) -> Unit,
    onReDiarizeWithSpeakerCount: (expectedSpeakers: Int) -> Unit,
    onShareTranscript: (String) -> Unit,
    onDelete: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()

    // Audio playback state
    var isPlaying by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var totalDurationMs by remember { mutableLongStateOf(0L) }
    var isUserDraggingSeekSlider by remember { mutableStateOf(false) }
    var sliderPreviewMs by remember { mutableFloatStateOf(0f) }

    // Manual scroll cooldown tracking so user scrolling is not hijacked
    var lastUserManualScrollMs by remember { mutableLongStateOf(0L) }
    var autoFollowEnabled by remember { mutableStateOf(true) }

    // Tabs: 0 = Transcript (Synced), 1 = Notes & AI Summary
    var selectedTabIndex by remember { mutableIntStateOf(0) }

    // Speaker Count Hint filter for re-running CAM++ diarization
    var selectedSpeakerCountHint by remember { mutableIntStateOf(-1) }

    // Speaker Profile labeling ModalBottomSheet state
    var segmentForSpeakerLabeling by remember { mutableStateOf<DiarizedTranscriptSegment?>(null) }

    // Initialize and manage Android MediaPlayer for /sdcard/Recordings/Muesli/<session>.wav
    val wavFile = remember(audioFilePath) {
        audioFilePath?.let { File(it) }?.takeIf { it.exists() && it.length() > 44L }
    }

    val mediaPlayer = remember(wavFile?.absolutePath) {
        if (wavFile != null) {
            try {
                MediaPlayer().apply {
                    setDataSource(wavFile.absolutePath)
                    prepare()
                    totalDurationMs = duration.toLong().coerceAtLeast(1L)
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

    // 80ms reactive playback position ticker while audio is playing
    LaunchedEffect(mediaPlayer, isPlaying) {
        while (isActive && isPlaying && mediaPlayer != null) {
            if (!isUserDraggingSeekSlider) {
                currentPositionMs = mediaPlayer.currentPosition.toLong()
            }
            delay(80L)
        }
    }

    // Track manual user touch scrolling in the transcript LazyColumn
    LaunchedEffect(lazyListState.isScrollInProgress) {
        if (lazyListState.isScrollInProgress && isPlaying) {
            lastUserManualScrollMs = System.currentTimeMillis()
        }
    }

    // Compute active transcript block index via binary search
    val activeSegmentIndex = remember(segments, currentPositionMs) {
        findActiveTranscriptSegmentIndex(segments, currentPositionMs)
    }

    // Auto-scroll LazyColumn to keep the highlighted block centered during playback
    LaunchedEffect(activeSegmentIndex, isPlaying, autoFollowEnabled) {
        if (isPlaying && autoFollowEnabled && selectedTabIndex == 0 && activeSegmentIndex in segments.indices) {
            val cooldownElapsed = (System.currentTimeMillis() - lastUserManualScrollMs) > 4000L
            if (cooldownElapsed) {
                lazyListState.animateScrollToItem(
                    index = activeSegmentIndex,
                    scrollOffset = -160
                )
            }
        }
    }

    fun seekToMs(targetMs: Long, autoPlay: Boolean = true) {
        val player = mediaPlayer ?: return
        val clamped = targetMs.coerceIn(0L, totalDurationMs.coerceAtLeast(1L))
        player.seekTo(clamped.toInt())
        currentPositionMs = clamped
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
                            text = subtitleLine + (wavFile?.let {
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
                    TextButton(onClick = onNavigateBack) {
                        Text("Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val formatted = segments.joinToString("\n\n") { seg ->
                                "[${formatDurationMs(seg.startMs)}] ${seg.speakerLabel}: ${seg.text}"
                            }
                            onShareTranscript(formatted)
                        }
                    ) {
                        Text("Share")
                    }
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            )
        },
        bottomBar = {
            if (mediaPlayer != null && wavFile != null) {
                SynchronizedAudioPlayerBar(
                    isPlaying = isPlaying,
                    currentPositionMs = if (isUserDraggingSeekSlider) sliderPreviewMs.toLong() else currentPositionMs,
                    totalDurationMs = totalDurationMs,
                    autoFollowEnabled = autoFollowEnabled,
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
                    onSkipBackward10s = { seekToMs(currentPositionMs - 10_000L, autoPlay = isPlaying) },
                    onSkipForward10s = { seekToMs(currentPositionMs + 10_000L, autoPlay = isPlaying) },
                    onSeekValueChange = { newValue ->
                        isUserDraggingSeekSlider = true
                        sliderPreviewMs = newValue
                    },
                    onSeekValueChangeFinished = {
                        seekToMs(sliderPreviewMs.toLong(), autoPlay = isPlaying)
                        isUserDraggingSeekSlider = false
                    },
                    onToggleAutoFollow = {
                        autoFollowEnabled = !autoFollowEnabled
                        lastUserManualScrollMs = 0L
                        if (autoFollowEnabled && activeSegmentIndex in segments.indices) {
                            coroutineScope.launch {
                                lazyListState.animateScrollToItem(activeSegmentIndex, scrollOffset = -160)
                            }
                        }
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
            TabRow(selectedTabIndex = selectedTabIndex) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    text = { Text("Transcript (${segments.size} blocks)") }
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    text = { Text("Notes & Summary") }
                )
            }

            if (isReDiarizing) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Running CAM++ Speaker Diarization & Profile Matching... ${(reDiarizationProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { reDiarizationProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            when (selectedTabIndex) {
                0 -> {
                    if (segments.isEmpty()) {
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
                    } else {
                        // Speaker overview + Speaker Count constraint bar
                        SpeakerControlHeaderBar(
                            segments = segments,
                            selectedSpeakerCountHint = selectedSpeakerCountHint,
                            onSelectSpeakerCountHint = { count ->
                                selectedSpeakerCountHint = count
                                onReDiarizeWithSpeakerCount(count)
                            },
                            onSpeakerChipClicked = { sampleSegment ->
                                segmentForSpeakerLabeling = sampleSegment
                            }
                        )

                        HorizontalDivider()

                        // Block-level synchronized transcript LazyColumn
                        LazyColumn(
                            state = lazyListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            itemsIndexed(
                                items = segments,
                                key = { _, seg -> seg.segmentId }
                            ) { index, segment ->
                                val isActiveBlock = (index == activeSegmentIndex) && (isPlaying || currentPositionMs > 0L)
                                TranscriptSegmentBlockCard(
                                    segment = segment,
                                    isActiveBlock = isActiveBlock,
                                    onBlockClick = {
                                        // Tap any block to jump audio to segment.startMs and highlight
                                        lastUserManualScrollMs = 0L
                                        seekToMs(segment.startMs, autoPlay = true)
                                    },
                                    onSpeakerBadgeClick = {
                                        segmentForSpeakerLabeling = segment
                                    }
                                )
                            }
                        }
                    }
                }
                1 -> {
                    NotesAndSummaryTab(
                        manualNotes = manualNotes,
                        aiSummary = aiSummary,
                        onNotesChanged = onNotesChanged
                    )
                }
            }
        }
    }

    // ModalBottomSheet for labeling a speaker and saving/updating their persistent voiceprint profile
    val targetSegment = segmentForSpeakerLabeling
    if (targetSegment != null) {
        SpeakerProfileEnrollmentBottomSheet(
            segment = targetSegment,
            enrolledProfiles = enrolledProfiles,
            onDismiss = { segmentForSpeakerLabeling = null },
            onSaveSpeakerProfile = { displayName, existingProfileId, badgeColorHex ->
                onAssignSpeakerProfile(
                    targetSegment.clusterId,
                    targetSegment.clusterCentroid,
                    displayName,
                    existingProfileId,
                    badgeColorHex
                )
                segmentForSpeakerLabeling = null
            }
        )
    }
}

@Composable
private fun SpeakerControlHeaderBar(
    segments: List<DiarizedTranscriptSegment>,
    selectedSpeakerCountHint: Int,
    onSelectSpeakerCountHint: (Int) -> Unit,
    onSpeakerChipClicked: (DiarizedTranscriptSegment) -> Unit
) {
    val distinctClusters = remember(segments) {
        segments.groupBy { it.clusterId }.map { (_, list) -> list.first() }
    }
    val countOptions = listOf(-1 to "Auto", 2 to "2 Spk", 3 to "3 Spk", 4 to "4 Spk", 5 to "5+ Spk")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Speakers (${distinctClusters.size}) — tap a badge to label & save a voice profile:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (distinctClusters.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(distinctClusters, key = { it.clusterId }) { sampleSeg ->
                    val badgeColor = parseHexColor(sampleSeg.badgeColorHex)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = badgeColor.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.55f)),
                        modifier = Modifier.clickable { onSpeakerChipClicked(sampleSeg) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(badgeColor)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (sampleSeg.speakerProfileId != null) {
                                    "${sampleSeg.speakerLabel} ✓"
                                } else {
                                    "${sampleSeg.speakerLabel} (Set Profile)"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = badgeColor,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item {
                Text(
                    text = "Speaker Count Hint:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
            items(countOptions, key = { it.first }) { (countVal, label) ->
                FilterChip(
                    selected = selectedSpeakerCountHint == countVal,
                    onClick = { onSelectSpeakerCountHint(countVal) },
                    label = { Text(label, fontSize = 11.sp) }
                )
            }
        }
    }
}

/**
 * Renders a single speaker-attributed transcript block card that highlights when
 * [isActiveBlock] is true during audio playback and seeks audio to
 * `segment.startMs` when tapped.
 */
@Composable
private fun TranscriptSegmentBlockCard(
    segment: DiarizedTranscriptSegment,
    isActiveBlock: Boolean,
    onBlockClick: () -> Unit,
    onSpeakerBadgeClick: () -> Unit
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
            .clickable { onBlockClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        border = BorderStroke(if (isActiveBlock) 2.dp else 0.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Clickable Speaker Profile Pill Badge
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = speakerColor.copy(alpha = 0.18f),
                    modifier = Modifier.clickable { onSpeakerBadgeClick() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(speakerColor)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (segment.speakerProfileId != null) {
                                "${segment.speakerLabel} ✓"
                            } else {
                                segment.speakerLabel
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = speakerColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!segment.languageCode.isNullOrBlank() && segment.languageCode != "auto") {
                        Text(
                            text = segment.languageCode.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    Text(
                        text = "${formatDurationMs(segment.startMs)} – ${formatDurationMs(segment.endMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isActiveBlock) FontWeight.Bold else FontWeight.Normal,
                        color = if (isActiveBlock) speakerColor else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = segment.text,
                style = MaterialTheme.typography.bodyMedium.copy(
                    lineHeight = 22.sp,
                    fontWeight = if (isActiveBlock) FontWeight.Medium else FontWeight.Normal
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun SynchronizedAudioPlayerBar(
    isPlaying: Boolean,
    currentPositionMs: Long,
    totalDurationMs: Long,
    autoFollowEnabled: Boolean,
    onTogglePlayPause: () -> Unit,
    onSkipBackward10s: () -> Unit,
    onSkipForward10s: () -> Unit,
    onSeekValueChange: (Float) -> Unit,
    onSeekValueChangeFinished: () -> Unit,
    onToggleAutoFollow: () -> Unit
) {
    Surface(
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Slider(
                value = currentPositionMs.toFloat().coerceIn(0f, totalDurationMs.toFloat().coerceAtLeast(1f)),
                valueRange = 0f..totalDurationMs.toFloat().coerceAtLeast(1f),
                onValueChange = onSeekValueChange,
                onValueChangeFinished = onSeekValueChangeFinished,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${formatDurationMs(currentPositionMs)} / ${formatDurationMs(totalDurationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onSkipBackward10s) {
                        Text("-10s")
                    }
                    Button(
                        onClick = onTogglePlayPause,
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text(if (isPlaying) "Pause" else "Play")
                    }
                    TextButton(onClick = onSkipForward10s) {
                        Text("+10s")
                    }
                }

                FilterChip(
                    selected = autoFollowEnabled,
                    onClick = onToggleAutoFollow,
                    label = { Text("Follow Text", fontSize = 11.sp) }
                )
            }
        }
    }
}

/**
 * Bottom sheet for labeling a speaker cluster and saving their 192-dim CAM++ voiceprint
 * profile so Muesli automatically identifies them in future meetings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeakerProfileEnrollmentBottomSheet(
    segment: DiarizedTranscriptSegment,
    enrolledProfiles: List<SpeakerProfileRecord>,
    onDismiss: () -> Unit,
    onSaveSpeakerProfile: (displayName: String, existingProfileId: String?, badgeColorHex: String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var customName by remember(segment) {
        mutableStateOf(
            if (segment.speakerLabel.startsWith("Speaker ")) "" else segment.speakerLabel
        )
    }
    var selectedColorHex by remember(segment) { mutableStateOf(segment.badgeColorHex) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = "Label Speaker & Save Voice Profile",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Saving a profile stores this speaker's 192-dim CAM++ voiceprint so they are automatically recognized in future English, Thai, and Chinese meetings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(14.dp))

            OutlinedTextField(
                value = customName,
                onValueChange = { customName = it },
                label = { Text("Speaker Name (e.g., Khun Somchai, Alice)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Badge Color",
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(modifier = Modifier.height(6.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(SpeakerDiarizer.DEFAULT_BADGE_COLORS) { hex ->
                    val color = parseHexColor(hex)
                    val isSelected = hex.equals(selectedColorHex, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .size(if (isSelected) 34.dp else 28.dp)
                            .clip(CircleShape)
                            .background(color)
                            .clickable { selectedColorHex = hex }
                    )
                }
            }

            if (enrolledProfiles.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Or Link to an Existing Enrolled Profile:",
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(enrolledProfiles, key = { it.profileId }) { profile ->
                        OutlinedButton(
                            onClick = {
                                onSaveSpeakerProfile(
                                    profile.displayName,
                                    profile.profileId,
                                    profile.badgeColorHex
                                )
                            }
                        ) {
                            Text("${profile.displayName} (${profile.sampleCount} clips)")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        val finalName = customName.trim().ifEmpty { segment.speakerLabel }
                        onSaveSpeakerProfile(finalName, segment.speakerProfileId, selectedColorHex)
                    },
                    enabled = customName.isNotBlank() || segment.speakerProfileId != null
                ) {
                    Text("Save Profile & Label All Turns")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun NotesAndSummaryTab(
    manualNotes: String,
    aiSummary: String?,
    onNotesChanged: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
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
                    Text(
                        text = aiSummary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        OutlinedTextField(
            value = manualNotes,
            onValueChange = onNotesChanged,
            label = { Text("Meeting Notes") },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
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

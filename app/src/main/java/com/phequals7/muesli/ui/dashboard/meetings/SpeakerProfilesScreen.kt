package com.phequals7.muesli.ui.dashboard.meetings

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.phequals7.muesli.data.AppDatabase
import com.phequals7.muesli.meetings.SpeakerDiarizer
import com.phequals7.muesli.meetings.SpeakerProfileMatch
import com.phequals7.muesli.meetings.SpeakerProfileRecord
import com.phequals7.muesli.theme.MuesliCorners
import com.phequals7.muesli.theme.MuesliSpacing
import com.phequals7.muesli.theme.MuesliTheme
import com.phequals7.muesli.ui.dashboard.voicenotes.MuesliSurfaceCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Pure enrollment-clip constants/helpers (unit-tested in [EnrollmentClipTest]).
 * Kept free of Android types so the 6-second gate matches the SpeakerDiarizer contract.
 */
internal object EnrollmentClip {
    const val SAMPLE_RATE = 16000
    const val RECORD_DURATION_MS = 8_000L

    /** SpeakerDiarizer.enrollSpeakerFromAudioSamples requires >= 6s of audio. */
    const val MIN_ENROLL_SAMPLES = SAMPLE_RATE * 6

    const val RECORD_TARGET_SAMPLES = (SAMPLE_RATE * RECORD_DURATION_MS / 1000).toInt()

    fun isUsableEnrollmentClip(sampleCount: Int): Boolean = sampleCount >= MIN_ENROLL_SAMPLES

    fun similarityPercent(similarity: Float): Int =
        (similarity.coerceIn(-1f, 1f) * 100f).roundToInt()
}

/** What the next recorded clip is for. */
private enum class RecordPurpose { ENROLL, TEST }

/**
 * One-shot 16 kHz mono float-PCM clip recorder for voice enrollment.
 * The reader thread is the only thread that touches the AudioRecord lifecycle
 * (start/stop/release); [cancel] and [release] just raise an abort flag that is
 * checked between 100 ms reads, so teardown never races a native read.
 */
private class EnrollmentClipRecorder {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var aborted = false

    /**
     * Records [targetSamples] float samples on a background thread.
     * [onDone] is invoked on the main thread with the clip, or with `samples == null`
     * plus a user-readable [error] (null error = user cancelled).
     */
    fun record(targetSamples: Int, onDone: (samples: FloatArray?, error: String?) -> Unit) {
        aborted = false
        Thread {
            var record: AudioRecord? = null
            var error: String? = null
            var collected: FloatArray? = null
            try {
                val minBuffer = AudioRecord.getMinBufferSize(
                    EnrollmentClip.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT
                )
                val built = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(EnrollmentClip.SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuffer, EnrollmentClip.SAMPLE_RATE * Float.SIZE_BYTES))
                    .build()
                if (built.state != AudioRecord.STATE_INITIALIZED) {
                    built.release()
                    error = "Microphone could not be initialized."
                } else {
                    record = built
                    built.startRecording()
                    val chunk = FloatArray(EnrollmentClip.SAMPLE_RATE / 10) // 100 ms
                    val chunks = ArrayList<FloatArray>()
                    var total = 0
                    while (!aborted && total < targetSamples) {
                        val want = minOf(chunk.size, targetSamples - total)
                        val read = built.read(chunk, 0, want, AudioRecord.READ_BLOCKING)
                        if (read < 0) {
                            error = "Microphone read failed (code $read)."
                            break
                        }
                        if (read == 0) continue
                        chunks.add(chunk.copyOf(read))
                        total += read
                    }
                    if (error == null && !aborted) {
                        collected = FloatArray(total)
                        var offset = 0
                        for (piece in chunks) {
                            System.arraycopy(piece, 0, collected!!, offset, piece.size)
                            offset += piece.size
                        }
                    }
                }
            } catch (t: Throwable) {
                error = t.message ?: "Recording failed."
            } finally {
                try {
                    record?.stop()
                } catch (_: Exception) {
                }
                record?.release()
            }
            val clip = if (aborted || error != null) null else collected
            mainHandler.post { onDone(clip, error) }
        }.apply { start() }
    }

    /** Discards the in-flight clip; [record]'s callback fires with nulls shortly after. */
    fun cancel() {
        aborted = true
    }

    /** Lifecycle teardown — flag only; the reader thread releases the AudioRecord itself. */
    fun release() {
        aborted = true
    }
}

private fun badgeColor(hex: String): Color =
    try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (_: IllegalArgumentException) {
        Color.Gray
    }

/**
 * v2.1 Audio Profiles screen: pre-enroll speakers with an 8-second 16 kHz mono
 * clip, test live cosine-similarity matching, and manage enrolled voiceprints.
 * Reachable from Settings and from the MeetingDetailScreen top bar.
 */
@Composable
fun SpeakerProfilesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = MuesliTheme.colors
    val scope = rememberCoroutineScope()
    val profileStore = remember { AppDatabase.getSpeakerProfileStore(context.applicationContext) }
    val diarizer = remember { SpeakerDiarizer(context.applicationContext, profileStore) }
    val profiles by profileStore.observeProfiles()
        .collectAsState(initial = emptyList<SpeakerProfileRecord>())

    val recorder = remember { EnrollmentClipRecorder() }
    DisposableEffect(recorder) {
        onDispose { recorder.release() }
    }

    var speakerName by remember { mutableStateOf("") }
    var enrollTarget by remember { mutableStateOf<SpeakerProfileRecord?>(null) }
    var recordingPurpose by remember { mutableStateOf<RecordPurpose?>(null) }
    var recordingStartedAtMs by remember { mutableLongStateOf(0L) }
    var recordingProgress by remember { mutableFloatStateOf(0f) }
    var busyLabel by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var lastClip by remember { mutableStateOf<FloatArray?>(null) }
    var matches by remember { mutableStateOf<List<SpeakerProfileMatch>?>(null) }
    var profilePendingRename by remember { mutableStateOf<SpeakerProfileRecord?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var profilePendingDelete by remember { mutableStateOf<SpeakerProfileRecord?>(null) }

    val enrollClip: (FloatArray) -> Unit = { samples ->
        val target = enrollTarget
        val name = target?.displayName ?: speakerName.trim()
        if (name.isEmpty()) {
            errorMessage = "Enter a speaker name first."
        } else {
            busyLabel = "Building voiceprint…"
            scope.launch {
                try {
                    val saved = diarizer.enrollSpeakerFromAudioSamples(
                        samples16kMono = samples,
                        displayName = name,
                        existingProfileId = target?.profileId
                    )
                    statusMessage = "Saved \"${saved.displayName}\" (${saved.sampleCount} sample" +
                        (if (saved.sampleCount == 1) "" else "s") + ")."
                    errorMessage = null
                    enrollTarget = null
                    speakerName = ""
                } catch (t: Throwable) {
                    errorMessage = t.message ?: "Enrollment failed."
                    statusMessage = null
                } finally {
                    busyLabel = null
                }
            }
        }
    }

    val identifyClip: (FloatArray) -> Unit = { samples ->
        busyLabel = "Matching voice…"
        scope.launch {
            try {
                val found = diarizer.identifySpeakerFromAudioSamples(samples)
                matches = found
                errorMessage = null
                statusMessage = when {
                    found.isEmpty() -> "No usable match — no profiles enrolled or no speech detected."
                    else -> "Best match: ${found.first().profile.displayName} " +
                        "(${EnrollmentClip.similarityPercent(found.first().similarity)}%)"
                }
            } catch (t: Throwable) {
                errorMessage = t.message ?: "Voice matching failed."
                statusMessage = null
            } finally {
                busyLabel = null
            }
        }
    }

    val startRecording: (RecordPurpose) -> Unit = { purpose ->
        errorMessage = null
        statusMessage = null
        matches = null
        recordingPurpose = purpose
        recordingStartedAtMs = System.currentTimeMillis()
        recordingProgress = 0f
        recorder.record(EnrollmentClip.RECORD_TARGET_SAMPLES) { samples, error ->
            recordingPurpose = null
            when {
                error != null -> errorMessage = error
                samples == null -> statusMessage = "Recording cancelled."
                !EnrollmentClip.isUsableEnrollmentClip(samples.size) -> errorMessage =
                    "Clip too short (${samples.size / EnrollmentClip.SAMPLE_RATE}s); at least 6 seconds is required."
                else -> {
                    lastClip = samples
                    if (purpose == RecordPurpose.ENROLL) enrollClip(samples) else identifyClip(samples)
                }
            }
        }
    }

    var pendingPurposeAfterPermission by remember { mutableStateOf<RecordPurpose?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val purpose = pendingPurposeAfterPermission
        pendingPurposeAfterPermission = null
        if (!granted) {
            errorMessage = "Microphone permission is required to record a voice clip."
        } else {
            purpose?.let(startRecording)
        }
    }

    val requestOrStart: (RecordPurpose) -> Unit = { purpose ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startRecording(purpose)
        } else {
            pendingPurposeAfterPermission = purpose
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Drive the visible 8s progress bar from the wall clock while recording.
    LaunchedEffect(recordingPurpose) {
        if (recordingPurpose == null) return@LaunchedEffect
        while (recordingPurpose != null) {
            val elapsed = System.currentTimeMillis() - recordingStartedAtMs
            recordingProgress = (elapsed.toFloat() / EnrollmentClip.RECORD_DURATION_MS).coerceIn(0f, 1f)
            if (elapsed >= EnrollmentClip.RECORD_DURATION_MS) break
            delay(50L)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(colors.backgroundBase)) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MuesliSpacing.s8, vertical = MuesliSpacing.s8)
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = colors.textPrimary
                )
            }
            Column {
                Text(
                    "Audio Profiles",
                    color = colors.textPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp
                )
                Text(
                    "Enroll speaker voiceprints and test matching.",
                    color = colors.textSecondary,
                    fontSize = 13.sp
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(MuesliSpacing.s16),
            verticalArrangement = Arrangement.spacedBy(MuesliSpacing.s16)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(MuesliSpacing.s8)) {
                    errorMessage?.let {
                        Text(
                            it,
                            color = colors.destructive,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    statusMessage?.let {
                        Text(it, color = colors.textSecondary, fontSize = 13.sp)
                    }
                    busyLabel?.let {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MuesliSpacing.s8)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Text(it, color = colors.textSecondary, fontSize = 13.sp)
                        }
                    }
                }
            }

            // ── Enroll ─────────────────────────────────────────────────
            item {
                MuesliSurfaceCard {
                    Column(
                        modifier = Modifier.padding(MuesliSpacing.s16),
                        verticalArrangement = Arrangement.spacedBy(MuesliSpacing.s12)
                    ) {
                        Text(
                            "Enroll a Speaker",
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            "Record an 8-second clip (English, Thai, or Chinese). Muesli can use the on-device voiceprint to identify this speaker in future meetings.",
                            color = colors.textSecondary,
                            fontSize = 11.sp
                        )

                        val target = enrollTarget
                        if (target != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(MuesliSpacing.s8),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(MuesliCorners.small))
                                    .background(colors.brandBlueSubtle)
                                    .padding(horizontal = MuesliSpacing.s12, vertical = MuesliSpacing.s8)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(badgeColor(target.badgeColorHex))
                                )
                                Text(
                                    "Adding a sample to ${target.displayName}",
                                    color = colors.textPrimary,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { enrollTarget = null }, modifier = Modifier.size(24.dp)) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Cancel update",
                                        tint = colors.textSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        } else {
                            OutlinedTextField(
                                value = speakerName,
                                onValueChange = { speakerName = it },
                                label = { Text("Speaker name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        if (recordingPurpose == RecordPurpose.ENROLL) {
                            RecordingIndicator(
                                progress = recordingProgress,
                                onCancel = { recorder.cancel() }
                            )
                        } else {
                            Button(
                                onClick = { requestOrStart(RecordPurpose.ENROLL) },
                                enabled = busyLabel == null && recordingPurpose == null &&
                                    (enrollTarget != null || speakerName.isNotBlank()),
                                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                                shape = RoundedCornerShape(MuesliCorners.medium)
                            ) {
                                Icon(
                                    Icons.Default.Mic,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    if (enrollTarget != null) "Record 8s Sample"
                                    else "Record 8-Second Clip"
                                )
                            }
                        }
                    }
                }
            }

            // ── Test matching ──────────────────────────────────────────
            item {
                MuesliSurfaceCard {
                    Column(
                        modifier = Modifier.padding(MuesliSpacing.s16),
                        verticalArrangement = Arrangement.spacedBy(MuesliSpacing.s12)
                    ) {
                        Text(
                            "Test Voice Matching",
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            "Record a clip (or reuse the last one) and see the cosine similarity against every enrolled profile.",
                            color = colors.textSecondary,
                            fontSize = 11.sp
                        )

                        if (recordingPurpose == RecordPurpose.TEST) {
                            RecordingIndicator(
                                progress = recordingProgress,
                                onCancel = { recorder.cancel() }
                            )
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(MuesliSpacing.s8)) {
                                Button(
                                    onClick = { requestOrStart(RecordPurpose.TEST) },
                                    enabled = busyLabel == null && recordingPurpose == null && profiles.isNotEmpty(),
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                                    shape = RoundedCornerShape(MuesliCorners.medium)
                                ) {
                                    Icon(
                                        Icons.Default.GraphicEq,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Record & Test")
                                }
                                OutlinedButton(
                                    onClick = { lastClip?.let(identifyClip) },
                                    enabled = busyLabel == null && recordingPurpose == null &&
                                        lastClip != null && profiles.isNotEmpty(),
                                    shape = RoundedCornerShape(MuesliCorners.medium)
                                ) {
                                    Text("Test Last Clip")
                                }
                            }
                            if (profiles.isEmpty()) {
                                Text(
                                    "Enroll at least one speaker first.",
                                    color = colors.textTertiary,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        matches?.let { found ->
                            if (found.isNotEmpty()) {
                                HorizontalDividerLine()
                                found.take(5).forEachIndexed { index, match ->
                                    val isTop = index == 0
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(MuesliSpacing.s8)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .background(badgeColor(match.profile.badgeColorHex))
                                        )
                                        Text(
                                            match.profile.displayName,
                                            color = if (isTop) colors.accent else colors.textPrimary,
                                            fontSize = if (isTop) 15.sp else 13.sp,
                                            fontWeight = if (isTop) FontWeight.Bold else FontWeight.Normal,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            "${EnrollmentClip.similarityPercent(match.similarity)}%",
                                            color = if (isTop) colors.accent else colors.textSecondary,
                                            fontSize = if (isTop) 15.sp else 13.sp,
                                            fontWeight = if (isTop) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                                Text(
                                    "Similarity is cosine match of the 192-dim voiceprint; higher is better.",
                                    color = colors.textTertiary,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }

            // ── Profile list ───────────────────────────────────────────
            item {
                MuesliSurfaceCard {
                    Column(
                        modifier = Modifier.padding(MuesliSpacing.s16),
                        verticalArrangement = Arrangement.spacedBy(MuesliSpacing.s12)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Enrolled Profiles",
                                color = colors.textPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${profiles.size}",
                                color = colors.textTertiary,
                                fontSize = 12.sp
                            )
                        }

                        if (profiles.isEmpty()) {
                            Text(
                                "No profiles yet. Enroll a speaker above — enrolled names are applied automatically when diarizing meetings.",
                                color = colors.textSecondary,
                                fontSize = 12.sp
                            )
                        } else {
                            profiles.forEach { profile ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(MuesliSpacing.s8)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clip(CircleShape)
                                            .background(badgeColor(profile.badgeColorHex))
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            profile.displayName,
                                            color = colors.textPrimary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            "${profile.sampleCount} sample" +
                                                (if (profile.sampleCount == 1) "" else "s") +
                                                " · updated " + SimpleDateFormat("d MMM yyyy", Locale.getDefault())
                                                .format(Date(profile.updatedAtMs)),
                                            color = colors.textSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            enrollTarget = profile
                                            statusMessage = "Recording next will add a sample to ${profile.displayName}."
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.GraphicEq,
                                            contentDescription = "Add sample",
                                            tint = colors.textSecondary
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            profilePendingRename = profile
                                            renameInput = profile.displayName
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = "Rename",
                                            tint = colors.textSecondary
                                        )
                                    }
                                    IconButton(
                                        onClick = { profilePendingDelete = profile }
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Delete",
                                            tint = colors.destructive.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Dialogs ──────────────────────────────────────────────────────
    profilePendingRename?.let { profile ->
        AlertDialog(
            onDismissRequest = { profilePendingRename = null },
            containerColor = colors.backgroundRaised,
            title = { Text("Rename Speaker", color = colors.textPrimary) },
            text = {
                TextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    singleLine = true,
                    label = { Text("Name") }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val renamed = profile
                        profilePendingRename = null
                        scope.launch {
                            try {
                                if (!profileStore.renameProfile(renamed.profileId, renameInput)) {
                                    errorMessage = "Profile no longer exists."
                                } else {
                                    statusMessage = "Renamed to \"${renameInput.trim()}\"."
                                }
                            } catch (t: Throwable) {
                                errorMessage = t.message ?: "Rename failed."
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { profilePendingRename = null }) {
                    Text("Cancel", color = colors.textSecondary)
                }
            }
        )
    }

    profilePendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { profilePendingDelete = null },
            containerColor = colors.backgroundRaised,
            title = { Text("Delete Profile?", color = colors.textPrimary) },
            text = {
                Text(
                    "\"${profile.displayName}\" and ${profile.sampleCount} stored sample" +
                        (if (profile.sampleCount == 1) "" else "s") +
                        " will be removed. Meeting transcripts already labeled stay unchanged.",
                    color = colors.textSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val deleted = profile
                        profilePendingDelete = null
                        scope.launch {
                            try {
                                profileStore.deleteProfile(deleted.profileId)
                                statusMessage = "Deleted \"${deleted.displayName}\"."
                                errorMessage = null
                            } catch (t: Throwable) {
                                errorMessage = t.message ?: "Delete failed."
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.destructive)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { profilePendingDelete = null }) {
                    Text("Cancel", color = colors.textSecondary)
                }
            }
        )
    }
}

@Composable
private fun RecordingIndicator(progress: Float, onCancel: () -> Unit) {
    val colors = MuesliTheme.colors
    val remainingSec = ceil(EnrollmentClip.RECORD_DURATION_MS * (1f - progress) / 1000f).toInt()
    Column(verticalArrangement = Arrangement.spacedBy(MuesliSpacing.s8)) {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MuesliSpacing.s12)
        ) {
            Text(
                if (remainingSec > 0) "Recording — speak normally… ${remainingSec}s"
                else "Finishing…",
                color = colors.textPrimary,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(MuesliCorners.medium)) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun HorizontalDividerLine() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MuesliTheme.colors.surfaceBorder)
    )
}

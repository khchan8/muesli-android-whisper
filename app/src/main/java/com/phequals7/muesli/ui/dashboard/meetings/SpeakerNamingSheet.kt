package com.phequals7.muesli.ui.dashboard.meetings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.phequals7.muesli.data.AppDatabase
import com.phequals7.muesli.meetings.DiarizedTranscriptSegment
import com.phequals7.muesli.meetings.SpeakerDiarizer
import com.phequals7.muesli.meetings.SpeakerProfileRecord
import kotlinx.coroutines.launch

/** One diarized speaker (cluster) of a meeting, as shown in the naming sheet. */
data class MeetingSpeaker(
    val clusterId: Int,
    val label: String,
    val badgeColorHex: String,
    val profileId: String?,
    val centroid: FloatArray,
    val turns: Int,
    val speakingMs: Long,
)

/** Distinct speakers in first-appearance order; the centroid is the first stored one. */
fun meetingSpeakers(segments: List<DiarizedTranscriptSegment>): List<MeetingSpeaker> =
    segments.groupBy { it.clusterId }.map { (clusterId, turns) ->
        val first = turns.first()
        MeetingSpeaker(
            clusterId = clusterId,
            label = first.speakerLabel,
            badgeColorHex = first.badgeColorHex,
            profileId = turns.firstNotNullOfOrNull { it.speakerProfileId },
            centroid = turns.firstOrNull { it.clusterCentroid.isNotEmpty() }?.clusterCentroid ?: FloatArray(0),
            turns = turns.size,
            speakingMs = turns.sumOf { (it.endMs - it.startMs).coerceAtLeast(0L) },
        )
    }

private fun formatSpeaking(ms: Long): String = "%d:%02d".format(ms / 60_000, (ms / 1000) % 60)

/**
 * Lists the meeting's speakers; picking one lets the user name it and save its
 * voiceprint as a profile (or link it to an existing profile) so every turn is
 * relabeled and the speaker is recognized in future meetings.
 *
 * [onSave] returns an error message, or null on success.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeakerNamingSheet(
    speakers: List<MeetingSpeaker>,
    onSave: suspend (speaker: MeetingSpeaker, name: String, existingProfileId: String?, colorHex: String) -> String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profiles by remember { AppDatabase.getSpeakerProfileStore(context.applicationContext).observeProfiles() }
        .collectAsState(initial = emptyList<SpeakerProfileRecord>())
    var selectedId by remember { mutableStateOf<Int?>(null) }
    val selected = speakers.firstOrNull { it.clusterId == selectedId }
    var name by remember(selectedId) {
        mutableStateOf(selected?.label?.takeUnless { it.startsWith("Speaker ") }.orEmpty())
    }
    var colorHex by remember(selectedId) { mutableStateOf(selected?.badgeColorHex.orEmpty()) }
    var error by remember(selectedId) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    fun save(speaker: MeetingSpeaker, saveName: String, profileId: String?, saveColor: String) {
        saving = true
        error = null
        scope.launch {
            val failure = onSave(speaker, saveName, profileId, saveColor)
            saving = false
            if (failure == null) selectedId = null else error = failure
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(
                if (selected == null) "Name speakers" else "Name ${selected.label}",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Saving a profile stores this speaker's voiceprint so they are recognized automatically in future meetings, and renames all of their turns here.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            if (selected == null) {
                speakers.forEach { s ->
                    val canSave = s.centroid.isNotEmpty()
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = canSave) { selectedId = s.clusterId }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(parseHexColor(s.badgeColorHex)))
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.label, fontWeight = FontWeight.SemiBold)
                            Text(
                                buildString {
                                    append("${s.turns} turns · ${formatSpeaking(s.speakingMs)}")
                                    if (s.profileId != null) append(" · profile saved")
                                    if (!canSave) append(" · no voiceprint stored for this meeting")
                                },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
            } else {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Speaker name (e.g. Alice)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text("Badge color", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(SpeakerDiarizer.DEFAULT_BADGE_COLORS) { hex ->
                        val isSelected = hex.equals(colorHex, ignoreCase = true)
                        Box(
                            Modifier.size(if (isSelected) 34.dp else 28.dp).clip(CircleShape)
                                .background(parseHexColor(hex)).clickable { colorHex = hex }
                        )
                    }
                }
                val linkable = profiles.filter { it.centroidVector.size == selected.centroid.size }
                if (linkable.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text("Or link to an existing profile", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(linkable, key = { it.profileId }) { p ->
                            OutlinedButton(enabled = !saving, onClick = { save(selected, p.displayName, p.profileId, p.badgeColorHex) }) {
                                Text("${p.displayName} (${p.sampleCount})")
                            }
                        }
                    }
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = !saving, onClick = { selectedId = null }) { Text("Back") }
                    Spacer(Modifier.size(8.dp))
                    Button(
                        enabled = name.isNotBlank() && !saving,
                        onClick = { save(selected, name.trim(), selected.profileId, colorHex) },
                    ) { Text(if (saving) "Saving…" else "Save profile & label all turns") }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

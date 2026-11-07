package com.phequals7.muesli.ui.dashboard.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.phequals7.muesli.ui.dashboard.meetings.SpeakerProfilesScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phequals7.muesli.audio.AudioInputRouteManager
import com.phequals7.muesli.data.SharedStore
import com.phequals7.muesli.data.AppBackup
import com.phequals7.muesli.data.entity.CustomWord
import com.phequals7.muesli.meetings.MeetingTemplate
import com.phequals7.muesli.meetings.RecordingExportAccess
import com.phequals7.muesli.meetings.rememberRecordingsFolderPicker
import com.phequals7.muesli.model.ModelManager
import com.phequals7.muesli.bubble.BubbleService
import com.phequals7.muesli.model.SpeechModelSpec
import com.phequals7.muesli.model.SpeechModels
import com.phequals7.muesli.engine.SherpaRecognizerHolder
import com.phequals7.muesli.summaries.ChatGptAuthManager
import com.phequals7.muesli.summaries.MeetingSummaryClient
import com.phequals7.muesli.theme.AppearanceController
import com.phequals7.muesli.theme.MuesliAccentTheme
import com.phequals7.muesli.theme.MuesliCorners
import com.phequals7.muesli.theme.MuesliSpacing
import com.phequals7.muesli.theme.MuesliTheme
import com.phequals7.muesli.ui.dashboard.voicenotes.MuesliSurfaceCard
import kotlinx.coroutines.launch

/**
 * Settings hub — port of the muesli-ios SettingsView structure:
 * a grouped list of navigation rows leading to section screens.
 */
@Composable
fun SettingsTab(store: SharedStore) {
    var section by remember { mutableStateOf<SettingsSection?>(null) }

    when (section) {
        null -> SettingsHub(onOpenSection = { section = it })
        SettingsSection.VOICE_NOTES -> SettingsScaffold("Voice Notes", onBack = { section = null }) {
            VoiceNotesSettings(store)
        }
        SettingsSection.SPEAKER_PROFILES -> SpeakerProfilesScreen(onBack = { section = null })
        SettingsSection.MEETINGS -> SettingsScaffold("Meetings", onBack = { section = null }) {
            MeetingsSettings(store)
        }
        SettingsSection.DICTIONARY -> SettingsScaffold("Dictionary", onBack = { section = null }) {
            DictionarySettings(store)
        }
        SettingsSection.MODELS -> SettingsScaffold("Models", onBack = { section = null }) {
            ModelsSettings(store)
        }
        SettingsSection.AI_SUMMARIES -> SettingsScaffold("AI Summaries", onBack = { section = null }) {
            AiSummariesSettings(store)
        }
        SettingsSection.APPEARANCE -> SettingsScaffold("Appearance", onBack = { section = null }) {
            AppearanceSettings()
        }
        SettingsSection.ABOUT -> SettingsScaffold("About", onBack = { section = null }) {
            AboutSettings(store)
        }
    }
}

private enum class SettingsSection(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
) {
    VOICE_NOTES("Voice Notes", "Speech engine, mic readiness, and keyboard setup.", Icons.Default.Keyboard),
    MEETINGS("Meetings", "Default template and audio retention.", Icons.Default.Groups),
    SPEAKER_PROFILES("Audio Profiles", "Enroll speaker voiceprints and test voice matching.", Icons.Default.RecordVoiceOver),
    DICTIONARY("Dictionary", "Filler word removal, custom phrases, names, and acronyms.", Icons.Default.Book),
    MODELS("Models", "Choose and download on-device Parakeet models.", Icons.Default.Memory),
    AI_SUMMARIES("AI Summaries", "Meeting summary providers, auth, and model selection.", Icons.Default.AutoAwesome),
    APPEARANCE("Appearance", "Light and dark mode, and app accent.", Icons.Default.Palette),
    ABOUT("About", "Version, profile, and open-source acknowledgements.", Icons.Default.Info),
}

// ───────────────────────────── hub ─────────────────────────────

@Composable
private fun SettingsHub(onOpenSection: (SettingsSection) -> Unit) {
    val colors = MuesliTheme.colors

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(MuesliSpacing.s16),
        verticalArrangement = Arrangement.spacedBy(MuesliSpacing.s16)
    ) {
        item {
            Column {
                Text("Settings", color = colors.textPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
                Text("Voice notes, meetings, local models, and privacy.", color = colors.textSecondary, fontSize = 13.sp)
            }
        }

        item {
            MuesliSurfaceCard {
                Column {
                    SettingsSection.entries.forEachIndexed { index, section ->
                        SettingsHubRow(
                            section = section,
                            onClick = { onOpenSection(section) }
                        )
                        if (index < SettingsSection.entries.lastIndex) {
                            HorizontalDivider(
                                color = colors.surfaceBorder,
                                modifier = Modifier.padding(start = 64.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsHubRow(
    section: SettingsSection,
    onClick: () -> Unit,
) {
    val colors = MuesliTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MuesliSpacing.s12),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(MuesliSpacing.s16)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(MuesliCorners.small))
                .background(colors.brandBlueSubtle),
            contentAlignment = Alignment.Center
        ) {
            Icon(section.icon, contentDescription = null, tint = colors.accent)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(section.title, color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(section.subtitle, color = colors.textSecondary, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = colors.textTertiary)
    }
}

@Composable
private fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = MuesliTheme.colors
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MuesliSpacing.s8, vertical = MuesliSpacing.s8)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.textPrimary)
            }
            Text(title, color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = MuesliSpacing.s16, vertical = MuesliSpacing.s8),
            verticalArrangement = Arrangement.spacedBy(MuesliSpacing.s16)
        ) {
            item { content() }
        }
    }
}

// ─────────────────────── shared row components ───────────────────────

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = MuesliTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = colors.textPrimary, fontSize = 14.sp)
            Text(subtitle, color = colors.textSecondary, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    accentWhenSelected: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = MuesliTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MuesliCorners.small))
            .clickable { onClick() }
            .padding(vertical = MuesliSpacing.s8)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = if (selected && accentWhenSelected) colors.accent else colors.textPrimary,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(subtitle, color = colors.textSecondary, fontSize = 11.sp)
        }
        if (selected) {
            Icon(Icons.Default.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    MuesliSurfaceCard {
        Column(
            modifier = Modifier.padding(MuesliSpacing.s16),
            verticalArrangement = Arrangement.spacedBy(MuesliSpacing.s12),
            content = { content() }
        )
    }
}

// ─────────────────────── Voice Notes ───────────────────────

@Composable
private fun VoiceNotesSettings(store: SharedStore) {
    val context = LocalContext.current
    val colors = MuesliTheme.colors
    var keepMicReady by remember { mutableStateOf(store.keepMicReady) }
    var bubbleEnabled by remember { mutableStateOf(store.bubbleEnabled) }
    var micPreference by remember {
        mutableStateOf(AudioInputRouteManager.MicPreference.fromId(store.micPreference))
    }
    val routeManager = remember { AudioInputRouteManager(context) }

    Column(verticalArrangement = Arrangement.spacedBy(MuesliSpacing.s16)) {
        SettingsCard {
            Text("Recording Microphone", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            AudioInputRouteManager.MicPreference.entries.forEach { option ->
                ChoiceRow(
                    title = option.label,
                    subtitle = micPreferenceSubtitle(routeManager, option),
                    selected = micPreference == option,
                    onClick = { micPreference = option; store.micPreference = option.id }
                )
            }
        }

        SettingsCard {
            Text("Keyboard", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            ToggleRow(
                title = "Keep mic ready",
                subtitle = "The keyboard prewarms the speech model when it opens — recording still only starts when you tap the mic.",
                checked = keepMicReady,
                onCheckedChange = { keepMicReady = it; store.keepMicReady = it }
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(MuesliCorners.small))
                    .clickable { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
                    .padding(vertical = MuesliSpacing.s8)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Keyboard setup", color = colors.textPrimary, fontSize = 14.sp)
                    Text("Enable or switch to the Muesli keyboard.", color = colors.textSecondary, fontSize = 11.sp)
                }
                Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = colors.textTertiary)
            }
        }

        SettingsCard {
            Text("Quick Capture", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            ToggleRow(
                title = "Floating mic bubble",
                subtitle = "A draggable bubble floats over other apps. Tap it to dictate — the note is saved and copied to your clipboard.",
                checked = bubbleEnabled && Settings.canDrawOverlays(context),
                onCheckedChange = { enabled ->
                    if (enabled && !Settings.canDrawOverlays(context)) {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            )
                        )
                    } else {
                        bubbleEnabled = enabled
                        store.bubbleEnabled = enabled
                        val intent = Intent(context, BubbleService::class.java)
                        if (enabled) context.startService(intent) else context.stopService(intent)
                    }
                }
            )
        }
    }
}

// ─────────────────────── Meetings ───────────────────────

@Composable
private fun MeetingsSettings(store: SharedStore) {
    val context = LocalContext.current
    val colors = MuesliTheme.colors
    var defaultTemplate by remember { mutableStateOf(MeetingTemplate.fromId(store.defaultMeetingTemplate)) }
    var retainAudio by remember { mutableStateOf(store.retainMeetingAudio) }
    var exportMp4 by remember { mutableStateOf(store.exportEmbeddedMp4Enabled) }
    var speakerLabels by remember { mutableStateOf(store.speakerLabelsEnabled) }
    var folderError by remember { mutableStateOf<String?>(null) }
    var folderRevision by remember { mutableIntStateOf(0) }
    val chooseFolder = rememberRecordingsFolderPicker(store, onSelected = { folderRevision++ }) { folderError = it }
    val folderLabel = remember(folderRevision, store.recordingsTreeUri) {
        RecordingExportAccess.describeFolder(context, store.recordingsTreeUri)
    }
    val scope = rememberCoroutineScope()
    var autoBackup by remember { mutableStateOf(store.autoBackupEnabled) }
    var backupBusy by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    var backupStatus by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val treeUri = remember(folderRevision, store.recordingsTreeUri) {
        store.recordingsTreeUri.takeIf { it.isNotBlank() }?.let(Uri::parse)
    }

    Column(verticalArrangement = Arrangement.spacedBy(MuesliSpacing.s16)) {
        SettingsCard {
            Text("Default Template", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            MeetingTemplate.entries.forEach { template ->
                ChoiceRow(
                    title = template.label,
                    subtitle = template.detail,
                    selected = template == defaultTemplate,
                    onClick = {
                        defaultTemplate = template
                        store.defaultMeetingTemplate = template.id
                    }
                )
            }
        }

        SettingsCard {
            Text("Recording", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            ToggleRow(
                title = "Speaker labels",
                subtitle = "Diarizes the meeting on-device after you stop and groups the transcript by speaker. Adds a short processing step.",
                checked = speakerLabels,
                onCheckedChange = { speakerLabels = it; store.speakerLabelsEnabled = it }
            )
            ToggleRow(
                title = "Retain meeting audio",
                subtitle = "Keeps the M4A audio file on device for future playback and speaker separation.",
                checked = retainAudio,
                onCheckedChange = { retainAudio = it; store.retainMeetingAudio = it }
            )
            ToggleRow(
                title = "Also export MP4 with embedded subtitles",
                subtitle = "After the SRT is saved, writes a matching .mp4 (dark video canvas + the audio + switchable subtitle track) next to it. Everything is processed on this device; notes and AI summaries stay in the .srt.",
                checked = exportMp4,
                onCheckedChange = { exportMp4 = it; store.exportEmbeddedMp4Enabled = it }
            )

            Text("Recordings folder", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(
                folderLabel?.let { "Audio (M4A) and SRT files are exported to $it." }
                    ?: "Pick a folder once so finished meetings export their audio and SRT files there. Until then they stay inside the app.",
                color = colors.textSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
            folderError?.let {
                Text(it, color = colors.destructive, fontSize = 11.sp)
            }
            Button(
                onClick = { folderError = null; chooseFolder() },
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                shape = RoundedCornerShape(MuesliCorners.medium),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(if (folderLabel != null) "Change folder" else "Choose recordings folder", fontSize = 12.sp)
            }
        }

        SettingsCard {
            Text("Backup & restore", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            ToggleRow(
                title = "Automatic backup",
                subtitle = "Saves ${AppBackup.FILE_NAME} in the Recordings folder when you leave the app: settings, custom words and voice profiles (not API keys or sign-ins). Other apps with access to that folder can read it.",
                checked = autoBackup,
                onCheckedChange = { autoBackup = it; store.autoBackupEnabled = it }
            )
            Text(
                if (treeUri == null) "Choose a recordings folder first." else "After reinstalling, choose the same folder and tap Restore.",
                color = colors.textSecondary, fontSize = 11.sp, lineHeight = 15.sp
            )
            backupStatus?.let {
                Text(it.first, color = if (it.second) colors.textSecondary else colors.destructive, fontSize = 11.sp, lineHeight = 15.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(MuesliSpacing.s8)) {
                Button(
                    enabled = treeUri != null && !backupBusy,
                    onClick = {
                        backupBusy = true
                        scope.launch {
                            val r = AppBackup.backup(context, treeUri!!, force = true)
                            backupStatus = (r.getOrNull() ?: r.exceptionOrNull()?.message ?: "Backup failed") to r.isSuccess
                            backupBusy = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                    shape = RoundedCornerShape(MuesliCorners.medium),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) { Text("Back up now", fontSize = 12.sp) }
                Button(
                    enabled = treeUri != null && !backupBusy,
                    onClick = { confirmRestore = true },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                    shape = RoundedCornerShape(MuesliCorners.medium),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) { Text("Restore", fontSize = 12.sp) }
            }
        }
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            containerColor = colors.backgroundRaised,
            title = { Text("Restore from backup?", color = colors.textPrimary) },
            text = {
                Text(
                    "Overwrites matching settings and adds or updates custom words and voice profiles from ${AppBackup.FILE_NAME}. Nothing already on this device is deleted.",
                    color = colors.textSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    backupBusy = true
                    scope.launch {
                        val r = AppBackup.restore(context, treeUri!!)
                        backupStatus = (r.getOrNull() ?: r.exceptionOrNull()?.message ?: "Restore failed") to r.isSuccess
                        backupBusy = false
                    }
                }) { Text("Restore", color = colors.accent) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = false }) { Text("Cancel", color = colors.textSecondary) }
            }
        )
    }
}

// ─────────────────────── Dictionary ───────────────────────

@Composable
private fun DictionarySettings(store: SharedStore) {
    val colors = MuesliTheme.colors
    val scope = rememberCoroutineScope()

    var fillerEnabled by remember { mutableStateOf(store.isFillerWordRemovalEnabled) }
    var customDictEnabled by remember { mutableStateOf(store.isCustomDictionaryEnabled) }
    var customWords by remember { mutableStateOf(emptyList<CustomWord>()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var entryWord by remember { mutableStateOf("") }
    var entryReplacement by remember { mutableStateOf("") }
    var entryThreshold by remember { mutableStateOf(0.85f) }

    LaunchedEffect(Unit) {
        store.getCustomWordsFlow().collect { customWords = it }
    }

    Column(verticalArrangement = Arrangement.spacedBy(MuesliSpacing.s16)) {
        SettingsCard {
            Text("Text Processing", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            ToggleRow(
                title = "Filter filler words",
                subtitle = "Removes 'uh', 'um', 'you know', etc.",
                checked = fillerEnabled,
                onCheckedChange = { fillerEnabled = it; store.isFillerWordRemovalEnabled = it }
            )
            ToggleRow(
                title = "Custom dictionary",
                subtitle = "Replace matched words on the fly.",
                checked = customDictEnabled,
                onCheckedChange = { customDictEnabled = it; store.isCustomDictionaryEnabled = it }
            )
        }

        SettingsCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Custom Words", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Button(
                    onClick = { showAddDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                    shape = RoundedCornerShape(MuesliCorners.medium),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add", fontSize = 12.sp)
                }
            }

            if (customWords.isEmpty()) {
                Text(
                    "No custom words yet. Add terms to match fuzzy inputs using Jaro-Winkler similarity.",
                    color = colors.textSecondary,
                    fontSize = 12.sp
                )
            } else {
                customWords.forEach { word ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(word.word, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            if (word.replacement != null) {
                                Text("→ ${word.replacement}", color = colors.accent, fontSize = 12.sp)
                            }
                            Text(
                                "Threshold: ${"%.2f".format(word.matchingThreshold)}",
                                color = colors.textSecondary,
                                fontSize = 10.sp
                            )
                        }
                        IconButton(onClick = { scope.launch { store.deleteCustomWordById(word.id) } }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = colors.destructive.copy(alpha = 0.7f))
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            containerColor = colors.backgroundRaised,
            title = { Text("Add Custom Word", color = colors.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextField(value = entryWord, onValueChange = { entryWord = it }, label = { Text("Word/Phrase") })
                    TextField(value = entryReplacement, onValueChange = { entryReplacement = it }, label = { Text("Replacement (Optional)") })
                    Column {
                        Text("Matching Threshold: ${"%.2f".format(entryThreshold)}", color = colors.textSecondary, fontSize = 12.sp)
                        Slider(value = entryThreshold, onValueChange = { entryThreshold = it }, valueRange = 0.5f..1.0f)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (entryWord.isNotBlank()) {
                            scope.launch {
                                store.insertCustomWord(
                                    CustomWord(
                                        word = entryWord,
                                        replacement = entryReplacement.ifBlank { null },
                                        matchingThreshold = entryThreshold.toDouble()
                                    )
                                )
                            }
                            showAddDialog = false
                            entryWord = ""
                            entryReplacement = ""
                            entryThreshold = 0.85f
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel", color = colors.textSecondary) }
            }
        )
    }
}

private fun micPreferenceSubtitle(
    routeManager: AudioInputRouteManager,
    option: AudioInputRouteManager.MicPreference,
): String = when (option) {
    AudioInputRouteManager.MicPreference.AUTO -> "Follows the system default route"
    AudioInputRouteManager.MicPreference.PHONE -> "Always uses the built-in phone microphone"
    AudioInputRouteManager.MicPreference.BLUETOOTH ->
        if (routeManager.isAvailable(option)) "Connected — uses the Bluetooth mic (e.g. AirPods)" else "Not connected — falls back to the phone mic"
    AudioInputRouteManager.MicPreference.USB ->
        if (routeManager.isAvailable(option)) "Connected — uses the external USB microphone" else "Not connected — falls back to the phone mic"
}

// ─────────────────────── Models ───────────────────────

@Composable
private fun ModelsSettings(store: SharedStore) {
    val colors = MuesliTheme.colors
    var selectedId by remember { mutableStateOf(SpeechModels.byId(store.selectedModelId).id) }
    var selectedSummaryId by remember { mutableStateOf(SpeechModels.summaryById(store.localSummaryModelId).id) }

    Column(verticalArrangement = Arrangement.spacedBy(MuesliSpacing.s16)) {
        Text(
            "Multilingual (EN + TH + ZH + YUE)",
            color = colors.textSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        SpeechModels.selectableAsrModels.forEach { model ->
            SpeechModelCard(
                model = model,
                selected = model.id == selectedId,
                onSelect = {
                    selectedId = model.id
                    store.selectedModelId = model.id
                    SherpaRecognizerHolder.reset()
                },
            )
        }

        Text(
            "Speaker Diarization (auto-installed with multilingual models)",
            color = colors.textSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        SpeechModels.diarizationModels.forEach { model ->
            SpeechModelCard(model = model, selected = false, onSelect = {})
        }

        Text(
            "Summary Models (on-device Qwen3 GGUF)",
            color = colors.textSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        SpeechModels.summaryModels.forEach { model ->
            SpeechModelCard(
                model = model,
                selected = model.id == selectedSummaryId,
                selectable = true,
                onSelect = {
                    selectedSummaryId = model.id
                    store.localSummaryModelId = model.id
                },
            )
        }

        SettingsCard {
            Text("About on-device models", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(
                "All models run fully on-device — audio and transcripts never leave this phone when on-device models are selected. The speech model is used for voice notes, the keyboard, and meetings. Legacy Parakeet models are European/English-only.",
                color = colors.textSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
    }
}

// ─────────────────────── AI Summaries ───────────────────────

@Composable
private fun AiSummariesSettings(store: SharedStore) {
    val context = LocalContext.current
    val colors = MuesliTheme.colors
    val scope = rememberCoroutineScope()
    val authManager = remember { ChatGptAuthManager(context.applicationContext) }

    var summariesEnabled by remember { mutableStateOf(store.summariesEnabled) }
    var backend by remember { mutableStateOf(store.summaryBackend) }
    var chatGptModel by remember { mutableStateOf(store.chatGptModel) }
    var openRouterModel by remember { mutableStateOf(store.openRouterModel) }
    var localSummaryModelId by remember { mutableStateOf(SpeechModels.summaryById(store.localSummaryModelId).id) }
    var apiKeyInput by remember { mutableStateOf(store.openRouterApiKey) }
    var isSignedIn by remember { mutableStateOf(authManager.isAuthenticated) }
    var signInInProgress by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(MuesliSpacing.s16)) {
        SettingsCard {
            ToggleRow(
                title = "Meeting Summaries",
                subtitle = "Structured notes after local transcription. Cloud providers receive transcript text only when selected; On-device Qwen never sends transcript or audio off the device.",
                checked = summariesEnabled,
                onCheckedChange = {
                    summariesEnabled = it
                    store.summariesEnabled = it
                }
            )
        }

        SettingsCard {
            Text("Summary Backend", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            ChoiceRow(
                title = "ChatGPT",
                subtitle = "Sign in with your ChatGPT account (primary)",
                selected = backend == "chatgpt",
                onClick = { backend = "chatgpt"; store.summaryBackend = "chatgpt" }
            )
            ChoiceRow(
                title = "OpenRouter",
                subtitle = "Bring your own API key",
                selected = backend == "openrouter",
                onClick = { backend = "openrouter"; store.summaryBackend = "openrouter" }
            )
            ChoiceRow(
                title = "Custom (OpenAI-compatible)",
                subtitle = "Your own HTTPS chat-completions endpoint, model, and optional key",
                selected = backend == "custom",
                onClick = { backend = "custom"; store.summaryBackend = "custom" }
            )
            ChoiceRow(
                title = "On-device Qwen",
                subtitle = "Run Qwen3 1.7B (~1.1 GB) or 4B (~2.5 GB) locally inside Muesli",
                selected = backend == "local_qwen",
                onClick = { backend = "local_qwen"; store.summaryBackend = "local_qwen" }
            )
        }
        if (backend == "chatgpt") {
            SettingsCard {
                Text("ChatGPT Account", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isSignedIn) colors.syncGreen else colors.textTertiary)
                    )
                    Spacer(modifier = Modifier.width(MuesliSpacing.s8))
                    Text(
                        if (isSignedIn) "Signed in" else "Not signed in",
                        color = if (isSignedIn) colors.syncGreen else colors.textSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (signInInProgress) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.accent)
                    }
                }

                if (!isSignedIn) {
                    Button(
                        onClick = {
                            signInInProgress = true
                            statusMessage = null
                            scope.launch {
                                val error = authManager.signIn { intent -> context.startActivity(intent) }
                                signInInProgress = false
                                if (error == null) {
                                    isSignedIn = true
                                    statusMessage = null
                                } else {
                                    statusMessage = error
                                }
                            }
                        },
                        enabled = !signInInProgress,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                        shape = RoundedCornerShape(MuesliCorners.medium),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Sign in with ChatGPT", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "Opens auth.openai.com in your browser. Times out after 5 minutes. Tokens stay on this device.",
                        color = colors.textSecondary,
                        fontSize = 11.sp
                    )
                } else {
                    TextButton(onClick = {
                        authManager.signOut()
                        isSignedIn = false
                    }) {
                        Text("Sign Out", color = colors.destructive)
                    }
                }

                statusMessage?.let {
                    Text(it, color = colors.destructive, fontSize = 11.sp)
                }
            }

            SettingsCard {
                Text("Summary Model", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                MeetingSummaryClient.CHATGPT_MODEL_PRESETS.forEach { preset ->
                    ChoiceRow(
                        title = preset,
                        subtitle = if (preset == "gpt-5.5") "Default" else "Faster, cheaper",
                        selected = chatGptModel == preset,
                        onClick = { chatGptModel = preset; store.chatGptModel = preset }
                    )
                }
            }
        } else if (backend == "openrouter") {
            SettingsCard {
                Text("OpenRouter API Key", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = {
                        apiKeyInput = it
                        store.openRouterApiKey = it.trim()
                    },
                    placeholder = { Text("sk-or-...", color = colors.textTertiary) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    if (store.openRouterApiKey.isNotBlank()) "Key saved on this device." else "Get a key at openrouter.ai/keys",
                    color = colors.textSecondary,
                    fontSize = 11.sp
                )
            }

            SettingsCard {
                Text("Summary Model", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                MeetingSummaryClient.OPENROUTER_MODEL_PRESETS.forEach { preset ->
                    ChoiceRow(
                        title = preset.substringAfter('/').substringBefore(':'),
                        subtitle = preset,
                        selected = openRouterModel == preset,
                        onClick = { openRouterModel = preset; store.openRouterModel = preset }
                    )
                }
            }
        } else if (backend == "custom") {
            var customUrl by remember { mutableStateOf(store.customSummaryUrl) }
            var customKey by remember { mutableStateOf(store.customSummaryApiKey) }
            var customModel by remember { mutableStateOf(store.customSummaryModel) }

            SettingsCard {
                Text("Custom Endpoint", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                OutlinedTextField(
                    value = customUrl,
                    onValueChange = {
                        customUrl = it
                        store.customSummaryUrl = it.trim()
                    },
                    label = { Text("Chat-completions URL") },
                    placeholder = { Text("https://host.example/v1/chat/completions") },
                    singleLine = true,
                    isError = customUrl.isNotBlank() && com.phequals7.muesli.summaries.SummaryHttp.customEndpointError(customUrl, customModel) != null,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = customModel,
                    onValueChange = {
                        customModel = it
                        store.customSummaryModel = it.trim()
                    },
                    label = { Text("Model ID") },
                    placeholder = { Text("e.g. qwen3-8b-instruct") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = customKey,
                    onValueChange = {
                        customKey = it
                        store.customSummaryApiKey = it.trim()
                    },
                    label = { Text("API key (optional)") },
                    placeholder = { Text("sk-...") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                val validation = com.phequals7.muesli.summaries.SummaryHttp.customEndpointError(customUrl, customModel)
                Text(
                    validation ?: "The full endpoint URL is used exactly as entered — Muesli never appends a path. " +
                        "The API key is stored on this device only.",
                    color = if (validation != null) colors.destructive else colors.textSecondary,
                    fontSize = 11.sp
                )
                Text(
                    "Notice: when this backend is selected, your transcript and written notes are uploaded to this endpoint.",
                    color = colors.textSecondary,
                    fontSize = 11.sp
                )
            }
        } else if (backend == "local_qwen") {
            SettingsCard {
                Text("On-device Qwen", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    "Downloads are ~1.1 GB (1.7B, default) or ~2.5 GB (4B). Local summaries run inside Muesli and do not send transcript or audio off this device.",
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
            SpeechModels.summaryModels.forEach { model ->
                SpeechModelCard(
                    model = model,
                    selected = model.id == localSummaryModelId,
                    selectable = true,
                    onSelect = {
                        localSummaryModelId = model.id
                        store.localSummaryModelId = model.id
                    },
                )
            }
        }
    }
}

// ─────────────────────── Appearance ───────────────────────

@Composable
private fun AppearanceSettings() {
    val context = LocalContext.current
    val colors = MuesliTheme.colors

    Column(verticalArrangement = Arrangement.spacedBy(MuesliSpacing.s16)) {
        SettingsCard {
            Text("Theme", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (mode, label) ->
                ChoiceRow(
                    title = label,
                    subtitle = when (mode) {
                        "system" -> "Follow the phone's dark mode setting"
                        "light" -> "Always use the light appearance"
                        else -> "Always use the dark appearance"
                    },
                    selected = AppearanceController.appearanceMode == mode,
                    onClick = { AppearanceController.setAppearanceMode(context, mode) }
                )
            }
        }

        SettingsCard {
            Text("Accent", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            MuesliAccentTheme.entries.forEach { theme ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MuesliSpacing.s12),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(MuesliCorners.small))
                        .clickable { AppearanceController.setAccentTheme(context, theme.id) }
                        .padding(vertical = MuesliSpacing.s8)
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(theme.color(dark = true))
                    )
                    Text(
                        theme.label,
                        color = colors.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = if (AppearanceController.accentThemeId == theme.id) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f)
                    )
                    if (AppearanceController.accentThemeId == theme.id) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

// ─────────────────────── About ───────────────────────

@Composable
private fun AboutSettings(store: SharedStore) {
    val colors = MuesliTheme.colors
    val context = LocalContext.current
    var profileName by remember { mutableStateOf(store.userProfileName) }

    Column(verticalArrangement = Arrangement.spacedBy(MuesliSpacing.s16)) {
        SettingsCard {
            Text("Profile", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            TextField(
                value = profileName,
                onValueChange = {
                    profileName = it
                    store.userProfileName = it
                },
                label = { Text("Your name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        SettingsCard {
            Text("Muesli for Android", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("Version", color = colors.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                Text("${info.versionName} (${info.longVersionCode})", color = colors.textPrimary, fontSize = 13.sp)
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("Speech runtime", color = colors.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("sherpa-onnx 1.13.4", color = colors.textPrimary, fontSize = 13.sp)
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("Model", color = colors.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(SpeechModels.selected(context).title, color = colors.textPrimary, fontSize = 13.sp)
            }
        }

        SettingsCard {
            Text("Acknowledgements", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(
                "sherpa-onnx (k2-fsa, Apache-2.0) · ONNX Runtime (Microsoft, MIT) · Parakeet models (NVIDIA NeMo) · Silero VAD. Companion to Muesli for iOS and macOS.",
                color = colors.textSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
    }
}

// ─────────────────────── shared cards ───────────────────────

/**
 * Model management card for the sherpa-onnx Parakeet V3 runtime: download
 * (resumable, with progress), status display, and deletion.
 */
@Composable
internal fun SpeechModelCard(
    model: SpeechModelSpec,
    selected: Boolean,
    onSelect: () -> Unit,
    selectable: Boolean = model.asrModelType != null,
) {
    val colors = MuesliTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { ModelManager.getInstance(context.applicationContext) }
    val statuses by manager.statuses.collectAsState()
    val status = statuses[model.id]

    val isDownloaded = status?.isInstalled == true
    val isDownloading = status?.isDownloading == true || status?.isExtracting == true
    val progress = status?.progress ?: 0f

    MuesliSurfaceCard {
        Column(
            modifier = Modifier.padding(MuesliSpacing.s16),
            verticalArrangement = Arrangement.spacedBy(MuesliSpacing.s12)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MuesliSpacing.s12),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(MuesliCorners.small))
                        .background(
                            when {
                                selected -> colors.accentSubtle
                                isDownloaded -> colors.syncGreenSubtle
                                else -> colors.brandBlueSubtle
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when {
                            selected -> Icons.Default.CheckCircle
                            isDownloaded -> Icons.Default.CheckCircle
                            else -> Icons.Default.CloudDownload
                        },
                        contentDescription = null,
                        tint = when {
                            selected -> colors.accent
                            isDownloaded -> colors.syncGreen
                            else -> colors.accent
                        }
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(model.title, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(model.capabilityLabel, color = colors.accent, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    Text(
                        text = when {
                            status?.isExtracting == true -> "Extracting…"
                            isDownloading && status != null && status.totalBytes > 0 ->
                                "${(progress * 100).toInt()}% · ${manager.formatBytes(status.downloadedBytes)} of ${manager.formatBytes(status.totalBytes)}"
                            isDownloaded -> "Downloaded · ${manager.formatBytes(status?.diskUsageBytes ?: model.totalSizeBytes)} on disk"
                            else -> "Not downloaded · ~${model.approxDownloadSizeMb} MB · ~${model.approxRamFootprintMb} MB RAM"
                        },
                        color = colors.textSecondary,
                        fontSize = 10.sp
                    )
                }
            }

            Text(model.subtitle, color = colors.textSecondary, fontSize = 11.sp, lineHeight = 15.sp)

            if (isDownloading) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(MuesliCorners.small)),
                    color = colors.accent,
                    trackColor = colors.surfacePrimary,
                )
            }

            status?.errorMessage?.let {
                Text(it, color = colors.destructive, fontSize = 11.sp)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(MuesliSpacing.s8)) {
                if (isDownloading) {
                    TextButton(onClick = { manager.cancelDownload(model.id) }) {
                        Text("Cancel", color = colors.textSecondary)
                    }
                } else if (!isDownloaded) {
                    Button(
                        onClick = { manager.downloadModel(model) },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                        shape = RoundedCornerShape(MuesliCorners.medium)
                    ) {
                        Text("Download", fontSize = 13.sp)
                    }
                } else {
                    if (selectable) {
                        if (!selected) {
                            Button(
                                onClick = onSelect,
                                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                                shape = RoundedCornerShape(MuesliCorners.medium)
                            ) {
                                Text("Use This Model", fontSize = 13.sp)
                            }
                        } else {
                            Text("Active", color = colors.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = MuesliSpacing.s8, vertical = MuesliSpacing.s8))
                        }
                    }
                    TextButton(onClick = { scope.launch { manager.deleteModel(model) } }) {
                        Text("Delete", color = colors.destructive)
                    }
                }
            }
        }
    }
}

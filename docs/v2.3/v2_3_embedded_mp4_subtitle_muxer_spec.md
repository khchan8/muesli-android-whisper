# Muesli for Android v2.3 — Option 1: Export `.mp4` with Embedded `.srt` Subtitles Alongside `.m4a` and `.srt`

**Target Repository:** `khchan8/muesli-android-whisper` (`package com.phequals7.muesli`)

## Integration notes

- The implementation uses the user-selected SAF recordings folder, not a fixed filesystem path. M4A and SRT remain the first two export stages; optional MP4 generation runs independently after the SRT.
- Automatic MP4 export is off by default. Settings enables it for future synchronization, and the meeting detail overflow menu can export or regenerate an MP4 manually. Legacy WAV and meetings without retained audio cannot be muxed.
- MP4 subtitles contain persisted timestamped transcript segments and speaker labels only. Written notes and AI summaries remain in the standalone SRT. Subtitle display requires a player that supports and enables `tx3g`.
- The canvas and completed MP4 are generated in temporary app cache before publication. Cancel or retry from the meeting detail. Replacement requires a SAF provider that supports rename; a failed replacement preserves the prior export where possible.
- The snippets below describe the original proposal; the implementation uses `muxM4aAndSegmentsToMp4` and the SAF export service rather than writing directly beside a private audio file.

## 1. Expected User-Facing Behavior
When the user enables the **"Also export `.mp4` with embedded subtitles"** setting (in Settings or Meeting Detail):
1. **Stage 1 (Immediately when Recording Stops)**:
   * `<baseName>.m4a` (`32 kHz` AAC-LC audio) is exported immediately to `/sdcard/Recordings/Muesli/<baseName>.m4a`.
2. **Stage 2 (As soon as CAM++ Speaker Diarization Finishes)**:
   * `<baseName>.srt` is exported to `/sdcard/Recordings/Muesli/<baseName>.srt`.
   * If **"Export `.mp4` with embedded subtitles"** is enabled, `Mp4SubtitleMuxer.muxM4aAndSrtToMp4(...)` immediately creates `/sdcard/Recordings/Muesli/<baseName>.mp4` with the **exact same base filename** (`<baseName>`).
   * Once completed, the user has all three files with the same base name in `/sdcard/Recordings/Muesli/`:
     * `<baseName>.m4a` (pure 32 kHz audio)
     * `<baseName>.srt` (standalone subtitle file)
     * `<baseName>.mp4` (1-fps `640×360` dark slate H.264 video canvas + lossless copy of `<baseName>.m4a` audio + embedded MPEG-4 `tx3g` Timed Text subtitle track containing `[Speaker]: transcript` cues in English, Thai, or Chinese).
   * **No AI Summary in `.mp4`**: The `.mp4` embeds strictly the timestamped speaker transcript cues from `.srt` / `DiarizedTranscriptSegment`—the AI meeting summary stays in the app's Notes/Summary tab.
   * **Speaker Rename Re-Sync**: If the user later renames a speaker or links a Speaker Profile in `MeetingDetailScreen.kt`, both `<baseName>.srt` and `<baseName>.mp4` are refreshed so the embedded subtitles reflect the new speaker name.

---

## 2. Implementation Steps for the AI Coder Agent

### Step 1: Add `isoparser` Dependency in `app/build.gradle.kts`
Add `mp4parser`'s lightweight pure-Java ISO box muxer (~300 KB) to `dependencies` in `app/build.gradle.kts`:
```kotlin
implementation("com.googlecode.mp4parser:isoparser:1.1.22")
```

### Step 2: Add `Mp4SubtitleMuxer.kt`
Create `app/src/main/java/com/phequals7/muesli/meetings/Mp4SubtitleMuxer.kt` (full reference code included below).

### Step 3: Wire into `SharedStore.kt` & `MeetingStorageManager.kt`
1. In `SharedStore.kt` (or Settings preferences), add a boolean preference:
   ```kotlin
   var exportEmbeddedMp4Enabled: Boolean
       get() = prefs.getBoolean("export_embedded_mp4_enabled", false)
       set(value) = prefs.edit().putBoolean("export_embedded_mp4_enabled", value).apply()
   ```
2. In `MeetingStorageManager.kt` (inside Stage 2 `exportSrtArtifactOnly` / `syncRecordingArtifacts` after `<baseName>.srt` is written):
   ```kotlin
   if (sharedStore.exportEmbeddedMp4Enabled && m4aFile.exists() && srtFile.exists()) {
       val mp4File = File(m4aFile.parentFile, "${m4aFile.nameWithoutExtension}.mp4")
       val muxedOk = Mp4SubtitleMuxer.muxM4aAndSrtToMp4(
           m4aFile = m4aFile,
           srtFile = srtFile,
           outputMp4File = mp4File,
           segments = segments
       )
       if (muxedOk) {
           MediaScannerConnection.scanFile(
               context.applicationContext,
               arrayOf(mp4File.absolutePath),
               arrayOf("video/mp4"),
               null
           )
       }
   }
   ```
3. Add a toggle switch in Settings (and/or an **"Export .mp4 (with Subtitles)"** action in `MeetingDetailScreen.kt`) so the user can enable automatic `.mp4` generation or trigger it on demand for any recorded meeting.

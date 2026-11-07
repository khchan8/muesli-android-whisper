package com.phequals7.muesli

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import android.content.Intent
import com.phequals7.muesli.data.SharedStore
import com.phequals7.muesli.engine.SherpaRecognizerHolder
import com.phequals7.muesli.model.ModelManager
import com.phequals7.muesli.model.SpeechModels
import com.phequals7.muesli.theme.AppearanceController
import com.phequals7.muesli.theme.MuesliTheme
import com.phequals7.muesli.ui.launch.LaunchWarmupScreen
import android.net.Uri
import com.phequals7.muesli.data.AppBackup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

  companion object {
    /** iOS LaunchWarmupContainer: max time the warmup screen stays up. */
    private const val WARMUP_DISPLAY_CAP_MS = 5_000L
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    AppearanceController.ensureInitialized(this)
    enableEdgeToEdge()

    // Restore the quick-capture bubble across app launches when enabled.
    val store = SharedStore(this)
    if (store.bubbleEnabled && android.provider.Settings.canDrawOverlays(this)) {
      startService(Intent(this, com.phequals7.muesli.bubble.BubbleService::class.java))
    }

    setContent {
      MuesliTheme {
        Surface(
          modifier = Modifier.fillMaxSize(),
          color = MaterialTheme.colorScheme.background
        ) {
          val context = this@MainActivity
          val shouldWarmup = remember {
            // Post-onboarding launches only, and only when the model exists
            // and isn't already warm (iOS shouldShowLaunchWarmup parity)
            SharedStore(context).isOnboardingCompleted &&
              ModelManager.getInstance(context).isDownloaded() &&
              SherpaRecognizerHolder.warmState != SherpaRecognizerHolder.WarmState.READY
          }
          var showWarmup by remember { mutableStateOf(shouldWarmup) }
          var warmState by remember { mutableStateOf(SherpaRecognizerHolder.warmState) }

          LaunchedEffect(Unit) {
            if (!shouldWarmup) return@LaunchedEffect
            SherpaRecognizerHolder.prewarmAsync(context)
            val startedAt = System.currentTimeMillis()
            while (System.currentTimeMillis() - startedAt < WARMUP_DISPLAY_CAP_MS) {
              warmState = SherpaRecognizerHolder.warmState
              if (warmState == SherpaRecognizerHolder.WarmState.READY ||
                warmState == SherpaRecognizerHolder.WarmState.FAILED
              ) {
                break
              }
              delay(120)
            }
            showWarmup = false
          }

          Box {
            MainNavigation()
            androidx.compose.animation.AnimatedVisibility(
              visible = showWarmup,
              exit = fadeOut(animationSpec = tween(320)),
            ) {
              LaunchWarmupScreen(
                warmState = warmState,
                modelName = SpeechModels.selected(context).shortName,
                versionText = "v${packageManager.getPackageInfo(packageName, 0).versionName} (${packageManager.getPackageInfo(packageName, 0).longVersionCode})"
              )
            }
          }
        }
      }
    }
  }

  override fun onStop() {
    super.onStop()
    val store = SharedStore(this)
    val tree = store.recordingsTreeUri
    if (tree.isBlank() || !store.autoBackupEnabled) return
    val appContext = applicationContext
    // Not tied to the activity: the write must finish after it is stopped.
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
      AppBackup.backup(appContext, Uri.parse(tree), force = false)
        .onFailure { android.util.Log.w("MainActivity", "Auto backup failed", it) }
    }
  }
}

package com.personal.musicapp.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.personal.musicapp.playback.AudioSettings
import com.personal.musicapp.playback.FocusMode
import com.personal.musicapp.playback.PlaybackService
import com.personal.musicapp.playback.PlaybackStatus
import com.personal.musicapp.playback.PolicyStatus
import com.personal.musicapp.playback.SessionCommands

/** M1 probe screen: pick a file, play/pause, switch focus mode, watch the policy event log. */
@Composable
fun PlayerScreen() {
    val context = LocalContext.current
    val controller by rememberMediaController()
    val status by PlaybackStatus.state.collectAsStateWithLifecycle()
    val mode by AudioSettings.focusMode.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val item = MediaItem.Builder()
                .setMediaId(uri.toString())
                .setUri(uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(displayName(context, uri)).build())
                .build()
            controller?.apply {
                setMediaItem(item)
                prepare()
            }
        }
    }

    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (status.confirmPlayDuringCall) {
        CallConfirmDialog(
            onConfirm = {
                PlaybackStatus.update { it.copy(confirmPlayDuringCall = false) }
                controller?.sendCustomCommand(SessionCommands.PLAY_DURING_CALL, Bundle.EMPTY)
            },
            onDismiss = { PlaybackStatus.update { it.copy(confirmPlayDuringCall = false) } },
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Personal Music", style = MaterialTheme.typography.headlineSmall)
        Text("M1 Foundation: service, audio focus, calls", style = MaterialTheme.typography.bodyMedium)

        Text(status.title ?: "No track loaded", style = MaterialTheme.typography.titleMedium)
        Text(stateLine(status), style = MaterialTheme.typography.bodyMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { picker.launch(arrayOf("audio/*")) }) { Text("Pick audio file") }
            Button(
                enabled = controller != null && status.title != null,
                onClick = {
                    controller?.let { if (status.userWantsPlay) it.pause() else it.play() }
                },
            ) { Text(if (status.userWantsPlay) "Pause" else "Play") }
        }

        Text("Audio focus mode", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FocusMode.entries.forEach { m ->
                FilterChip(
                    selected = m == mode,
                    onClick = { AudioSettings.setFocusMode(m) },
                    label = { Text(m.label) },
                )
            }
        }
        Text(
            if (mode.bitPerfectCompatible) "Bit-perfect: compatible (engine arrives in M2/M4)"
            else "Bit-perfect: OFF in this mode",
            style = MaterialTheme.typography.bodySmall,
        )

        BatteryRow()

        Text("Event log (for the call/focus test matrix)", style = MaterialTheme.typography.titleSmall)
        Column {
            status.log.asReversed().take(15).forEach {
                Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}

private fun stateLine(s: PolicyStatus): String = buildString {
    append(
        when {
            s.playing && s.ducked -> "Playing (ducked)"
            s.playing -> "Playing"
            s.userWantsPlay && s.inCall -> "Paused for call, will resume when it ends"
            s.userWantsPlay -> "Paused by another app, will resume"
            else -> "Stopped"
        },
    )
    append(" | call: ${if (s.inCall) "yes" else "no"} | focus: ${if (s.hasFocus) "held" else "none"}")
}

@Composable
private fun CallConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("You are on a call") },
        text = {
            Text(
                "Play music anyway? It plays on the media stream only and is never sent into the call, " +
                    "but you will hear it alongside the call.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Play anyway") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun BatteryRow() {
    val context = LocalContext.current
    val powerManager = remember { context.getSystemService(PowerManager::class.java) }
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val exempt = remember(refresh) { powerManager.isIgnoringBatteryOptimizations(context.packageName) }
    if (exempt) {
        Text("Battery: unrestricted (good for background playback)", style = MaterialTheme.typography.bodySmall)
    } else {
        OutlinedButton(
            onClick = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            },
        ) { Text("Allow unrestricted battery use") }
    }
}

@Composable
private fun rememberMediaController(): State<MediaController?> {
    val context = LocalContext.current
    val state = remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            { state.value = runCatching { future.get() }.getOrNull() },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            state.value = null
            MediaController.releaseFuture(future)
        }
    }
    return state
}

private fun displayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "Selected track"

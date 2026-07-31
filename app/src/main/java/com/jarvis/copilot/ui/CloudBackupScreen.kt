package com.jarvis.copilot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jarvis.copilot.backup.CloudBackupRepository
import com.jarvis.copilot.media.MediaVaultRepository

private sealed class BackfillState {
    object Idle : BackfillState()
    data class Running(val progress: Float) : BackfillState()
    data class Paused(val progress: Float) : BackfillState()
    object Done : BackfillState()
}

@Composable
fun CloudBackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var autoBackupEnabled by remember { mutableStateOf(CloudBackupRepository.isAutoBackupEnabled(context)) }
    var backfillState by remember { mutableStateOf<BackfillState>(BackfillState.Idle) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Cloud Backup", style = MaterialTheme.typography.headlineSmall)
            OutlinedButton(onClick = onBack) { Text("Back") }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Auto-backup new photos", modifier = Modifier.weight(1f))
            Switch(
                checked = autoBackupEnabled,
                onCheckedChange = { enabled ->
                    autoBackupEnabled = enabled
                    CloudBackupRepository.setAutoBackup(context, enabled)
                }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text("Backup existing photos", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        when (val state = backfillState) {
            is BackfillState.Idle -> Button(onClick = {
                val photos = MediaVaultRepository.queryAllPhotos(context).map { it.uri }
                CloudBackupRepository.startBackfill(context, photos)
                backfillState = BackfillState.Running(0f)
            }) { Text("Backup All Existing Photos") }

            is BackfillState.Running -> Column {
                LinearProgressIndicator(progress = state.progress, modifier = Modifier.fillMaxWidth())
                Text("${(state.progress * 100).toInt()}% complete")
                Row {
                    Button(onClick = {
                        CloudBackupRepository.pauseBackfill(context, (state.progress * 100).toInt())
                        backfillState = BackfillState.Paused(state.progress)
                    }) { Text("Pause") }
                    Spacer(modifier = Modifier.height(0.dp))
                    OutlinedButton(onClick = {
                        CloudBackupRepository.stopBackfill(context)
                        backfillState = BackfillState.Idle
                    }) { Text("Stop") }
                }
            }

            is BackfillState.Paused -> Column {
                Text("Paused — ${(state.progress * 100).toInt()}% done")
                Row {
                    Button(onClick = {
                        val photos = MediaVaultRepository.queryAllPhotos(context).map { it.uri }
                        CloudBackupRepository.resumeBackfill(context, photos)
                        backfillState = BackfillState.Running(state.progress)
                    }) { Text("Resume") }
                    OutlinedButton(onClick = {
                        CloudBackupRepository.stopBackfill(context)
                        backfillState = BackfillState.Idle
                    }) { Text("Stop") }
                }
            }

            is BackfillState.Done -> Text("Backup complete")
        }
    }
}

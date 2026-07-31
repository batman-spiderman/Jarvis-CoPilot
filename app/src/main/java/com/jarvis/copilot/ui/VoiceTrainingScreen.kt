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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jarvis.copilot.voice.VoiceTrainingRepository
import kotlinx.coroutines.launch

/**
 * Sample count / clear-data actions call the relay server's /voice-samples
 * endpoints (see relay-server/main.py); the audio + transcript bytes
 * themselves live in Backblaze B2, not on-device or in this app's process.
 *
 * "Train Model" marks samples used-in-training server-side once an external
 * fine-tuning pipeline has consumed them — that pipeline is out of scope
 * for this app, so the button is left as a TODO hook rather than faked.
 */
@Composable
fun VoiceTrainingScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var sampleCount by remember { mutableStateOf<Int?>(null) }
    var untrainedCount by remember { mutableStateOf<Int?>(null) }
    var statusText by remember { mutableStateOf("") }

    suspend fun refreshCounts() {
        try {
            val (count, untrained) = VoiceTrainingRepository.sampleCounts()
            sampleCount = count
            untrainedCount = untrained
        } catch (e: Exception) {
            statusText = "Couldn't reach the relay server."
        }
    }

    LaunchedEffect(Unit) { refreshCounts() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Voice Training", style = MaterialTheme.typography.headlineSmall)
            OutlinedButton(onClick = onBack) { Text("Back") }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            if (sampleCount != null) "Samples stored in B2: $sampleCount ($untrainedCount untrained)"
            else "Samples stored in B2: --"
        )
        if (statusText.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(statusText, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = {
            // TODO: kick off / hand off to an external fine-tuning pipeline,
            // then call VoiceTrainingRepository against the sample IDs it
            // consumed. Not wired here — no such pipeline exists yet.
        }) { Text("Train Model") }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = {
            scope.launch {
                try {
                    val deleted = VoiceTrainingRepository.clearAllSamples()
                    statusText = "Deleted $deleted sample(s) from B2."
                    refreshCounts()
                } catch (e: Exception) {
                    statusText = "Couldn't clear samples — check the relay server."
                }
            }
        }) { Text("Clear Training Data") }
    }
}

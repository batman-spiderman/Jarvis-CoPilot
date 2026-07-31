package com.jarvis.copilot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.jarvis.copilot.JarvisMode
import com.jarvis.copilot.JarvisViewModel
import com.jarvis.copilot.ui.theme.HudSurface
import com.jarvis.copilot.ui.theme.NeonCyan

private data class DiagnosticCardInfo(val title: String, val subtitle: String)

private val cards = listOf(
    DiagnosticCardInfo("System Diagnostics", "RAM & cache"),
    DiagnosticCardInfo("Notification Reader", "Read-aloud + history"),
    DiagnosticCardInfo("Power Profiles", "Location-aware battery"),
    DiagnosticCardInfo("Media Vault", "Duplicates & backup"),
    DiagnosticCardInfo("Voice Training", "Samples stored in B2")
)

@Composable
fun JarvisHudScreen(viewModel: JarvisViewModel) {
    val context = LocalContext.current
    val mode by viewModel.mode.collectAsState()
    val status by viewModel.systemStatus.collectAsState()
    var commandText by remember { mutableStateOf("") }
    var currentScreen by remember { mutableStateOf(HudScreen.HOME) }

    when (currentScreen) {
        HudScreen.HOME -> Column(
            modifier = Modifier.fillMaxSize().padding(16.dp)
        ) {
            TopStatusBar(status.batteryPercent, status.freeMemoryMb, status.cpuLoadPercent, mode)

            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                StateRing(mode = mode)
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(4.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(cards) { card ->
                    DiagnosticCard(card) {
                        currentScreen = when (card.title) {
                            "Notification Reader" -> HudScreen.NOTIFICATION_HISTORY
                            "Media Vault" -> HudScreen.CLOUD_BACKUP
                            "Voice Training" -> HudScreen.VOICE_TRAINING
                            else -> HudScreen.HOME
                        }
                    }
                }
            }

            BottomControls(
                commandText = commandText,
                onTextChange = { commandText = it },
                onSubmit = {
                    viewModel.handleCommand(commandText, context)
                    commandText = ""
                },
                onMicToggle = { listening -> viewModel.setListening(listening) }
            )
        }
        HudScreen.NOTIFICATION_HISTORY -> NotificationHistoryScreen(onBack = { currentScreen = HudScreen.HOME })
        HudScreen.CLOUD_BACKUP -> CloudBackupScreen(onBack = { currentScreen = HudScreen.HOME })
        HudScreen.VOICE_TRAINING -> VoiceTrainingScreen(onBack = { currentScreen = HudScreen.HOME })
    }
}

private enum class HudScreen { HOME, NOTIFICATION_HISTORY, CLOUD_BACKUP, VOICE_TRAINING }

@Composable
private fun TopStatusBar(batteryPercent: Int, freeMemoryMb: Long, cpuLoad: Int, mode: JarvisMode) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("Battery: $batteryPercent%", color = NeonCyan, style = MaterialTheme.typography.labelMedium)
        Text("Mem free: ${freeMemoryMb}MB", color = NeonCyan, style = MaterialTheme.typography.labelMedium)
        Text("Status: ${mode.name}", color = NeonCyan, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun StateRing(mode: JarvisMode) {
    val color = when (mode) {
        JarvisMode.IDLE -> NeonCyan.copy(alpha = 0.4f)
        JarvisMode.LISTENING -> NeonCyan
        JarvisMode.PROCESSING -> Color(0xFFFFA500)
    }
    Box(
        modifier = Modifier
            .size(140.dp)
            .clip(CircleShape)
            .padding(4.dp)
    ) {
        // Replace with an animated glow ring (Canvas / Compose animation) in production.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
        )
        Text(mode.name, color = color, modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
private fun DiagnosticCard(info: DiagnosticCardInfo, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .padding(6.dp)
            .aspectRatio(1.4f),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = HudSurface),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(info.title, color = NeonCyan, fontWeight = FontWeight.Bold)
            Text(info.subtitle, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BottomControls(
    commandText: String,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onMicToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = commandText,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Type a command...") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSubmit() })
        )
        // Push-to-talk: mic only active while held. Wire to SpeechRecognizer
        // start/stop in onMicToggle — no continuous background listening.
        IconButton(onClick = { onMicToggle(true) }) {
            Icon(Icons.Filled.Mic, contentDescription = "Tap to Speak", tint = NeonCyan)
        }
    }
}

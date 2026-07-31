package com.jarvis.copilot.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun OnboardingFlow(onComplete: () -> Unit) {
    var step by remember { mutableStateOf(0) }
    val context = LocalContext.current

    when (step) {
        0 -> PermissionExplainerScreen(
            title = "Notification Reading",
            justification = "Jarvis can read your notifications aloud, one at a time. " +
                "You choose which apps to exclude later in Settings.",
            onGrant = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                step++
            },
            onSkip = { step++ }
        )
        1 -> PermissionExplainerScreen(
            title = "Accessibility (Settings Automation)",
            justification = "Lets Jarvis toggle Battery Saver and read the current screen aloud — " +
                "only when you tap the button. It does nothing in the background.",
            onGrant = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                step++
            },
            onSkip = { step++ }
        )
        2 -> onComplete()
    }
}

@Composable
fun PermissionExplainerScreen(
    title: String,
    justification: String,
    onGrant: () -> Unit,
    onSkip: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(12.dp))
        Text(justification, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onGrant) { Text("Grant") }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onSkip) { Text("Skip for now") }
    }
}

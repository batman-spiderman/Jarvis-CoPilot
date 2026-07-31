package com.jarvis.copilot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jarvis.copilot.data.NotificationEntry
import com.jarvis.copilot.data.NotificationHistoryDb
import com.jarvis.copilot.service.NotificationDigestService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun NotificationHistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { NotificationHistoryDb.getInstance(context).notificationDao() }
    val entries by dao.getAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScopeCompat()

    var excludedApps by remember { mutableStateOf(NotificationDigestService.getExcludedApps(context)) }
    var newExclusion by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Notification History", style = MaterialTheme.typography.headlineSmall)
            OutlinedButton(onClick = onBack) { Text("Back") }
        }

        Button(
            onClick = { scope.launch { dao.clearAll() } },
            modifier = Modifier.padding(vertical = 8.dp)
        ) { Text("Clear All") }

        Text("Excluded apps (won't be read aloud or logged)", style = MaterialTheme.typography.titleSmall)
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            OutlinedTextField(
                value = newExclusion,
                onValueChange = { newExclusion = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("e.g. com.example.banking") }
            )
            Button(onClick = {
                val pkg = newExclusion.trim()
                if (pkg.isNotBlank()) {
                    val updated = excludedApps + pkg
                    NotificationDigestService.setExcludedApps(context, updated)
                    excludedApps = updated
                    newExclusion = ""
                }
            }) { Text("Exclude") }
        }
        excludedApps.forEach { pkg ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(pkg, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    val updated = excludedApps - pkg
                    NotificationDigestService.setExcludedApps(context, updated)
                    excludedApps = updated
                }) { Text("Remove") }
            }
        }

        LazyColumn {
            items(entries) { entry: NotificationEntry ->
                NotificationRow(entry)
                Divider()
            }
        }
    }
}

@Composable
private fun NotificationRow(entry: NotificationEntry) {
    val formatter = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(entry.title.ifBlank { entry.packageName }, style = MaterialTheme.typography.bodyLarge)
        Text(entry.text, style = MaterialTheme.typography.bodyMedium)
        Text(formatter.format(Date(entry.timestamp)), style = MaterialTheme.typography.labelSmall)
    }
}

// Small helper since this file avoids importing viewModelScope for a screen-local action.
@Composable
private fun rememberCoroutineScopeCompat() = androidx.compose.runtime.rememberCoroutineScope()

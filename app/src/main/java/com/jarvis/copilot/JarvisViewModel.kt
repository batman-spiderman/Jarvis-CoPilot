package com.jarvis.copilot

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.copilot.intent.LocalIntentEngine
import com.jarvis.copilot.network.ChatRequest
import com.jarvis.copilot.network.RelayApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class JarvisMode { IDLE, LISTENING, PROCESSING }

data class SystemStatus(
    val batteryPercent: Int = 0,
    val freeMemoryMb: Long = 0,
    val cpuLoadPercent: Int = 0
)

class JarvisViewModel : ViewModel() {

    private val _mode = MutableStateFlow(JarvisMode.IDLE)
    val mode: StateFlow<JarvisMode> = _mode.asStateFlow()

    private val _systemStatus = MutableStateFlow(SystemStatus())
    val systemStatus: StateFlow<SystemStatus> = _systemStatus.asStateFlow()

    private val _responseText = MutableStateFlow("")
    val responseText: StateFlow<String> = _responseText.asStateFlow()

    private val _locationPermissionGranted = MutableStateFlow(false)
    val locationPermissionGranted: StateFlow<Boolean> = _locationPermissionGranted.asStateFlow()

    private val _autoBackupEnabled = MutableStateFlow(false)
    val autoBackupEnabled: StateFlow<Boolean> = _autoBackupEnabled.asStateFlow()

    fun handleCommand(command: String, context: Context) {
        _mode.value = JarvisMode.PROCESSING

        val handledLocally = LocalIntentEngine.dispatch(command, context)
        if (handledLocally) {
            _mode.value = JarvisMode.IDLE
            return
        }

        // Falls through to the cloud relay — client never touches provider keys directly.
        viewModelScope.launch {
            try {
                val response = RelayApiClient.api.chat(ChatRequest(command))
                _responseText.value = response.text
            } catch (e: Exception) {
                _responseText.value = "Sorry, I couldn't reach the assistant service."
            } finally {
                _mode.value = JarvisMode.IDLE
            }
        }
    }

    fun refreshSystemStatus(context: Context) {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryPercent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val freeMb = memInfo.availMem / (1024 * 1024)

        _systemStatus.value = SystemStatus(
            batteryPercent = batteryPercent,
            freeMemoryMb = freeMb,
            cpuLoadPercent = 0 // CPU load needs /proc/stat parsing or a library; left as a hook
        )
    }

    fun setListening(listening: Boolean) {
        _mode.value = if (listening) JarvisMode.LISTENING else JarvisMode.IDLE
    }

    fun setLocationPermissionGranted(granted: Boolean) {
        _locationPermissionGranted.value = granted
    }

    fun setAutoBackupEnabled(enabled: Boolean) {
        _autoBackupEnabled.value = enabled
    }
}

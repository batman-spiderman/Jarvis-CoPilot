package com.jarvis.copilot.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.copilot.network.ChatRequest
import com.jarvis.copilot.network.RelayApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Deliberately narrow (architecture §7 / §13): does nothing unless
 * requestScreenCapture() was just called from a UI button press. Fires once,
 * then goes back to inert. No package-name tracking, no window-state logging,
 * no persistent buffer — this is the boundary between "gated automation" and
 * "arbitrary UI automation," which we explicitly did not build.
 */
class ScreenSummarizerAccessibilityService : AccessibilityService() {

    private var captureRequested = false
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!captureRequested) return
        captureRequested = false // one-shot: consume immediately

        val rootNode = rootInActiveWindow ?: return
        val extractedText = extractTextFromNode(rootNode)
        rootNode.recycle()

        if (extractedText.isNotBlank()) {
            summarizeViaRelay(extractedText)
        }
    }

    /** Called from the UI when the user taps "Summarize this screen." */
    fun requestScreenCapture() {
        captureRequested = true
    }

    /** Called from the UI on "toggle battery saver" voice/text command. */
    fun toggleBatterySaverViaSettings() {
        // Real implementation: locate the Battery Saver toggle node in
        // Settings via findAccessibilityNodeInfosByViewId / text search and
        // perform ACTION_CLICK on it. Left as an integration point since the
        // exact node IDs vary by OEM (stock/Samsung/MIUI settings layouts differ).
    }

    private fun extractTextFromNode(node: AccessibilityNodeInfo): String {
        val builder = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            n.text?.let { builder.append(it).append(" ") }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(node)
        return builder.toString().trim()
    }

    private fun summarizeViaRelay(screenText: String) {
        scope.launch {
            try {
                val response = RelayApiClient.api.chat(
                    ChatRequest("Summarize this screen content in 2-3 sentences: $screenText")
                )
                // Hand off to JarvisViewModel / TTS for spoken output —
                // wire via a shared StateFlow or event bus in production.
            } catch (e: Exception) {
                // network failure — fail silently for this one-shot feature
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        var instance: ScreenSummarizerAccessibilityService? = null
            private set
    }
}

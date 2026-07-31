package com.jarvis.copilot

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.copilot.ui.JarvisHudScreen
import com.jarvis.copilot.ui.OnboardingFlow
import com.jarvis.copilot.ui.theme.JarvisCoPilotTheme

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* handled per-feature via callbacks passed into Compose */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            JarvisCoPilotTheme {
                var onboardingComplete by remember { mutableStateOf(hasCompletedOnboarding()) }
                val viewModel: JarvisViewModel = viewModel()

                if (!onboardingComplete) {
                    OnboardingFlow(
                        onComplete = {
                            markOnboardingComplete()
                            onboardingComplete = true
                        }
                    )
                } else {
                    JarvisHudScreen(viewModel = viewModel)
                }
            }
        }
    }

    fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    fun requestRuntimePermission(permission: String) {
        requestPermissionLauncher.launch(permission)
    }

    private fun hasCompletedOnboarding(): Boolean =
        getSharedPreferences("onboarding_prefs", MODE_PRIVATE).getBoolean("completed", false)

    private fun markOnboardingComplete() {
        getSharedPreferences("onboarding_prefs", MODE_PRIVATE).edit()
            .putBoolean("completed", true).apply()
    }
}

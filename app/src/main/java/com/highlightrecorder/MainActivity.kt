package com.highlightrecorder

import android.app.Activity
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.highlightrecorder.service.RecordingService
import com.highlightrecorder.ui.HomeScreen
import com.highlightrecorder.ui.LibraryScreen
import com.highlightrecorder.ui.MainViewModel
import com.highlightrecorder.ui.OnboardingScreen
import com.highlightrecorder.ui.SettingsScreen
import com.highlightrecorder.ui.theme.HighlightRecorderTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HighlightRecorderTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val vm: MainViewModel = viewModel()
                    val nav = rememberNavController()
                    val onboarded by vm.onboarded.collectAsState()

                    // 前后台切换时刷新权限状态(从系统设置返回后自动检测)
                    val lifecycleOwner = LocalLifecycleOwner.current
                    DisposableEffect(lifecycleOwner) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_RESUME) vm.refreshPermissions()
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                    }

                    // MediaProjection 系统同意弹窗(每次开始录制都需确认,Android 14+ 强制)
                    val projectionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.StartActivityForResult(),
                    ) { result ->
                        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                            ContextCompat.startForegroundService(
                                this,
                                RecordingService.startIntent(
                                    this, result.resultCode, result.data!!,
                                ),
                            )
                        }
                    }
                    val requestProjection = {
                        val mpm = getSystemService(MediaProjectionManager::class.java)
                        val intent = if (Build.VERSION.SDK_INT >= 34) {
                            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                        } else {
                            @Suppress("DEPRECATION")
                            mpm.createScreenCaptureIntent()
                        }
                        projectionLauncher.launch(intent)
                    }

                    NavHost(
                        navController = nav,
                        startDestination = if (onboarded) "home" else "onboarding",
                    ) {
                        composable("onboarding") {
                            OnboardingScreen(
                                viewModel = vm,
                                onFinished = {
                                    nav.navigate("home") { popUpTo("onboarding") { inclusive = true } }
                                },
                            )
                        }
                        composable("home") {
                            HomeScreen(
                                viewModel = vm,
                                onRequestStartRecording = requestProjection,
                                onGoOnboarding = { nav.navigate("onboarding") },
                                onGoSettings = { nav.navigate("settings") },
                                onGoLibrary = { nav.navigate("library") },
                            )
                        }
                        composable("settings") {
                            SettingsScreen(viewModel = vm, onBack = { nav.popBackStack() })
                        }
                        composable("library") {
                            LibraryScreen(onBack = { nav.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}

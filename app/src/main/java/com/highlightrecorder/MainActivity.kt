package com.highlightrecorder

import android.app.Activity
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.highlightrecorder.service.RecordingService
import com.highlightrecorder.ui.HomeScreen
import com.highlightrecorder.ui.LibraryScreen
import com.highlightrecorder.ui.MainViewModel
import com.highlightrecorder.ui.OnboardingScreen
import com.highlightrecorder.ui.SettingsScreen
import com.highlightrecorder.ui.TrashScreen
import com.highlightrecorder.ui.theme.HighlightRecorderTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mountContent()
    }

    /**
     * 部分 ROM(HyperOS 等)在外部 Activity(录屏授权弹窗/浏览器/播放器)往返后,
     * ComposeView 可能丢失,整页只剩窗口底色(深色灰屏/浅色白屏)。
     * onResume 时发现内容为空则重新挂载。
     */
    override fun onResume() {
        super.onResume()
        val content = findViewById<android.view.ViewGroup>(android.R.id.content)
        if (content.childCount == 0) {
            com.highlightrecorder.data.FileLogger.log("MainActivity", "content view lost, remounting")
            mountContent()
        }
    }

    private fun mountContent() {
        setContent {
            HighlightRecorderTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val vm: MainViewModel = viewModel()
                    // 不用 navigation-compose:其预测式返回转场在 Android 15(强制开启,
                    // manifest 无法关闭)慢手势下会把导航栈掏空,整页只剩窗口底色。
                    // 本应用只有 5 个页面,状态机式导航足够且返回行为完全可控。
                    var route by rememberSaveable {
                        mutableStateOf(if (vm.onboarded.value) "home" else "onboarding")
                    }

                    // 子页面的返回(含侧滑手势)统一回到上一级;主页/引导页返回走系统默认(退出)
                    BackHandler(enabled = route == "settings" || route == "library" || route == "trash") {
                        route = if (route == "trash") "library" else "home"
                    }

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

                    when (route) {
                        "onboarding" -> OnboardingScreen(
                            viewModel = vm,
                            onFinished = { route = "home" },
                        )

                        "settings" -> SettingsScreen(
                            viewModel = vm,
                            onBack = { route = "home" },
                        )

                        "library" -> LibraryScreen(
                            onBack = { route = "home" },
                            onGoTrash = { route = "trash" },
                        )

                        "trash" -> TrashScreen(onBack = { route = "library" })

                        else -> HomeScreen(
                            viewModel = vm,
                            onRequestStartRecording = requestProjection,
                            onGoOnboarding = { route = "onboarding" },
                            onGoSettings = { route = "settings" },
                            onGoLibrary = { route = "library" },
                        )
                    }
                }
            }
        }
    }
}

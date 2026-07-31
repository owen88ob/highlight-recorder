package com.highlightrecorder.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 分步权限引导:每项说明"为什么需要",授权状态实时刷新。
 * MediaProjection 属每次会话同意,此处只做说明,实际弹窗在开始录制时。
 */
@Composable
fun OnboardingScreen(
    viewModel: MainViewModel,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val perms by viewModel.permissions.collectAsState()

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refreshPermissions() }
    val storageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refreshPermissions() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("欢迎使用高光回录", style = MaterialTheme.typography.headlineSmall)
        Text(
            "开始前需要以下几项授权,逐项完成即可:",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(4.dp))

        PermissionCard(
            title = "1. 悬浮窗权限",
            why = "在游戏全屏画面上显示保存按钮,不打断操作。",
            granted = perms.overlay,
        ) {
            context.startActivity(PermissionHelper.overlaySettingsIntent(context))
        }

        PermissionCard(
            title = "2. 屏幕录制(每次需确认)",
            why = "Android 系统安全要求:每次开始录制都会弹一次系统确认框,这是正常现象。点主页「开始录制」时确认即可。",
            granted = true,
            actionLabel = "知道了",
        ) {}

        PermissionCard(
            title = "3. 通知权限",
            why = "前台录制服务需要常驻通知(Android 13+ 需授权),也用于提示保存结果。",
            granted = perms.notification,
        ) {
            notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        PermissionCard(
            title = "4. 存储权限",
            why = "保存高光视频到相册(仅 Android 9 及以下需要,你的系统可能显示已授权)。",
            granted = perms.storage,
        ) {
            storageLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        PermissionCard(
            title = "5. 电池优化白名单(建议)",
            why = "防止录制被系统在后台杀掉导致游戏中断录制。可选,但强烈建议。",
            granted = perms.battery,
        ) {
            runCatching {
                context.startActivity(PermissionHelper.batteryWhitelistIntent(context))
            }.onFailure {
                context.startActivity(PermissionHelper.appSettingsIntent(context))
            }
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                viewModel.markOnboarded()
                onFinished()
            },
            enabled = perms.mandatoryOk,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (perms.mandatoryOk) "全部就绪,进入主页" else "请先完成上方必需项")
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    why: String,
    granted: Boolean,
    actionLabel: String = "去授权",
    onAction: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(why, style = MaterialTheme.typography.bodySmall)
            }
            if (granted) {
                Text(
                    "已就绪",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            } else {
                OutlinedButton(
                    onClick = onAction,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

package com.highlightrecorder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.highlightrecorder.data.SettingsHolder
import com.highlightrecorder.service.RecordingService

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onRequestStartRecording: () -> Unit,
    onGoOnboarding: () -> Unit,
    onGoSettings: () -> Unit,
    onGoLibrary: () -> Unit,
) {
    val state by viewModel.recordingState.collectAsState()
    val buffered by viewModel.bufferedSeconds.collectAsState()
    val perms by viewModel.permissions.collectAsState()
    val recording = state == RecordingService.State.Recording ||
        state == RecordingService.State.Saving
    val rewind = SettingsHolder.current.rewindSeconds

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("高光回录", style = MaterialTheme.typography.headlineSmall)

        if (!perms.mandatoryOk) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("有必需权限未授予,录制不可用", color = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onGoOnboarding) { Text("重新引导") }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    when (state) {
                        is RecordingService.State.Recording -> "循环录制中"
                        is RecordingService.State.Saving -> "正在保存…"
                        is RecordingService.State.Error ->
                            "出错: ${(state as RecordingService.State.Error).message}"
                        else -> "未在录制"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (recording) "已缓冲 $buffered 秒 / 最多回录 $rewind 秒"
                    else "开始后将在后台持续缓冲最近 $rewind 秒",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Button(
            onClick = {
                if (recording) viewModel.requestStop() else onRequestStartRecording()
            },
            enabled = perms.mandatoryOk,
            colors = if (recording) {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.buttonColors()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text(if (recording) "停止录制" else "开始循环录制")
        }

        OutlinedButton(
            onClick = { viewModel.requestSave() },
            enabled = recording,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("立即保存最近 $rewind 秒")
        }

        Text(
            "提示:录制中悬浮球会一直显示,单击悬浮球即可保存,无需回到本页。",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.weight(1f))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onGoSettings, modifier = Modifier.weight(1f)) { Text("设置") }
            OutlinedButton(onClick = onGoLibrary, modifier = Modifier.weight(1f)) { Text("视频库") }
        }
    }
}

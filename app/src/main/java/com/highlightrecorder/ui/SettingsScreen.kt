package com.highlightrecorder.ui

import android.media.MediaCodecList
import android.media.MediaFormat
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
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.highlightrecorder.data.AudioSource
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsState()

    val hevcSupported = remember {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any {
                it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
            }
        }
    }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.updateSettings { it.copy(audioSource = AudioSource.MIC) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall)

        // ---- 回退时长 ----
        Section("回退时长: ${settings.rewindSeconds} 秒") {
            Slider(
                value = settings.rewindSeconds.toFloat(),
                onValueChange = { v ->
                    viewModel.updateSettings { it.copy(rewindSeconds = (v / 15).roundToInt() * 15) }
                },
                valueRange = 15f..120f,
            )
            val memMb = settings.videoBitrateBps / 8L * (settings.rewindSeconds + 2) / 1_000_000
            Text("缓冲内存预估: 约 ${memMb} MB(码率 × 时长)", style = MaterialTheme.typography.bodySmall)
        }

        // ---- 分辨率 ----
        Section("分辨率") {
            ChipRow(
                options = listOf(0 to "跟随屏幕", 720 to "720p", 1080 to "1080p"),
                selected = settings.resolutionShortEdge,
            ) { v -> viewModel.updateSettings { it.copy(resolutionShortEdge = v) } }
            Text(
                "游戏场景建议 720p;「跟随屏幕」在高刷/2K 屏上开销最大",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // ---- 帧率 ----
        Section("帧率") {
            ChipRow(
                options = listOf(30 to "30 fps", 60 to "60 fps"),
                selected = settings.frameRate,
            ) { v -> viewModel.updateSettings { it.copy(frameRate = v) } }
        }

        // ---- 码率 ----
        Section("码率: %.1f Mbps".format(settings.videoBitrateBps / 1_000_000f)) {
            ChipRow(
                options = listOf(4_000_000 to "低", 8_000_000 to "中", 16_000_000 to "高", -1 to "自定义"),
                selected = if (settings.videoBitrateBps in listOf(4_000_000, 8_000_000, 16_000_000)) {
                    settings.videoBitrateBps
                } else {
                    -1
                },
            ) { v ->
                if (v > 0) viewModel.updateSettings { it.copy(videoBitrateBps = v) }
                else viewModel.updateSettings { it.copy(videoBitrateBps = 12_000_000) }
            }
            if (settings.videoBitrateBps !in listOf(4_000_000, 8_000_000, 16_000_000)) {
                Slider(
                    value = settings.videoBitrateBps / 1_000_000f,
                    onValueChange = { v ->
                        viewModel.updateSettings { it.copy(videoBitrateBps = (v * 1_000_000).toInt()) }
                    },
                    valueRange = 1f..30f,
                )
            }
            val perHourMb = settings.videoBitrateBps / 8L * 3600 / 1_000_000
            Text("每小时素材约 ${perHourMb} MB(仅缓冲最近片段落盘)", style = MaterialTheme.typography.bodySmall)
        }

        // ---- 编码器 ----
        Section("编码器") {
            ChipRow(
                options = buildList {
                    add("video/avc" to "H.264")
                    if (hevcSupported) add("video/hevc" to "H.265 (HEVC)")
                },
                selected = settings.videoMime,
            ) { v -> viewModel.updateSettings { it.copy(videoMime = v) } }
            if (!hevcSupported) {
                Text("本机不支持 H.265 编码", style = MaterialTheme.typography.bodySmall)
            }
        }

        // ---- 音频来源 ----
        Section("音频来源") {
            ChipRow(
                options = listOf(
                    AudioSource.INTERNAL to "内录",
                    AudioSource.MIC to "麦克风",
                    AudioSource.MUTE to "静音",
                ),
                selected = settings.audioSource,
            ) { v ->
                when {
                    v == AudioSource.MIC && !viewModel.permissions.value.mic ->
                        micLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    else -> viewModel.updateSettings { it.copy(audioSource = v) }
                }
            }
            Text("内录仅 Android 10+ 且目标 App 允许时有效", style = MaterialTheme.typography.bodySmall)
        }

        // ---- 悬浮窗 ----
        Section("悬浮窗外观") {
            Text("透明度 %.0f%%".format(settings.overlayAlpha * 100), style = MaterialTheme.typography.bodySmall)
            Slider(
                value = settings.overlayAlpha,
                onValueChange = { v -> viewModel.updateSettings { it.copy(overlayAlpha = v) } },
                valueRange = 0.3f..1f,
            )
            Text("大小 %.2fx".format(settings.overlayScale), style = MaterialTheme.typography.bodySmall)
            Slider(
                value = settings.overlayScale,
                onValueChange = { v -> viewModel.updateSettings { it.copy(overlayScale = v) } },
                valueRange = 0.75f..1.5f,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("贴边时半隐藏", modifier = Modifier.weight(1f))
                Switch(
                    checked = settings.overlayEdgeHide,
                    onCheckedChange = { on -> viewModel.updateSettings { it.copy(overlayEdgeHide = on) } },
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        androidx.compose.material3.OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("返回")
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun <T> ChipRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}

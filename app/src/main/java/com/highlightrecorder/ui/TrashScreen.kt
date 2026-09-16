package com.highlightrecorder.ui

import android.content.Intent
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.os.Build
import android.util.Size
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.highlightrecorder.data.SettingsHolder
import com.highlightrecorder.data.VideoItem
import com.highlightrecorder.data.VideoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 回收站:展示已软删除的视频,支持恢复、彻底删除。
 * 彻底删除前提示「该视频将会永久消失!(真的很久!)」。
 * 超过设置天数(默认 30 天)的条目在进入本页/视频库时自动清理。
 */
@Composable
fun TrashScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { VideoRepository(context) }
    val scope = rememberCoroutineScope()
    var clips by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<List<VideoItem>?>(null) }
    val trashDays = SettingsHolder.current.trashAutoDeleteDays

    fun refresh() {
        scope.launch {
            repo.purgeExpired(trashDays)
            clips = repo.listClips(trash = true)
            loaded = true
        }
    }

    LaunchedEffect(Unit) { refresh() }

    fun restore(item: VideoItem) {
        scope.launch {
            val ok = runCatching { repo.restore(item) }.getOrDefault(false)
            Toast.makeText(
                context,
                if (ok) "已恢复到视频库" else "恢复失败",
                Toast.LENGTH_SHORT,
            ).show()
            refresh()
        }
    }

    fun deleteForever(items: List<VideoItem>) {
        scope.launch {
            var ok = 0
            items.forEach { runCatching { if (repo.delete(it)) ok++ } }
            Toast.makeText(context, "已彻底删除 $ok / ${items.size} 个", Toast.LENGTH_SHORT).show()
            refresh()
        }
    }

    // 彻底删除确认
    pendingDelete?.let { items ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = {
                Text(if (items.size > 1) "彻底删除 ${items.size} 个视频?" else "彻底删除该视频?")
            },
            text = { Text("该视频将会永久消失!(真的很久!)") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    deleteForever(items)
                }) { Text("彻底删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("再想想") }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("回收站", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (clips.isNotEmpty()) {
                TextButton(onClick = { pendingDelete = clips }) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        Text(
            "删除的视频保留 $trashDays 天,到期自动彻底删除",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (loaded && clips.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("回收站是空的")
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(clips, key = { it.id }) { item ->
                    TrashRow(
                        item = item,
                        remainDays = if (item.trashedAtMs > 0) {
                            (trashDays - (System.currentTimeMillis() - item.trashedAtMs) / 86_400_000L)
                                .toInt().coerceAtLeast(0)
                        } else {
                            trashDays
                        },
                        onPlay = {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(item.uri, "video/mp4")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching { context.startActivity(intent) }
                        },
                        onRestore = { restore(item) },
                        onDelete = { pendingDelete = listOf(item) },
                    )
                }
            }
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回") }
    }
}

@Composable
private fun TrashRow(
    item: VideoItem,
    remainDays: Int,
    onPlay: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var thumb by remember(item.id) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(item.id) {
        thumb = withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 29) {
                    context.contentResolver.loadThumbnail(item.uri, Size(320, 180), null)
                } else {
                    @Suppress("DEPRECATION")
                    ThumbnailUtils.createVideoThumbnail(
                        item.filePath ?: item.uri.toString(),
                        android.provider.MediaStore.Video.Thumbnails.MINI_KIND,
                    )
                }
            }.getOrNull()
        }
    }

    Card(modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onPlay)) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (thumb != null) {
                Image(
                    bitmap = thumb!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(96.dp, 54.dp),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier.size(96.dp, 54.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("▶") }
            }
            Column(modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp)) {
                Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Spacer(Modifier.size(2.dp))
                val date = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                    .format(Date(item.dateAddedSec * 1000))
                Text(
                    "%.1f 秒 · %.1f MB · %s".format(
                        item.durationMs / 1000f,
                        item.sizeBytes / 1_048_576f,
                        date,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "剩余 $remainDays 天自动删除",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Row {
                    TextButton(onClick = onRestore) { Text("恢复") }
                    TextButton(onClick = onDelete) {
                        Text("彻底删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

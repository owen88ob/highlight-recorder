package com.highlightrecorder.ui

import android.content.Intent
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.os.Build
import android.util.Size
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import com.highlightrecorder.data.VideoItem
import com.highlightrecorder.data.VideoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 已保存高光列表:缩略图/时长/大小/时间。
 * 单点播放;长按进入多选模式,支持全选、批量删除、批量分享。
 * 删除一律经二次确认后移入回收站(见 TrashScreen)。
 */
@Composable
fun LibraryScreen(onBack: () -> Unit, onGoTrash: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { VideoRepository(context) }
    val scope = rememberCoroutineScope()
    var clips by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var pendingTrash by remember { mutableStateOf<List<VideoItem>?>(null) }
    val selectionMode = selected.isNotEmpty()
    val trashDays = com.highlightrecorder.data.SettingsHolder.current.trashAutoDeleteDays

    fun refresh() {
        scope.launch {
            repo.purgeExpired(trashDays)
            clips = repo.listClips()
            loaded = true
            selected = emptySet()
        }
    }

    LaunchedEffect(Unit) { refresh() }

    fun play(item: VideoItem) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, "没有可播放的应用", Toast.LENGTH_SHORT).show() }
    }

    fun share(items: List<VideoItem>) {
        if (items.isEmpty()) return
        val intent = if (items.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, items.first().uri)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "video/mp4"
                putParcelableArrayListExtra(
                    Intent.EXTRA_STREAM,
                    ArrayList(items.map { it.uri }),
                )
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(Intent.createChooser(intent, "分享高光")) }
            .onFailure { Toast.makeText(context, "没有可分享的应用", Toast.LENGTH_SHORT).show() }
    }

    fun trashItems(items: List<VideoItem>) {
        scope.launch {
            var ok = 0
            items.forEach { item ->
                runCatching { if (repo.moveToTrash(item)) ok++ }
            }
            Toast.makeText(context, "已移入回收站 $ok / ${items.size} 个", Toast.LENGTH_SHORT).show()
            refresh()
        }
    }

    // 删除(移入回收站)二次确认
    pendingTrash?.let { items ->
        AlertDialog(
            onDismissRequest = { pendingTrash = null },
            title = { Text(if (items.size > 1) "删除 ${items.size} 个视频?" else "删除该视频?") },
            text = { Text("将移入回收站,${trashDays} 天后自动彻底删除,期间可在回收站恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingTrash = null
                    trashItems(items)
                }) { Text("移入回收站", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingTrash = null }) { Text("取消") }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selectionMode) {
            // 多选工具栏
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "已选 ${selected.size} 项",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    selected = if (selected.size == clips.size) emptySet() else clips.map { it.id }.toSet()
                }) { Text(if (selected.size == clips.size) "取消全选" else "全选") }
                TextButton(onClick = { share(clips.filter { it.id in selected }) }) { Text("分享") }
                TextButton(onClick = { pendingTrash = clips.filter { it.id in selected } }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = { selected = emptySet() }) { Text("退出") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("视频库", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onGoTrash) { Text("回收站") }
                TextButton(onClick = { refresh() }) { Text("刷新") }
            }
        }

        if (loaded && clips.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("还没有高光视频,录制中点悬浮球保存一个吧")
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(clips, key = { it.id }) { item ->
                    ClipRow(
                        item = item,
                        selectionMode = selectionMode,
                        checked = item.id in selected,
                        onClick = {
                            if (selectionMode) {
                                selected = if (item.id in selected) selected - item.id else selected + item.id
                            } else {
                                play(item)
                            }
                        },
                        onLongClick = {
                            if (!selectionMode) selected = setOf(item.id)
                        },
                        onShare = { share(listOf(item)) },
                        onDelete = { pendingTrash = listOf(item) },
                    )
                }
            }
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipRow(
    item: VideoItem,
    selectionMode: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onShare: () -> Unit,
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
                        item.uri.toString(), android.provider.MediaStore.Video.Thumbnails.MINI_KIND,
                    )
                }
            }.getOrNull()
        }
    }

    Card(
        colors = if (checked) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Checkbox(checked = checked, onCheckedChange = { onClick() })
            }
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
                if (!selectionMode) {
                    Row {
                        TextButton(onClick = onShare) { Text("分享") }
                        TextButton(onClick = onDelete) { Text("删除") }
                    }
                }
            }
        }
    }
}

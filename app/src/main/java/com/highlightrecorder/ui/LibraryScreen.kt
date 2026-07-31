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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.highlightrecorder.data.VideoItem
import com.highlightrecorder.data.VideoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 已保存高光列表:缩略图/时长/大小/时间,支持播放、分享、删除。 */
@Composable
fun LibraryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { VideoRepository(context) }
    val scope = rememberCoroutineScope()
    var clips by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            clips = repo.listClips()
            loaded = true
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("视频库", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { refresh() }) { Text("刷新") }
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
                        onPlay = {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(item.uri, "video/mp4")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching { context.startActivity(intent) }
                                .onFailure {
                                    Toast.makeText(context, "没有可播放的应用", Toast.LENGTH_SHORT).show()
                                }
                        },
                        onShare = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "video/mp4"
                                putExtra(Intent.EXTRA_STREAM, item.uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "分享高光"))
                        },
                        onDelete = {
                            scope.launch {
                                runCatching { repo.delete(item) }
                                    .onSuccess { refresh() }
                                    .onFailure {
                                        Toast.makeText(context, "删除失败: ${it.message}", Toast.LENGTH_SHORT).show()
                                    }
                            }
                        },
                    )
                }
            }
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回") }
    }
}

@Composable
private fun ClipRow(
    item: VideoItem,
    onPlay: () -> Unit,
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
                Spacer(Modifier.height(2.dp))
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
                Row {
                    TextButton(onClick = onShare) { Text("分享") }
                    TextButton(onClick = onDelete) { Text("删除") }
                }
            }
        }
    }
}

package com.highlightrecorder.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class VideoItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    /** 进入回收站的时间(毫秒),不在回收站为 0。 */
    val trashedAtMs: Long = 0L,
    /** 仅 API 26-28 使用的磁盘路径。 */
    val filePath: String? = null,
)

/**
 * 已保存高光视频的查询、回收站(软删除)与永久删除。
 * 回收站 = `Movies/高光回录/回收站/` 子目录;进入时间记录在 SharedPreferences,
 * [purgeExpired] 按设置天数自动清理。
 */
class VideoRepository(private val context: Context) {

    companion object {
        const val MAIN_DIR = "高光回录"
        const val TRASH_DIR = "高光回录/回收站"
        private const val PREFS = "trash_meta"
    }

    private val trashMeta = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DATE_ADDED,
        MediaStore.Video.Media.DATA,
    )

    /** [trash]=true 列回收站,否则列正式视频。 */
    suspend fun listClips(trash: Boolean = false): List<VideoItem> = withContext(Dispatchers.IO) {
        val out = ArrayList<VideoItem>()
        val selection: String?
        val args: Array<String>?
        if (Build.VERSION.SDK_INT >= 29) {
            selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
            args = if (trash) {
                arrayOf("%/$TRASH_DIR/%")
            } else {
                // 回收站是子目录,SQL 会一并命中,靠下方内存过滤排除
                arrayOf("%/$MAIN_DIR/%")
            }
        } else {
            selection = "${MediaStore.Video.Media.DATA} LIKE ?"
            args = arrayOf("%/$MAIN_DIR/%")
        }
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection, selection, args,
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val dataCol = c.getColumnIndex(MediaStore.Video.Media.DATA)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val path = if (dataCol >= 0) c.getString(dataCol) else null
                // 有 DATA 时按路径精确判断;Q+ 回收站查询已被 SQL 限定
                val inTrash = path?.contains("/回收站/") ?: trash
                if (inTrash != trash) continue
                out.add(
                    VideoItem(
                        id = id,
                        uri = ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id,
                        ),
                        name = c.getString(nameCol) ?: "",
                        durationMs = c.getLong(durCol),
                        sizeBytes = c.getLong(sizeCol),
                        dateAddedSec = c.getLong(dateCol),
                        trashedAtMs = trashMeta.getLong(metaKey(id), 0L),
                        filePath = path,
                    ),
                )
            }
        }
        out
    }

    /** 移入回收站(软删除)。 */
    suspend fun moveToTrash(item: VideoItem): Boolean = move(item, toTrash = true)

    /** 从回收站恢复。 */
    suspend fun restore(item: VideoItem): Boolean = move(item, toTrash = false)

    /** 永久删除(回收站内)。 */
    suspend fun delete(item: VideoItem): Boolean = withContext(Dispatchers.IO) {
        val ok = context.contentResolver.delete(item.uri, null, null) > 0
        if (ok) trashMeta.edit().remove(metaKey(item.id)).apply()
        ok
    }

    /** 清理进入回收站超过 [days] 天的视频,返回清理数量。 */
    suspend fun purgeExpired(days: Int): Int {
        if (days <= 0) return 0
        val now = System.currentTimeMillis()
        var count = 0
        listClips(trash = true).forEach { item ->
            val at = item.trashedAtMs
            if (at > 0 && now - at > days * 86_400_000L) {
                if (delete(item)) count++
            }
        }
        return count
    }

    private suspend fun move(item: VideoItem, toTrash: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= 29) {
                val dir = if (toTrash) TRASH_DIR else MAIN_DIR
                val values = ContentValues().apply {
                    put(
                        MediaStore.Video.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_MOVIES}/$dir",
                    )
                }
                val ok = context.contentResolver.update(item.uri, values, null, null) > 0
                if (ok) markTrash(item.id, toTrash)
                ok
            } else {
                // 旧系统直接挪文件
                val src = item.filePath?.let { File(it) } ?: return@withContext false
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    if (toTrash) TRASH_DIR else MAIN_DIR,
                ).apply { mkdirs() }
                val ok = src.renameTo(File(dir, src.name))
                if (ok) {
                    markTrash(item.id, toTrash)
                    // 旧路径的媒体库记录删掉,等系统重新扫描新路径
                    context.contentResolver.delete(item.uri, null, null)
                }
                ok
            }
        }

    private fun markTrash(id: Long, trashed: Boolean) {
        val e = trashMeta.edit()
        if (trashed) e.putLong(metaKey(id), System.currentTimeMillis())
        else e.remove(metaKey(id))
        e.apply()
    }

    private fun metaKey(id: Long) = "trashed_at_$id"
}

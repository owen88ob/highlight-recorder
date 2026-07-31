package com.highlightrecorder.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** 设置持久化(DataStore)。服务启动时经 [SettingsHolder] 读取快照。 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val REWIND = intPreferencesKey("rewind_seconds")
        val RESOLUTION = intPreferencesKey("resolution_short_edge")
        val FPS = intPreferencesKey("frame_rate")
        val BITRATE = intPreferencesKey("video_bitrate_bps")
        val MIME = stringPreferencesKey("video_mime")
        val AUDIO = stringPreferencesKey("audio_source")
        val OVERLAY_ALPHA = floatPreferencesKey("overlay_alpha")
        val OVERLAY_SCALE = floatPreferencesKey("overlay_scale")
        val OVERLAY_EDGE = booleanPreferencesKey("overlay_edge_hide")
    }

    val settings: Flow<RecordingSettings> = context.dataStore.data.map { p ->
        RecordingSettings(
            rewindSeconds = p[Keys.REWIND] ?: 30,
            resolutionShortEdge = p[Keys.RESOLUTION] ?: 720,
            frameRate = p[Keys.FPS] ?: 30,
            videoBitrateBps = p[Keys.BITRATE] ?: 8_000_000,
            videoMime = p[Keys.MIME] ?: "video/avc",
            audioSource = p[Keys.AUDIO]?.let {
                runCatching { AudioSource.valueOf(it) }.getOrNull()
            } ?: AudioSource.INTERNAL,
            overlayAlpha = p[Keys.OVERLAY_ALPHA] ?: 0.85f,
            overlayScale = p[Keys.OVERLAY_SCALE] ?: 1.0f,
            overlayEdgeHide = p[Keys.OVERLAY_EDGE] ?: false,
        )
    }

    suspend fun update(transform: (RecordingSettings) -> RecordingSettings) {
        context.dataStore.edit { p ->
            val cur = RecordingSettings(
                rewindSeconds = p[Keys.REWIND] ?: 30,
                resolutionShortEdge = p[Keys.RESOLUTION] ?: 720,
                frameRate = p[Keys.FPS] ?: 30,
                videoBitrateBps = p[Keys.BITRATE] ?: 8_000_000,
                videoMime = p[Keys.MIME] ?: "video/avc",
                audioSource = p[Keys.AUDIO]?.let {
                    runCatching { AudioSource.valueOf(it) }.getOrNull()
                } ?: AudioSource.INTERNAL,
                overlayAlpha = p[Keys.OVERLAY_ALPHA] ?: 0.85f,
                overlayScale = p[Keys.OVERLAY_SCALE] ?: 1.0f,
                overlayEdgeHide = p[Keys.OVERLAY_EDGE] ?: false,
            )
            val s = transform(cur)
            p[Keys.REWIND] = s.rewindSeconds
            p[Keys.RESOLUTION] = s.resolutionShortEdge
            p[Keys.FPS] = s.frameRate
            p[Keys.BITRATE] = s.videoBitrateBps
            p[Keys.MIME] = s.videoMime
            p[Keys.AUDIO] = s.audioSource.name
            p[Keys.OVERLAY_ALPHA] = s.overlayAlpha
            p[Keys.OVERLAY_SCALE] = s.overlayScale
            p[Keys.OVERLAY_EDGE] = s.overlayEdgeHide
        }
    }
}

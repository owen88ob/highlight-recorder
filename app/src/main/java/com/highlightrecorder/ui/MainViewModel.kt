package com.highlightrecorder.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.highlightrecorder.data.RecordingSettings
import com.highlightrecorder.data.SettingsHolder
import com.highlightrecorder.data.SettingsRepository
import com.highlightrecorder.service.RecordingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = SettingsRepository(app)

    /** 设置项(持久化),同时桥接到 [SettingsHolder] 供服务读取。 */
    val settings: StateFlow<RecordingSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, RecordingSettings())

    init {
        viewModelScope.launch {
            settings.collect { SettingsHolder.current = it }
        }
    }

    fun updateSettings(transform: (RecordingSettings) -> RecordingSettings) {
        viewModelScope.launch { settingsRepo.update(transform) }
    }

    /** 权限快照,页面 onResume 时 [refreshPermissions]。 */
    data class PermissionSnapshot(
        val overlay: Boolean = false,
        val notification: Boolean = false,
        val storage: Boolean = false,
        val battery: Boolean = false,
        val mic: Boolean = false,
    ) {
        val mandatoryOk: Boolean get() = overlay && notification && storage
    }

    private val _permissions = MutableStateFlow(PermissionSnapshot())
    val permissions: StateFlow<PermissionSnapshot> = _permissions

    val recordingState = RecordingService.state
    val bufferedSeconds = RecordingService.bufferedSeconds
    val savedEvents = RecordingService.savedEvents

    /** 首次启动是否已完成引导。 */
    private val prefs = app.getSharedPreferences("app", Application.MODE_PRIVATE)
    private val _onboarded = MutableStateFlow(prefs.getBoolean("onboarded", false))
    val onboarded: StateFlow<Boolean> = _onboarded

    fun refreshPermissions() {
        val ctx = getApplication<Application>()
        _permissions.value = PermissionSnapshot(
            overlay = PermissionHelper.overlayGranted(ctx),
            notification = PermissionHelper.notificationGranted(ctx),
            storage = PermissionHelper.storageGranted(ctx),
            battery = PermissionHelper.batteryWhitelistGranted(ctx),
            mic = PermissionHelper.micGranted(ctx),
        )
    }

    fun markOnboarded() {
        prefs.edit().putBoolean("onboarded", true).apply()
        _onboarded.value = true
    }

    fun requestStop() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            ctx.startService(
                android.content.Intent(ctx, RecordingService::class.java)
                    .setAction(RecordingService.ACTION_STOP),
            )
        }
    }

    fun requestSave() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            ctx.startService(
                android.content.Intent(ctx, RecordingService::class.java)
                    .setAction(RecordingService.ACTION_SAVE),
            )
        }
    }
}

package com.highlightrecorder.data

/** 当前生效的录制设置(内存态)。步骤 7 由 DataStore 持久化设置页写入。 */
object SettingsHolder {
    @Volatile
    var current: RecordingSettings = RecordingSettings()
}

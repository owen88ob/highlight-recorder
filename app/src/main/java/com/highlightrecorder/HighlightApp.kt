package com.highlightrecorder

import android.app.Application
import com.highlightrecorder.data.FileLogger

class HighlightApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FileLogger.init(this)
    }
}

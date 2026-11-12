package dev.zissha.vr

import android.app.Application
import dev.zissha.vr.core.AppLog

class ZisshaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
    }
}

package com.seyal.app

import android.app.Application
import com.seyal.app.data.Settings
import com.seyal.app.data.Store
import com.seyal.app.device.Notify
import com.seyal.app.engine.Engine
import com.seyal.app.engine.Scheduler

class SeyalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Store.init(this)
        Settings.init(this)
        Notify.createChannels(this)
        Engine.init(this)
        Scheduler.rescheduleAll(this)
    }

    companion object {
        lateinit var instance: SeyalApp
            private set
    }
}

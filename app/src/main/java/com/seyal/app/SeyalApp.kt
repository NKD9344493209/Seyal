package com.seyal.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.seyal.app.agent.EventReceiver
import com.seyal.app.agent.Agent
import com.seyal.app.agent.Scheduler
import com.seyal.app.data.Settings
import com.seyal.app.data.Store
import com.seyal.app.device.Notify

class SeyalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Store.init(this)
        Settings.init(this)
        Notify.createChannels(this)
        Agent.init(this)
        Scheduler.rescheduleAll(this)
        runCatching { com.seyal.app.device.PhoneEvents.sync(this) }
        // Charger plugged in / unplugged only reach receivers registered while the app runs.
        runCatching {
            val filter = IntentFilter().apply { addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED) }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(EventReceiver(), filter, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(EventReceiver(), filter)
        }
    }

    companion object {
        lateinit var instance: SeyalApp
            private set
    }
}

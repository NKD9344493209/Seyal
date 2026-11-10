package com.seyal.app.device

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import com.seyal.app.ui.MainActivity

/** Quick Settings tile "Ask Seyal": one swipe and a tap from anywhere opens Seyal, already listening. */
class SeyalTile : TileService() {
    override fun onClick() {
        val intent = Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_LISTEN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 7, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(intent)
            }
        }
    }
}

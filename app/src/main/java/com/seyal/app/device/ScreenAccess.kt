package com.seyal.app.device

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings as SysSettings
import com.seyal.app.agent.PayPause
import com.seyal.app.data.Settings
import kotlinx.coroutines.delay

/**
 * Switches Seyal's OWN screen control (its accessibility service) off and on. Off needs nothing. On needs
 * the WRITE_SECURE_SETTINGS permission, which only the USB installer can give; without it the user is taken
 * to the Accessibility page instead. Seyal never touches any other app's service. See agent/PayPause for why.
 */
object ScreenAccess {
    private fun component(ctx: Context) = ComponentName(ctx, SeyalAccessibilityService::class.java)

    /** Whether Seyal can switch its screen control on by itself. */
    fun canSwitch(ctx: Context): Boolean =
        runCatching { ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    /** "Only while Seyal works" is chosen and possible on this phone. */
    fun onDemand(ctx: Context): Boolean = Settings.screenOnDemand && canSwitch(ctx)

    /** Switches screen control off. Returns whether it was on. */
    fun off(ctx: Context): Boolean {
        val was = ScreenDriver.available
        // Seyal did this itself: the "your phone switched it off" warning must stay quiet.
        Settings.screenWasOn = false
        runCatching { ScreenDriver.disable() }
        if (canSwitch(ctx)) runCatching {
            val me = component(ctx)
            val cr = ctx.contentResolver
            val list = SysSettings.Secure.getString(cr, SysSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            SysSettings.Secure.putString(cr, SysSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES, PayPause.without(list, me.flattenToString(), me.flattenToShortString()))
        }
        return was
    }

    /** Switches screen control on and waits until it answers. False = it cannot be done from here (see [openSettings]). */
    suspend fun on(ctx: Context, waitMs: Long = 5000): Boolean {
        if (ScreenDriver.available) return true
        if (!canSwitch(ctx)) return false
        runCatching {
            val cr = ctx.contentResolver
            val list = SysSettings.Secure.getString(cr, SysSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            SysSettings.Secure.putString(cr, SysSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES, PayPause.with(list, component(ctx).flattenToString()))
            SysSettings.Secure.putInt(cr, SysSettings.Secure.ACCESSIBILITY_ENABLED, 1)
        }
        var waited = 0L
        while (!ScreenDriver.available && waited < waitMs) { delay(200); waited += 200 }
        return ScreenDriver.available
    }

    /** The Accessibility page, for phones where Seyal cannot switch it on itself. */
    fun openSettings(ctx: Context): Boolean =
        runCatching { ctx.startActivity(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)

    /** Waits for the user to switch it on in Settings (they were just sent there). */
    suspend fun waitForUser(waitMs: Long = 90_000): Boolean {
        var waited = 0L
        while (!ScreenDriver.available && waited < waitMs) { delay(500); waited += 500 }
        return ScreenDriver.available
    }
}

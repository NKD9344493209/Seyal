package com.seyal.app.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as SysSettings

/**
 * Many phones (Xiaomi, Redmi, POCO, Oppo, Realme, Vivo, OnePlus, Samsung) close apps in the background
 * to save battery. For Seyal that means reminders arrive late, messages are not read and a task can stop
 * half way. Each brand hides the fix in a different place: this finds the right page and says what to do there.
 */
object KeepAlive {
    /** xiaomi | oppo | vivo | samsung | huawei | oneplus | other */
    fun family(manufacturer: String = Build.MANUFACTURER ?: "", brand: String = Build.BRAND ?: ""): String {
        val m = "$manufacturer $brand".lowercase()
        return when {
            Regex("xiaomi|redmi|poco").containsMatchIn(m) -> "xiaomi"
            Regex("oneplus").containsMatchIn(m) -> "oneplus"
            Regex("oppo|realme").containsMatchIn(m) -> "oppo"
            Regex("vivo|iqoo").containsMatchIn(m) -> "vivo"
            Regex("samsung").containsMatchIn(m) -> "samsung"
            Regex("huawei|honor").containsMatchIn(m) -> "huawei"
            else -> "other"
        }
    }

    /** What the brand is called in the steps. */
    fun brandName(family: String): String = when (family) {
        "xiaomi" -> "Xiaomi / Redmi / POCO"; "oppo" -> "Oppo / Realme"; "vivo" -> "Vivo / iQOO"; "samsung" -> "Samsung"; "huawei" -> "Huawei / Honor"; "oneplus" -> "OnePlus"; else -> "This phone"
    }

    /** The steps, in plain words, for this brand. Each is one thing to tap. */
    fun steps(family: String): List<String> = when (family) {
        "xiaomi" -> listOf(
            "Tap \"Open the setting\" below. In the list, find Seyal and turn Autostart ON.",
            "Tap \"Battery\" below and choose \"No restrictions\".",
            "Open the recent apps screen, press and hold Seyal, and tap the lock.",
        )
        "oppo", "oneplus" -> listOf(
            "Tap \"Open the setting\" below. Find Seyal and turn ON \"Allow auto-launch\" (Auto-start).",
            "Tap \"Battery\" below, open Battery usage and turn ON \"Allow background activity\".",
            "Open the recent apps screen, tap the two dots above Seyal, and tap Lock.",
        )
        "vivo" -> listOf(
            "Tap \"Open the setting\" below. Find Seyal and turn Autostart ON.",
            "Open Settings → Battery → Background power consumption management → Seyal → \"Don't restrict background power usage\".",
            "Open the recent apps screen, pull Seyal down, and tap the lock.",
        )
        "samsung" -> listOf(
            "Tap \"Battery\" below and choose \"Unrestricted\".",
            "Open Settings → Battery → Background usage limits → Never sleeping apps → add Seyal.",
        )
        "huawei" -> listOf(
            "Tap \"Open the setting\" below. Find Seyal, switch off \"Manage automatically\", and keep Auto-launch, Secondary launch and Run in background ON.",
            "Tap \"Battery\" below and allow Seyal to ignore battery optimisation.",
        )
        else -> listOf("Tap \"Battery\" below and choose \"Unrestricted\" (or \"Don't optimise\") for Seyal.")
    }

    private fun comp(pkg: String, cls: String) = pkg to cls

    /** The brand's own "start by itself" pages, most likely first. Not every phone has every one. */
    internal fun autostartPages(family: String): List<Pair<String, String>> = when (family) {
        "xiaomi" -> listOf(comp("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
        "oppo" -> listOf(
            comp("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            comp("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            comp("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            comp("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"),
        )
        "oneplus" -> listOf(
            comp("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
            comp("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"),
        )
        "vivo" -> listOf(
            comp("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            comp("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            comp("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        )
        "huawei" -> listOf(
            comp("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            comp("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        )
        "samsung" -> listOf(
            comp("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            comp("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        )
        else -> emptyList()
    }

    private fun appDetails(ctx: Context) = Intent(SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))

    /** Opens the brand's autostart page; when the phone has none of the known ones, Seyal's own page in Settings. */
    fun openAutostart(ctx: Context): Boolean {
        for (i in autostartPages(family()).map { Intent().setComponent(ComponentName(it.first, it.second)) } + appDetails(ctx)) {
            if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)) return true
        }
        return false
    }

    /** Opens the battery choice for Seyal: Android's own "no limits" question first, then Seyal's page in Settings. */
    fun openBattery(ctx: Context): Boolean {
        val ask = Intent(SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
        val pages = if (unrestricted(ctx)) listOf(appDetails(ctx)) else listOf(ask, appDetails(ctx))
        for (i in pages) if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)) return true
        return false
    }

    /** Android's own battery limit is off for Seyal. (The brand's extra limits can't be read: only the steps fix those.) */
    fun unrestricted(ctx: Context): Boolean =
        runCatching { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName) }.getOrDefault(false)

    /** Whether this brand is known for closing apps: the card is shown up front on these. */
    fun strict(family: String = family()): Boolean = family != "other"
}

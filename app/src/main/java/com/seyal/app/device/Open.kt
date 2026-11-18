package com.seyal.app.device

import android.app.Activity
import android.app.PendingIntent
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.seyal.app.ui.MainActivity

/** Opens what a reminder or notice is about (see agent/Target.kt): a link, an app, the mail app. */
object Open {
    private const val GMAIL = "com.google.android.gm"

    /** What a tap does: goes to [target] through [OpenActivity] (which falls back to Seyal if the target cannot be opened). */
    fun pending(ctx: Context, target: String, code: String): PendingIntent {
        val intent = Intent(ctx, OpenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("open", target).setData(Uri.parse("seyal://open/${code.hashCode()}"))
        return PendingIntent.getActivity(ctx, ("open$code").hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun packageFor(ctx: Context, name: String): String? {
        val pm = ctx.packageManager
        val apps = runCatching { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0) }.getOrDefault(emptyList())
        val n = name.trim()
        val hit = apps.firstOrNull { it.loadLabel(pm).toString().equals(n, true) }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().replace(" ", "").equals(n.replace(" ", ""), true) }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().startsWith(n, true) }
        return hit?.activityInfo?.packageName
    }

    /** The name on the app's icon for a "pkg:" target; otherwise what [com.seyal.app.agent.Target.label] says. */
    fun label(ctx: Context, target: String): String {
        if (target.startsWith("note:")) return label(ctx, com.seyal.app.agent.Target.noticeParts(target).first).ifBlank { "the message" }
        if (target.startsWith("pkg:")) return runCatching { ScreenDriver.appLabel(ctx, target.removePrefix("pkg:")) }.getOrDefault("the app")
        if (target.startsWith("mail:")) return if (ctx.packageManager.getLaunchIntentForPackage(GMAIL) != null) "Gmail" else "your mail"
        return com.seyal.app.agent.Target.label(target)
    }

    private fun start(ctx: Context, intent: Intent): Boolean = runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    private fun launch(ctx: Context, pkg: String): Boolean = ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { start(ctx, it) } ?: false

    /** Opens [target]. False = it could not be opened (the app is gone, the link has no handler). */
    fun go(ctx: Context, target: String): Boolean = runCatching {
        when {
            target.startsWith("note:") -> com.seyal.app.agent.Target.noticeParts(target).let { (fallback, key) -> SeyalNotificationListener.tap(key) || go(ctx, fallback) }
            target.startsWith("url:") -> start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(target.removePrefix("url:"))))
            target.startsWith("pkg:") -> launch(ctx, target.removePrefix("pkg:"))
            target.startsWith("app:") -> packageFor(ctx, target.removePrefix("app:"))?.let { launch(ctx, it) } ?: false
            target.startsWith("mail:") -> {
                val q = target.removePrefix("mail:").trim()
                // Gmail, on what the reminder is about when it says so; else Gmail's inbox; else whichever app sends mail.
                val search = Intent(Intent.ACTION_SEARCH).setPackage(GMAIL).putExtra(SearchManager.QUERY, q)
                (q.isNotEmpty() && search.resolveActivity(ctx.packageManager) != null && start(ctx, search)) || launch(ctx, GMAIL) ||
                    start(ctx, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_EMAIL))
            }
            else -> false
        }
    }.getOrDefault(false)
}

/**
 * A tap on a reminder lands here for an instant: it opens what the reminder is about and closes itself.
 * If that cannot be opened (the app was removed), Seyal opens instead, so a tap never does nothing.
 */
class OpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = intent?.getStringExtra("open").orEmpty()
        if (!Open.go(this, target)) runCatching { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
        finish()
    }
}

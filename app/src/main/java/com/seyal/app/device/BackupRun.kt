package com.seyal.app.device

import android.content.Context
import android.net.Uri
import com.seyal.app.BuildConfig
import com.seyal.app.data.Backup
import com.seyal.app.data.Settings
import java.io.File

/** Control → Back up / Restore, on the phone: the file goes where the user chose (Downloads, Drive…). */
object BackupRun {
    class Done(val ok: Boolean, val text: String)

    private fun seyalDir(ctx: Context) = File(ctx.filesDir, "seyal")

    /** Writes everything to [uri]. [withKey]: the AI key and the Gmail app password go in too, locked with [password]. */
    fun save(ctx: Context, uri: Uri, withKey: Boolean, password: String): String = runCatching {
        val files = Backup.collect(seyalDir(ctx))
        val prefs = Backup.prefsToJson(ctx.getSharedPreferences("seyal", Context.MODE_PRIVATE).all)
        val secrets = if (withKey) mapOf("api_key" to Settings.apiKey, "gmail_app_password" to Settings.gmailAppPassword) else null
        val out = ctx.contentResolver.openOutputStream(uri) ?: error("the file could not be opened")
        val n = out.use { Backup.write(it, files, prefs, secrets, password.takeIf { withKey }, BuildConfig.VERSION_NAME, System.currentTimeMillis()) }
        val skills = Backup.Contents(org.json.JSONObject(), prefs, files, null).skills
        "Backed up: $skills skill${if (skills == 1) "" else "s"}, ${files.keys.count { it.startsWith("sessions/") }} chats, $n files" +
            (if (withKey && secrets!!.values.any { it.isNotBlank() }) ", and your AI key (locked with your password)." else ". Your AI key is not in it.") +
            " Keep this file for yourself: never send it to anyone."
    }.getOrElse { "Backup failed: ${it.message ?: it.javaClass.simpleName}" }

    /** Reads the file the user picked. Throws [Backup.Bad] with a plain reason. */
    fun open(ctx: Context, uri: Uri): Backup.Contents =
        (ctx.contentResolver.openInputStream(uri) ?: throw Backup.Bad("The file could not be opened.")).use { Backup.read(it) }

    /**
     * Puts the backup back. [password]: for the AI key, when the backup has it (a wrong one changes nothing).
     * Everything is written at once (commit, not apply) because Seyal closes right after.
     */
    fun restore(ctx: Context, c: Backup.Contents, password: String?): Done {
        val secrets = if (c.hasSecrets && !password.isNullOrEmpty()) Backup.unlock(c.secrets!!, password) ?: return Done(false, "Wrong password: nothing was changed. Try again.") else null
        return runCatching {
            val n = Backup.restoreFiles(c, seyalDir(ctx))
            val prefs = ctx.getSharedPreferences("seyal", Context.MODE_PRIVATE).edit()
            for ((k, v) in Backup.prefsFromJson(c.prefs)) {
                @Suppress("UNCHECKED_CAST")
                when (v) { is Boolean -> prefs.putBoolean(k, v); is Int -> prefs.putInt(k, v); is Long -> prefs.putLong(k, v); is Float -> prefs.putFloat(k, v); is String -> prefs.putString(k, v); is Set<*> -> prefs.putStringSet(k, v as Set<String>) }
            }
            prefs.commit()
            secrets?.forEach { (k, v) -> Settings.putSecretNow(k, v) }
            Done(true, "Restored $n files" + (if (secrets != null) " and your AI key" else if (c.hasSecrets) " (the AI key was left out: no password given)" else "") + ". Seyal closed itself: open it again and everything is back.")
        }.getOrElse { Done(false, "Restore failed: ${it.message ?: it.javaClass.simpleName}. Some files may already be back; try again.") }
    }
}

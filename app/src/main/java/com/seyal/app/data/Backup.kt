package com.seyal.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * One file that holds all of Seyal: skills, memory, interests, chats, settings, and (only when the user asks, locked
 * with their own password) the AI key and the Gmail app password. Uninstalling Seyal deletes everything; this file
 * brings it back.
 *
 * The file is a zip: manifest.json, prefs.json, files/… (everything under the app's seyal folder) and, when the
 * secrets are included, secrets.bin (AES-256-GCM, the key made from the password with PBKDF2, 120 000 rounds).
 * Plain Kotlin and java.*, so it is tested without a phone.
 */
object Backup {
    const val FORMAT = 1
    const val MIN_PASSWORD = 6
    private const val ROUNDS = 120_000
    /** The most one backup may hold, so a wrong or damaged file cannot fill the phone. */
    const val MAX_BYTES = 300L * 1024 * 1024

    /** The secrets that may go in, by their names in [Settings]. */
    val SECRET_KEYS = listOf("api_key", "gmail_app_password")

    class Contents(
        val manifest: JSONObject,
        val prefs: JSONObject,
        /** Path under the seyal folder → bytes. */
        val files: Map<String, ByteArray>,
        val secrets: ByteArray?,
    ) {
        val hasSecrets: Boolean get() = secrets != null
        val skills: Int get() = runCatching { JSONArray(String(files["skills.json"] ?: return 0)).length() }.getOrDefault(0)
    }

    class Bad(message: String) : Exception(message)

    // ------------------------------------------------------------------ settings ↔ JSON (with each value's type)

    /** SharedPreferences.getAll() as JSON that keeps each value's type. */
    fun prefsToJson(all: Map<String, *>): JSONObject = JSONObject().apply {
        for ((k, v) in all) {
            val o = when (v) {
                is Boolean -> JSONObject().put("t", "b").put("v", v)
                is Int -> JSONObject().put("t", "i").put("v", v)
                is Long -> JSONObject().put("t", "l").put("v", v)
                is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                is String -> JSONObject().put("t", "s").put("v", v)
                is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                else -> null
            } ?: continue
            put(k, o)
        }
    }

    /** The values back, typed, for writing into SharedPreferences. */
    fun prefsFromJson(o: JSONObject): Map<String, Any> = buildMap {
        for (k in o.keys()) {
            val e = o.optJSONObject(k) ?: continue
            val v: Any = when (e.optString("t")) {
                "b" -> e.optBoolean("v")
                "i" -> e.optInt("v")
                "l" -> e.optLong("v")
                "f" -> e.optDouble("v").toFloat()
                "s" -> e.optString("v")
                "set" -> e.optJSONArray("v")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: continue
                else -> continue
            }
            put(k, v)
        }
    }

    // ------------------------------------------------------------------ locking the secrets

    private fun keyFrom(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ROUNDS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    /** [secrets] locked with [password]: salt (16) + iv (12) + AES-GCM of the JSON. */
    fun lock(secrets: Map<String, String>, password: String, random: SecureRandom = SecureRandom()): ByteArray {
        require(password.length >= MIN_PASSWORD) { "password too short" }
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keyFrom(password, salt), GCMParameterSpec(128, iv))
        val plain = JSONObject(secrets.filterKeys { it in SECRET_KEYS }).toString().toByteArray()
        return salt + iv + c.doFinal(plain)
    }

    /** The secrets, or null when the password is wrong (or the bytes were changed). */
    fun unlock(blob: ByteArray, password: String): Map<String, String>? {
        if (blob.size < 16 + 12 + 16) return null
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, keyFrom(password, blob.copyOfRange(0, 16)), GCMParameterSpec(128, blob.copyOfRange(16, 28)))
            val o = JSONObject(String(c.doFinal(blob.copyOfRange(28, blob.size))))
            buildMap { for (k in o.keys()) if (k in SECRET_KEYS) put(k, o.optString(k)) }
        }.getOrNull()
    }

    // ------------------------------------------------------------------ writing and reading the file

    /** Every file under [seyalDir] worth keeping (half-written ".tmp" files are not). Paths use "/". */
    fun collect(seyalDir: File): Map<String, ByteArray> {
        if (!seyalDir.isDirectory) return emptyMap()
        val base = seyalDir.canonicalFile
        return base.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }
            .associate { f -> f.canonicalFile.relativeTo(base).invariantSeparatorsPath to f.readBytes() }
            .toSortedMap()
    }

    /**
     * Writes the backup to [out]. [secrets]: null to leave the key and passwords out (then [password] is not used).
     * Returns how many files went in.
     */
    fun write(out: OutputStream, files: Map<String, ByteArray>, prefs: JSONObject, secrets: Map<String, String>?, password: String?, appVersion: String, now: Long): Int {
        val locked = if (secrets != null && secrets.values.any { it.isNotBlank() }) lock(secrets.filterValues { it.isNotBlank() }, password ?: throw Bad("A password is needed to include the AI key.")) else null
        val manifest = JSONObject().put("format", FORMAT).put("app", "Seyal").put("version", appVersion).put("created", now)
            .put("files", files.size).put("secrets", locked != null)
        ZipOutputStream(out).use { z ->
            fun put(name: String, bytes: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
            put("manifest.json", manifest.toString(2).toByteArray())
            put("prefs.json", prefs.toString().toByteArray())
            for ((path, bytes) in files) put("files/$path", bytes)
            locked?.let { put("secrets.bin", it) }
        }
        return files.size
    }

    /** A path from the file that is safe to write under the seyal folder: no "..", not absolute, no odd names. */
    fun safePath(p: String): String? {
        val t = p.replace('\\', '/').trim()
        if (t.isEmpty() || t.startsWith("/") || t.contains("\u0000") || Regex("^[A-Za-z]:").containsMatchIn(t)) return null
        val parts = t.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        return t
    }

    /** Reads a backup. Throws [Bad] with a plain reason when it is not one, or is damaged. */
    fun read(input: InputStream): Contents {
        var manifest: JSONObject? = null
        var prefs = JSONObject()
        val files = sortedMapOf<String, ByteArray>()
        var secrets: ByteArray? = null
        var total = 0L
        try {
            ZipInputStream(input).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    val buf = ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    while (true) {
                        val n = z.read(chunk); if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw Bad("This file is too big to be a Seyal backup.")
                        buf.write(chunk, 0, n)
                    }
                    val bytes = buf.toByteArray()
                    when {
                        e.name == "manifest.json" -> manifest = runCatching { JSONObject(String(bytes)) }.getOrNull()
                        e.name == "prefs.json" -> prefs = runCatching { JSONObject(String(bytes)) }.getOrDefault(JSONObject())
                        e.name == "secrets.bin" -> secrets = bytes
                        e.name.startsWith("files/") -> safePath(e.name.removePrefix("files/"))?.let { files[it] = bytes }
                    }
                }
            }
        } catch (b: Bad) { throw b } catch (e: Exception) { throw Bad("This file could not be read: it is not a Seyal backup, or it is damaged.") }
        val m = manifest ?: throw Bad("This is not a Seyal backup (it has no Seyal label inside).")
        if (m.optString("app") != "Seyal") throw Bad("This is not a Seyal backup.")
        if (m.optInt("format") > FORMAT) throw Bad("This backup was made by a newer Seyal. Update Seyal first, then restore.")
        return Contents(m, prefs, files, secrets)
    }

    /**
     * Puts the files back under [seyalDir]: every file in the backup replaces the one on the phone; files the backup
     * does not have are left alone. Returns how many were written.
     */
    fun restoreFiles(c: Contents, seyalDir: File): Int {
        val base = seyalDir.canonicalFile.apply { mkdirs() }
        var n = 0
        for ((path, bytes) in c.files) {
            val safe = safePath(path) ?: continue
            val f = File(base, safe).canonicalFile
            if (!f.path.startsWith(base.path + File.separator)) continue
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".restore.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) { f.writeBytes(bytes); tmp.delete() }
            n++
        }
        return n
    }

    /** "Seyal-backup-2026-11-19.zip" */
    fun fileName(day: java.time.LocalDate): String = "Seyal-backup-$day.zip"

    /** One line on what a backup holds, for the screen before restoring. */
    fun summary(c: Contents, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
        val made = c.manifest.optLong("created").takeIf { it > 0 }?.let {
            java.time.Instant.ofEpochMilli(it).atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", java.util.Locale.ENGLISH))
        } ?: "an unknown day"
        val chats = c.files.keys.count { it.startsWith("sessions/") }
        return "Made $made by Seyal ${c.manifest.optString("version").ifBlank { "?" }}: ${c.skills} skill${if (c.skills == 1) "" else "s"}, $chats chat${if (chats == 1) "" else "s"}, ${c.files.size} files in all" +
            (if (c.hasSecrets) ", and the AI key (locked with a password)." else ". The AI key is not in it: you type it again in Control → AI.")
    }
}

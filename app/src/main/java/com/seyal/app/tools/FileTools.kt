package com.seyal.app.tools

import android.Manifest
import android.app.usage.StorageStatsManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.MediaStore
import com.seyal.app.agent.Tool
import com.seyal.app.agent.ToolResult
import com.seyal.app.agent.schema
import com.seyal.app.agent.noArgs
import com.seyal.app.device.ScreenDriver
import com.seyal.app.tools.Common.ctx
import com.seyal.app.tools.Common.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * The phone's own files: find videos, songs, photos and documents, open one in the app the user
 * names (MX Player, VLC…), and say what is taking up storage. Reads through Android's media index,
 * so it is fast; with "All files access" (the USB installer turns it on) it sees every file.
 */
object FileTools {

    /** One file the index knows about. */
    class Found(val id: Long, val uri: Uri, val name: String, val folder: String, val size: Long, val modified: Long, val mime: String, val durationMs: Long, val kind: String) {
        fun toJson(): JSONObject = JSONObject().put("uri", uri.toString()).put("name", name).put("folder", folder.trimEnd('/'))
            .put("size", human(size)).put("modified", java.text.SimpleDateFormat("d MMM yyyy", Locale.US).format(java.util.Date(modified * 1000)))
            .put("kind", kind).also { if (durationMs > 0) it.put("duration_min", Math.round(durationMs / 60_000.0)) }
    }

    val all: List<Tool> by lazy {
        listOf(
            Tool(
                "files_find", "phone",
                "Find files on this phone by name: movies and videos, songs, photos, documents (PDF, Word…). Words match anywhere in the file name (\"ramayana\" finds Ramayana.2026.1080p.mkv). " +
                    "Leave query empty for the newest of that kind. Output: files[uri, name, folder, size, modified, kind, duration_min].",
                schema {
                    str("query", "Words from the file name")
                    str("kind", "What kind of file", enum = listOf("video", "audio", "image", "document", "any"))
                    int("limit", "How many, default 10")
                },
            ) { a, _ -> find(a.optString("query"), a.optString("kind", "any"), a.optInt("limit", 10).coerceIn(1, 30)) },

            Tool(
                "file_open", "phone",
                "Open a file from this phone: play a movie or song, show a photo or a PDF. Give the uri from files_find (or a name to look up). " +
                    "app = the app to open it in when the user named one (\"MX Player\", \"VLC\"); otherwise the phone's usual app. Output: what opened, and the screen when screen control is on (tap Play if it isn't playing).",
                schema {
                    str("uri", "uri from files_find")
                    str("name", "File name words, if there is no uri")
                    str("app", "App to open it with, e.g. 'MX Player'")
                },
            ) { a, _ -> open(a.optString("uri"), a.optString("name"), a.optString("app")) },

            Tool(
                "storage_info", "phone",
                "How the phone's storage is used: total, used and free; the biggest folders; the biggest files; and the apps taking the most space. Use this for any storage question instead of the Settings or Files screens.",
                noArgs,
            ) { _, _ -> storage() },
        )
    }

    // ---------------------------------------------------------------- finding

    private fun canRead(): Boolean =
        (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) ||
            (Build.VERSION.SDK_INT >= 33 && (Common.has(Manifest.permission.READ_MEDIA_VIDEO) || Common.has(Manifest.permission.READ_MEDIA_AUDIO) || Common.has(Manifest.permission.READ_MEDIA_IMAGES))) ||
            (Build.VERSION.SDK_INT < 33 && Common.has(Manifest.permission.READ_EXTERNAL_STORAGE))

    private const val NO_ACCESS = "Seyal can't see your files yet. On the Chat tab, the setup card has \"Files and media\": tap Set up and allow it. Tell the user."

    private val docMimes = listOf("application/pdf", "application/msword", "application/vnd.openxmlformats-officedocument%", "application/vnd.ms-%", "text/plain", "application/epub+zip")

    internal fun kindOf(mime: String, mediaType: Int): String = when {
        mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO || mime.startsWith("video/") -> "video"
        mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO || mime.startsWith("audio/") -> "audio"
        mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE || mime.startsWith("image/") -> "image"
        else -> "document"
    }

    /** The words of a file name, for matching: "Ramayana.2026.1080p.mkv" → "ramayana 2026 1080p mkv". */
    internal fun words(s: String): List<String> = s.lowercase().split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).filter { it.isNotBlank() }

    /** How well a file name fits the asked words: all words found → higher; 0 = no fit. */
    internal fun score(fileName: String, query: String): Int {
        val q = words(query).filter { it.length > 1 || query.length <= 2 }
        if (q.isEmpty()) return 1
        val name = words(fileName).joinToString(" ")
        val hits = q.count { name.contains(it) }
        return if (hits == 0) 0 else hits * 10 + (if (hits == q.size) 100 else 0) - name.length / 20
    }

    fun search(query: String, kind: String, limit: Int): List<Found> {
        val uri = MediaStore.Files.getContentUri("external")
        val cols = mutableListOf(
            MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED, MediaStore.Files.FileColumns.MIME_TYPE, MediaStore.Files.FileColumns.MEDIA_TYPE,
        )
        if (Build.VERSION.SDK_INT >= 29) { cols += MediaStore.MediaColumns.RELATIVE_PATH; cols += MediaStore.MediaColumns.DURATION }
        val where = StringBuilder("${MediaStore.Files.FileColumns.SIZE} > 0")
        val args = mutableListOf<String>()
        when (kind) {
            "video" -> where.append(" AND (${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO} OR ${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'video/%')")
            "audio" -> where.append(" AND (${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO} OR ${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'audio/%')")
            "image" -> where.append(" AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}")
            "document" -> where.append(" AND (" + docMimes.joinToString(" OR ") { "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ?" } + ")").also { args += docMimes }
        }
        // Any one of the words narrows the index; the best fits are picked below.
        val q = words(query).filter { it.length > 1 }
        if (q.isNotEmpty()) {
            where.append(" AND (" + q.joinToString(" OR ") { "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?" } + ")")
            q.forEach { args += "%$it%" }
        }
        val out = mutableListOf<Found>()
        ctx.contentResolver.query(uri, cols.toTypedArray(), where.toString(), args.toTypedArray(), "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val iMod = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val iMime = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val iType = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val iPath = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            val iDur = c.getColumnIndex(MediaStore.MediaColumns.DURATION)
            var n = 0
            while (c.moveToNext() && n < 2000) {
                n++
                val id = c.getLong(iId)
                val mime = c.getString(iMime) ?: ""
                val type = c.getInt(iType)
                val k = kindOf(mime, type)
                val contentUri = when (k) {
                    "video" -> ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    "audio" -> ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    "image" -> ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    else -> ContentUris.withAppendedId(uri, id)
                }
                out += Found(
                    id, contentUri, c.getString(iName) ?: "", if (iPath >= 0) c.getString(iPath) ?: "" else "",
                    c.getLong(iSize), c.getLong(iMod), mime, if (iDur >= 0) c.getLong(iDur) else 0L, k,
                )
            }
        }
        return if (q.isEmpty()) out.take(limit)
        else out.map { it to score(it.name, query) }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }.take(limit)
    }

    private fun find(query: String, kind: String, limit: Int): ToolResult {
        if (!canRead()) return ToolResult.Failed(NO_ACCESS)
        val found = runCatching { search(query, kind, limit) }.getOrElse { return ToolResult.Failed("Could not read the file index: ${it.message}") }
        val arr = JSONArray().apply { found.forEach { put(it.toJson()) } }
        val what = if (kind == "any" || kind.isBlank()) "files" else "${kind}s"
        if (found.isEmpty()) return ToolResult.Done(JSONObject().put("ok", true).put("files", arr).put("note", "Nothing called \"$query\". Try fewer or other words, or another kind."), "No $what called \"$query\"")
        return ToolResult.Done(JSONObject().put("ok", true).put("files", arr), "Found ${found.size} $what" + if (query.isNotBlank()) " for \"$query\"" else "")
    }

    // ---------------------------------------------------------------- opening

    /** Apps that can open [intent], as (label, package). */
    private fun handlers(intent: Intent): List<Pair<String, String>> {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val list = if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        else pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        return list.map { it.loadLabel(pm).toString() to it.activityInfo.packageName }.distinctBy { it.second }
    }

    /** Known apps by the names people use, for when the label differs ("MX" → MX Player). */
    private val knownPlayers = mapOf(
        "mx" to listOf("com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.pro", "com.mxtech.videoplayer.beta"),
        "vlc" to listOf("org.videolan.vlc"),
        "playit" to listOf("com.playit.videoplayer"),
    )

    private suspend fun open(uriText: String, name: String, app: String): ToolResult {
        if (!canRead()) return ToolResult.Failed(NO_ACCESS)
        val file: Found = if (uriText.isNotBlank()) {
            val uri = runCatching { Uri.parse(uriText) }.getOrNull() ?: return ToolResult.Failed("That is not a file uri from files_find.")
            describe(uri) ?: return ToolResult.Failed("That file is not on the phone any more. Use files_find again.")
        } else {
            if (name.isBlank()) return ToolResult.Failed("Say which file: give the uri from files_find, or its name.")
            search(name, "any", 5).firstOrNull() ?: return ToolResult.Failed("No file called \"$name\" on this phone. Use files_find with other words.")
        }
        val mime = file.mime.ifBlank { "*/*" }
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(file.uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val apps = handlers(intent)
        if (apps.isEmpty()) return ToolResult.Failed("No app on this phone can open ${file.name} (${file.kind}). The user may need a player like MX Player or VLC.")
        var chosen: Pair<String, String>? = null
        if (app.isNotBlank()) {
            val key = app.lowercase().replace(" ", "")
            chosen = apps.firstOrNull { it.first.replace(" ", "").equals(key, true) }
                ?: apps.firstOrNull { it.first.replace(" ", "").contains(key, true) || key.contains(it.first.replace(" ", "").lowercase()) }
                ?: knownPlayers.entries.firstOrNull { key.contains(it.key) }?.value?.let { pkgs -> apps.firstOrNull { it.second in pkgs } }
                ?: return ToolResult.Failed("$app can't open this file, or isn't installed. Apps that can: ${apps.joinToString { it.first }}.")
            intent.setPackage(chosen.second)
        } else if (apps.size == 1) chosen = apps[0]
        if (!launch(intent)) return ToolResult.Failed("Could not open ${file.name}")
        val out = JSONObject().put("ok", true).put("opened", file.name).put("kind", file.kind)
        chosen?.let { out.put("app", it.first) }
        if (chosen == null) out.put("note", "Android may ask which app to use: pick one on screen (apps: ${apps.joinToString { it.first }}).")
        if (ScreenDriver.available) {
            chosen?.let { if (!ScreenDriver.waitForPackage(it.second, 6000)) out.put("note", "${it.first} did not come to the front yet. Check the screen.") }
            ScreenDriver.snapshotAfter(1500)?.let { out.put("screen", it) }
        }
        return ToolResult.Done(out, "Opened ${file.name.take(40)}" + (chosen?.let { " in ${it.first}" } ?: ""))
    }

    /** The index entry for a content uri, or null when it is gone. */
    private fun describe(uri: Uri): Found? {
        val cols = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.MIME_TYPE)
        return runCatching {
            ctx.contentResolver.query(uri, cols, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null
                val mime = c.getString(3) ?: ctx.contentResolver.getType(uri) ?: ""
                Found(ContentUris.parseId(uri), uri, c.getString(0) ?: "", "", c.getLong(1), c.getLong(2), mime, 0L, kindOf(mime, 0))
            }
        }.getOrNull()
    }

    // ---------------------------------------------------------------- storage

    internal fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
        else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }

    /**
     * The folder a file counts towards: its top folder ("DCIM", "Download"), except under Android/media,
     * where the app's own folder is what people recognise ("WhatsApp media").
     */
    internal fun bucket(relativePath: String, label: (String) -> String): String {
        val parts = relativePath.trim('/').split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return "(top folder)"
        if (parts[0].equals("Android", true) && parts.size >= 3 && parts[1].equals("media", true)) return "${label(parts[2])} media (Android/media/${parts[2]})"
        return parts[0]
    }

    private fun storage(): ToolResult {
        val out = JSONObject().put("ok", true)
        val stat = StatFs(Environment.getDataDirectory().path)
        val total = runCatching {
            if (Build.VERSION.SDK_INT >= 26) (ctx.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager).getTotalBytes(StorageManager.UUID_DEFAULT) else stat.totalBytes
        }.getOrDefault(stat.totalBytes)
        val free = runCatching {
            (ctx.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager).getFreeBytes(StorageManager.UUID_DEFAULT)
        }.getOrDefault(stat.availableBytes)
        out.put("total", human(total)).put("used", human(total - free)).put("free", human(free)).put("used_percent", ((total - free) * 100 / total.coerceAtLeast(1)).toInt())

        // Folders and files, from the media index (every file with All files access, else media only).
        if (canRead() && Build.VERSION.SDK_INT >= 29) {
            val sizes = HashMap<String, Long>()
            val biggest = java.util.PriorityQueue<Pair<Long, String>>(compareBy { it.first })
            val label: (String) -> String = { pkg -> ScreenDriver.appLabel(ctx, pkg) }
            val labels = HashMap<String, String>()
            runCatching {
                ctx.contentResolver.query(
                    MediaStore.Files.getContentUri("external"),
                    arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DISPLAY_NAME),
                    "${MediaStore.MediaColumns.SIZE} > 0", null, null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val path = c.getString(0) ?: ""
                        val size = c.getLong(1)
                        val b = bucket(path) { pkg -> labels.getOrPut(pkg) { label(pkg) } }
                        sizes[b] = (sizes[b] ?: 0L) + size
                        biggest.add(size to (path + (c.getString(2) ?: "")))
                        if (biggest.size > 5) biggest.poll()
                    }
                }
            }
            out.put("biggest_folders", JSONArray().apply { sizes.entries.sortedByDescending { it.value }.take(8).forEach { put(JSONObject().put("folder", it.key).put("size", human(it.value))) } })
            out.put("biggest_files", JSONArray().apply { biggest.sortedByDescending { it.first }.forEach { put(JSONObject().put("file", it.second).put("size", human(it.first))) } })
            if (!(Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager())) out.put("note", "Folders count photos, videos and music only (no All files access).")
        } else out.put("note", "Folder sizes need Files and media access (setup card on the Chat tab).")

        // Apps, when Seyal may read usage stats (the USB installer allows it).
        runCatching {
            val ssm = ctx.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
            val pm = ctx.packageManager
            val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).map { it.activityInfo.packageName }.distinct()
            val sized = apps.mapNotNull { pkg ->
                runCatching {
                    val st = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, android.os.Process.myUserHandle())
                    pkg to st.appBytes + st.dataBytes + st.cacheBytes
                }.getOrNull()
            }
            if (sized.isNotEmpty()) out.put("biggest_apps", JSONArray().apply { sized.sortedByDescending { it.second }.take(6).forEach { put(JSONObject().put("app", ScreenDriver.appLabel(ctx, it.first)).put("size", human(it.second))) } })
        }
        return ToolResult.Done(out, "Storage: ${human(total - free)} used of ${human(total)} (${human(free)} free)")
    }
}

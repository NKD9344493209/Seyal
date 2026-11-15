package com.seyal.app.device

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.seyal.app.data.Part
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * One file the user attached in the chat. A picture, PDF, video or voice clip goes to the AI as
 * it is ([part]); a text or Word file goes as its words ([text]); anything else, or anything too
 * big, carries only a [problem] to show.
 */
class Attachment(val name: String, val kind: String, val part: Part.Image? = null, val text: String? = null, val problem: String? = null, val uri: Uri? = null) {
    val ok: Boolean get() = problem == null
}

/** Reading attached files. The sorting and the size rules are plain functions, testable without a phone. */
object Attach {
    const val MAX_FILES = 4
    /** The most of each kind that is sent, in bytes: beyond this the AI call gets too slow or is refused. */
    fun limit(kind: String): Int = when (kind) { "video" -> 9_000_000; "pdf" -> 8_000_000; "audio" -> 6_000_000; else -> 2_000_000 }

    /** image | pdf | video | audio | text | word | slides | other, from the file's type and name. */
    fun kind(mime: String, name: String): String {
        val m = mime.lowercase(); val n = name.lowercase()
        return when {
            m.startsWith("image/") || Regex("\\.(jpe?g|png|webp|heic|gif|bmp)$").containsMatchIn(n) -> "image"
            m == "application/pdf" || n.endsWith(".pdf") -> "pdf"
            m.startsWith("video/") || Regex("\\.(mp4|mkv|mov|3gp|webm)$").containsMatchIn(n) -> "video"
            m.startsWith("audio/") || Regex("\\.(mp3|m4a|wav|ogg|opus|aac)$").containsMatchIn(n) -> "audio"
            n.endsWith(".docx") || m.contains("wordprocessingml") -> "word"
            n.endsWith(".pptx") || m.contains("presentationml") -> "slides"
            m.startsWith("text/") || m == "application/json" || Regex("\\.(txt|md|csv|json|xml|html?|log|kt|java|py|js|c|cpp|sql)$").containsMatchIn(n) -> "text"
            else -> "other"
        }
    }

    private fun mb(bytes: Int) = String.format(java.util.Locale.ENGLISH, "%.1f", bytes / 1_000_000.0)

    /** Why a file of this [kind] and [size] can't go to the AI, or null when it can. [provider]: gemini | anthropic | openai. */
    fun problem(kind: String, size: Int, provider: String): String? = when {
        kind == "other" -> "This kind of file can't be read here. Pictures, PDFs, videos, voice clips, text, Word and PowerPoint files can."
        size > limit(kind) -> "Too big (${mb(size)} MB). The most for a $kind is ${mb(limit(kind))} MB."
        kind in setOf("video", "audio") && provider != "gemini" -> "Only the Gemini AI can take a $kind. Switch to it in Control → AI, or attach pictures instead."
        kind == "pdf" && provider == "openai" -> "This AI can't read PDFs. Use Gemini or Claude in Control → AI."
        else -> null
    }

    /** The words of a Word (.docx) or PowerPoint (.pptx) file: paragraphs and slide texts, in order. */
    fun officeText(bytes: ByteArray, slides: Boolean): String {
        val out = StringBuilder()
        val parts = sortedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                val n = e.name
                if ((!slides && n == "word/document.xml") || (slides && Regex("^ppt/slides/slide\\d+\\.xml$").matches(n))) parts[n.replace(Regex("\\d+")) { it.value.padStart(4, '0') }] = zip.readBytes().toString(Charsets.UTF_8)
                e = zip.nextEntry
            }
        }
        parts.values.forEachIndexed { i, xml ->
            if (slides) out.append("Slide ${i + 1}:\n")
            val paragraphs = xml.split(Regex("</w:p>|</a:p>"))
            for (p in paragraphs) {
                val line = Regex("<(?:w|a):t[^>]*>([^<]*)</(?:w|a):t>").findAll(p).joinToString("") { it.groupValues[1] }
                    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                if (line.isNotBlank()) out.append(line.trim()).append('\n')
            }
            if (slides) out.append('\n')
        }
        return out.toString().trim()
    }

    /** Reads [uri] into an [Attachment]. Runs off the main thread. */
    fun load(ctx: Context, uri: Uri, provider: String): Attachment {
        var name = "file"; var size = -1
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) { name = c.getString(0) ?: name; if (!c.isNull(1)) size = c.getLong(1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
            }
        }
        val mime = runCatching { ctx.contentResolver.getType(uri) }.getOrNull().orEmpty()
        val kind = kind(mime, name)
        if (kind == "image") return Images.load(ctx, uri, 1280)?.let { Attachment(name, kind, part = it, uri = uri) } ?: Attachment(name, kind, problem = "This picture could not be opened.")
        if (size >= 0) problem(kind, size, provider)?.let { return Attachment(name, kind, problem = it) }
        val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return Attachment(name, kind, problem = "This file could not be opened.")
        problem(kind, bytes.size, provider)?.let { return Attachment(name, kind, problem = it) }
        return when (kind) {
            "text" -> Attachment(name, kind, text = bytes.toString(Charsets.UTF_8).take(60_000), uri = uri)
            "word", "slides" -> runCatching { officeText(bytes, kind == "slides") }.getOrNull()?.takeIf { it.isNotBlank() }?.let { Attachment(name, kind, text = it.take(60_000), uri = uri) }
                ?: Attachment(name, kind, problem = "No text could be read from this file.")
            else -> Attachment(name, kind, part = Part.Image(if (kind == "pdf") "application/pdf" else mime.ifBlank { if (kind == "video") "video/mp4" else "audio/mpeg" }, Base64.encodeToString(bytes, Base64.NO_WRAP)), uri = uri)
        }
    }

    /** The pictures most recently attached in each chat, for "make these into a PDF" (kept while the app runs). */
    private val recent = HashMap<String, List<Uri>>()
    @Synchronized fun remember(sessionId: String, uris: List<Uri>) { if (uris.isNotEmpty()) { recent[sessionId] = uris; if (recent.size > 20) recent.remove(recent.keys.first()) } }
    @Synchronized fun recentPictures(sessionId: String): List<Uri> = recent[sessionId].orEmpty()
}

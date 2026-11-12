package com.seyal.app.tools

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.seyal.app.data.Level
import com.seyal.app.agent.Tool
import com.seyal.app.agent.ToolResult
import com.seyal.app.agent.schema
import com.seyal.app.data.Settings
import com.seyal.app.device.MailSender
import com.seyal.app.device.ScreenDriver
import com.seyal.app.tools.Common.ctx
import com.seyal.app.tools.Common.launch
import com.seyal.app.util.Office
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Makes documents on the phone (a slide deck, a Word document, a PDF, a text note) and sends files
 * to people: "make a PPT about solar power and send it to dad".
 */
object DocTools {
    private val mimes = mapOf(
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "pdf" to "application/pdf",
        "txt" to "text/plain",
    )

    val all: List<Tool> by lazy {
        listOf(
            Tool(
                "doc_create", "phone",
                "Create a file on the phone: kind pptx (slide deck), docx (Word document), pdf, or txt. You write the content: a title, and sections (for a deck each section is one slide: a short heading and 3-6 short bullets; " +
                    "for a document each section is a heading with text and/or bullets). A good deck has 6-10 slides: what/why, the main points one per slide, an example or numbers, and a closing slide with next steps. " +
                    "Research first (web_search) when the topic needs facts. Saved in Documents/Seyal. Output: uri, name. Then file_open shows it, send_file sends it.",
                schema {
                    str("kind", "File type", required = true, enum = listOf("pptx", "docx", "pdf", "txt"))
                    str("title", "Title", required = true)
                    str("subtitle", "A line under the title (optional)")
                    objList("sections", "Slides or sections, in order", required = true) {
                        str("heading", "Slide title or section heading", required = true)
                        strList("bullets", "Short points")
                        str("text", "Paragraph text (documents)")
                    }
                },
                sideEffect = true,
                preview = { "Create ${it.optString("kind")}: ${it.optString("title")}" },
            ) { a, _ -> create(a) },

            Tool(
                "send_file", "comms",
                "Send a file from the phone to someone: via whatsapp (give phone), email (give email; needs Gmail connected in Control to send by itself) or share (opens Android's share list). " +
                    "Give the file's uri (from doc_create or files_find) or its name. Look the person up with contacts_search first.",
                schema {
                    str("uri", "uri of the file"); str("name", "File name words, if there is no uri")
                    str("via", "How to send", required = true, enum = listOf("whatsapp", "email", "share"))
                    str("phone", "Phone number (whatsapp)"); str("email", "Email address (email)")
                    str("to_name", "The person's name, for the user")
                    str("message", "Caption or email text"); str("subject", "Email subject")
                },
                defaultLevel = Level.ASK, sideEffect = true,
                target = { (it.optString("email").lowercase().ifBlank { Common.normalisePhone(it.optString("phone")) }).ifBlank { null } },
                preview = { "Send ${it.optString("name").ifBlank { "the file" }} to ${it.optString("to_name").ifBlank { it.optString("email").ifBlank { it.optString("phone") } }} by ${it.optString("via")}" + (it.optString("message").takeIf { m -> m.isNotBlank() }?.let { m -> "\n\"${m.take(200)}\"" } ?: "") },
            ) { a, ctx -> send(a, ctx.sessionId) },
        )
    }

    // ---------------------------------------------------------------- creating

    private fun blocks(a: JSONObject): List<Office.Block> {
        val arr = a.optJSONArray("sections") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val bullets = o.optJSONArray("bullets")?.let { b -> (0 until b.length()).map { b.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()
            Office.Block(o.optString("heading").trim(), bullets, o.optString("text").trim()).takeIf { it.heading.isNotBlank() || it.bullets.isNotEmpty() || it.text.isNotBlank() }
        }
    }

    private suspend fun create(a: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val kind = a.optString("kind").lowercase()
        val mime = mimes[kind] ?: return@withContext ToolResult.Failed("kind must be pptx, docx, pdf or txt")
        val title = a.optString("title").trim().ifBlank { "Untitled" }
        val subtitle = a.optString("subtitle").trim()
        val blocks = blocks(a)
        if (blocks.isEmpty()) return@withContext ToolResult.Failed("Give the content in sections: each with a heading and bullets or text.")
        val by = listOf(Settings.userName, java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.US).format(java.util.Date())).filter { it.isNotBlank() }.joinToString(" · ")
        val bytes = runCatching {
            when (kind) {
                "pptx" -> Office.pptx(title, subtitle, by, blocks)
                "docx" -> Office.docx(title, listOf(subtitle, by).filter { it.isNotBlank() }.joinToString(" · "), blocks)
                "pdf" -> pdf(title, listOf(subtitle, by).filter { it.isNotBlank() }.joinToString(" · "), blocks)
                else -> buildString {
                    appendLine(title); if (subtitle.isNotBlank()) appendLine(subtitle); appendLine()
                    blocks.forEach { b -> if (b.heading.isNotBlank()) appendLine(b.heading); if (b.text.isNotBlank()) appendLine(b.text); b.bullets.forEach { appendLine("- $it") }; appendLine() }
                }.toByteArray()
            }
        }.getOrElse { return@withContext ToolResult.Failed("Could not make the file: ${it.message}") }
        val name = Office.fileName(title, kind)
        val uri = save(name, mime, bytes) ?: return@withContext ToolResult.Failed("Could not save the file (needs Android 10 or newer).")
        val count = if (kind == "pptx") "${Office.paginate(blocks).size + 1} slides" else "${blocks.size} sections"
        ToolResult.ok("Created $name · $count", "uri" to uri.toString(), "name" to name, "folder" to "Documents/Seyal", "kind" to kind, "size" to FileTools.human(bytes.size.toLong()))
    }

    /** Saves into the phone's shared Documents/Seyal folder, where file managers and other apps can see it. */
    private fun save(name: String, mime: String, bytes: ByteArray): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/Seyal")
        }
        val resolver = ctx.contentResolver
        val uri = runCatching { resolver.insert(MediaStore.Files.getContentUri("external"), values) }.getOrNull() ?: return null
        return runCatching { resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null; uri }.getOrElse { runCatching { resolver.delete(uri, null, null) }; null }
    }

    /** A simple A4 PDF: title, a line under it, then headings with wrapped text and bullets. */
    private fun pdf(title: String, subtitle: String, blocks: List<Office.Block>): ByteArray {
        val doc = PdfDocument()
        val w = 595; val h = 842; val margin = 56f
        val titleP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 24f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); color = 0xFF1F2A44.toInt() }
        val subP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = 0xFF6B7280.toInt() }
        val headP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); color = 0xFF1F2A44.toInt() }
        val bodyP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11.5f; color = 0xFF2B2B2B.toInt() }
        val accent = Paint().apply { color = 0xFF3DB68A.toInt() }
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
            y = margin
        }
        /** Draws [text] wrapped to the page width from [x]; starts a new page when the page is full. */
        fun draw(text: String, p: Paint, x: Float, gapAfter: Float) {
            val line = p.textSize * 1.4f
            val maxW = w - margin - x
            for (para in text.split('\n')) {
                var rest = para.trim()
                if (rest.isEmpty()) { y += line * 0.5f; continue }
                while (rest.isNotEmpty()) {
                    var n = p.breakText(rest, true, maxW, null).coerceAtLeast(1)
                    if (n < rest.length) { val sp = rest.lastIndexOf(' ', n); if (sp > 0) n = sp }
                    if (y + line > h - margin) newPage()
                    y += line
                    page!!.canvas.drawText(rest.substring(0, n), x, y, p)
                    rest = rest.substring(n).trimStart()
                }
            }
            y += gapAfter
        }
        newPage()
        draw(title, titleP, margin, 2f)
        if (subtitle.isNotBlank()) draw(subtitle, subP, margin, 4f)
        page!!.canvas.drawRect(margin, y + 4f, margin + 70f, y + 7f, accent)
        y += 22f
        for (b in blocks) {
            if (y > h - margin - 80f) newPage()
            if (b.heading.isNotBlank()) draw(b.heading, headP, margin, 4f)
            if (b.text.isNotBlank()) draw(b.text, bodyP, margin, 4f)
            for (item in b.bullets) {
                if (y + bodyP.textSize * 1.4f > h - margin) newPage()
                page!!.canvas.drawCircle(margin + 5f, y + bodyP.textSize * 1.4f - 4f, 2.2f, accent)
                draw(item, bodyP, margin + 16f, 2f)
            }
            y += 12f
        }
        page?.let { doc.finishPage(it) }
        val out = ByteArrayOutputStream()
        doc.writeTo(out); doc.close()
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- sending

    private suspend fun send(a: JSONObject, sessionId: String): ToolResult {
        val file: FileTools.Found = a.optString("uri").takeIf { it.isNotBlank() }?.let { u -> runCatching { Uri.parse(u) }.getOrNull()?.let { FileTools.describe(it) } }
            ?: a.optString("name").takeIf { it.isNotBlank() }?.let { n -> runCatching { FileTools.search(n, "any", 3).firstOrNull() }.getOrNull() }
            ?: return ToolResult.Failed("Could not find that file. Give the uri from doc_create or files_find.")
        val mime = file.mime.ifBlank { ctx.contentResolver.getType(file.uri) ?: "*/*" }
        val who = a.optString("to_name").ifBlank { a.optString("email").ifBlank { a.optString("phone") } }
        val message = a.optString("message")
        return when (a.optString("via").lowercase()) {
            "email" -> {
                val to = a.optString("email").split(',', ';').map { it.trim() }.filter { it.contains("@") }
                if (to.isEmpty()) return ToolResult.Failed("No email address. Look the person up with contacts_search, or ask the user.")
                val subject = a.optString("subject").ifBlank { file.name.substringBeforeLast('.') }
                val sig = Settings.signature
                val body = message.ifBlank { "Please find ${file.name} attached." }.let { if (sig.isNotBlank() && !it.contains(sig)) "$it\n\n$sig" else it }
                if (Settings.hasGmail) withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = ctx.contentResolver.openInputStream(file.uri)?.use { it.readBytes() } ?: error("Could not read the file")
                        if (bytes.size > 20 * 1024 * 1024) error("The file is over 20 MB: too big for email")
                        MailSender.send(to, subject, body, emptyList(), MailSender.Attachment(file.name, mime, bytes))
                        ToolResult.ok("Emailed ${file.name} to ${to.joinToString()}", "sent" to true, "to" to to.joinToString())
                    }.getOrElse { ToolResult.Failed(it.message ?: "Could not send") }
                } else {
                    val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_EMAIL, to.toTypedArray()).putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, body)
                        .putExtra(Intent.EXTRA_STREAM, file.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (launch(Intent.createChooser(i, "Send mail").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))) ToolResult.ok("Mail with ${file.name} opened · tap Send", "sent" to false, "note" to "Gmail isn't connected in Control, so the mail app opened with the file attached for the user to send.")
                    else ToolResult.Failed("No mail app found and Gmail isn't connected (Control → Gmail)")
                }
            }
            "whatsapp" -> {
                val phone = Common.normalisePhone(a.optString("phone"))
                if (phone.isBlank()) return ToolResult.Failed("No phone number. Look the person up with contacts_search.")
                val pm = ctx.packageManager
                val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { runCatching { pm.getPackageInfo(it, 0) }.isSuccess } ?: return ToolResult.Failed("WhatsApp is not installed")
                // Straight into that person's chat with the file attached; the preview shows a Send button.
                val i = Intent(Intent.ACTION_SEND).setType(mime).setPackage(pkg).putExtra(Intent.EXTRA_STREAM, file.uri).putExtra("jid", "$phone@s.whatsapp.net")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (message.isNotBlank()) i.putExtra(Intent.EXTRA_TEXT, message)
                if (!launch(i)) return ToolResult.Failed("Could not open WhatsApp")
                if (!ScreenDriver.available || !ScreenDriver.claim(sessionId)) return ToolResult.ok("WhatsApp opened with ${file.name} for $who · tap Send", "sent" to false, "to" to who, "note" to "The user must tap Send (screen control is off or busy).")
                val start = System.currentTimeMillis()
                var ready = false
                while (System.currentTimeMillis() - start < 15_000) {
                    if (ScreenDriver.frontPackage() == pkg && (ScreenDriver.isVisible("Send", exactOnly = true) || ScreenDriver.fieldText(pkg, "send") != null)) { ready = true; break }
                    delay(400)
                }
                if (!ready) return ToolResult.Done(JSONObject().put("ok", true).put("sent", false).put("to", who).put("note", "WhatsApp opened but no Send button showed. Look at the screen and finish it (pick the chat if asked, then Send).").put("screen", ScreenDriver.snapshotAfter(0)), "WhatsApp opened for $who · finishing on screen")
                delay(700)
                val tapped = ScreenDriver.tapViewId(pkg, "send") || ScreenDriver.tap(null, "Send", exactOnly = true, returnScreen = false, pkg = pkg).optBoolean("ok")
                delay(1500)
                val screen = ScreenDriver.snapshotAfter(300)
                if (!tapped) ToolResult.Done(JSONObject().put("ok", true).put("sent", false).put("to", who).put("note", "Could not press Send. Finish it on screen.").put("screen", screen), "WhatsApp opened for $who · tap Send")
                else ToolResult.Done(JSONObject().put("ok", true).put("sent", true).put("to", who).put("file", file.name).put("note", "Pressed Send. The screen shows the chat: check the file is there.").put("screen", screen), "Sent ${file.name} to $who on WhatsApp")
            }
            else -> {
                val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, file.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (message.isNotBlank()) i.putExtra(Intent.EXTRA_TEXT, message)
                if (launch(Intent.createChooser(i, "Send ${file.name}").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))) ToolResult.ok("Share list opened for ${file.name}", "sent" to false, "note" to "Android's share list is open: pick the app and person on screen.")
                else ToolResult.Failed("Could not open the share list")
            }
        }
    }
}

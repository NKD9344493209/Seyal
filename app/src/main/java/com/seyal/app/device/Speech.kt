package com.seyal.app.device

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Reads answers out loud (Control → Speak answers). Tamil text uses the Tamil voice when the phone has one. */
object Speech {
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private var pending: String? = null

    fun say(ctx: Context, text: String) {
        val clean = text.replace(Regex("[*_#`>]"), "").trim().take(600)
        if (clean.isBlank()) return
        val t = tts
        if (t != null && ready) { speak(t, clean); return }
        pending = clean
        if (t == null) {
            tts = TextToSpeech(ctx.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) pending?.let { p -> tts?.let { speak(it, p) } }
                pending = null
            }
        }
    }

    private fun speak(t: TextToSpeech, text: String) {
        val tamil = text.any { it in '஀'..'௿' }
        runCatching { t.language = if (tamil) Locale("ta", "IN") else Locale("en", "IN") }
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "seyal")
    }

    fun stop() { runCatching { tts?.stop() } }
}

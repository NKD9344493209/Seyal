package com.seyal.app.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.seyal.app.SeyalApp
import java.net.HttpURLConnection
import java.net.URL

/** Bits every tool file needs. */
internal object Common {
    val ctx: Context get() = SeyalApp.instance

    fun has(perm: String) = ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    fun launch(intent: Intent): Boolean = runCatching {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    fun http(url: String, accept: String = "text/html,application/xml,application/json,*/*", timeoutMs: Int = 20_000): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = minOf(15_000, timeoutMs)
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Seyal/2.0")
            setRequestProperty("Accept", accept)
            setRequestProperty("Accept-Language", "en-IN,en;q=0.9")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    /** Indian numbers typed without a country code get +91. */
    fun normalisePhone(raw: String): String {
        val digits = raw.filter { it.isDigit() || it == '+' }
        val d = digits.removePrefix("+")
        return when {
            d.length == 10 -> "91$d"
            d.startsWith("0") && d.length == 11 -> "91" + d.drop(1)
            else -> d
        }
    }
}

package com.seyal.app.device

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.seyal.app.data.Part
import java.io.ByteArrayOutputStream

/** Turns a shared picture into something small enough to send to the AI. */
object Images {
    fun load(ctx: Context, uri: Uri, maxSide: Int = 1024): Part.Image? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > maxSide * 2 || bounds.outHeight / sample > maxSide * 2) sample *= 2
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val scale = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 72, out)
        Part.Image("image/jpeg", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
    }.getOrNull()
}

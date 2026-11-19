package com.seyal.app.device

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.seyal.app.agent.DayStrip
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Seyal's eyes: the words in a picture of the screen, with where each one is. Read on the phone itself (Google's
 * ML Kit text recognition, built into the app: no internet, no AI request, a fraction of a second).
 *
 * Why. Some apps draw text that they do not hand to accessibility. On a real phone (19 Nov 2026) BookMyShow's dates
 * (FRI 20 NOV …) and its "How many seats?" numbers could not be read as text at all, and the one name the app did give
 * a date chip was wrong. A person reads those off the glass; so does this.
 */
object Eyes {
    /** The words in [bmp] (each with its box, in the picture's own pixels), or null when reading failed. */
    suspend fun read(bmp: Bitmap): List<DayStrip.Seen>? {
        val done = CompletableDeferred<List<DayStrip.Seen>?>()
        runCatching {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                .process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { t ->
                    done.complete(
                        runCatching {
                            t.textBlocks.flatMap { it.lines }.flatMap { it.elements }.mapNotNull { e ->
                                val r = e.boundingBox ?: return@mapNotNull null
                                DayStrip.Seen(e.text.trim(), r.left, r.top, r.right, r.bottom)
                            }
                        }.getOrNull(),
                    )
                }
                .addOnFailureListener { done.complete(null) }
        }.onFailure { done.complete(null) }
        return withTimeoutOrNull(8000) { done.await() }
    }
}

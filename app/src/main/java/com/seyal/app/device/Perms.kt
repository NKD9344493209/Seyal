package com.seyal.app.device

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Permissions are asked for at the moment a request needs them, with Android's own question, instead of
 * as a list on first open. "Call Amma" asks for Contacts the first time; someone who never asks Seyal to
 * find a person is never asked.
 *
 * It can only ask while Seyal is on the screen (Android shows the question over the app). When a skill runs
 * in the background the tool fails with a plain sentence saying what to allow.
 */
object Perms {
    /** Set by the main screen while it is showing: shows Android's permission question. */
    @Volatile var asker: ((Array<String>) -> Unit)? = null
    @Volatile private var waiting: CompletableDeferred<Unit>? = null

    fun granted(ctx: Context, perm: String): Boolean = ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    /** Called by the main screen when the user has answered the question. */
    fun answered() { waiting?.complete(Unit) }

    /**
     * True when at least one of [perms] is granted ([any]) or all of them are; asks first when it can.
     * Waits up to a minute for the user to answer.
     */
    suspend fun ensure(ctx: Context, perms: List<String>, any: Boolean = false): Boolean {
        fun ok() = if (any) perms.any { granted(ctx, it) } else perms.all { granted(ctx, it) }
        if (ok()) return true
        val ask = asker ?: return false
        val d = CompletableDeferred<Unit>()
        waiting = d
        val shown = runCatching { withContext(Dispatchers.Main) { ask(perms.toTypedArray()) } }.isSuccess
        if (shown) withTimeoutOrNull(60_000) { d.await() }
        waiting = null
        return ok()
    }

    /** What to tell the user when it was not allowed. [what]: "Contacts"; [why]: "to find people by name". */
    fun refused(what: String, why: String): String =
        "$what is not allowed for Seyal, and it is needed $why. Tell the user exactly that, and that they can allow it in Control → Phone permissions (or say the request again and tap Allow)."
}

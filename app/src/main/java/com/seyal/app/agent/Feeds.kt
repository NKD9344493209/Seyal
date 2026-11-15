package com.seyal.app.agent

/**
 * Everything Seyal learns from an incoming message, in one place: a deadline or bill to remember,
 * a scam to warn about, a payment to add up, an order to track, and the message itself for finding
 * later. All of it is pattern matching on the phone: no AI call, nothing sent anywhere, no reply to anyone.
 */
object Feeds {
    class Found(val caught: Caught? = null, val scam: Scam.Verdict? = null, val txn: Txn? = null, val parcel: Parcel? = null)

    /** What each part may do, as the user has set it. */
    class Allow(val catch: Boolean = true, val scam: Boolean = true, val history: Boolean = true, val trustedSenders: String = "")

    /**
     * Takes in one message. [app]: the app's name; [title]: the notification's title (person, group or sender ID).
     * [message]: it is a message from someone (SMS, chat, mail), not an app's own notice such as a news headline.
     * Returns what is new and worth telling the user about (each part null when there is nothing).
     */
    fun take(app: String, title: String, text: String, group: Boolean, now: Long, allow: Allow = Allow(), message: Boolean = true): Found {
        var caught: Caught? = null; var scam: Scam.Verdict? = null; var txn: Txn? = null; var parcel: Parcel? = null
        if (allow.scam && message && !Scam.trusted(title, allow.trustedSenders)) scam = runCatching { Scam.check(title, text) }.getOrNull()
        if (allow.catch) {
            // A scam's "pay before tonight" is not a bill, and its "payment" is not income.
            if (scam == null) {
                caught = runCatching { Catcher.scan(app, title, text, now) }.getOrNull()?.takeIf { Inbox.add(it, now) }
                txn = runCatching { MoneyParser.parse(app, title, text, now) }.getOrNull()?.takeIf { Ledger.add(it) }
            }
            parcel = runCatching { ParcelParser.parse(app, title, text, now) }.getOrNull()?.takeIf { Parcels.upsert(it) }
        }
        if (allow.history && message) runCatching { Past.add(app, title, text, group, now) }
        return Found(caught, scam, txn, parcel)
    }
}

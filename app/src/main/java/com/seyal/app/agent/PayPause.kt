package com.seyal.app.agent

/**
 * Google Pay and several bank apps refuse to pay ("Payments blocked") while ANY app has screen control
 * (an accessibility service) switched on. That is their protection against remote-control fraud and it
 * applies to Seyal like to every other such app. Seyal's answer is to keep its own screen control off
 * except while it is working on a request, and always off by the time the user pays.
 *
 * The rules and the words are here (plain, testable); the switching itself is device/ScreenAccess.
 */
object PayPause {
    private val I = RegexOption.IGNORE_CASE

    /** Android's list of switched-on services ("a/b:c/d") with [component] added once. */
    fun with(list: String?, component: String): String {
        val items = list.orEmpty().split(':').map { it.trim() }.filter { it.isNotEmpty() && !it.equals("null", true) }
        return if (items.any { it.equals(component, true) }) items.joinToString(":") else (items + component).joinToString(":")
    }

    /** The same list without [component] (in either its long or its [short] spelling). */
    fun without(list: String?, component: String, short: String): String =
        list.orEmpty().split(':').map { it.trim() }.filter { it.isNotEmpty() && !it.equals("null", true) && !it.equals(component, true) && !it.equals(short, true) }.joinToString(":")

    enum class Ask { OFF, ON }

    private val off = Regex(
        "^(?:please )?(?:pause|stop|turn off|switch off|disable|off)\\s+(?:seyal'?s? )?(?:the )?screen control(?: for (?:payments?|gpay|google pay|paying|a while|now))?$" +
            "|^(?:screen control|accessibility) off$|^(?:payment|pay) mode(?: on)?$|^i (?:want|need) to pay(?: now)?$|^(?:gpay|google pay|phonepe|paytm|bank app) (?:is )?(?:blocked|not working|says payments? blocked)$" +
            "|^payments? blocked$",
        I,
    )
    private val on = Regex(
        "^(?:please )?(?:resume|start|turn on|switch on|enable|on)\\s+(?:seyal'?s? )?(?:the )?screen control(?: again| back| now)?$|^(?:screen control|accessibility) on$|^(?:payment|pay) mode off$|^(?:i(?:'ve| have)? )?(?:paid|finished paying|done paying)$",
        I,
    )

    /** "pause screen control", "payment mode", "payments blocked" → OFF; "turn on screen control", "paid" → ON. Null = something else. */
    fun asks(text: String): Ask? {
        val t = text.trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
        return when { off.matches(t) -> Ask.OFF; on.matches(t) -> Ask.ON; else -> null }
    }

    const val WHY = "Google Pay and many bank apps refuse to pay while any app has screen control on. It is their rule against remote-control fraud, and it applies to every such app."

    /** What is said after switching off. [canSwitchBack]: Seyal can switch itself on again when a request needs it. */
    fun offSaid(wasOn: Boolean, canSwitchBack: Boolean): String = buildString {
        append(if (wasOn) "Screen control is off now." else "Screen control is already off.")
        append(" You can pay. If Google Pay still shows \"Payments blocked\", restart the phone once: it remembers the block until then.")
        append(if (canSwitchBack) " I switch it on again by myself when you ask for something inside another app." else " To let me work inside apps again: Settings → Accessibility → Downloaded apps → Seyal → On.")
    }

    /** What is said after an attempt to switch on. */
    fun onSaid(isOn: Boolean, openedSettings: Boolean): String = when {
        isOn -> "Screen control is on. I can work inside other apps again."
        openedSettings -> "I opened Accessibility. Choose Seyal under Downloaded apps and switch it on."
        else -> "I could not switch it on from here. Open Settings → Accessibility → Downloaded apps → Seyal → On."
    }

    /**
     * The line added to the answer of a booking or an order that has reached payment.
     * [onDemand]: Seyal is switching its screen control off by itself right now.
     */
    fun payNote(reachedPay: Boolean, screenOn: Boolean, onDemand: Boolean): String? = when {
        !reachedPay || !screenOn -> null
        onDemand -> "Screen control is switching off now, so Google Pay and bank apps will let you pay."
        else -> "Paying with Google Pay or a bank app? They refuse to pay while screen control is on. Say \"pause screen control\" to me first, then pay."
    }

    /** The answer has brought the user to the paying step ("…the payment page is open", "Pay ₹340 to confirm"). */
    fun speaksOfPaying(answer: String): Boolean =
        Regex("\\bpay(?:ment|ing)?\\b|\\bcheckout\\b|பணம் செலுத்", I).containsMatchIn(answer) && !Regex("\\b(?:could ?n[o']t|can ?not|can't|unable|did ?n[o']t|failed)\\b", I).containsMatchIn(answer.take(200))

    /** Asked when a booking or an order needs the screen and screen control is off. */
    fun needQuestion(app: String): String =
        "Screen control is off, so I can't tap or type inside ${app.ifBlank { "the app" }} for you.\n" +
            "(If you switched it off because Google Pay said \"Payments blocked\": turn it on for this, and say \"pause screen control\" before you pay.)"
    const val TURN_ON = "Turn it on"
    const val JUST_OPEN = "Just open the app"

    /** What the answer to [needQuestion] means: true = the user wants to switch it on. */
    fun wantsOn(answer: String): Boolean = Regex("turn it on|turn on|switch on|\\bon\\b|\\byes\\b|\\bok(ay)?\\b|ஆன்|சரி", I).containsMatchIn(answer) && !Regex("just open|don'?t|\\bno\\b", I).containsMatchIn(answer)

    fun stillOff(app: String): String =
        "Screen control is still off, so the steps inside ${app.ifBlank { "the app" }} are yours this time. To let me do them: Settings → Accessibility → Downloaded apps → Seyal → On, then say \"continue\"."

    /** The Control tab's words for the "only while working" switch. */
    const val SWITCH_TITLE = "Screen control only while Seyal works"
    const val SWITCH_ABOUT = "For Google Pay and bank apps: they block payments while any screen-control app is on. With this, Seyal switches its own on when a request needs it and off when it is done or it is time to pay. While off, ad skipping and \"when I open an app\" skills rest."
    const val NEEDS_USB = "This needs one permission that only the USB installer can give. Connect the phone and run the installer once more (on Xiaomi: Developer options → \"USB debugging (Security settings)\" must be on)."
}

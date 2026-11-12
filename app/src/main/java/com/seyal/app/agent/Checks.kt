package com.seyal.app.agent

import org.json.JSONObject

/** One thing the agent did in this run: the tool, whether it worked, what came back, and whether the user said no to it. */
class Did(val name: String, val ok: Boolean, val output: JSONObject? = null, val denied: Boolean = false)

/** A reminder handed to the AI before its answer is accepted as final. [key]: each kind is given once per run. */
class Check(val key: String, val text: String)

/**
 * "Done" has to mean done. Before the agent's final answer is accepted, the request is compared
 * with what actually happened: a file that was asked for must exist, a file that was to be sent must
 * have been sent, a booking must have reached the payment step (or have a real reason why not), and
 * seats for a group must be next to each other. If something is missing the AI is told once, plainly,
 * and gets to finish the job instead of the user getting a half-answer.
 */
object Checks {
    private val fileKind = Regex("\\b(ppt|pptx|power ?point|slides?|slide deck|presentation|pdf|docx|word (file|document|doc)|doc file)\\b|பிபிடி", RegexOption.IGNORE_CASE)
    private val makeVerb = Regex("\\b(make|create|prepare|generate|build|write|draft|convert|turn|put|send|share|mail|email|whatsapp|forward|anuppu|anupu|pannu|podu|ready|venum|as (a |an )?(ppt|pptx|pdf|powerpoint|presentation|word|docx|document))\\b|அனுப்பு|தயார்", RegexOption.IGNORE_CASE)
    private val sendVerb = Regex("\\b(send|share|mail|email|whatsapp|forward|anuppu|anupu)\\b|அனுப்பு", RegexOption.IGNORE_CASE)
    private val paymentStop = Regex("payment or final-confirmation|PIN, OTP or card", RegexOption.IGNORE_CASE)

    /** The kind of file the user asked to be made ("pptx" | "pdf" | "docx"), or null when they asked for none. */
    fun fileAsked(said: String): String? {
        val m = fileKind.find(said) ?: return null
        if (!makeVerb.containsMatchIn(said)) return null
        val w = m.value.lowercase()
        return when {
            w.startsWith("pdf") -> "pdf"
            w.startsWith("word") || w.startsWith("doc") -> "docx"
            else -> "pptx"
        }
    }

    /** Whether the run got as far as a payment or final-confirmation button (which Seyal never presses). */
    fun reachedPayment(did: List<Did>): Boolean = did.any { d ->
        d.output?.optBoolean("blocked") == true || paymentStop.containsMatchIn(d.output?.optString("error").orEmpty())
    }

    /**
     * What is still missing, or null when the answer may go out. [said]: the user's request; [kind]: the
     * purchase kind, if it is one; [already]: checks made earlier in this run; [manyPeople]: more than one ticket.
     */
    fun beforeDone(said: String, kind: Preflight.Kind?, did: List<Did>, already: Set<String>, manyPeople: Boolean = true): Check? {
        fun ok(name: String) = did.any { it.name == name && it.ok }
        val refused = did.any { it.denied }
        val usedScreen = did.any { it.name.startsWith("screen_") || it.name == "open_app" || it.name == "open_url" }

        val file = fileAsked(said)
        if (file != null && "file" !in already && !ok("doc_create") && !ok("send_file") && !ok("file_open")) {
            val word = when (file) { "pdf" -> "PDF"; "docx" -> "Word document"; else -> "PowerPoint (PPT)" }
            return Check(
                "file",
                "Before you finish: the user asked for a $word FILE, and no file has been made. You DO have the tool for it: doc_create (kind \"$file\"). " +
                    "Call it now with the real content (title, then sections; for a PPT each section is one slide with a heading and 3-6 short bullets), built from what you already found. " +
                    "Then, if they asked to send it, send_file. A plain text message is NOT a substitute for the file they asked for. " +
                    "If the user did not actually ask you to make a file, just give your answer again.",
            )
        }
        if (file != null && "send" !in already && ok("doc_create") && sendVerb.containsMatchIn(said) && !ok("send_file") && !refused) {
            return Check(
                "send",
                "Before you finish: the file is made but it has not been sent. The user asked to send it. Use send_file with the uri from doc_create " +
                    "(contacts_search first for the person's number; via \"whatsapp\" unless they said email). If it truly cannot be sent, say exactly why and where the file is saved.",
            )
        }
        if (kind != null && usedScreen && !reachedPayment(did)) {
            if ("purchase" !in already) return Check(
                "purchase",
                "Before you finish: this task is to ${kind.what}, and it has not reached the payment step yet. Do not hand the remaining steps to the user. " +
                    "If the app looked stuck, recover and carry on: screen_wait a few seconds for a word you expect → screen_read → screenshot and tap by position with screen_tap_at → screen_back once → open_app again and continue from where it opens. " +
                    "A different app or the website (open_url) is fine if this one will not work. Stop only at the payment / place-order button, " +
                    "or for a real blocker (the user must log in, the item or show is not available, the shop is closed, the user told you to stop): then say exactly that, and what you already did.",
            )
        } else if (kind == Preflight.Kind.MOVIE && manyPeople && usedScreen && "seats" !in already && !did.any { it.name == "seats_check" }) {
            return Check(
                "seats",
                "Before you finish: which seats are selected? Read their labels from the screen (the bar near the Pay button shows them, e.g. \"A5, A6\"; screenshot if needed) and call seats_check with them and the number of tickets. " +
                    "People who book together sit together: the seats must be next to each other in one row, unless the user agreed to sit apart.",
            )
        }
        return null
    }
}

/**
 * Seat labels as theatres and buses write them ("A5", "C-12", "Row B Seat 7"), and whether a set of
 * them is one block of neighbours.
 */
object Seats {
    class Seat(val row: String, val number: Int) { override fun toString() = "$row$number" }

    private val pattern = Regex("^(?:row\\s*)?([A-Za-z]{1,2})\\s*[-–: ]?\\s*(?:seat\\s*)?(\\d{1,3})$", RegexOption.IGNORE_CASE)

    fun parse(label: String): Seat? {
        val m = pattern.find(label.trim()) ?: return null
        return Seat(m.groupValues[1].uppercase(), m.groupValues[2].toInt())
    }

    /** Splits "A5, A6 and A7" / "A5 A6" into labels. */
    fun split(text: String): List<String> = text.split(Regex("[,;/&]|\\band\\b", RegexOption.IGNORE_CASE)).flatMap { part ->
        val p = part.trim()
        if (parse(p) != null) listOf(p) else p.split(Regex("\\s+")).filter { it.isNotBlank() }
    }.filter { it.isNotBlank() }

    class Verdict(val ok: Boolean, val together: Boolean, val message: String)

    /**
     * [labels] are the seats selected, [wanted] how many tickets. Together means: one row, numbers in a run.
     * A single seat is always fine.
     */
    fun check(labels: List<String>, wanted: Int): Verdict {
        val seats = labels.map { parse(it) }
        if (labels.isEmpty() || seats.any { it == null }) return Verdict(false, false, "Could not read these seat labels: ${labels.joinToString(", ").take(80)}. Give them as the screen shows them, like A5, A6.")
        val list = seats.filterNotNull().distinctBy { it.toString() }
        if (wanted > 0 && list.size != wanted) return Verdict(false, false, "${list.size} seat${if (list.size == 1) " is" else "s are"} selected (${list.joinToString(", ")}), but the user wants $wanted. Select exactly $wanted.")
        if (list.size == 1) return Verdict(true, true, "One seat: ${list[0]}.")
        val rows = list.groupBy { it.row }
        val nums = list.map { it.number }.sorted()
        val run = nums.zipWithNext().all { (a, b) -> b - a == 1 }
        if (rows.size == 1 && run) return Verdict(true, true, "Seats ${list.sortedBy { it.number }.joinToString(", ")} are next to each other in row ${list[0].row}.")
        val how = if (rows.size > 1) "they are in different rows (${rows.keys.sorted().joinToString(", ")})" else "there is a gap between them in row ${list[0].row}"
        return Verdict(
            true, false,
            "Seats ${list.joinToString(", ")} are NOT together: $how. Unselect them and pick ${list.size} free seats side by side in ONE row (same letter, numbers in a run such as 7, 8). " +
                "If no row in the user's seat class and budget has ${list.size} free seats together, do NOT book separate seats: ask_user, saying exactly what is free " +
                "(for example \"2 together in row C and 1 alone in row F\"), and offer: sit apart, another show time, or another seat class. Go on with separate seats only if the user says yes.",
        )
    }
}

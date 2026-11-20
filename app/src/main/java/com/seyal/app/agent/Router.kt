package com.seyal.app.agent

import org.json.JSONObject

/**
 * Sends the AI only the tools a request can need.
 *
 * Every AI call carries the description of every tool it may use. With 80+ tools that was about
 * 10,000 tokens a step, most of it for things the request has nothing to do with (the plan coach
 * while ordering food, the skill builder while reading a message). A free AI key allows a fixed
 * number of tokens a minute, so those wasted tokens are what made long tasks wait and fail.
 *
 * Tools are kept in groups. The small core is always sent. A group is added when the words of the
 * request call for it, when one of its tools was already used in this chat, or when the AI asks for
 * it with more_tools. Nothing is ever out of reach: a tool no group knows about is always sent.
 */
object Router {
    class Group(val name: String, val about: String, val tools: Set<String>, val trigger: Regex?)

    /** Always sent: the things nearly every request touches. */
    val core = setOf(
        "ask_user", "memory_save", "memory_forget", "contacts_search", "call", "send_sms", "send_whatsapp", "send_email",
        "alarm_set", "alarm_list", "alarm_cancel", "reminder_set", "open_app", "open_url", "apps_list", "phone_state", "set_setting", "location",
        "web_search", "read_page", "news", "weather", "wikipedia", "notify", "say", "wait", "clipboard", "app_usage", MORE,
    )

    const val MORE = "more_tools"

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    val groups = listOf(
        // The screen tools are the hands: always there when screen control is on.
        Group("screen", "see and operate any app", setOf(
            "screen_read", "screenshot", "screen_tap", "screen_day", "screen_tap_at", "screen_long_press", "screen_type", "screen_scroll", "screen_swipe",
            "screen_wait", "screen_back", "screen_home", "screen_recents", "screen_notifications", "lesson_save", "your_turn",
        ), null),
        Group("automation", "skills: things that run later, on a schedule or on a trigger; list, run or delete skills", setOf("skill_save", "skill_save_this", "skill_list", "skill_run", "skill_delete"),
            rx("\\b(every|each|whenever|when (i|my|the|a|an|it|he|she|they|someone|anyone|somebody|\\w+ (calls?|messages?|texts?|sends?|reach|arrives?))|if (i|my|the|a|an|it|someone|anyone)|daily|weekly|monthly|weekdays?|weekends?|schedule[ds]?|later|tomorrow|tonight|day after|next (week|month|monday|tuesday|wednesday|thursday|friday|saturday|sunday)|on (monday|tuesday|wednesday|thursday|friday|saturday|sunday)|at \\d|by \\d|after \\d|in \\d+ ?(min|mins|minutes|hours?|hrs?|days?)|automat\\w*|skills?|routine|triggers?|by itself|on its own|from now on|always|until i|keep (checking|doing|sending))\\b|dhinamum|thinamum|ovvoru|naalaiku|naalai|kaalaila|nightla|தினமும்|நாளை|ஒவ்வொரு"),
        ),
        Group("files", "find, open and play files on the phone; storage; make a PowerPoint, Word or PDF file; send a file; pictures into one PDF", setOf("files_find", "file_open", "storage_info", "doc_create", "send_file", "photos_to_pdf"),
            rx("\\b(ppt|pptx|power ?point|slides?|presentation|pdf|word|docx?|documents?|files?|folders?|storage|space|downloads?|downloaded|videos?|movies?|films?|clips?|photos?|pictures?|pics?|images?|screenshots?|songs?|music|audio|mp3|mp4|mkv|resume|cv|letter|report|notes|certificate|attach\\w*|gallery|player|vlc|mx player|it|this|that|them)\\b"),
        ),
        Group("mail", "read and search the user's mail", setOf("read_mail", "search_mail"), rx("\\b(e-?mails?|mails?|gmail|inbox|otp from|newsletter)\\b|மெயில்")),
        Group("notifications", "read, answer, open or clear the phone's notifications", setOf("notifications_read", "notification_reply", "notification_open", "notifications_clear"),
            rx("\\b(notifications?|notifs?|unread|reply|replies|replied|respond|answer (him|her|them|it)|messages?|msgs?|texts?|chats?|missed|who (called|messaged|texted)|what did \\w+ (say|send)|whatsapp|telegram|sms)\\b|மெசேஜ்|பதில்"),
        ),
        Group("calendar", "calendar events, timers and meeting links", setOf("calendar_add", "calendar_list", "timer_set", "meeting_link"),
            rx("\\b(calendar|meetings?|meet|events?|appointments?|timers?|countdown|stopwatch|schedule[ds]?|agenda|free (today|tomorrow|at|on)|busy (today|tomorrow|at|on)|exam on|class at|birthday|anniversary|invite)\\b"),
        ),
        Group("watch", "\"tell me when…\" watchers on web pages, busy mode for calls, deadlines and bills caught from messages, the day's summary", setOf("watch_add", "watch_list", "watch_remove", "busy_mode", "inbox_list", "inbox_done", "daily_brief"),
            rx("\\b(tell me when|let me know (when|if)|notify me|alert me|inform me|ping me|watch(ing|es|er|ers)?|keep an eye|price (drops?|falls?|goes|is below)|drops? below|back in stock|in stock|available|bookings? (open|start)|tickets? (open|go on sale)|results?|busy|in (a )?(class|meeting|exam|interview|movie|theatre|hospital|function)|driving|sleeping|till \\d|until \\d|do not disturb me|deadlines?|due|bills?|renewals?|expir\\w+|summary|brief|briefing|caught|my day|today'?s plan)\\b"),
        ),
        Group("money", "money in and out added up from payment messages, orders on the way, the credit book, splitting a bill", setOf("money_summary", "orders_list", "khata", "split_bill"),
            rx("\\b(spen[dt]|spending|expenses?|sales|collections?|received?|income|earn\\w*|money|paid|pay(ment|ments)?|owes?|owed|credit|khata|udhaar|kadan|dues?|split|share (the|a|this) bill|my orders?|orders? (status|on the way)|where (is|are) my|parcels?|package|deliver\\w*|track\\w*|courier|shipped|refund|upi|gpay|phonepe|paytm)\\b|vasool|selavu|கடன்|செலவு|வசூல்"),
        ),
        Group("plans", "the plan coach: make a workout, study, diet or habit plan, mark today's session, change or list plans", setOf("plan_create", "plan_list", "plan_mark", "plan_change"),
            rx("\\b(plans?|workouts?|work ?out|gym|exercises?|fitness|study|studying|revision|diet|habits?|routine|timetable|time table|streak|schedule|session|training|practice|lose weight|weight loss|muscle)\\b|udarpayirchi|padippu"),
        ),
        Group("past", "search the messages of the last 30 days and earlier chats with Seyal", setOf("past_search", "chats_search"),
            rx("\\b(find|search my|what did|what was|which|who sent|sent (me|us)|shared|forwarded|told me|said|mentioned|last time|same as|earlier|previous(ly)?|before|ago|yesterday|last (week|month|night|sunday|monday|tuesday|wednesday|thursday|friday|saturday)|history|old|recall|remember when|address|number of|link (he|she|they|\\w+) sent|as usual|usual)\\b|pona thadava|anupina|anuppina|சொன்ன|அனுப்பின"),
        ),
        Group("many", "one message to many people at once (wishes, an invitation)", setOf("send_to_many"),
            rx("\\b(everyone|everybody|all my|all of (them|my)|to all|wish(es)?|greetings?|invit\\w+|each of them|group of|multiple people|many people|several people|my (friends|family|team|classmates|relatives|customers|colleagues|students))\\b|\\b\\w+, \\w+ and \\w+\\b|ellarukum|எல்லாருக்கும்"),
        ),
        // Also added by the agent itself when it recognises a purchase or a booking.
        Group("order", "check a booking or an order against what the user asked for", setOf("order_check", "seats_check"),
            rx("\\b(book\\w*|order\\w*|buy|buying|bought|purchase|tickets?|seats?|cart|checkout|check out|reserv\\w+|recharge|subscri\\w+|pay)\\b|vaangu|vangu|டிக்கெட்|ஆர்டர்"),
        ),
        Group("foresight", "Dirghadarshi mode: what is starting to rise in the world (AI, tech, jobs, money), with proof, what it means for the user, and how past predictions turned out", setOf("foresight"),
            rx("\\b(dirghadarshi|dheerkadharisi|deerkadarisi|theerkadarisi|theerkatharisi|foresight|trends?|trending|early signals?|early adopter|what('?s| is) (coming|next|rising|the future)|future of|what will happen|predict\\w*|forecast|ahead of (others|everyone|time)|before (others|everyone)|get ready for|prepare for the future)\\b|தீர்க்கதரிசி"),
        ),
        Group("jobs", "the internship and job watcher: what to watch (roles, places, internships or jobs), the openings that fit from companies' own job boards, check now, companies watched", setOf("job_watch", "jobs_list", "jobs_check"),
            rx("\\b(jobs?|internships?|interns?|hiring|vacanc\\w*|openings?|placements?|off.?campus|freshers?|job watch\\w*)\\b"),
        ),
        Group("big", "sub-agents working in parallel on a many-part job", setOf("delegate"),
            rx("\\b(research|compare|comparison|each of|for all|every one of|in parallel|at the same time|all of these|detailed report)\\b"),
        ),
    )

    private val byTool: Map<String, String> = groups.flatMap { g -> g.tools.map { it to g.name } }.toMap()

    /** The group a tool belongs to; null for core tools and tools no group lists. */
    fun groupOf(tool: String): String? = byTool[tool]

    /** Questions about Seyal itself, which need the "about this app" notes. */
    private val aboutApp = rx("\\b(seyal|this app|the app|your (app|settings?|features?)|share log|report problem|skills? tab|control tab|activity tab|chat tab|caught for you|simple mode|family check|what can you do|how do (i|you)|where (is|are|do|can)|can you|are you able|do you (have|support)|features?|settings?|button|help)\\b|செயல்")
    fun asksAboutApp(text: String): Boolean = aboutApp.containsMatchIn(text)

    /**
     * The groups a chat needs.
     * [text]: everything the user said in this chat. [used]: tools already called in it. [asked]: groups the AI asked for.
     * [purchase]: it is a booking or an order. [attached]: files came with the message. [fromSkill]: a skill is running.
     */
    fun groupsFor(text: String, used: Collection<String> = emptyList(), asked: Collection<String> = emptyList(), purchase: Boolean = false, attached: Boolean = false, fromSkill: Boolean = false): Set<String> {
        val out = LinkedHashSet<String>()
        out += "screen"
        for (g in groups) if (g.trigger?.containsMatchIn(text) == true) out += g.name
        for (t in used) byTool[t]?.let { out += it }
        // A tool named outright (skills spell their steps out this way).
        if (text.contains('_')) for ((tool, group) in byTool) if (text.contains(tool)) out += group
        for (a in asked) { val n = a.trim().lowercase(); if (n == "all") out += groups.map { it.name } else if (groups.any { it.name == n }) out += n }
        if (purchase) out += "order"
        if (attached) out += "files"
        // A skill was written with every tool in view, and often answers a message: give it room.
        if (fromSkill) { out += "notifications"; out += "automation"; out += "files" }
        // Very long requests are usually several jobs in one: don't guess.
        if (text.length > 600) out += groups.map { it.name }
        return out
    }

    /** Of [all] the tools on this phone, the ones to send for these [loaded] groups. */
    fun pick(all: Collection<String>, loaded: Set<String>): Set<String> =
        all.filter { t -> t in core || byTool[t]?.let { it in loaded } ?: true }.toSet()

    /** The groups not loaded yet, as "name (what it has)", for the more_tools description. */
    fun missing(loaded: Set<String>): List<Group> = groups.filter { it.name !in loaded }

    /** What more_tools says about itself, listing only what is not already loaded. */
    fun moreAbout(loaded: Set<String>): String {
        val rest = missing(loaded)
        return "You are seeing only the tools this request seemed to need. Load more when the job calls for it (before saying something can't be done): " +
            rest.joinToString("; ") { "${it.name} = ${it.about}" } + ". \"all\" loads everything. The new tools are usable from your next step."
    }

    /** What loading [names] added, in words for the AI. */
    fun loadedNote(names: Collection<String>, before: Set<String>): String {
        val wanted = names.map { it.trim().lowercase() }
        val known = if ("all" in wanted) groups.map { it.name } else wanted.filter { n -> groups.any { it.name == n } }
        val fresh = known.filter { it !in before }
        val unknown = wanted.filter { it != "all" && it !in known }
        return buildString {
            if (fresh.isNotEmpty()) append("Loaded: " + fresh.joinToString("; ") { n -> n + " (" + groups.first { it.name == n }.tools.joinToString(", ") + ")" } + ". Use them now.")
            else if (known.isNotEmpty()) append("Already loaded: ${known.joinToString(", ")}. The tools are in your list.")
            if (unknown.isNotEmpty()) append(" No group called ${unknown.joinToString(", ")}. Groups: ${groups.joinToString(", ") { it.name }}.")
        }.trim()
    }

    /** A final answer that gives up ("I can't", "I don't have a tool"): worth one more try with every tool in view. */
    private val givesUp = rx("\\b(i (can'?t|cannot|can not|am unable|'m unable|am not able|'m not able|don'?t have (a |the |any )?(tool|way|ability|access|capability|feature|option))|unable to|not able to|no (tool|way) (to|for)|isn'?t (possible|supported)|not (possible|supported)|beyond (what|my)|outside (what|my))\\b|முடியாது|mudiyathu|mudiyadhu")
    fun givesUp(answer: String): Boolean = givesUp.containsMatchIn(answer.take(600))

    /** The arguments of more_tools. */
    fun asked(args: JSONObject): List<String> {
        val a = args.optJSONArray("groups")
        val list = if (a != null) (0 until a.length()).map { a.optString(it) } else emptyList()
        return (list + args.optString("group").split(',', ' ')).map { it.trim() }.filter { it.isNotBlank() }
    }
}

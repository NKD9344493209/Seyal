package com.seyal.app.agent

import android.content.Context
import com.seyal.app.device.Notify
import com.seyal.app.tools.Common
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId

/** The reading behind Dirghadarshi's awareness (see [Aware] for the rules): feeds fetched side by side, no AI. */
object AwareRun {
    private val one = Mutex()

    /** [asked] readings were tried and [failed] of them did not answer; all failed = nothing is known (that is not "no news"). */
    class Swept(val heads: List<Aware.Headline>, val read: Int, val asked: Int = 0, val failed: Int = 0) {
        val unreachable get() = asked > 0 && failed == asked
    }

    /** Every headline on [topic] from the last [days] days, from all five angles. */
    suspend fun sweep(topic: String, days: Int = 7, only: List<String> = emptyList()): Swept = coroutineScope {
        val angles = Aware.ANGLES.filter { only.isEmpty() || it.id in only }
        val got = angles.map { a ->
            async(Dispatchers.IO) { runCatching { Aware.parse(Common.http(Aware.query(topic, a, days), "application/rss+xml,application/xml,*/*", 10_000), a.id, topic) }.getOrNull() }
        }.awaitAll()
        val items = got.filterNotNull().flatten()
        Swept(Aware.merge(items, System.currentTimeMillis(), days), items.size, angles.size, got.count { it == null })
    }

    /** The headlines on one topic, as text for the chat: the first page, every angle given its share; the rest waits for "more headlines". */
    suspend fun news(topic: String, days: Int = 7): String {
        val s = sweep(topic, days)
        if (s.unreachable) return Aware.UNREACHABLE
        val page = Aware.numbered(Aware.pick(s.heads, Aware.PAGE))
        AwareStore.listed(page, s.heads.filter { it !in page })
        return Aware.withNote(Aware.text(topic, page, s.heads.size, s.read, days, ZoneId.systemDefault()), s.failed, s.asked)
    }

    /** The next page of the last list. */
    fun more(): String {
        val from = AwareStore.shown.size
        val text = Aware.more(AwareStore.rest, from, ZoneId.systemDefault())
        AwareStore.paged(Aware.PAGE)
        return text
    }

    class Detail(val say: String, val text: String, val url: String)

    /**
     * The details of headline [n] of the last list: the story's own page is found by its title (the list's links
     * are Google News addresses, which only a browser can follow) and what that page says it is about is shown.
     */
    suspend fun detail(n: Int): Detail {
        val shown = AwareStore.shown
        val h = shown.getOrNull(n - 1)
            ?: return Detail(if (shown.isEmpty()) "There is no list of headlines yet. Ask, for example, \"any recent news on AI\", then \"more on 2\"." else "The last list has ${shown.size} headlines, so there is no number $n.", "", "")
        val st = story(h)
        return Detail(Aware.detail(n, h, st.url, st.lead, shown.size, ZoneId.systemDefault()), st.text, st.url ?: h.item.url)
    }

    /** A story's own page: [url] when it was found, [lead] = what the page says it is about, [text] = its words. */
    class Story(val url: String?, val lead: String?, val text: String)

    /** Finds and reads the page of one headline (used by "more on 2" and by a tap on a headline in the Dirghadarshi tab). */
    suspend fun story(h: Aware.Headline): Story = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val found = runCatching { com.seyal.app.tools.WebTools.links(h.item.title.take(140), 10) }.getOrDefault(emptyList())
        val url = Aware.story(h.item.title, h.item.source, found)
        val html = url?.let { u -> runCatching { Common.http(u, timeoutMs = 9_000) }.getOrNull() }
        Story(url, html?.let { Aware.lead(it) }, html?.let { com.seyal.app.util.Parsers.strip(it).take(6000) }.orEmpty())
    }

    /** A brief that was made: [ok] is false when the news could not be reached at all (then [text] says so and nothing is kept). */
    class Briefed(val text: String, val notice: String, val ok: Boolean)

    /** When the last brief was tried, so a phone with no internet is not asked again every few seconds. */
    @Volatile var lastTry: Long = 0; private set

    /**
     * The brief over every interest (the last two days), kept as the latest one. Null when no interest is set.
     * [morning] = this is the day's own brief going out as a notification; only that one counts as "today's was sent",
     * so reading the brief by hand at 7:30 does not cancel the one promised for 8.
     */
    suspend fun brief(morning: Boolean = false): Briefed? = one.withLock {
        val interests = AwareStore.interests
        if (interests.isEmpty()) return@withLock null
        lastTry = System.currentTimeMillis()
        val swept = interests.map { t -> t to sweep(t, 2) }
        if (swept.all { it.second.unreachable }) return@withLock Briefed(Aware.UNREACHABLE, "", false)
        val by = swept.map { it.first to it.second.heads }
        val zone = ZoneId.systemDefault()
        val b = Aware.brief(by, LocalDate.now(zone), 4, zone)
        val text = Aware.withNote(b.text, swept.sumOf { it.second.failed }, swept.sumOf { it.second.asked })
        AwareStore.listed(b.shown, b.rest)
        AwareStore.briefed(text, System.currentTimeMillis(), if (morning) LocalDate.now(zone).toString() else AwareStore.lastDay, b.shown, b.rest, b.warned)
        AwareStore.remember(by.flatMap { it.second }.filter { it.urgent }.map { Aware.key(it.item.title) })
        Briefed(text, Aware.notice(by), true)
    }

    /** From the background wake-up: the morning brief when it is due, and the watch for something big. */
    suspend fun tick(ctx: Context) {
        if (!AwareStore.daily || AwareStore.interests.isEmpty()) return
        val now = System.currentTimeMillis(); val zone = ZoneId.systemDefault()
        if (Aware.dueDaily(now, AwareStore.lastDay, zone)) {
            // No internet this morning: tried again a quarter of an hour later, not every few seconds.
            if (now - lastTry < Aware.RETRY_MS) return
            val b = runCatching { brief(morning = true) }.getOrNull()
            if (b != null && b.ok) { Notify.foresight(ctx, "Dirghadarshi brief", b.notice, tab = "aware"); AwareStore.watched(System.currentTimeMillis()) }
            return
        }
        if (Aware.dueWatch(now, AwareStore.lastWatch, zone)) {
            AwareStore.watched(now)
            val heads = AwareStore.interests.flatMap { t -> runCatching { sweep(t, 1, only = listOf("threat")).heads }.getOrDefault(emptyList()) }
            val fresh = Aware.alerts(heads, AwareStore.seen()).take(2)
            if (fresh.isNotEmpty()) {
                AwareStore.remember(fresh.map { Aware.key(it.item.title) })
                AwareStore.alerted(fresh.map { h -> Aware.Alert(h.item.title, h.item.source, h.item.url, h.item.topic, h.also + 1, now) })
                fresh.forEach { h -> Notify.alert(ctx, "aware" + Aware.key(h.item.title).hashCode(), "Watch out: ${h.item.topic}", h.item.title + " · " + h.item.source + " and ${h.also} more", h.item.url) }
            }
        }
    }

    /** When the background should next wake for this. */
    fun next(now: Long): Long? {
        if (!AwareStore.daily || AwareStore.interests.isEmpty()) return null
        val zone = ZoneId.systemDefault()
        return Aware.nextWake(now, AwareStore.lastDay, AwareStore.lastWatch, lastTry, zone)
    }
}

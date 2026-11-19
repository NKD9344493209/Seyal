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

    class Swept(val heads: List<Aware.Headline>, val read: Int)

    /** Every headline on [topic] from the last [days] days, from all five angles. */
    suspend fun sweep(topic: String, days: Int = 7, only: List<String> = emptyList()): Swept = coroutineScope {
        val angles = Aware.ANGLES.filter { only.isEmpty() || it.id in only }
        val items = angles.map { a ->
            async(Dispatchers.IO) { runCatching { Aware.parse(Common.http(Aware.query(topic, a, days), "application/rss+xml,application/xml,*/*", 10_000), a.id, topic) }.getOrDefault(emptyList()) }
        }.awaitAll().flatten()
        Swept(Aware.merge(items, System.currentTimeMillis(), days), items.size)
    }

    /** The headlines on one topic, as text for the chat: the first page, every angle given its share; the rest waits for "more headlines". */
    suspend fun news(topic: String, days: Int = 7): String {
        val s = sweep(topic, days)
        val page = Aware.numbered(Aware.pick(s.heads, Aware.PAGE))
        AwareStore.listed(page, s.heads.filter { it !in page })
        return Aware.text(topic, page, s.heads.size, s.read, days, ZoneId.systemDefault())
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
        val found = runCatching { com.seyal.app.tools.WebTools.links(h.item.title.take(140), 10) }.getOrDefault(emptyList())
        val url = Aware.story(h.item.title, h.item.source, found)
        val html = url?.let { u -> runCatching { Common.http(u, timeoutMs = 9_000) }.getOrNull() }
        val lead = html?.let { Aware.lead(it) }
        return Detail(Aware.detail(n, h, url, lead, shown.size, ZoneId.systemDefault()), html?.let { com.seyal.app.util.Parsers.strip(it).take(6000) }.orEmpty(), url ?: h.item.url)
    }

    /** The brief over every interest (the last two days), kept as the latest one. Null when no interest is set. */
    suspend fun brief(): Pair<String, String>? = one.withLock {
        val interests = AwareStore.interests
        if (interests.isEmpty()) return@withLock null
        val by = interests.map { t -> t to sweep(t, 2).heads }
        val zone = ZoneId.systemDefault()
        val b = Aware.brief(by, LocalDate.now(zone), 4, zone)
        val text = b.text
        AwareStore.listed(b.shown, b.rest)
        AwareStore.briefed(text, System.currentTimeMillis(), LocalDate.now(zone).toString())
        AwareStore.remember(by.flatMap { it.second }.filter { it.urgent }.map { Aware.key(it.item.title) })
        text to Aware.notice(by)
    }

    /** From the background wake-up: the morning brief when it is due, and the watch for something big. */
    suspend fun tick(ctx: Context) {
        if (!AwareStore.daily || AwareStore.interests.isEmpty()) return
        val now = System.currentTimeMillis(); val zone = ZoneId.systemDefault()
        if (Aware.dueDaily(now, AwareStore.lastDay, zone)) {
            runCatching { brief() }.getOrNull()?.let { (_, line) -> Notify.foresight(ctx, "Dirghadarshi brief", line) }
            AwareStore.watched(System.currentTimeMillis())
            return
        }
        if (Aware.dueWatch(now, AwareStore.lastWatch, zone)) {
            AwareStore.watched(now)
            val heads = AwareStore.interests.flatMap { t -> runCatching { sweep(t, 1, only = listOf("threat")).heads }.getOrDefault(emptyList()) }
            val fresh = Aware.alerts(heads, AwareStore.seen()).take(2)
            if (fresh.isNotEmpty()) {
                AwareStore.remember(fresh.map { Aware.key(it.item.title) })
                fresh.forEach { h -> Notify.alert(ctx, "aware" + Aware.key(h.item.title).hashCode(), "Watch out: ${h.item.topic}", h.item.title + " · " + h.item.source + " and ${h.also} more", h.item.url) }
            }
        }
    }

    /** When the background should next wake for this. */
    fun next(now: Long): Long? {
        if (!AwareStore.daily || AwareStore.interests.isEmpty()) return null
        val zone = ZoneId.systemDefault()
        return minOf(Aware.nextDaily(now, AwareStore.lastDay, zone), maxOf(now + 60_000, AwareStore.lastWatch + 3 * 3_600_000L))
    }
}

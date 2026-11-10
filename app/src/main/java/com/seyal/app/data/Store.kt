package com.seyal.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Small JSON-file storage on the phone. Nothing here leaves the device.
 *   seyal/sessions/<id>.json   one file per conversation (the AI transcript)
 *   seyal/index.json           what lists need: id, title, status, result
 *   seyal/skills.json, timers.json, memory.md
 */
object Store {
    private lateinit var dir: File
    private val lock = Any()

    fun init(context: Context) {
        dir = File(context.filesDir, "seyal").apply { mkdirs() }
        File(dir, "sessions").mkdirs()
    }

    private fun readArray(name: String): JSONArray = synchronized(lock) {
        val f = File(dir, name)
        if (!f.exists()) return JSONArray()
        runCatching { JSONArray(f.readText()) }.getOrDefault(JSONArray())
    }

    private fun writeText(f: File, text: String) = synchronized(lock) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private fun writeArray(name: String, arr: JSONArray) = writeText(File(dir, name), arr.toString())

    // ---- sessions ----
    private fun sessionFile(id: String) = File(File(dir, "sessions"), "$id.json")

    fun saveSession(s: Session) {
        writeText(sessionFile(s.id), s.toJson().toString())
    }

    fun loadSession(id: String): Session? = synchronized(lock) {
        val f = sessionFile(id)
        if (!f.exists()) return null
        runCatching { Session.fromJson(JSONObject(f.readText())) }.getOrNull()
    }

    fun deleteSession(id: String) = synchronized(lock) { sessionFile(id).delete() }

    /** Newest first. */
    fun loadIndex(): MutableList<JSONObject> {
        val arr = readArray("index.json")
        val out = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out += it }
        return out
    }

    fun saveIndex(entries: List<JSONObject>) {
        val keep = entries.sortedByDescending { it.optLong("created_at") }.take(300)
        writeArray("index.json", JSONArray().apply { keep.forEach { put(it) } })
        // Drop transcript files that fell off the index.
        val ids = keep.map { it.optString("id") }.toSet()
        synchronized(lock) { File(dir, "sessions").listFiles()?.forEach { f -> if (f.name.removeSuffix(".json") !in ids && f.name.endsWith(".json")) f.delete() } }
    }

    // ---- skills ----
    fun loadSkills(): MutableList<Skill> {
        val arr = readArray("skills.json").let { if (it.length() == 0) readArray("recipes.json") else it }
        val out = mutableListOf<Skill>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { o -> runCatching { out += Skill.fromJson(o) } }
        return out
    }

    fun saveSkills(skills: List<Skill>) = writeArray("skills.json", JSONArray().apply { skills.forEach { put(it.toJson()) } })

    // ---- alarms and reminders ----
    fun loadTimers(): MutableList<Timer> {
        val arr = readArray("timers.json")
        val out = mutableListOf<Timer>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out += Timer.fromJson(it) }
        return out
    }

    fun saveTimers(timers: List<Timer>) = writeArray("timers.json", JSONArray().apply { timers.forEach { put(it.toJson()) } })

    // ---- memory (plain text the AI reads every time) ----
    fun loadMemory(): String = synchronized(lock) { File(dir, "memory.md").let { if (it.exists()) it.readText() else "" } }
    fun saveMemory(text: String) = writeText(File(dir, "memory.md"), text)

    fun wipe() = synchronized(lock) {
        dir.walkBottomUp().forEach { if (it != dir) it.delete() }
        File(dir, "sessions").mkdirs()
    }
}

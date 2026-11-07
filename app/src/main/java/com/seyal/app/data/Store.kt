package com.seyal.app.data

import android.content.Context
import org.json.JSONArray
import java.io.File

/** Small JSON-file storage on the phone. Nothing here leaves the device. */
object Store {
    private lateinit var dir: File
    private val lock = Any()

    fun init(context: Context) {
        dir = File(context.filesDir, "seyal").apply { mkdirs() }
    }

    private fun read(name: String): JSONArray = synchronized(lock) {
        val f = File(dir, name)
        if (!f.exists()) return JSONArray()
        runCatching { JSONArray(f.readText()) }.getOrDefault(JSONArray())
    }

    private fun write(name: String, arr: JSONArray) = synchronized(lock) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(File(dir, name))
    }

    // ---- runs ----
    fun loadRuns(): MutableList<Run> {
        val arr = read("runs.json")
        val out = mutableListOf<Run>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { o -> runCatching { out += Run.fromJson(o) } }
        return out
    }

    fun saveRuns(runs: List<Run>) {
        val arr = JSONArray()
        runs.sortedByDescending { it.createdAt }.take(150).forEach { arr.put(it.toJson()) }
        write("runs.json", arr)
    }

    // ---- recipes ----
    fun loadRecipes(): MutableList<Recipe> {
        val arr = read("recipes.json")
        val out = mutableListOf<Recipe>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { o -> runCatching { out += Recipe.fromJson(o) } }
        return out
    }

    fun saveRecipes(recipes: List<Recipe>) {
        val arr = JSONArray()
        recipes.forEach { arr.put(it.toJson()) }
        write("recipes.json", arr)
    }

    // ---- alarms and reminders ----
    fun loadTimers(): MutableList<Timer> {
        val arr = read("timers.json")
        val out = mutableListOf<Timer>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out += Timer.fromJson(it) }
        return out
    }

    fun saveTimers(timers: List<Timer>) {
        val arr = JSONArray()
        timers.forEach { arr.put(it.toJson()) }
        write("timers.json", arr)
    }

    fun wipe() = synchronized(lock) {
        dir.listFiles()?.forEach { it.delete() }
    }
}

package com.seyal.app.agent

import com.seyal.app.ai.ToolDecl
import com.seyal.app.data.Level
import com.seyal.app.data.Part
import org.json.JSONArray
import org.json.JSONObject

/** What a tool returns to the agent. */
sealed class ToolResult {
    /** [output] goes back to the AI; [line] is the one-liner the user sees; [image] is shown to the AI too (screenshots). */
    class Done(val output: JSONObject, val line: String, val image: Part.Image? = null) : ToolResult()
    class Failed(val message: String, val retryable: Boolean = false) : ToolResult()

    companion object {
        fun ok(line: String, vararg kv: Pair<String, Any?>): Done =
            Done(JSONObject().apply { put("ok", true); kv.forEach { (k, v) -> if (v != null) put(k, v) } }, line)
    }
}

/** Runtime context a tool may use. */
class ToolContext(
    val sessionId: String,
    /** Runs sub-goals in parallel as child sessions and returns their one-line results. */
    val delegate: suspend (goals: List<String>) -> List<JSONObject>,
    /** Asks the user something and waits for the answer (only ask_user uses it). */
    val askUser: suspend (question: String, options: List<String>) -> String,
)

/**
 * One capability the agent can use. The AI only ever proposes a call; the agent decides if it
 * runs ([defaultLevel] and the user's settings), and [run] does the work.
 */
class Tool(
    val name: String,
    val group: String,
    val description: String,
    val params: JSONObject,
    val defaultLevel: Level = Level.AUTO,
    /** Changes something outside the app (sends, calls, writes). Never retried by itself. */
    val sideEffect: Boolean = false,
    /** Whether the tool can work right now (edition, service on, permission). Unavailable tools are hidden from the AI. */
    val available: () -> Boolean = { true },
    /** The thing an "Always allow" decision is tied to, e.g. a phone number or an app name. */
    val target: (JSONObject) -> String? = { null },
    /** What the approval card shows. */
    val preview: (JSONObject) -> String = { "" },
    val run: suspend (JSONObject, ToolContext) -> ToolResult,
) {
    fun decl(): ToolDecl = ToolDecl(name, description, params)
}

/** Tiny JSON-schema builder so tool definitions stay readable. */
class Schema {
    private val props = JSONObject()
    private val required = JSONArray()

    private fun add(name: String, type: String, desc: String, req: Boolean, extra: (JSONObject.() -> Unit)? = null) {
        props.put(name, JSONObject().put("type", type).put("description", desc).also { extra?.invoke(it) })
        if (req) required.put(name)
    }

    fun str(name: String, desc: String, required: Boolean = false, enum: List<String>? = null) =
        add(name, "string", desc, required) { enum?.let { put("enum", JSONArray(it)) } }
    fun int(name: String, desc: String, required: Boolean = false) = add(name, "integer", desc, required)
    fun num(name: String, desc: String, required: Boolean = false) = add(name, "number", desc, required)
    fun bool(name: String, desc: String, required: Boolean = false) = add(name, "boolean", desc, required)
    fun strList(name: String, desc: String, required: Boolean = false) =
        add(name, "array", desc, required) { put("items", JSONObject().put("type", "string")) }
    fun objList(name: String, desc: String, required: Boolean = false, item: Schema.() -> Unit) =
        add(name, "array", desc, required) { put("items", Schema().apply(item).build()) }

    fun build(): JSONObject = JSONObject().put("type", "object").put("properties", props).also { if (required.length() > 0) it.put("required", required) }
}

fun schema(block: Schema.() -> Unit): JSONObject = Schema().apply(block).build()
val noArgs: JSONObject get() = JSONObject().put("type", "object").put("properties", JSONObject())

package com.seyal.app.engine

import android.content.Context
import com.seyal.app.ai.Plan
import com.seyal.app.ai.Planner
import com.seyal.app.data.Level
import com.seyal.app.data.Recipe
import com.seyal.app.data.Run
import com.seyal.app.data.RunStatus
import com.seyal.app.data.Settings
import com.seyal.app.data.Step
import com.seyal.app.data.StepStatus
import com.seyal.app.data.Store
import com.seyal.app.data.Trigger
import com.seyal.app.data.newId
import com.seyal.app.device.Notify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The fixed part of Seyal. The AI proposes a plan; this engine runs it:
 * lanes in parallel, dependencies in order, approvals, retries, cancel, one-line results.
 */
object Engine {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val stepJobs = ConcurrentHashMap<String, Job>()

    /**
     * The engine changes these run/recipe objects in place. Screens never see them: every change
     * publishes fresh copies below, so Compose always notices (same object = "nothing changed").
     */
    @Volatile private var live: List<Run> = emptyList()
    @Volatile private var liveRecipes: List<Recipe> = emptyList()

    private val _runs = MutableStateFlow<List<Run>>(emptyList())
    val runs: StateFlow<List<Run>> = _runs
    private val _recipes = MutableStateFlow<List<Recipe>>(emptyList())
    val recipes: StateFlow<List<Recipe>> = _recipes
    private val _planning = MutableStateFlow(false)
    val planning: StateFlow<Boolean> = _planning
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused

    /**
     * Bumps on every change. The run objects are updated in place, so the lists above stay
     * "equal" and StateFlow would not re-emit them; screens watch this counter instead.
     */
    private val _tick = MutableStateFlow(0L)
    val tick: StateFlow<Long> = _tick

    fun init(context: Context) {
        app = context.applicationContext
        live = Store.loadRuns().sortedByDescending { it.createdAt }
        liveRecipes = Store.loadRecipes()
        _runs.value = live.map { it.snapshot() }
        _recipes.value = liveRecipes.map { it.snapshot() }
        _paused.value = Settings.paused
    }

    private fun bump() {
        _runs.value = live.map { it.snapshot() }
        _tick.value = _tick.value + 1
        Store.saveRuns(live)
    }

    private fun bumpRecipes() {
        _recipes.value = liveRecipes.map { it.snapshot() }
        _tick.value = _tick.value + 1
        Store.saveRecipes(liveRecipes)
    }

    /** The engine's own recipe objects (for the scheduler, which updates next-run times). */
    internal fun recipeList(): List<Recipe> = liveRecipes

    fun run(id: String): Run? = live.firstOrNull { it.id == id }
    fun recipe(id: String): Recipe? = liveRecipes.firstOrNull { it.id == id }

    private fun history(): String = live.take(4).joinToString("\n") { r ->
        "- \"${r.command.take(120)}\" → ${r.summary ?: r.status.name.lowercase()}" + (r.finalText?.let { " · " + it.take(100).replace("\n", " ") } ?: "")
    }

    // ------------------------------------------------------------ commands

    /** Plan a command with the AI and start it. Returns the run that was created. */
    suspend fun submit(command: String): Run {
        _planning.value = true
        try {
            val t0 = System.currentTimeMillis()
            val plan = runCatching { Planner.plan(command, history()) }.getOrElse { e ->
                return addRun(Run(newId(), command, "Couldn't plan", System.currentTimeMillis(), mutableListOf()).apply {
                    mode = "answer"; status = RunStatus.FAILED; finalText = e.message ?: "The AI did not answer."; summary = "Failed"
                })
            }
            return applyPlan(command, plan, System.currentTimeMillis() - t0)
        } finally {
            _planning.value = false
        }
    }

    private suspend fun applyPlan(command: String, plan: Plan, planMs: Long): Run {
        val run = Run(newId(), command, plan.title, System.currentTimeMillis(), plan.steps.map { it.fresh() }.toMutableList())
        run.mode = plan.mode
        run.planMs = planMs
        when (plan.mode) {
            "answer" -> { run.status = RunStatus.DONE; run.finalText = plan.answer; run.summary = "Answered" }
            "clarify" -> { run.status = RunStatus.DONE; run.question = plan.question; run.options = plan.options; run.summary = "Needs an answer" }
            "schedule" -> {
                val trig = plan.trigger ?: Trigger("once", "09:00", "tomorrow")
                val recipe = Recipe(newId(), plan.title, trig, plan.steps.map { it.fresh() }, command)
                addRecipe(recipe)
                run.status = RunStatus.DONE
                run.summary = "Scheduled"
                run.finalText = "Saved as a recipe: ${trig.describe()}" + (if (recipe.nextRun > 0) "\nNext run: ${Times.format(recipe.nextRun)}" else "")
            }
            else -> {}
        }
        addRun(run)
        if (plan.mode == "now") start(run)
        return run
    }

    private suspend fun addRun(run: Run): Run = lock.withLock {
        live = listOf(run) + live
        bump()
        run
    }

    /** Answer a clarifying question by re-planning with the choice attached. */
    suspend fun answerClarify(runId: String, answer: String): Run {
        val old = run(runId)
        val command = if (old != null) "${old.command}\n(Answer to \"${old.question}\": $answer)" else answer
        return submit(command)
    }

    // ------------------------------------------------------------ execution

    fun start(run: Run) {
        scope.launch { tick(run.id) }
    }

    private suspend fun tick(runId: String) {
        val jobs = mutableListOf<Job>()
        lock.withLock {
            val run = run(runId) ?: return
            if (run.status == RunStatus.CANCELLED) return
            if (_paused.value) { if (run.status.isOpen) { run.status = RunStatus.WAITING; bump() }; return }

            // Skip steps whose dependencies can never finish, then start every ready lane together.
            Lanes.skipBlocked(run.steps)
            val ready = Lanes.ready(run.steps) { stepJobs.containsKey("${run.id}/${it.id}") }
            val anyJob = stepJobs.keys.any { it.startsWith("${run.id}/") }
            if (ready.isEmpty() && Lanes.failStuck(run.steps, anyJob)) Lanes.skipBlocked(run.steps)

            when {
                ready.isNotEmpty() -> {
                    run.status = RunStatus.RUNNING
                    for (s in ready) {
                        val key = "$runId/${s.id}"
                        // Registered under the lock, so a parallel tick can never start the same step twice.
                        val job = scope.launch(start = CoroutineStart.LAZY) {
                            try { execute(runId, s.id) } finally { stepJobs.remove(key) }
                            tick(runId)
                        }
                        stepJobs[key] = job
                        jobs += job
                    }
                }
                anyJob -> run.status = RunStatus.RUNNING
                Lanes.isFinished(run.steps) -> if (run.status.isOpen) finish(run)
                run.steps.any { it.status.isWaiting } -> run.status = RunStatus.WAITING
            }
            run.updatedAt = System.currentTimeMillis()
            bump()
        }
        jobs.forEach { it.start() }
    }

    private fun effectiveLevel(step: Step, args: JSONObject): Level {
        val spec = Tools.spec(step.tool) ?: return Level.OFF
        var level = Settings.level(spec.name, spec.defaultLevel)
        if (spec.name == "send_whatsapp" && level == Level.ASK) {
            val phone = args.optString("phone").filter { it.isDigit() }.let { if (it.length == 10) "91$it" else it }
            if (Settings.autoSendContacts.contains(phone)) level = Level.AUTO
        }
        return level
    }

    /** Under the lock: decides whether a step may run now. Returns the tool and resolved args, or null. */
    private fun prepare(runId: String, stepId: String): Pair<ToolSpec, JSONObject>? {
        val r = run(runId) ?: return null
        val step = r.step(stepId) ?: return null
        if (step.status != StepStatus.PENDING) return null
        val spec = Tools.spec(step.tool)
        if (spec == null) {
            step.status = StepStatus.FAILED; step.error = "Unknown tool ${step.tool}"; bump(); return null
        }
        val args = substitute(step.args, r)
        when (effectiveLevel(step, args)) {
            Level.OFF -> { step.status = StepStatus.SKIPPED; step.error = "Turned off in Control"; bump(); return null }
            Level.ASK -> if (!step.approved) {
                step.status = StepStatus.WAITING_APPROVAL
                step.line = preview(step, args)
                bump()
                Notify.approval(app, r, step, step.line ?: step.title)
                return null
            }
            Level.AUTO -> {}
        }
        step.status = StepStatus.RUNNING
        step.attempts++
        bump()
        return spec to args
    }

    private suspend fun execute(runId: String, stepId: String) {
        val prepared = lock.withLock { prepare(runId, stepId) } ?: return
        val (spec, args) = prepared

        val result = runCatching { withContext(Dispatchers.IO) { spec.run.invoke(args) } }
            .getOrElse { ToolResult.Failed(it.message ?: "Error", retryable = true) }

        lock.withLock {
            val run = run(runId) ?: return
            val step = run.step(stepId) ?: return
            if (run.status == RunStatus.CANCELLED) { step.status = StepStatus.CANCELLED; bump(); return }
            when (result) {
                is ToolResult.Done -> { step.status = StepStatus.DONE; step.output = result.output; step.line = result.line; step.error = null }
                is ToolResult.Choice -> {
                    step.status = StepStatus.WAITING_CHOICE
                    step.choicePrompt = result.prompt
                    step.choices = JSONArray().apply { result.options.forEach { (label, value) -> put(JSONObject().put("label", label).put("value", value)) } }
                    Notify.line(app, "${run.title}: ${result.prompt}", runId)
                }
                is ToolResult.Failed -> {
                    if (result.retryable && !spec.sideEffect && step.attempts < 3) {
                        step.status = StepStatus.PENDING
                        step.error = result.message
                        bump()
                        scope.launch { delay(1500L * step.attempts); tick(runId) }
                        return
                    }
                    step.status = StepStatus.FAILED
                    step.error = result.message
                }
            }
            step.line = step.line ?: step.title
            run.updatedAt = System.currentTimeMillis()
            bump()
        }
    }

    /** Short preview shown on the approval card, e.g. the message text. */
    private fun preview(step: Step, args: JSONObject): String = when (step.tool) {
        "send_whatsapp" -> "WhatsApp to ${args.optString("name").ifBlank { args.optString("phone") }}: \"${args.optString("text").take(140)}\""
        "send_email" -> "Email to ${args.optString("to")} · ${args.optString("subject")}\n${args.optString("body").take(240)}"
        "call" -> "Call ${args.optString("name").ifBlank { args.optString("phone") }}"
        "create_event" -> "Add event \"${args.optString("title")}\" at ${Times.parse(args.optString("start"))?.let { Times.format(Times.toEpoch(it)) } ?: args.optString("start")}"
        else -> step.title
    }

    fun substitute(args: JSONObject, run: Run): JSONObject = Lanes.substitute(args, run.steps)

    private fun finish(run: Run) {
        val out = Lanes.outcome(run.steps)
        run.status = out.status
        run.summary = out.summary
        run.finalText = out.finalText
        run.updatedAt = System.currentTimeMillis()
        if (!Settings.quiet || run.status != RunStatus.DONE) {
            Notify.line(app, "${run.title}: ${run.summary}", run.id)
        }
        run.recipeId?.let { rid -> recipe(rid)?.let { r -> r.lastRun = System.currentTimeMillis(); bumpRecipes() } }
    }

    // ------------------------------------------------------------ user actions

    fun approve(runId: String, stepId: String, yes: Boolean) = scope.launch {
        lock.withLock {
            val run = run(runId) ?: return@withLock
            val step = run.step(stepId) ?: return@withLock
            if (step.status != StepStatus.WAITING_APPROVAL) return@withLock
            if (yes) { step.approved = true; step.status = StepStatus.PENDING }
            else { step.status = StepStatus.CANCELLED; step.error = "You said no" }
            Notify.cancelApproval(app, run.id, step.id)
            bump()
        }
        tick(runId)
    }

    fun approveAll(runId: String, yes: Boolean) = scope.launch {
        lock.withLock {
            val run = run(runId) ?: return@withLock
            run.steps.filter { it.status == StepStatus.WAITING_APPROVAL }.forEach { step ->
                if (yes) { step.approved = true; step.status = StepStatus.PENDING }
                else { step.status = StepStatus.CANCELLED; step.error = "You said no" }
                Notify.cancelApproval(app, run.id, step.id)
            }
            bump()
        }
        tick(runId)
    }

    fun choose(runId: String, stepId: String, index: Int) = scope.launch {
        lock.withLock {
            val run = run(runId) ?: return@withLock
            val step = run.step(stepId) ?: return@withLock
            val opt = step.choices?.optJSONObject(index) ?: return@withLock
            step.output = opt.optJSONObject("value") ?: JSONObject()
            step.line = "Chose ${opt.optString("label")}"
            step.status = StepStatus.DONE
            step.choices = null
            bump()
        }
        tick(runId)
    }

    fun retryFailed(runId: String) = scope.launch {
        lock.withLock {
            val run = run(runId) ?: return@withLock
            run.steps.forEach { s ->
                if (s.status == StepStatus.FAILED || s.status == StepStatus.SKIPPED || s.status == StepStatus.CANCELLED) {
                    s.status = StepStatus.PENDING; s.error = null; s.line = null; s.attempts = 0
                }
            }
            run.status = RunStatus.PLANNED
            bump()
        }
        tick(runId)
    }

    /** Run the same plan again from scratch (e.g. "run again"). */
    fun rerun(runId: String) = scope.launch {
        val old = run(runId) ?: return@launch
        val fresh = Run(newId(), old.command, old.title, System.currentTimeMillis(), old.steps.map { it.fresh() }.toMutableList(), old.recipeId)
        addRun(fresh)
        tick(fresh.id)
    }

    fun cancel(runId: String) = scope.launch {
        lock.withLock {
            val run = run(runId) ?: return@withLock
            run.steps.forEach { s ->
                if (!s.status.isFinished) { s.status = StepStatus.CANCELLED; s.error = "Cancelled"; Notify.cancelApproval(app, run.id, s.id) }
            }
            stepJobs.filterKeys { it.startsWith("$runId/") }.values.forEach { it.cancel() }
            run.status = RunStatus.CANCELLED
            run.summary = "Cancelled"
            run.updatedAt = System.currentTimeMillis()
            bump()
        }
    }

    fun delete(runId: String) = scope.launch {
        lock.withLock { live = live.filterNot { it.id == runId }; bump() }
    }

    fun setPaused(paused: Boolean) = scope.launch {
        Settings.paused = paused
        _paused.value = paused
        if (paused) {
            stepJobs.values.forEach { it.cancel() }
            lock.withLock {
                live.filter { it.status.isOpen }.forEach { r ->
                    r.steps.forEach { if (it.status == StepStatus.RUNNING) it.status = StepStatus.PENDING }
                    r.status = RunStatus.WAITING
                }
                bump()
            }
            Scheduler.cancelAll(app)
        } else {
            Scheduler.rescheduleAll(app)
            live.filter { it.status.isOpen }.forEach { tick(it.id) }
        }
    }

    // ------------------------------------------------------------ recipes

    private fun addRecipe(recipe: Recipe) {
        recipe.nextRun = Times.nextFire(recipe.trigger)
        liveRecipes = listOf(recipe) + liveRecipes
        bumpRecipes()
        Scheduler.schedule(app, recipe)
    }

    fun saveAsRecipe(runId: String, name: String, trigger: Trigger): Recipe? {
        val run = run(runId) ?: return null
        val recipe = Recipe(newId(), name.ifBlank { run.title }, trigger, run.steps.map { it.fresh() }, run.command)
        addRecipe(recipe)
        return recipe
    }

    fun setRecipeEnabled(id: String, enabled: Boolean) {
        val r = recipe(id) ?: return
        r.enabled = enabled
        if (enabled) { r.nextRun = Times.nextFire(r.trigger); Scheduler.schedule(app, r) } else Scheduler.cancel(app, r)
        bumpRecipes()
    }

    fun deleteRecipe(id: String) {
        val r = recipe(id) ?: return
        Scheduler.cancel(app, r)
        liveRecipes = liveRecipes.filterNot { it.id == id }
        bumpRecipes()
    }

    fun updateRecipe(id: String, name: String, trigger: Trigger) {
        val r = recipe(id) ?: return
        r.name = name.ifBlank { r.name }
        r.trigger = trigger
        r.nextRun = Times.nextFire(trigger)
        if (r.enabled) Scheduler.schedule(app, r)
        bumpRecipes()
    }

    /** Called by the scheduler (or "Test run"). Steps are fixed; no planning. */
    fun runRecipe(id: String, fromSchedule: Boolean = false) = scope.launch {
        val r = recipe(id) ?: return@launch
        if (fromSchedule) {
            if (r.trigger.type == "once") { r.enabled = false; r.nextRun = 0 } else { r.nextRun = Times.nextFire(r.trigger); Scheduler.schedule(app, r) }
            bumpRecipes()
            if (_paused.value) return@launch
        }
        val run = Run(newId(), r.command, r.name, System.currentTimeMillis(), r.steps.map { it.fresh() }.toMutableList(), r.id)
        addRun(run)
        tick(run.id)
    }

    fun wipeAll() = scope.launch {
        stepJobs.values.forEach { it.cancel() }
        lock.withLock {
            liveRecipes.forEach { Scheduler.cancel(app, it) }
            live = emptyList(); liveRecipes = emptyList()
            _runs.value = emptyList(); _recipes.value = emptyList(); _tick.value = _tick.value + 1
            Store.wipe()
        }
    }
}

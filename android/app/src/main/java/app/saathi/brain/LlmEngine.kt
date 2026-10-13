package app.saathi.brain

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import app.saathi.agent.Brain
import app.saathi.agent.BrainParser
import app.saathi.agent.TaskRunner
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.PromptTemplates
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The on-device model behind smart understanding: Gemma 3 1B (a MediaPipe `.task` file, about
 * 550 MB) run on the CPU by MediaPipe LLM Inference. Fully offline: the app has no INTERNET
 * permission and the model file is not in the APK. The owner pushes it to the app's own folder:
 *
 *     adb push Gemma3-1B-IT_….task /sdcard/Android/data/app.saathi/files/models/
 *
 * Found at startup ([attach]); loaded lazily on a background thread when a guide session starts
 * ([prewarm]); unloaded under memory pressure, or a minute after Saathi goes to the background
 * when no guide session is running (guide mode itself sends Saathi to the back to act, so it must
 * keep the model then). Each answer: a fresh session (temperature 0.2, topK 5), stopped as soon as
 * one JSON object is complete, given up after the caller's timeout. Never logs prompts or answers.
 */
@SuppressLint("StaticFieldLeak") // holds only the application context
object LlmEngine : Brain {
    private const val TAG = "SaathiBrain"
    const val MODELS_DIR = "models"
    /** Shown in Settings: where the file goes. */
    const val MODELS_PATH = "Android/data/app.saathi/files/models/"

    /** Context window (prompt + answer). The prompt is ~500–800 tokens; the answer stops at one JSON object. */
    const val MAX_TOKENS = 1280
    private const val ANSWER_TOKENS = 256
    /** A JSON answer is ~40 tokens; anything this long is rambling: stop. (~256 tokens) */
    private const val MAX_OUTPUT_CHARS = 900
    const val TOP_K = 5
    const val TEMPERATURE = 0.2f
    private const val TOP_P = 0.9f
    private const val BACKGROUND_UNLOAD_MS = 60_000L
    private const val POLL_MS = 20L
    private const val PREFS = "saathi_brain"
    private const val KEY_ON = "smartUnderstanding"
    private const val KEY_TOO_SLOW = "tooSlow"

    /** Missed the guide's time limit this many times in a row: too slow on this phone. */
    const val SLOW_AFTER_MISSES = 2

    /** Waiting for a stopped answer to wind down before its session may be closed. */
    private const val WIND_DOWN_MS = 60_000L

    sealed interface Status {
        /** No model file in the folder. */
        data object Absent : Status

        /** A file is there but Smart understanding is switched off. */
        data class Off(val file: String) : Status

        /** It answered too slowly on this phone (missed the time limit [SLOW_AFTER_MISSES] times in a row): not loaded. */
        data class TooSlow(val file: String) : Status

        /** A file is there; it loads when a guide session starts. */
        data class Installed(val file: String, val mb: Long) : Status

        data class Loading(val file: String) : Status

        data class Ready(val file: String, val mb: Long, val loadMs: Long) : Status

        data class Failed(val reason: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Absent)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _thinking = MutableStateFlow(false)

    /** True while the model works on an answer (the Guide screen says "Thinking…" and ticks). */
    val thinking: StateFlow<Boolean> = _thinking.asStateFlow()

    fun setThinking(on: Boolean) { _thinking.value = on }

    // One thread owns the engine: load, session create/close and unload never overlap.
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "saathi-llm") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @SuppressLint("StaticFieldLeak") // the application context, never an activity
    @Volatile private var app: Context? = null
    @Volatile private var llm: LlmInference? = null
    @Volatile private var loadedFile: File? = null
    @Volatile private var generating = false
    @Volatile private var unloadWhenIdle = false
    @Volatile private var loadJob: Job? = null
    @Volatile private var backgroundUnload: Job? = null

    /** Debug timing only (DebugCommandReceiver brainbench): load on the CPU even if the GPU works. */
    @Volatile var cpuOnlyForTesting = false

    private fun log(line: String) = Log.i(TAG, line) // sizes, timings and states only

    // -- discovery and settings ------------------------------------------------------------------

    /** Remember the app context, watch memory, and look for a model file. Cheap; safe to call often. */
    fun attach(context: Context) {
        if (app == null) {
            synchronized(this) {
                if (app == null) {
                    app = context.applicationContext
                    context.applicationContext.registerComponentCallbacks(memoryWatch)
                }
            }
        }
        refresh()
    }

    fun folder(context: Context): File? = context.getExternalFilesDir(MODELS_DIR)?.also { if (!it.exists()) it.mkdirs() }

    /** The model file: a `.task` in the folder, Gemma 3 1B first when there are several. */
    fun modelFile(context: Context): File? = folder(context)
        ?.listFiles { f -> f.isFile && f.name.endsWith(".task", ignoreCase = true) && f.length() > 0 }
        ?.sortedWith(compareByDescending<File> { Regex("gemma.?3.*1b", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }.thenBy { it.name })
        ?.firstOrNull()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Settings switch. Default ON (it only matters when a model file is present). */
    fun isOn(context: Context): Boolean = prefs(context).getBoolean(KEY_ON, true)

    fun setOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ON, on).apply()
        if (!on) unload("switched off")
        refresh()
    }

    /**
     * 14 Oct, Galaxy Tab S6 Lite: one answer took ~22 s on the GPU and ~58 s on the CPU, against a
     * 6 s limit, so it never helped, yet it held ~600 MB and the system ran short of memory. After
     * [SLOW_AFTER_MISSES] missed limits in a row it is marked too slow and no longer loaded; the
     * built-in rules answer as before. Settings > "Try again" clears it.
     */
    fun isTooSlow(context: Context): Boolean = prefs(context).getBoolean(KEY_TOO_SLOW, false)

    fun clearTooSlow(context: Context) {
        prefs(context).edit().remove(KEY_TOO_SLOW).apply()
        missedInARow = 0
        refresh()
    }

    @Volatile private var missedInARow = 0

    private fun missedLimit() {
        val ctx = app ?: return
        missedInARow++
        if (missedInARow < SLOW_AFTER_MISSES) return
        prefs(ctx).edit().putBoolean(KEY_TOO_SLOW, true).apply()
        log("too slow on this phone: missed the time limit $missedInARow times in a row; not loading it again")
        unload("too slow on this phone")
        refresh()
    }

    /** Delete all data: the switch goes back to its default. The model file itself stays (said in Settings). */
    fun resetSetting(context: Context) {
        prefs(context).edit().clear().apply()
        refresh()
    }

    /** Recompute the Settings status from the folder (not while loading / loaded). */
    fun refresh() {
        val ctx = app ?: return
        val s = _status.value
        if (s is Status.Loading || (s is Status.Ready && llm != null)) return
        val f = modelFile(ctx)
        _status.value = when {
            f == null -> Status.Absent
            !isOn(ctx) -> Status.Off(f.name)
            isTooSlow(ctx) -> Status.TooSlow(f.name)
            s is Status.Failed && loadedFile == f -> s
            else -> Status.Installed(f.name, f.length() / 1_000_000)
        }
    }

    /** Settings > "Remove AI model" (after a Cancel-first confirmation): unload and delete the file(s). */
    fun removeModel(context: Context): Boolean {
        unload("model removed")
        loadedFile = null
        val files = folder(context)?.listFiles { f -> f.isFile && f.name.endsWith(".task", ignoreCase = true) }.orEmpty()
        val ok = files.all { it.delete() }
        _status.value = Status.Absent
        refresh()
        log("model removed ok=$ok files=${files.size}")
        return ok
    }

    // -- loading ---------------------------------------------------------------------------------

    /** Start loading in the background if it's on, a file is there and nothing is loaded yet. */
    fun prewarm() {
        val ctx = app ?: return
        backgroundUnload?.cancel()
        if (!isOn(ctx) || isTooSlow(ctx) || llm != null || loadJob?.isActive == true) return
        val f = modelFile(ctx) ?: run { refresh(); return }
        loadJob = scope.launch { load(ctx, f) }
    }

    private fun load(ctx: Context, f: File) {
        if (llm != null) return
        _status.value = Status.Loading(f.name)
        val t0 = System.currentTimeMillis()
        try {
            fun options(backend: LlmInference.Backend) = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(f.absolutePath)
                .setMaxTokens(MAX_TOKENS)
                .setMaxTopK(TOP_K)
                .setPreferredBackend(backend)
                .build()
            // The GPU reads the prompt several times faster (14 Oct, Tab S6 Lite: the CPU missed the
            // 6 s limit); not every phone can run it, so the CPU is the fallback.
            var backend = if (cpuOnlyForTesting) LlmInference.Backend.CPU else LlmInference.Backend.GPU
            llm = try {
                LlmInference.createFromOptions(ctx, options(backend))
            } catch (t: Throwable) {
                if (t is OutOfMemoryError) throw t
                Log.w(TAG, "GPU load failed (${t::class.simpleName}); using the CPU")
                backend = LlmInference.Backend.CPU
                LlmInference.createFromOptions(ctx, options(backend))
            }
            loadedFile = f
            val ms = System.currentTimeMillis() - t0
            _status.value = Status.Ready(f.name, f.length() / 1_000_000, ms)
            log("model loaded in $ms ms (${f.length() / 1_000_000} MB, $backend)")
            if (!isOn(ctx)) unloadNow("switched off while loading")
        } catch (t: Throwable) {
            llm = null
            loadedFile = f
            val reason = when (t) {
                is OutOfMemoryError -> "not enough memory"
                else -> (t.message ?: t::class.simpleName ?: "error").lineSequence().first().take(120)
            }
            _status.value = Status.Failed(reason)
            Log.w(TAG, "model load failed: ${t::class.simpleName}")
        }
    }

    /** Debug timing only: close the engine so the next [prewarm] loads it again. */
    fun unloadForTesting() = unload("reload for a timing test")

    /** Close the engine (now, or when the answer in progress ends). */
    fun unload(reason: String) {
        if (llm == null) return
        if (generating) { unloadWhenIdle = true; return }
        scope.launch { unloadNow(reason) }
    }

    private fun unloadNow(reason: String) {
        val e = llm ?: return
        if (generating) { unloadWhenIdle = true; return }
        llm = null
        unloadWhenIdle = false
        runCatching { e.close() }
        log("model unloaded: $reason")
        _status.value = Status.Absent
        refresh()
    }

    // -- answering -------------------------------------------------------------------------------

    override val ready: Boolean get() = llm != null && app?.let(::isOn) == true

    @Volatile private var current: LlmInferenceSession? = null
    @Volatile private var stopRequested = false

    /**
     * One answer. The engine thread does all the blocking work (session, prefill, generation); the
     * caller only waits, so its timeout always holds even if the prefill is slow. On timeout or
     * cancellation the engine is told to stop and the session is closed once it has.
     */
    override suspend fun complete(prompt: String, timeoutMs: Long): String? {
        if (!ready || generating) return null // one answer at a time (a stopped one may still be winding down)
        backgroundUnload?.cancel()
        val answer = CompletableDeferred<String?>()
        stopRequested = false
        generating = true
        val t0 = System.currentTimeMillis()
        scope.launch { generate(prompt, answer) }
        try {
            val text = withTimeoutOrNull(timeoutMs) { answer.await() }
            val timedOut = !answer.isCompleted
            log(if (text == null) "no answer within $timeoutMs ms" else "answered in ${System.currentTimeMillis() - t0} ms (${text.length} chars)")
            if (timedOut) {
                stop()
                missedLimit()
            } else if (text != null) {
                missedInARow = 0
            }
            return text
        } finally {
            if (!answer.isCompleted) stop()
        }
    }

    private fun stop() {
        stopRequested = true
        runCatching { current?.cancelGenerateResponseAsync() }
    }

    private fun sessionOptions() = LlmInferenceSession.LlmInferenceSessionOptions.builder()
        .setTopK(TOP_K)
        .setTopP(TOP_P)
        .setTemperature(TEMPERATURE)
        .setRandomSeed(7)
        // The prompt already carries Gemma's turn markers (BrainPrompt): no second template.
        .setPromptTemplates(PromptTemplates.builder().build())
        .build()

    /** Engine thread only. Completes [answer] with the text, or null (too long, stopped, error). */
    private fun generate(prompt: String, answer: CompletableDeferred<String?>) {
        var session: LlmInferenceSession? = null
        var future: java.util.concurrent.Future<*>? = null
        try {
            val engine = llm ?: return
            session = LlmInferenceSession.createFromOptions(engine, sessionOptions())
            current = session
            if (session.sizeInTokens(prompt) > MAX_TOKENS - ANSWER_TOKENS) { log("prompt too long"); return }
            if (stopRequested) return
            session.addQueryChunk(prompt)
            if (stopRequested) return
            val out = StringBuilder()
            val running = session.generateResponseAsync { partial, done ->
                val text = synchronized(out) { out.append(partial); out.toString() }
                // Stop as soon as one JSON object is complete (a small answer: ~40 tokens), or it rambles.
                if (done || BrainParser.looksComplete(text) || text.length > MAX_OUTPUT_CHARS) answer.complete(text)
            }
            future = running
            while (!running.isDone) {
                if (answer.isCompleted || stopRequested) {
                    runCatching { session.cancelGenerateResponseAsync() }
                    break
                }
                Thread.sleep(POLL_MS)
            }
            // 14 Oct crash (SIGABRT "invalid global reference" in the engine): the session was closed
            // while the engine still called back into it. Wait until it has really finished.
            runCatching { running.get(WIND_DOWN_MS, TimeUnit.MILLISECONDS) }
            if (!answer.isCompleted) answer.complete(runCatching { running.get(0, TimeUnit.MILLISECONDS) }.getOrNull())
        } catch (t: Throwable) {
            Log.w(TAG, "generate failed: ${t::class.simpleName}")
        } finally {
            answer.complete(null) // no-op when already answered
            current = null
            // Never close a session the engine is still using (it would crash the whole app); a
            // session stuck past the wind-down is left open, and the engine counts as busy.
            val finished = future?.isDone != false
            if (finished) runCatching { session?.close() } else Log.w(TAG, "answer still running after ${WIND_DOWN_MS} ms; session left open")
            generating = !finished
            if (unloadWhenIdle) unloadNow("memory (after answer)")
        }
    }

    // -- memory ----------------------------------------------------------------------------------

    private val memoryWatch = object : ComponentCallbacks2 {
        @Suppress("DEPRECATION")
        override fun onTrimMemory(level: Int) {
            when {
                // The system is about to kill apps: free ~600 MB now (or right after the answer).
                level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> unload("memory critical (level $level)")
                // Saathi's screen went away: guide mode does that on purpose to act in the other app.
                level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> scheduleBackgroundUnload()
                level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> if (!TaskRunner.isGuiding) unload("memory low")
            }
        }

        override fun onLowMemory() = unload("low memory")

        override fun onConfigurationChanged(newConfig: Configuration) = Unit
    }

    private fun scheduleBackgroundUnload() {
        if (llm == null) return
        backgroundUnload?.cancel()
        backgroundUnload = scope.launch {
            delay(BACKGROUND_UNLOAD_MS)
            if (!TaskRunner.isGuiding) unloadNow("in background")
        }
    }
}

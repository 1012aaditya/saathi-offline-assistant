package app.saathi.agent

import android.content.Context
import android.content.Intent
import android.util.Log
import app.saathi.MainActivity
import app.saathi.data.AppState
import app.saathi.data.Outcome as HistoryOutcome
import app.saathi.safety.Answer
import app.saathi.safety.Apps
import app.saathi.safety.Screen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives one offline task: snapshot -> plan -> gate -> execute -> wait for change -> repeat
 * ([AgentLoop] over [DeviceBridge]). Exposes narration lines and the result as a StateFlow for
 * the Working / Confirm / Result / Handover screens. [stop] cancels at once.
 */
object TaskRunner {
    private const val TAG = "SaathiTask"
    private const val CAPTURE_MAX_AGE_MS = 5 * 60_000L

    /** The other app's screen, read when Volume-down was held there (for "what's on screen"). */
    data class Captured(val screen: Screen, val label: String, val atMillis: Long)

    sealed interface State {
        val runId: Int

        data object Idle : State { override val runId: Int get() = 0 }

        data class Running(override val runId: Int, val command: Command, val title: String, val app: String, val lines: List<String>) : State

        data class Confirming(override val runId: Int, val command: Command, val title: String, val request: ConfirmRequest, val lines: List<String>) : State

        /** The chat's box already holds the user's own text: keep / replace / add / send it? */
        data class Choosing(override val runId: Int, val command: Command, val title: String, val request: DraftChoiceRequest, val lines: List<String>) : State

        /** Several people match the name: which one? (names verbatim from WhatsApp's screen) */
        data class Picking(override val runId: Int, val command: Command, val title: String, val request: ContactChoiceRequest, val lines: List<String>) : State

        /** Grocery: which product / size? (options verbatim from Blinkit's screen) */
        data class ChoosingItem(override val runId: Int, val command: Command, val title: String, val request: PickRequest, val lines: List<String>) : State

        data class Finished(override val runId: Int, val command: Command?, val title: String, val outcome: Outcome) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val session = Session()

    @Volatile
    var captured: Captured? = null

    /** A task command waiting for the Working screen to start it (set by [Requests]). */
    @Volatile
    var pending: Command? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    @Volatile private var cleanupJob: Job? = null // removing an earlier draft after a reconnect
    @Volatile private var answer: CompletableDeferred<Answer>? = null
    @Volatile private var choice: CompletableDeferred<DraftChoice>? = null
    @Volatile private var itemPick: CompletableDeferred<Int?>? = null
    @Volatile private var picked: CompletableDeferred<String?>? = null
    private var runCounter = 0

    /** Why a running task was stopped (decides who says what afterwards). */
    enum class StopReason { USER, TALK_KEY, SERVICE_LOST }

    @Volatile private var stopReason = StopReason.USER

    /** The last Stop came from the accessibility service going away (screens show / say the result). */
    val stoppedByServiceLoss: Boolean get() = stopReason == StopReason.SERVICE_LOST

    /** The service came back while a job was still finishing: clean pending drafts after it. */
    @Volatile private var reconnectedWhileBusy = false

    /** Last UI speaker and language (for telling the user about a draft removed later). */
    @Volatile private var speaker: ((String) -> Unit)? = null
    @Volatile private var lastLang: Lang = Lang.EN

    @Volatile private var store: PrefsDraftStore? = null

    /** Typed-but-unsent drafts, remembered on the phone until removed (no message text). */
    fun drafts(context: Context): PrefsDraftStore =
        store ?: synchronized(this) { store ?: PrefsDraftStore(context).also { store = it } }

    private fun log(line: String) = Log.i(TAG, line) // callers pass lengths / booleans only

    val isRunning: Boolean get() = job?.isActive == true

    /** The command whose result is showing (for "Reply" / "Repeat" follow-ups). */
    val lastCommand: Command? get() = (_state.value as? State.Finished)?.command

    fun title(command: Command): String = when (command) {
        is Command.ReadLast -> "Read ${command.contact ?: "a"}'s last message"
        is Command.Reply -> if (command.contact == Names.SELF) "Message yourself" else "Reply to ${command.contact ?: "someone"}"
        is Command.UnreadSummary -> "Who has new messages"
        is Command.ScreenSummary -> "What's on screen"
        is Command.OpenApp -> "Open ${appName(command.packageName)}"
        is Command.LaunchApp -> "Open ${command.name}"
        is Command.Help -> "Help"
        is GroceryCommand -> OrderTexts(Lang.EN).title(command)
        else -> "Couldn't do: ${command.utterance.trim().take(40)}"
    }

    fun appName(pkg: String): String = when (pkg) {
        Apps.WHATSAPP -> "WhatsApp"
        Apps.GPAY -> "Google Pay"
        Apps.BLINKIT -> "Blinkit"
        Apps.SETTINGS_APP -> "Settings"
        else -> SaathiAccessibilityService.running?.appLabel(pkg) ?: "the app"
    }

    /** Show an answer without operating the phone (help, unsupported, a missing part). */
    fun showResult(command: Command?, outcome: Outcome, appState: AppState? = null) {
        val title = command?.let(::title) ?: ""
        _state.value = State.Finished(++runCounter, command, title, outcome)
        if (appState != null && command != null) record(appState, command, outcome)
    }

    /**
     * Start [command]. False when a task is already running or the service is off.
     * [speak] is called on the main thread for each narration line.
     */
    fun start(context: Context, command: Command, lang: Lang, appState: AppState, speak: (String) -> Unit): Boolean {
        if (isRunning) return false
        val device = DeviceBridge.gated() ?: return false
        val app = context.applicationContext
        val t = Texts(lang)
        val cap = captured?.takeIf { System.currentTimeMillis() - it.atMillis < CAPTURE_MAX_AGE_MS }
        // Phone contacts (only if the user allowed them): display names, memory only, never logged.
        val book = if (command is Command.Reply || command is Command.ReadLast) PhoneBook.names(app) else emptyList()
        // Blinkit: Settings (Grocery app level, Allow placing orders, spending limit) decide first.
        val grocery = (command as? GroceryCommand)?.let { GroceryTasks.prepare(it, appState, device.gate, lang) }
        grocery?.early?.let { early ->
            showResult(command, early, appState.takeIf { early is Outcome.Failed })
            return true
        }
        val flow = grocery?.flow ?: Flows.forCommand(command, t, device.gate, cap?.screen, cap?.label.orEmpty(), ::appName, phoneBook = book)
        if (flow == null) {
            showResult(command, Outcome.Asked(Flows.missing(command, t) ?: t.unsupported))
            return true
        }
        session.remember(command)
        val id = ++runCounter
        val title = title(command)
        val appLabel = when (command) {
            is Command.OpenApp -> appName(command.packageName)
            is Command.ScreenSummary -> cap?.label ?: "Saathi"
            is GroceryCommand -> "Blinkit"
            else -> "WhatsApp"
        }
        _state.value = State.Running(id, command, title, appLabel, emptyList())
        Log.i(TAG, "start ${command::class.simpleName}")
        speaker = speak
        lastLang = lang
        stopReason = StopReason.USER
        val loop = AgentLoop(device, t, app.packageName, drafts = drafts(app), log = ::log)

        val earlierCleanup = cleanupJob
        val previous = job // a stopped task may still be removing its draft (NonCancellable)
        job = scope.launch {
            previous?.join() // never two device drivers at once
            earlierCleanup?.join()
            val outcome: Outcome = try {
                loop.run(
                    flow,
                    grocery?.ctx ?: Flows.contextFor(command, book),
                    say = { line ->
                        addLine(id, line)
                        withContext(Dispatchers.Main) { speak(line) }
                    },
                    choose = { req -> awaitChoice(app, id, command, title, req) },
                    pick = { req -> awaitPick(app, id, command, title, req) },
                    pickOption = { req -> awaitItemPick(app, id, command, title, req) },
                    confirm = { req -> awaitAnswer(app, id, command, title, req) },
                )
            } catch (e: CancellationException) {
                // The loop already tried to remove a typed draft (NonCancellable). Say what happened
                // to it; if it couldn't be removed it is remembered and removed on reconnect.
                withContext(NonCancellable) {
                    val reason = stopReason
                    val note = loop.lastNote
                    val base = if (reason == StopReason.SERVICE_LOST) t.stoppedServiceLost else t.stopped
                    // Grocery: say what is left in the cart (never leave silent changes).
                    val stopped = Outcome.Stopped(base).withNote(note).withNote((flow as? OrderAware)?.endNote(Outcome.Stopped(base)))
                    log("stopped reason=$reason draftCleanup=${loop.lastCleanup} pendingDrafts=${drafts(app).all().size}")
                    // Working's Stop already said "Stopped": add what happened to the typed text.
                    // Volume-down: the user is about to talk (a leftover is removed and said before
                    // the next task). Service lost: the Confirm / Result screen says it all.
                    if (reason == StopReason.USER && note != null) withContext(Dispatchers.Main) { speak(note) }
                    // Removing a typed draft may have brought WhatsApp forward: come back to Saathi either way.
                    finish(app, id, command, title, stopped, appState, front = true)
                    afterJob(app)
                }
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "task error: ${e::class.simpleName} draftCleanup=${loop.lastCleanup}") // no screen text in logs
                Outcome.Failed(t.stuck).withNote(loop.lastNote)
            }
            // "Open WhatsApp" leaves the user in that app; everything else comes back to Saathi.
            val front = !(command is Command.OpenApp && outcome is Outcome.Answered)
            if (!front) withContext(Dispatchers.Main) { speak(outcome.message) }
            finish(app, id, command, title, outcome, appState, front)
            afterJob(app)
        }
        return true
    }

    /** The accessibility service is going away (unbind / destroy): stop; a typed draft stays recorded. */
    fun onServiceLost() {
        if (job?.isActive == true) {
            stopReason = StopReason.SERVICE_LOST
            log("service lost during a task: stopping (draft record kept if any)")
            job?.cancel()
        }
        cleanupJob?.cancel()
    }

    /**
     * The service (re)connected: remove drafts an earlier run couldn't (service restart, process
     * death). Only when Saathi or WhatsApp is in front, so the user is never pulled out of
     * something else; otherwise it waits for the next task, which removes them first.
     */
    fun onServiceConnected(context: Context) {
        val app = context.applicationContext
        val pending = drafts(app).all().size
        log("service connected pendingDrafts=$pending running=$isRunning")
        if (pending == 0) return
        if (job?.isCompleted == false || cleanupJob?.isCompleted == false) { reconnectedWhileBusy = true; return }
        cleanPendingLater(app)
    }

    private fun afterJob(context: Context) {
        if (reconnectedWhileBusy) {
            reconnectedWhileBusy = false
            if (drafts(context).all().isNotEmpty()) cleanPendingLater(context)
        }
    }

    private fun cleanPendingLater(app: Context) {
        cleanupJob = scope.launch {
            try {
                cleanPendingNow(app)
            } finally {
                withContext(NonCancellable) { if (job?.isCompleted != false) afterJob(app) }
            }
        }
    }

    private suspend fun cleanPendingNow(app: Context) {
        delay(1_500) // let the service settle (windows readable)
        val device = DeviceBridge.gated() ?: return
        val front = device.snapshot()?.packageName
        if (front != app.packageName && front != Apps.WHATSAPP) {
            log("pending draft cleanup deferred: ${if (front == null) "screen unreadable" else "another app is in front"}")
            return
        }
        val t = Texts(lastLang)
        val r = AgentLoop(device, t, app.packageName, drafts = drafts(app), log = ::log).cleanPending() ?: return
        log("pending draft cleanup results=${r.results}")
        if (front == app.packageName) bringSaathiToFront(app)
        val note = r.note ?: return
        (_state.value as? State.Finished)?.let { f -> _state.value = f.copy(outcome = f.outcome.withNote(note)) }
        speaker?.let { s -> withContext(Dispatchers.Main) { runCatching { s(note) } } }
    }

    /**
     * The user's answer on the Confirm screen. Only [Answer.YES] can send. [source] says where
     * it came from ("tap", "voice", "cancel", "left the screen", "debug" …); it is logged so any
     * answer can be traced (never the message text).
     */
    fun answer(a: Answer, source: String = "unknown") {
        log("confirm answer $a from $source (waiting=${answer != null})")
        val s = _state.value
        if (s is State.Confirming && a == Answer.YES) {
            _state.value = State.Running(s.runId, s.command, s.title, if (s.command is GroceryCommand) "Blinkit" else "WhatsApp", s.lines)
        }
        answer?.complete(a)
    }

    /** The user's answer on the Draft choice sheet. KEEP (the default) ends the task untouched. [source] as in [answer]. */
    fun choose(c: DraftChoice, source: String = "unknown") {
        log("draft choice $c from $source (waiting=${choice != null})")
        val s = _state.value
        if (s is State.Choosing && c != DraftChoice.KEEP) {
            _state.value = State.Running(s.runId, s.command, s.title, "WhatsApp", s.lines)
        }
        choice?.complete(c)
    }

    /** Stop now: the loop ends and removes a draft it typed; nothing else runs. */
    fun stop(reason: StopReason = StopReason.USER) {
        if (job?.isActive == true) {
            stopReason = reason
            log("stop requested reason=$reason")
        }
        job?.cancel() // a pending confirmation is cancelled with it (never answered yes)
        cleanupJob?.cancel() // an unfinished later cleanup keeps its record and retries
    }

    private suspend fun awaitAnswer(context: Context, id: Int, command: Command, title: String, req: ConfirmRequest): Answer {
        val d = CompletableDeferred<Answer>()
        answer = d
        val lines = (_state.value as? State.Running)?.lines.orEmpty()
        _state.value = State.Confirming(id, command, title, req, lines)
        bringSaathiToFront(context)
        return try {
            d.await().also { log("confirm answered yes=${it == Answer.YES}") }
        } finally {
            answer = null
        }
    }

    /** The user's pick on the Which-one screen: one of the names, or null (Cancel, the default). [source] as in [answer]. */
    fun pick(name: String?, source: String = "unknown") {
        log("contact pick ${if (name == null) "none" else "one"} from $source (waiting=${picked != null})") // never the name
        val s = _state.value
        if (s is State.Picking && name != null) {
            _state.value = State.Running(s.runId, s.command, s.title, "WhatsApp", s.lines)
        }
        picked?.complete(name)
    }

    private suspend fun awaitPick(context: Context, id: Int, command: Command, title: String, req: ContactChoiceRequest): String? {
        val d = CompletableDeferred<String?>()
        picked = d
        val lines = (_state.value as? State.Running)?.lines.orEmpty()
        _state.value = State.Picking(id, command, title, req, lines)
        bringSaathiToFront(context)
        return try {
            d.await()
        } finally {
            picked = null
        }
    }

    /** Grocery "which one?": the option's index, or null (Cancel, the default). [source] as in [answer]. */
    fun pickItem(index: Int?, source: String = "unknown") {
        log("item pick ${index ?: "none"} from $source (waiting=${itemPick != null})")
        val s = _state.value
        if (s is State.ChoosingItem && index != null) {
            _state.value = State.Running(s.runId, s.command, s.title, "Blinkit", s.lines)
        }
        itemPick?.complete(index)
    }

    private suspend fun awaitItemPick(context: Context, id: Int, command: Command, title: String, req: PickRequest): Int? {
        val d = CompletableDeferred<Int?>()
        itemPick = d
        val lines = (_state.value as? State.Running)?.lines.orEmpty()
        _state.value = State.ChoosingItem(id, command, title, req, lines)
        bringSaathiToFront(context)
        return try {
            d.await()
        } finally {
            itemPick = null
        }
    }

    private suspend fun awaitChoice(context: Context, id: Int, command: Command, title: String, req: DraftChoiceRequest): DraftChoice {
        val d = CompletableDeferred<DraftChoice>()
        choice = d
        val lines = (_state.value as? State.Running)?.lines.orEmpty()
        _state.value = State.Choosing(id, command, title, req, lines)
        bringSaathiToFront(context)
        return try {
            d.await().also { log("draft choice answered: $it") } // the choice only, never text
        } finally {
            choice = null
        }
    }

    private fun addLine(id: Int, line: String) {
        val s = _state.value
        if (s is State.Running && s.runId == id) _state.value = s.copy(lines = s.lines + line)
    }

    private suspend fun finish(context: Context, id: Int, command: Command, title: String, outcome: Outcome, appState: AppState, front: Boolean) {
        Log.i(TAG, "finished ${outcome::class.simpleName}")
        _state.value = State.Finished(id, command, title, outcome)
        withContext(Dispatchers.Main) { record(appState, command, outcome) }
        if (front) bringSaathiToFront(context)
    }

    private fun record(appState: AppState, command: Command, outcome: Outcome) {
        val o = when (outcome) {
            is Outcome.Answered -> HistoryOutcome.Done
            is Outcome.Cancelled, is Outcome.Stopped ->
                if (command is GroceryCommand) HistoryOutcome.StoppedBeforeOrder else HistoryOutcome.Cancelled
            is Outcome.HandedOver -> HistoryOutcome.HandedOver
            is Outcome.Asked, is Outcome.Failed -> HistoryOutcome.Failed
        }
        appState.record(title(command), o) // titles name the contact, never the message text
    }

    fun bringSaathiToFront(context: Context) {
        val i = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { context.startActivity(i) }
    }
}

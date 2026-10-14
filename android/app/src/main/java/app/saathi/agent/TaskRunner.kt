package app.saathi.agent

import app.saathi.brain.LaptopBrain
import app.saathi.brain.LaptopVision
import app.saathi.brain.ScreenPixels
import android.content.Context
import android.content.Intent
import android.util.Log
import app.saathi.MainActivity
import app.saathi.brain.BrainHooks
import app.saathi.data.AppLevel
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withTimeoutOrNull
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

        /** Guide mode ("let's do it together"): the conversation so far, newest last. Waiting for the user. */
        data class Guiding(override val runId: Int, val command: Command, val title: String, val app: String, val turns: List<GuideLine>) : State
    }

    /**
     * One line of the guide conversation: Saathi's ([fromUser] false) or what the user said.
     * [hint]: shown under Saathi's line, never spoken ("Say “read more” to hear the rest.").
     */
    data class GuideLine(val fromUser: Boolean, val text: String, val kind: GuideTurn.Kind? = null, val hint: String? = null)

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
        is Command.Guide -> "Do it together"
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
    fun start(context: Context, command: Command, lang: Lang, appState: AppState, floating: Boolean = false, speak: (String) -> Unit): Boolean {
        if (isRunning && isFloatingGuiding) {
            // A new request from Saathi's own screens ends the floating session first (never two drivers).
            val previous = job
            stop()
            scope.launch {
                previous?.join()
                withContext(Dispatchers.Main) { start(context, command, lang, appState, floating, speak) }
            }
            return true
        }
        if (isRunning) return false
        val device = DeviceBridge.gated() ?: return false
        if (command is Command.Guide) return startGuide(context, command, lang, appState, speak)
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
        // Started from the bubble's panel: progress and the Confirm / "which one?" questions stay
        // on the panel over the app; Saathi's screen isn't brought forward (see [floatingRun]).
        if (floating) floatingRun = id
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
            // Stuck on another app's screen: carry on together (guide mode) instead of just failing.
            val offer = (outcome as? Outcome.Failed)?.offer
            if (offer != null && offer.packageName != app.packageName) {
                log("flow handed off to guide mode")
                val guided = runGuide(app, id, command, title, offer.packageName, offer.intro ?: GuideTexts(lang).notSure, null, lang, appState)
                finish(app, id, command, title, guided, appState, front = true)
                afterJob(app)
                return@launch
            }
            // "Open WhatsApp" leaves the user in that app; everything else comes back to Saathi.
            val front = !(command is Command.OpenApp && outcome is Outcome.Answered)
            if (!front) withContext(Dispatchers.Main) { speak(outcome.message) }
            finish(app, id, command, title, outcome, appState, front)
            afterJob(app)
        }
        return true
    }

    // -- guide mode ("let's do it together") --------------------------------------------------------

    @Volatile private var inbox: Channel<Heard>? = null
    @Volatile private var guideTurns: List<GuideLine> = emptyList()

    /** A guide session is running (Volume-down then means "talk to the guide", not "stop"). */
    val isGuiding: Boolean get() = inbox != null && isRunning

    /** The run of the guide session now running (0: none). */
    @Volatile private var guideRun = 0

    /**
     * The run driven from Floating Saathi (the overlay), 0 if none. Such a run never brings
     * Saathi's own screen forward: the app being worked on stays in front the whole time, and its
     * questions (Confirm, "which one?") are asked on the floating panel.
     */
    @Volatile private var floatingRun = 0

    /** A job said on the bubble's home panel for the next guide session to do right away. */
    @Volatile var pendingGoal: String? = null

    /** The app the running guide session is about (the last one it looked at). */
    @Volatile var guideTarget: String? = null
        private set

    /** A guide run ended because the floating panel started another: it ends quietly, in place. */
    @Volatile private var supersededRun = 0

    /** [runId] was a Guide-screen session ended by a floating one (its screens let go quietly). */
    fun wasSuperseded(runId: Int): Boolean = runId != 0 && runId == supersededRun

    /** True if [runId] is (or was) driven from the floating panel. */
    fun isFloatingRun(runId: Int): Boolean = runId != 0 && runId == floatingRun

    /** A guide session is running and the floating panel drives it. */
    val isFloatingGuiding: Boolean get() = isGuiding && guideRun != 0 && guideRun == floatingRun

    /** A guide session is running on Saathi's own Guide screen (the older way). */
    val isAppGuiding: Boolean get() = isGuiding && !isFloatingGuiding

    /**
     * Floating Saathi: a guide session ON THE APP IN FRONT ([target]), driven from the overlay. No
     * app is switched and Saathi's activity is never brought forward. Any running guide session is
     * ended first (its job is awaited: never two drivers). False when the service is off or a
     * scripted task is running (the panel shows that task and offers Stop instead).
     */
    fun startFloatingGuide(context: Context, target: String, lang: Lang, appState: AppState, speak: (String) -> Unit): Boolean {
        if (DeviceBridge.gated() == null) return false
        if (isRunning && !isGuiding) return false
        val app = context.applicationContext
        if (target == app.packageName) return false
        if (isRunning) {
            // The session being replaced must not pull Saathi's screen forward as it ends.
            supersededRun = guideRun
            stop()
        }
        val command = Command.Guide(null, "floating")
        val id = ++runCounter
        val title = title(command)
        floatingRun = id
        guideTarget = target
        speaker = speak
        lastLang = lang
        val previous = job
        val earlierCleanup = cleanupJob
        Log.i(TAG, "start floating guide")
        job = scope.launch {
            previous?.join() // a stopped session says where it left things first
            earlierCleanup?.join()
            stopReason = StopReason.USER
            _state.value = State.Running(id, command, title, appName(target), emptyList())
            val out = runGuide(app, id, command, title, target, null, null, lang, appState)
            finish(app, id, command, title, out, appState, front = false)
            afterJob(app)
        }
        return true
    }

    /**
     * The user pressed the floating bubble on the app a Guide-screen session is about: the panel
     * takes that session over (same conversation, now without Saathi coming forward to talk).
     */
    fun adoptGuideForFloating(speak: (String) -> Unit): Boolean {
        if (!isAppGuiding) return false
        floatingRun = guideRun
        speaker = speak
        log("guide session moved to the floating panel")
        // Re-publish so the panel picks up the conversation (and the Guide screen lets go of it).
        val s = _state.value
        if (s is State.Guiding) _state.value = s.copy(turns = s.turns.toList())
        return true
    }

    /** A Guide-screen session reached its app: the floating panel takes it from here (see showApp). */
    private fun moveToBubble(id: Int) {
        if (guideRun != id || floatingRun == id) return
        floatingRun = id
        log("guide session moved to the floating panel (app in front)")
        val s = _state.value
        if (s.runId == id) _state.value = when (s) {
            is State.Guiding -> s.copy(turns = s.turns.toList())
            is State.Running -> s.copy(lines = s.lines.toList())
            else -> s
        }
    }

    private val _guideTalk = MutableSharedFlow<Long>(extraBufferCapacity = 1)

    /**
     * Volume-down held while guiding: the Guide screen starts listening (the user's own press).
     * Carries when the key was held (elapsedRealtime), so a late / buffered request never opens
     * the mic after Saathi's next turn ([app.saathi.agent.PushToTalk]).
     */
    val guideTalk: SharedFlow<Long> get() = _guideTalk

    fun requestGuideTalk(at: Long = android.os.SystemClock.elapsedRealtime()) { _guideTalk.tryEmit(at) }

    /**
     * What the user said (or a Repeat / Read more / Stop button, or a debug-build `--es text`
     * command) for the running guide session. [source] is logged, never the words. Nothing here
     * can answer a Confirm sheet: the guide grammar has no yes.
     */
    fun guideCommand(heard: Heard, source: String): Boolean {
        val box = inbox ?: return false
        log("guide command from $source (${heard.text.length} chars)")
        val s = _state.value
        guideTurns = guideTurns + GuideLine(true, heard.text)
        if (s is State.Guiding) _state.value = s.copy(turns = guideTurns)
        return box.trySend(heard).isSuccess
    }

    private fun guidePolicy(state: AppState): GuidePolicy {
        fun access(name: String, default: AppLevel) = when (state.appLevels.firstOrNull { it.first == name }?.second ?: default) {
            AppLevel.Off -> AppAccess.OFF
            AppLevel.ReadOnly -> AppAccess.READ_ONLY
            AppLevel.AskFirst -> AppAccess.ASK_FIRST
        }
        return GuidePolicy(
            grocery = GroceryTasks.policy(state),
            whatsapp = access("WhatsApp", AppLevel.AskFirst),
            payments = access("Payments", AppLevel.Off),
        )
    }

    /**
     * "Let's do it together [in <app>]". The app: the one named (WhatsApp / Blinkit / … opened
     * through the gate; any other app only if the user already allowed Saathi to open it), or the
     * app the user was in when they held Volume-down.
     */
    private fun startGuide(context: Context, command: Command.Guide, lang: Lang, appState: AppState, speak: (String) -> Unit): Boolean {
        val app = context.applicationContext
        val gt = GuideTexts(lang)
        val named = command.app?.lowercase()?.trim()
        var open: String? = null
        var target: String? = null
        when {
            named != null && named in Apps.APP_ALIASES -> open = named
            named != null -> {
                when (val r = AppResolver.resolve(named, AppLauncher.installed(app), app.packageName)) {
                    is AppResolver.Result.One -> if (AppLauncher.decide(app, r.app, appState) == LaunchDecision.Launch) target = r.app.packageName
                    else -> Unit
                }
                if (target == null) {
                    showResult(command, Outcome.Asked(gt.noTarget))
                    return true
                }
            }
            else -> target = captured?.takeIf { System.currentTimeMillis() - it.atMillis < CAPTURE_MAX_AGE_MS }?.screen?.packageName
        }
        if (open == null && target == null) {
            showResult(command, Outcome.Asked(gt.noTarget))
            return true
        }
        val id = ++runCounter
        val title = title(command)
        speaker = speak
        lastLang = lang
        stopReason = StopReason.USER
        _state.value = State.Running(id, command, title, target?.let(::appName) ?: appName(Apps.APP_ALIASES.getValue(open!!)), emptyList())
        Log.i(TAG, "start guide")
        val previous = job
        val earlierCleanup = cleanupJob
        job = scope.launch {
            previous?.join()
            earlierCleanup?.join()
            val out = runGuide(app, id, command, title, target, null, open, lang, appState)
            finish(app, id, command, title, out, appState, front = !isFloatingRun(id))
            afterJob(app)
        }
        return true
    }

    /** One guide session on [target] (or the app [open] names), in the calling job. Never throws except cancellation. */
    private suspend fun runGuide(
        app: Context,
        id: Int,
        command: Command,
        title: String,
        target: String?,
        intro: String?,
        open: String?,
        lang: Lang,
        appState: AppState,
    ): Outcome {
        val device = DeviceBridge.gated() ?: return Outcome.Failed(Texts(lang).serviceOff)
        val box = Channel<Heard>(capacity = 8)
        guideTurns = emptyList()
        guideRun = id
        guideTarget = target
        inbox = box
        val appLabel = target?.let(::appName) ?: open?.let { appName(Apps.APP_ALIASES.getValue(it)) } ?: "Saathi"
        val io = object : GuideIO {
            override suspend fun say(turn: GuideTurn) {
                guideTurns = guideTurns + GuideLine(false, turn.text, turn.kind, turn.hint)
                _state.value = State.Guiding(id, command, title, appLabel, guideTurns)
                speaker?.let { s -> withContext(Dispatchers.Main) { s(turn.text) } }
            }

            override suspend fun next(timeoutMs: Long): Heard? = withTimeoutOrNull(timeoutMs) { box.receive() }

            override suspend fun showApp(packageName: String): Boolean {
                // Floating: never switch apps; the app is in front or it isn't.
                guideTarget = packageName
                if (isFloatingRun(id)) return device.snapshot()?.packageName == packageName
                val ok = bringAppForward(app, packageName, appState)
                // Owner, 14 Oct: "don't open the app again and again, everything via the bubble".
                // Once the app is in front, the floating panel drives the session: Saathi's screen
                // isn't brought forward again (not to talk, not at the end).
                if (ok && appState.floatingSaathi && SaathiAccessibilityService.running != null) moveToBubble(id)
                return ok
            }

            override val floating: Boolean get() = isFloatingRun(id)

            override suspend fun showSaathi() {
                if (isFloatingRun(id)) return // the floating panel is already over the app
                if (device.snapshot()?.packageName == app.packageName) return
                bringSaathiToFront(app)
                repeat(8) {
                    delay(250)
                    if (device.snapshot()?.packageName == app.packageName) return
                }
            }

            override suspend fun confirm(request: ConfirmRequest): Answer = awaitAnswer(app, id, command, title, request)

            override suspend fun pick(request: PickRequest): Int? = awaitItemPick(app, id, command, title, request)

            /**
             * "What does this picture say?" (demo builds, the laptop's vision model). Refused for
             * private apps (LaptopBrain.mayRead), PIN / password screens, a screen that changed, and
             * a protected screen (shows black: Android blanks secure windows in screenshots).
             */
            override suspend fun lookAtScreen(packageName: String, lang: Lang): Look {
                if (!LaptopBrain.usable(app)) return Look.Unavailable
                if (!LaptopBrain.mayRead(packageName)) return Look.NotHere
                val svc = SaathiAccessibilityService.running ?: return Look.Unavailable
                if (svc.frontPackageName() != packageName) return Look.Failed
                if (runCatching { svc.frontIsSensitive() }.getOrDefault(true)) return Look.Protected
                val shot = svc.screenshot() ?: return Look.Failed
                try {
                    if (ScreenPixels.mostlyBlack(shot)) return Look.Protected
                    // Still the same app after the screenshot (nothing else slipped in front).
                    if (svc.frontPackageName() != packageName) return Look.Failed
                    val text = LaptopVision.describe(shot, LaptopVision.Kind.SCREEN, lang) ?: return Look.Unavailable
                    return Look.Described(text)
                } finally {
                    shot.recycle()
                }
            }

            override fun appLabel(packageName: String): String = appName(packageName)
        }
        // [brain hook] Smart understanding (on-device AI): null when off / no model file = rules only.
        val brain = BrainHooks.forGuide(app, lang, ::log)
        // "Do it for me" (demo builds: the laptop / OpenAI) and a job asked for on the bubble's home panel.
        val agent = BrainHooks.agentFor(app, ::log)
        val goal = pendingGoal.also { pendingGoal = null }
        val session = GuideSession(device, lang, io, guidePolicy(appState), target, app.packageName, ::log, brain = brain, agent = agent, goal = goal)
        return try {
            session.run(intro, open)
        } catch (e: CancellationException) {
            // Stop / service lost: say where things are left, and come back to Saathi.
            withContext(NonCancellable) {
                inbox = null
                val base = if (stopReason == StopReason.SERVICE_LOST) Texts(lang).stoppedServiceLost else Texts(lang).stopped
                finish(app, id, command, title, Outcome.Stopped(base).withNote(session.leftNote()), appState, front = !isFloatingRun(id))
            }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "guide error: ${e::class.simpleName}") // no screen text in logs
            Outcome.Failed(Texts(lang).stuck).withNote(session.leftNote())
        } finally {
            inbox = null
            if (guideRun == id) guideRun = 0
            box.close()
        }
    }

    /**
     * Bring the app the guide works in to the front: Saathi steps back (its task goes behind, so the
     * app the user came from shows again, exactly as they left it). If that isn't the app, an app the
     * user allowed Saathi to open is started by its launcher; the safety-list apps are opened by the
     * session itself through the gate (gated OpenApp).
     */
    private suspend fun bringAppForward(app: Context, pkg: String, appState: AppState): Boolean {
        val device = DeviceBridge.gated() ?: return false
        suspend fun inFront(): Boolean {
            repeat(12) {
                if (device.snapshot()?.packageName == pkg) { delay(300); return true }
                delay(250)
            }
            return false
        }
        if (device.snapshot()?.packageName == pkg) return true
        if (withContext(Dispatchers.Main) { MainActivity.sendToBack() } && inFront()) return true
        if (pkg in Apps.APP_ALIASES.values) {
            // The guide session was allowed to use this app when it started (gated OpenApp then):
            // reopen the same app if stepping back showed something else (e.g. the home screen).
            val intent = app.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return false
            if (!withContext(Dispatchers.Main) { runCatching { app.startActivity(intent) }.isSuccess }) return false
            return inFront()
        }
        val launchable = AppLauncher.installed(app).firstOrNull { it.packageName == pkg } ?: return false
        if (!withContext(Dispatchers.Main) { AppLauncher.launch(app, launchable, appState) }) return false
        return inFront()
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
        if (!isFloatingRun(id)) bringSaathiToFront(context) // floating: asked on the panel, over the app
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
        // The leftover-draft and "which contact?" questions are asked on Saathi's own screens only
        // (13 Oct incident sheets; not on the floating panel): a bubble task hands over here.
        if (isFloatingRun(id)) { floatingRun = 0; log("question moved from the bubble to Saathi's screen") }
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
        if (!isFloatingRun(id)) bringSaathiToFront(context) // floating: asked on the panel, over the app
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
        // The leftover-draft and "which contact?" questions are asked on Saathi's own screens only
        // (13 Oct incident sheets; not on the floating panel): a bubble task hands over here.
        if (isFloatingRun(id)) { floatingRun = 0; log("question moved from the bubble to Saathi's screen") }
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
        if (front && !isFloatingRun(id) && id != supersededRun) bringSaathiToFront(context)
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

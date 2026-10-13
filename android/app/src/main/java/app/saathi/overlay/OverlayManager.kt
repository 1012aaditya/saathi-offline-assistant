package app.saathi.overlay

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.saathi.agent.Floating
import app.saathi.agent.Outcome
import app.saathi.agent.Requests
import app.saathi.agent.SaathiAccessibilityService
import app.saathi.agent.TaskRunner
import app.saathi.agent.Texts
import app.saathi.data.AppState
import app.saathi.feedback.Cue
import app.saathi.safety.Bounds
import app.saathi.safety.Point
import app.saathi.ui.Deps
import app.saathi.ui.LocalDeps
import app.saathi.ui.SharedDeps
import app.saathi.ui.screens.FloatingPanel
import app.saathi.ui.theme.SaathiTheme
import app.saathi.ui.theme.ThemeChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Floating Saathi: the bubble and the panel, drawn by the accessibility service as
 * TYPE_ACCESSIBILITY_OVERLAY windows (no "display over other apps" permission). Main thread only.
 *
 *  - Shown when the service runs and Settings > "Floating Saathi" is on (default), over any app
 *    except Saathi itself, the lock screen and system UI; hidden entirely over password fields and
 *    PIN screens ([Floating.visibility]). Hiding the panel while a question is open cancels it.
 *  - A press (bubble tap, or Volume-down held over another app) starts / continues a guide session
 *    ON THE APP IN FRONT ([Floating.press] -> [TaskRunner.startFloatingGuide]); Saathi's own
 *    screen is never brought forward and no app is switched.
 *  - The overlay is never part of what Saathi reads (only TYPE_APPLICATION windows are), and an
 *    injected gesture that would land on it is passed through it ([passThrough]).
 *  - Uses the SAME Voice as Saathi's screens ([SharedDeps]): one speak / listen interlock.
 */
class OverlayManager(private val service: SaathiAccessibilityService) {
    private companion object {
        const val TAG = "SaathiFloating"
        const val BUBBLE_DP = 64
        const val EDGE_DP = 8
        const val CHECK_DELAY_MS = 300L
        const val PASS_THROUGH_SETTLE_MS = 150L

        /** A brief "no app readable" (between two screens) doesn't hide anything: it must last this long. */
        const val NO_APP_GRACE_MS = 1_500L
        const val KEY_RIGHT = "bubble.right"
        const val KEY_Y = "bubble.y"
    }

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = service.getSharedPreferences(AppState.PREFS, Context.MODE_PRIVATE)
    private val own = service.packageName
    private val density get() = service.resources.displayMetrics.density

    private var deps: Deps? = null
    private var stateJob: Job? = null

    private var bubble: BubbleView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var dragStartX = 0
    private var dragStartY = 0

    private var panel: ComposeView? = null
    private var panelOwner: OverlayOwner? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private var panelOpen = false
    private var hiddenByUser = false
    private var front: String? = null
    private var frontSeenAt = 0L
    private var sensitive = false
    private var keyboard = false
    private var checkPending = false
    private var passing = false
    private var endSaidFor = 0
    private var lastWhy: Floating.Why? = null

    /** The floating run the panel was opened for by itself (once; the user may hide it after). */
    private var panelShownFor = 0

    /** The app in front, for the panel's words ("Saathi · WhatsApp"). */
    private val appLabel = mutableStateOf("the app")

    /** A push-to-talk press for the panel (when it was made); the panel consumes it. */
    private val talkAt = MutableStateFlow<Long?>(null)

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AppState.FLOATING_KEY || key == null) main.post { syncEnabled() }
    }

    // -- lifecycle -------------------------------------------------------------------------------------

    fun start() {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        syncEnabled()
    }

    fun stop() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        disable(stopSession = false) // the service is going: TaskRunner stops its job itself
        scope.cancel()
    }

    private fun syncEnabled() {
        val on = AppState.floatingEnabled(service)
        if (on && deps == null) enable() else if (!on && deps != null) disable(stopSession = true)
    }

    private fun enable() {
        hiddenByUser = false // Settings > Floating Saathi off and on again always brings the bubble back
        deps = SharedDeps.acquire(service)
        Log.i(TAG, "on")
        stateJob = scope.launch { TaskRunner.state.collect { onRunnerState(it) } }
        check()
    }

    private fun disable(stopSession: Boolean) {
        if (deps == null) return
        Log.i(TAG, "off")
        if (stopSession && TaskRunner.isFloatingGuiding) TaskRunner.stop()
        stateJob?.cancel()
        stateJob = null
        panelOpen = false
        removePanel()
        removeBubble()
        deps = null
        SharedDeps.release()
    }

    fun onConfigurationChanged() {
        val p = bubbleParams ?: return
        placeBubble(p)
        bubble?.let { runCatching { wm.updateViewLayout(it, p) } }
    }

    // -- what's in front ------------------------------------------------------------------------------

    /** An accessibility event: look again soon (at most every [CHECK_DELAY_MS]). */
    fun onScreenEvent() {
        if (deps == null || checkPending) return
        checkPending = true
        main.postDelayed({ checkPending = false; check() }, CHECK_DELAY_MS)
    }

    private fun check() {
        if (deps == null) return
        val now = SystemClock.uptimeMillis()
        val f = runCatching { service.frontPackageName() }.getOrNull()
        if (f == null && front != null && now - frontSeenAt < NO_APP_GRACE_MS) {
            onScreenEvent() // between two screens: look again before hiding anything
            return
        }
        if (f != null) frontSeenAt = now
        front = f
        sensitive = f != null && f != own && runCatching { service.frontIsSensitive() }.getOrDefault(true) // unreadable = careful
        keyboard = runCatching { service.isKeyboardVisible() }.getOrDefault(false)
        if (f != null && f != own) appLabel.value = service.appLabel(f)
        refresh()
    }

    private fun keyguardLocked(): Boolean =
        (service.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true

    private fun refresh() {
        val shown = Floating.visibility(
            Floating.Inputs(
                enabled = deps != null,
                front = front,
                ownPackage = own,
                keyguardLocked = keyguardLocked(),
                sensitive = sensitive,
                hiddenByUser = hiddenByUser,
                panelOpen = panelOpen,
            ),
        )
        if (shown.why != lastWhy) { lastWhy = shown.why; Log.i(TAG, "bubble ${if (shown.bubble) "shown" else "hidden"}: ${shown.why}") }
        if (shown.bubble) addBubble() else removeBubble()
        if (shown.panel) addPanel() else removePanel()
        panelParams?.let { p ->
            // The keyboard is up (typing in the app): the panel moves to the top so it doesn't cover it.
            val g = (if (keyboard) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
            if (p.gravity != g) {
                p.gravity = g
                panel?.let { runCatching { wm.updateViewLayout(it, p) } }
            }
        }
    }

    // -- presses ---------------------------------------------------------------------------------------

    /**
     * Volume-down held. True when the overlay took it (an app other than Saathi is in front and
     * Floating Saathi is on); false = the old behaviour (Saathi's Listening screen). A running
     * scripted task keeps the old behaviour too (the key stops it).
     */
    fun onTalkKey(): Boolean {
        if (deps == null) return false
        if (TaskRunner.isRunning && !TaskRunner.isGuiding) return false
        check()
        val f = front ?: return false
        if (f == own || f in Floating.SYSTEM_UI || sensitive || keyguardLocked()) return false
        hiddenByUser = false
        press("volume-down", SystemClock.elapsedRealtime())
        return true
    }

    private fun press(source: String, at: Long) {
        val d = deps ?: return
        val f = front
        val usable = f != null && f != own && f !in Floating.SYSTEM_UI && !sensitive && !keyguardLocked()
        val run = when {
            TaskRunner.isFloatingGuiding -> Floating.Run.FLOATING_GUIDE
            TaskRunner.isAppGuiding -> Floating.Run.APP_GUIDE
            TaskRunner.isRunning -> Floating.Run.TASK
            else -> Floating.Run.NONE
        }
        val decision = Floating.press(run, TaskRunner.guideTarget, f, usable)
        Log.i(TAG, "press from $source: $decision") // never screen text
        val speak: (String) -> Unit = { line -> d.voice.speak(line) }
        when (decision) {
            Floating.Press.START, Floating.Press.RESTART -> {
                d.voice.stopSpeaking()
                if (TaskRunner.startFloatingGuide(service, f!!, Requests.lang(d), d.state, speak)) {
                    d.feedback.cue(Cue.Tick)
                    openPanel()
                } else {
                    d.feedback.cue(Cue.Cancelled)
                    d.voice.speak(Texts(Requests.lang(d)).serviceOff)
                }
            }
            Floating.Press.TALK -> { openPanel(); talkAt.value = at }
            Floating.Press.ADOPT -> {
                TaskRunner.adoptGuideForFloating(speak)
                openPanel()
                talkAt.value = at
            }
            Floating.Press.SHOW_BUSY -> openPanel()
            Floating.Press.IGNORE -> d.feedback.cue(Cue.Cancelled)
        }
    }

    private fun openPanel() {
        panelOpen = true
        refresh()
    }

    private fun closePanel() {
        panelOpen = false
        refresh()
    }

    private fun hideBubble() {
        val d = deps ?: return
        hiddenByUser = true
        d.feedback.cue(Cue.Cancelled)
        d.voice.speak("Saathi bubble hidden. Hold Volume-down in any app to bring it back.")
        refresh()
    }

    /** The session moved on: open the panel for its questions; say how it ended (once). */
    private fun onRunnerState(s: TaskRunner.State) {
        val d = deps ?: return
        if (!TaskRunner.isFloatingRun(s.runId)) return
        // A session moved here from Saathi's screen ("guide me in Blinkit"): show its panel once.
        if (panelShownFor != s.runId && s !is TaskRunner.State.Finished) {
            panelShownFor = s.runId
            if (!panelOpen) openPanel()
        }
        when (s) {
            is TaskRunner.State.Confirming, is TaskRunner.State.ChoosingItem -> openPanel()
            is TaskRunner.State.Finished -> if (endSaidFor != s.runId) {
                endSaidFor = s.runId
                val o = s.outcome
                d.feedback.cue(
                    when (o) {
                        is Outcome.HandedOver -> Cue.HandOver
                        is Outcome.Answered -> Cue.Done
                        else -> Cue.Cancelled
                    },
                )
                // A session ended by a new one / Saathi's own screens stays quiet (the next one talks).
                if (o !is Outcome.Stopped || TaskRunner.stoppedByServiceLoss) d.voice.speak(o.message)
                if (o is Outcome.HandedOver) openPanel() // "Your turn" (hidden again over a PIN screen)
            }
            else -> Unit
        }
    }

    // -- never pressing our own windows ------------------------------------------------------------

    /** Screen rectangles of the bubble and the panel (empty when hidden). */
    private fun rects(): List<Bounds> = listOfNotNull(bubble, panel).filter { it.isAttachedToWindow && it.width > 0 }.map { v ->
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        Bounds(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    /**
     * Run an injected gesture along [path]. If it would land on the bubble or the panel, the
     * overlay is made untouchable (and invisible) for the gesture, so the touch reaches the app
     * underneath and never presses Saathi's own buttons. Node clicks don't need this.
     */
    suspend fun <T> passThrough(path: List<Point>, block: suspend () -> T): T {
        val hit = withContext(Dispatchers.Main) { Floating.gestureHitsOverlay(path, rects()) }
        if (!hit) return block()
        withContext(Dispatchers.Main) { setPassing(true) }
        try {
            delay(PASS_THROUGH_SETTLE_MS) // let the input system see the untouchable windows
            return block()
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { setPassing(false) }
        }
    }

    /** Run [block] with the bubble and panel invisible (a screenshot of the app alone). */
    suspend fun <T> hidden(block: suspend () -> T): T {
        withContext(Dispatchers.Main) { setPassing(true) }
        try {
            delay(PASS_THROUGH_SETTLE_MS * 2) // let the screen redraw without them
            return block()
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { setPassing(false) }
        }
    }

    private fun setPassing(on: Boolean) {
        passing = on
        Log.i(TAG, "gesture ${if (on) "passes through" else "done; overlay back"}")
        bubble?.let { v -> bubbleParams?.let { applyPassing(it); runCatching { wm.updateViewLayout(v, it) } } }
        panel?.let { v -> panelParams?.let { applyPassing(it); runCatching { wm.updateViewLayout(v, it) } } }
    }

    private fun applyPassing(p: WindowManager.LayoutParams) {
        p.flags = if (passing) p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        p.alpha = if (passing) 0f else 1f
    }

    // -- windows ------------------------------------------------------------------------------------

    private fun overlayParams(width: Int, height: Int): WindowManager.LayoutParams = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // Never focusable (the app keeps focus and its keyboard); touches outside go to the app.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).also {
        it.title = "Saathi"
        applyPassing(it)
    }

    private fun isDark(theme: ThemeChoice): Boolean = when (theme) {
        ThemeChoice.Dark -> true
        ThemeChoice.Light -> false
        ThemeChoice.System -> (service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            return b.width() to b.height()
        }
        val m = service.resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }

    private fun placeBubble(p: WindowManager.LayoutParams) {
        val (w, h) = screenSize()
        val size = (BUBBLE_DP * density).toInt()
        val gap = (EDGE_DP * density).toInt()
        val right = prefs.getBoolean(KEY_RIGHT, true)
        p.x = if (right) (w - size - gap).coerceAtLeast(0) else gap
        p.y = Floating.clampY((prefs.getFloat(KEY_Y, 0.4f) * h).toInt(), size, h, top = (24 * density).toInt())
    }

    private fun addBubble() {
        if (bubble != null) return
        val d = deps ?: return
        val dark = isDark(d.state.theme)
        // Clarity accent: indigo + white (light), lavender + black (dark).
        val accent = if (dark) 0xFFC9C6FF.toInt() else 0xFF2B22B8.toInt()
        val onAccent = if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        val size = (BUBBLE_DP * density).toInt()
        val p = overlayParams(size, size).also {
            it.gravity = Gravity.TOP or Gravity.START
            placeBubble(it)
        }
        val v = BubbleView(
            service,
            accent,
            onAccent,
            onTap = { press("bubble", SystemClock.elapsedRealtime()) },
            onHide = { hideBubble() },
            onDown = {
                dragStartX = p.x
                dragStartY = p.y
            },
            onDrag = { dx, dy -> moveBubble(dx, dy) },
            onDrop = { dropBubble() },
        )
        runCatching { wm.addView(v, p) }
            .onSuccess { bubble = v; bubbleParams = p }
            .onFailure { Log.w(TAG, "bubble not shown: ${it::class.simpleName}") }
    }

    private fun moveBubble(dx: Int, dy: Int) {
        val bp = bubbleParams ?: return
        val x = dragStartX + dx
        val y = dragStartY + dy
        if (bp.x == x && bp.y == y) return
        bp.x = x
        bp.y = y
        bubble?.let { runCatching { wm.updateViewLayout(it, bp) } }
    }

    private fun dropBubble() {
        val p = bubbleParams ?: return
        val (w, h) = screenSize()
        val size = (BUBBLE_DP * density).toInt()
        val gap = (EDGE_DP * density).toInt()
        p.x = Floating.snapX(p.x, size, w, gap)
        p.y = Floating.clampY(p.y, size, h, top = (24 * density).toInt())
        prefs.edit().putBoolean(KEY_RIGHT, p.x > w / 2 - size).putFloat(KEY_Y, p.y.toFloat() / h).apply()
        bubble?.let { runCatching { wm.updateViewLayout(it, p) } }
    }

    private fun removeBubble() {
        val v = bubble ?: return
        bubble = null
        bubbleParams = null
        runCatching { wm.removeView(v) }
    }

    private fun addPanel() {
        if (panel != null) return
        val d = deps ?: return
        val owner = OverlayOwner().also { it.create() }
        // As wide as the panel itself (full width on a phone, readable width on a tablet): the app
        // beside it stays touchable.
        val p = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).also {
            it.gravity = (if (keyboard) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
        }
        val v = ComposeView(service).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setContent {
                SaathiTheme(d.state.theme) {
                    CompositionLocalProvider(LocalDeps provides d, LocalActivityResultRegistryOwner provides owner) {
                        FloatingPanel(
                            app = appLabel.value,
                            talkAt = talkAt,
                            consumeTalk = { talkAt.value = null },
                            onHide = { closePanel() },
                            onClose = { closePanel() },
                        )
                    }
                }
            }
        }
        runCatching { wm.addView(v, p) }
            .onSuccess {
                panel = v
                panelOwner = owner
                panelParams = p
            }
            .onFailure {
                owner.destroy()
                Log.w(TAG, "panel not shown: ${it::class.simpleName}")
            }
    }

    /** Removing the panel ends its composition: an open question is answered Cancel (never left waiting). */
    private fun removePanel() {
        val v = panel ?: return
        panel = null
        panelParams = null
        runCatching { wm.removeView(v) }
        panelOwner?.destroy()
        panelOwner = null
    }
}

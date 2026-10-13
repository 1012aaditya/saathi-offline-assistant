package app.saathi.agent

import app.saathi.safety.Bounds
import app.saathi.safety.Point

/**
 * Floating Saathi (owner, 14 Oct: "operate everything from a floating icon; it persists on every
 * screen and does conversations and does stuff"). A bubble + a small panel drawn by the
 * accessibility service OVER the app in front, so guide mode never has to bring Saathi's own
 * screen forward and step back again (on the tablet that step back failed: "I couldn't get back
 * to the app").
 *
 * Everything here is pure (JVM-tested); the app's OverlayManager only draws what these decide.
 * Nothing here can act on a phone or answer a question: sessions still run through GuideSession
 * -> GatedDevice, answers still go through ConfirmDialog / PickDialog -> VoiceAnswer.
 */
object Floating {
    // -- when the bubble / panel are shown ----------------------------------------------------------

    /** Why the overlay is (not) showing. Logged, never shown. */
    enum class Why { SHOWN, OFF, HIDDEN_BY_USER, SAATHI_IN_FRONT, LOCKED, SENSITIVE, NO_APP, SYSTEM_UI }

    data class Shown(val bubble: Boolean, val panel: Boolean, val why: Why)

    /**
     * [enabled]: Settings > "Floating Saathi". [front]: the app in front (null = none readable).
     * [sensitive]: the front screen has a password field or is a PIN screen: the overlay hides
     * itself there entirely (the user types secrets alone; nothing of Saathi is drawn over them).
     * [panelOpen]: a floating session (or its last result) is showing.
     */
    data class Inputs(
        val enabled: Boolean,
        val front: String?,
        val ownPackage: String,
        val keyguardLocked: Boolean = false,
        val sensitive: Boolean = false,
        val hiddenByUser: Boolean = false,
        val panelOpen: Boolean = false,
    )

    val SYSTEM_UI: Set<String> = setOf("com.android.systemui")

    fun visibility(i: Inputs): Shown {
        val hidden = { why: Why -> Shown(false, false, why) }
        return when {
            !i.enabled -> hidden(Why.OFF)
            i.keyguardLocked -> hidden(Why.LOCKED)
            i.front == null -> hidden(Why.NO_APP)
            i.front == i.ownPackage -> hidden(Why.SAATHI_IN_FRONT)
            i.front in SYSTEM_UI -> hidden(Why.SYSTEM_UI)
            i.sensitive -> hidden(Why.SENSITIVE)
            i.hiddenByUser -> Shown(bubble = false, panel = i.panelOpen, why = Why.HIDDEN_BY_USER)
            else -> Shown(bubble = true, panel = i.panelOpen, why = Why.SHOWN)
        }
    }

    // -- what a press means (bubble tap, Volume-down held) -----------------------------------------

    /** What Saathi is doing when the user presses. */
    enum class Run {
        /** Nothing running. */
        NONE,

        /** A guide session driven from the floating panel. */
        FLOATING_GUIDE,

        /** A guide session driven from Saathi's own Guide screen (the older way). */
        APP_GUIDE,

        /** A scripted task (reply, order …): the panel only shows it and offers Stop. */
        TASK,

        /** Walk mode from the bubble (camera warnings over any app). */
        WALK,
    }

    enum class Press {
        /**
         * Nothing running: open the home panel ("What shall we do?") and listen once. From there:
         * help me here (guide), walk, what's in front, messages, or anything said.
         */
        HOME,

        /** Walking: show the walk panel (Stop walking, What's in front). */
        SHOW_WALK,

        /** Start a guide session on the app in front (it describes the screen first). */
        START,

        /** Push-to-talk for the session already running on this app. */
        TALK,

        /** Take over the Guide-screen session on this app from the panel, then talk. */
        ADOPT,

        /** The session is about another app: end it and start one on the app in front. */
        RESTART,

        /** A scripted task is running: show it (with Stop), start nothing. */
        SHOW_BUSY,

        /** Nothing usable in front (Saathi itself, a locked or sensitive screen): do nothing. */
        IGNORE,
    }

    fun press(run: Run, sessionApp: String?, front: String?, usable: Boolean): Press = when {
        run == Run.TASK -> Press.SHOW_BUSY
        run == Run.WALK -> Press.SHOW_WALK
        !usable || front == null -> Press.IGNORE
        run == Run.NONE -> Press.HOME
        sessionApp != front -> Press.RESTART
        run == Run.FLOATING_GUIDE -> Press.TALK
        else -> Press.ADOPT
    }

    // -- the panel's phase (status line, which controls are live) ----------------------------------

    enum class Phase {
        /** Session starting (Saathi is reading the screen). */
        STARTING,

        /** Saathi spoke; waiting for the user's push-to-talk. */
        YOUR_MOVE,

        LISTENING,

        /** The on-device AI is working out what the user meant. */
        THINKING,

        /** Acting on the user's command (between the user's line and Saathi's answer). */
        WORKING,

        /** The Confirm question (send / order): Cancel first. */
        CONFIRM,

        /** "Which one?": Cancel first. */
        PICK,

        /** The session ended (its last words show until closed). */
        ENDED,

        /** PIN / CAPTCHA / sign-in: the user carries on alone. */
        YOUR_TURN,

        /** A scripted task is running. */
        BUSY,

        /** Nothing running: "What shall we do?" (Speak + Help me here · Walk · What's in front · Messages). */
        HOME,

        /** Walk mode is on. */
        WALKING,
    }

    /** What the runner is in, as far as the panel cares. */
    sealed interface RunView {
        data object None : RunView
        data object Starting : RunView
        data class Turn(val lastFromUser: Boolean) : RunView
        data object Confirm : RunView
        data object Pick : RunView
        data class Ended(val handedOver: Boolean) : RunView
        data object Busy : RunView
        data object Home : RunView
        data object Walking : RunView
    }

    fun phase(run: RunView, listening: Boolean, thinking: Boolean): Phase = when (run) {
        RunView.Confirm -> Phase.CONFIRM
        RunView.Pick -> Phase.PICK
        is RunView.Ended -> if (run.handedOver) Phase.YOUR_TURN else Phase.ENDED
        RunView.Busy -> Phase.BUSY
        RunView.Walking -> Phase.WALKING
        RunView.Home -> Phase.HOME // listening shows on the panel's own Speak button
        RunView.None, RunView.Starting -> Phase.STARTING
        is RunView.Turn -> when {
            listening -> Phase.LISTENING
            thinking -> Phase.THINKING
            run.lastFromUser -> Phase.WORKING
            else -> Phase.YOUR_MOVE
        }
    }

    /** Speak (push-to-talk for a command) works only while the conversation waits for the user. */
    fun speakEnabled(p: Phase): Boolean = p == Phase.YOUR_MOVE || p == Phase.LISTENING || p == Phase.HOME || p == Phase.WALKING

    // -- never touching the overlay itself -----------------------------------------------------------

    /** Extra pixels around the bubble / panel that count as "on the overlay" (fingers are wide). */
    const val TOUCH_MARGIN_PX = 8

    fun inside(p: Point, r: Bounds, margin: Int = TOUCH_MARGIN_PX): Boolean =
        p.x >= r.x1 - margin && p.x <= r.x2 + margin && p.y >= r.y1 - margin && p.y <= r.y2 + margin

    /**
     * Would an injected gesture along [path] (a tap = one point, a swipe = its points) touch one of
     * the overlay [rects]? Segments are sampled every [stepPx] so a swipe crossing the panel counts.
     * If so, the service lets the gesture pass through the overlay (or refuses it): an injected
     * touch must never press Saathi's own buttons (13 Oct: picks registered with no human tap).
     */
    fun gestureHitsOverlay(path: List<Point>, rects: List<Bounds>, stepPx: Int = 6): Boolean {
        if (path.isEmpty() || rects.isEmpty()) return false
        if (path.size == 1) return rects.any { inside(path[0], it) }
        for ((a, b) in path.zipWithNext()) {
            val len = maxOf(Math.abs(b.x - a.x), Math.abs(b.y - a.y))
            val steps = maxOf(1, len / stepPx)
            for (s in 0..steps) {
                val p = Point(a.x + (b.x - a.x) * s / steps, a.y + (b.y - a.y) * s / steps)
                if (rects.any { inside(p, it) }) return true
            }
        }
        return false
    }

    // -- the bubble's place ---------------------------------------------------------------------------

    /** After a drag the bubble snaps to the nearer side edge. Returns the new left x. */
    fun snapX(x: Int, bubbleWidth: Int, screenWidth: Int, edgeGap: Int = 0): Int {
        val center = x + bubbleWidth / 2
        return if (center < screenWidth / 2) edgeGap else (screenWidth - bubbleWidth - edgeGap).coerceAtLeast(0)
    }

    /** Keep the bubble on screen (between the status bar and the bottom). */
    fun clampY(y: Int, bubbleHeight: Int, screenHeight: Int, top: Int = 0): Int =
        y.coerceIn(top, (screenHeight - bubbleHeight).coerceAtLeast(top))
}

/**
 * Answer sheets ignore a TAP on an acting button (Send, Place order, an option) this soon after
 * they appear: a touch meant for the app underneath (13 Oct). Shared by the full-screen sheets and
 * the floating ones. Cancel is never delayed.
 */
object AnswerArming {
    const val TAP_ARM_MS = 700L

    fun armed(shownAtMs: Long, nowMs: Long): Boolean = nowMs - shownAtMs >= TAP_ARM_MS
}

/**
 * Words the floating panel shows or says (English, like the Clarity screens). None contains a yes
 * word or a position word: an answer must never be found in what Saathi itself shows (tested).
 * Every one of them is also an echo source for the floating answer sheets.
 */
object FloatingTexts {
    const val BUBBLE = "Saathi. Double-tap to talk."
    const val BUBBLE_HIDE = "Hide the Saathi bubble"
    const val PANEL = "Saathi"
    const val SPEAK = "Speak"
    const val SPEAK_DESC = "Speak. Tell Saathi what to do"
    const val STOP_LISTENING = "Stop listening"
    const val REPEAT = "Repeat"
    const val REPEAT_DESC = "Repeat what Saathi said"
    const val MORE = "Read more"
    const val MORE_DESC = "Read more of the screen"
    const val HIDE = "Hide"
    const val HIDE_DESC = "Hide this panel. Saathi keeps the conversation; tap the bubble to come back."
    const val STOP = "Stop"
    const val STOP_DESC = "Stop. Saathi tells you where things are left."
    const val CLOSE = "Close"
    const val CLOSE_DESC = "Close this panel"
    const val YOUR_TURN = "Your turn"
    const val BUSY_STOP_DESC = "Stop this task now"
    const val GETTING_READY = "Getting ready…"
    const val MIC_FROM_APP = "To use the microphone here, open Saathi once and allow it."
    const val LOOKING = "Looking…"
    const val HOME_TITLE = "What shall we do?"
    const val HELP_HERE = "Help me here"
    const val HELP_HERE_DESC = "Help me in this app: Saathi says what it sees and does each step you say."
    const val WALK = "Walk"
    const val WALK_DESC = "Walk mode: the back camera watches ahead and Saathi warns you, over any app."
    const val STOP_WALK = "Stop walking"
    const val STOP_WALK_DESC = "Stop walk mode: the camera turns off."
    const val LOOK = "What's in front"
    const val LOOK_DESC = "Take a picture with the back camera and hear what is in front of you."
    const val MESSAGES = "Read messages"
    const val MESSAGES_DESC = "Hear who sent you new WhatsApp messages."
    const val HOME_HINT = "You can say: help me here, walk, what's in front, read my messages, or message someone."

    /** The floating Confirm sheet's chrome (question and hint are the full-screen sheet's own: [Texts]). */
    const val CONFIRM_TITLE_SEND = "Send this message?"
    const val CONFIRM_EXACT = "Exactly as typed on screen"
    const val CANCEL = "Cancel"
    const val CANCEL_DEFAULT = "Default"
    const val SEND = "Send"
    const val PICK_FOOTNOTE = "Names, sizes and prices exactly as the app shows them. Nothing is ordered without your answer."
    const val PICK_CANCEL_DESC = "Cancel. This is the default. Nothing will be added."
    const val PICK_OPTION_SUFFIX = "Adds it to the cart; I'll still ask before ordering."
    const val ASKING_AGAIN = "Asking again…"
    const val NOT_SURE = "Sorry, I'm not sure."

    fun cancelSendDesc(undoing: String) = "Cancel. This is the default. Nothing will be sent. $undoing"

    fun sendDesc(message: String, to: String) = "Send “$message” to $to. It will really be sent."

    fun status(phase: Floating.Phase, app: String): String = when (phase) {
        Floating.Phase.STARTING -> "Saathi is reading $app."
        Floating.Phase.YOUR_MOVE -> "Your move. Press Speak to tell Saathi what to do."
        Floating.Phase.LISTENING -> "Listening"
        Floating.Phase.THINKING -> "Thinking…"
        Floating.Phase.WORKING -> "Working in $app."
        Floating.Phase.CONFIRM -> "Needs your answer. Cancel is selected."
        Floating.Phase.PICK -> "Needs your answer: which one? Cancel is selected."
        Floating.Phase.ENDED -> "Saathi has finished."
        Floating.Phase.YOUR_TURN -> "Your turn. Saathi has stopped."
        Floating.Phase.BUSY -> "Saathi is working on a task."
        Floating.Phase.HOME -> "What shall we do? Press Speak, or pick one below."
        Floating.Phase.WALKING -> "Walking. Saathi warns you about things ahead."
    }

    /** Everything above (for the no-yes / no-position tests and as echo sources). */
    val all: List<String> = listOf(
        BUBBLE, BUBBLE_HIDE, PANEL, SPEAK, SPEAK_DESC, STOP_LISTENING, REPEAT, REPEAT_DESC, MORE, MORE_DESC, HIDE, HIDE_DESC,
        STOP, STOP_DESC, CLOSE, CLOSE_DESC, YOUR_TURN, BUSY_STOP_DESC, GETTING_READY, MIC_FROM_APP, CONFIRM_TITLE_SEND,
        CONFIRM_EXACT, CANCEL, CANCEL_DEFAULT, SEND, PICK_FOOTNOTE, PICK_CANCEL_DESC, PICK_OPTION_SUFFIX,
        ASKING_AGAIN, NOT_SURE, LOOKING, HOME_TITLE, HELP_HERE, HELP_HERE_DESC, WALK, WALK_DESC, STOP_WALK, STOP_WALK_DESC, LOOK,
        LOOK_DESC, MESSAGES, MESSAGES_DESC, HOME_HINT,
    ) + Floating.Phase.entries.map { status(it, "the app") }
}

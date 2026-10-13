package app.saathi.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.saathi.agent.Command
import app.saathi.agent.EchoGuard
import app.saathi.agent.Floating
import app.saathi.overlay.FloatingHome
import app.saathi.brain.LlmEngine
import app.saathi.agent.FloatingTexts
import app.saathi.agent.GuideTurn
import app.saathi.agent.Heard
import app.saathi.agent.Outcome
import app.saathi.agent.AutoListen
import app.saathi.agent.PushToTalk
import app.saathi.agent.TaskRunner
import app.saathi.feedback.Cue
import app.saathi.ui.LocalDeps
import app.saathi.ui.components.DangerButton
import app.saathi.ui.components.SecondaryButton
import app.saathi.ui.components.StatusLine
import app.saathi.ui.components.ReadableWidth
import app.saathi.ui.theme.Clarity
import kotlinx.coroutines.flow.StateFlow

/**
 * Floating Saathi's panel: a bottom sheet drawn over the app the user is in (never Saathi's own
 * screen), at most ~45 % of the height. Saathi's latest line (large), the user's last command, a
 * big Speak button (push-to-talk), Repeat / Read more, Hide and Stop. When the session asks
 * something, the same Confirm / "which one?" question as the full-screen sheets takes its place
 * ([FloatingConfirmSheet], [FloatingPickSheet]).
 *
 * Push-to-talk by default, exactly like the Guide screen: every start goes through [PushToTalk]; a
 * press (Speak, the bubble, Volume-down held) arms one session; each new Saathi turn disarms it and
 * cancels any listening, so the panel never listens to Saathi's own description (13 Oct incidents;
 * 14 Oct 00:40 "read more"). With Settings > "Listen after Saathi speaks" on ([AutoListen]) the mic
 * also opens once after each turn, when Saathi's speech has completely ended and only while the
 * conversation waits for the user. Each transcript carries its timing to the session. Nothing said here
 * can answer a question: the guide grammar has no yes, and questions use their own sheet.
 *
 * TalkBack: the status line is a polite live region with SHORT phase words ("Your move", "Needs
 * your answer"). Saathi's full lines are spoken once by Saathi's own voice, not again by TalkBack
 * (two voices at once, and TalkBack's speech isn't known to the speak/listen interlock).
 */
/** The bubble opened the home panel ([count] each time); a finished run [hideRun] gives way to it. */
data class HomeOpen(val count: Int, val hideRun: Int)

@Composable
internal fun FloatingPanel(
    app: String,
    home: FloatingHome?,
    walking: StateFlow<Boolean>?,
    homeOpened: StateFlow<HomeOpen>,
    talkAt: StateFlow<Long?>,
    consumeTalk: () -> Unit,
    onHide: () -> Unit,
    onClose: () -> Unit,
) {
    val deps = LocalDeps.current
    val c = Clarity.colors
    val st by TaskRunner.state.collectAsState()
    val thinking by LlmEngine.thinking.collectAsState()

    val floating = TaskRunner.isFloatingRun(st.runId)
    val guiding = (st as? TaskRunner.State.Guiding)?.takeIf { floating }
    val turns = guiding?.turns.orEmpty()
    val lastSaathi = turns.lastOrNull { !it.fromUser }
    val lastUser = turns.lastOrNull { it.fromUser }
    val opened by homeOpened.collectAsState()
    val finished = (st as? TaskRunner.State.Finished)?.takeIf { floating && it.runId != opened.hideRun }
    val isWalking = walking?.collectAsState()?.value == true
    val homeLine = home?.line?.collectAsState()?.value
    val looking = home?.busy?.collectAsState()?.value == true

    // Words said on the panel: to the guide session if one runs here, else to the home panel's router.
    val listener = ScreenParts.rememberCommandListener { heard ->
        when {
            guiding != null -> TaskRunner.guideCommand(heard, "floating voice")
            heard.msAfterSpeech < EchoGuard.MIN_HEARD_AFTER_SPEECH_MS -> home?.say("Sorry, say that again.")
            else -> home?.handle(heard.text)
        }
    }
    val ptt = remember { PushToTalk { SystemClock.elapsedRealtime() } }
    val auto = remember { AutoListen() }

    val run: Floating.RunView = when {
        floating && st is TaskRunner.State.Confirming -> Floating.RunView.Confirm
        floating && st is TaskRunner.State.ChoosingItem -> Floating.RunView.Pick
        finished != null -> Floating.RunView.Ended(finished.outcome is Outcome.HandedOver)
        guiding != null -> Floating.RunView.Turn(lastFromUser = turns.lastOrNull()?.fromUser == true)
        // A guide session getting ready; a bubble task (messages, orders) shows its progress + Stop.
        floating && (st as? TaskRunner.State.Running)?.command is Command.Guide -> Floating.RunView.Starting
        floating && st !is TaskRunner.State.Finished && st !is TaskRunner.State.Running -> Floating.RunView.Starting
        TaskRunner.isRunning -> Floating.RunView.Busy
        isWalking -> Floating.RunView.Walking
        else -> Floating.RunView.Home
    }
    val phase = Floating.phase(run, listener.active, thinking)
    val currentPhase by rememberUpdatedState(phase)

    /** The user's own press (Speak, the bubble, Volume-down at [at]): listen once, or finish listening. */
    fun talk(at: Long = SystemClock.elapsedRealtime()) {
        if (listener.active) { listener.finish(); return }
        if (!Floating.speakEnabled(phase)) return
        auto.pressed()
        ptt.press(at)
        if (ptt.mayListen()) listener.start()
    }

    fun send(text: String, source: String) {
        listener.cancel()
        deps.voice.stopSpeaking()
        TaskRunner.guideCommand(Heard(text), source) // a button: no speech timing to check
    }

    // A new Saathi turn: no listening survives into it, and a press made before it no longer counts.
    LaunchedEffect(turns.size) {
        val last = turns.lastOrNull() ?: return@LaunchedEffect
        if (!last.fromUser) {
            ptt.turnSpoken()
            listener.cancel()
            deps.feedback.cue(if (last.kind == GuideTurn.Kind.REFUSED) Cue.Cancelled else Cue.NeedsAnswer)
            if (auto.afterTurn(deps.state.listenAfterSpeaking, refused = last.kind == GuideTurn.Kind.REFUSED)) {
                ScreenParts.listenAfterSpeech(deps.voice, listener) { Floating.speakEnabled(currentPhase) }
            }
        }
    }
    // The bubble / Volume-down: the user's own push-to-talk (only a fresh press counts).
    LaunchedEffect(Unit) {
        talkAt.collect { at ->
            if (at == null) return@collect
            consumeTalk()
            if (!listener.active) talk(at)
        }
    }
    // The bubble opened the home panel: after "What shall we do?" it listens once (the tap was the press).
    LaunchedEffect(opened.count) {
        if (opened.count > 0 && guiding == null) {
            ScreenParts.listenAfterSpeech(deps.voice, listener) { currentPhase == Floating.Phase.HOME }
        }
    }
    // Saathi answered on the home / walk panel: listen again only if "Listen after Saathi speaks" is on.
    LaunchedEffect(homeLine) {
        if (homeLine != null && deps.state.listenAfterSpeaking && guiding == null) {
            ScreenParts.listenAfterSpeech(deps.voice, listener) { currentPhase == Floating.Phase.HOME || currentPhase == Floating.Phase.WALKING }
        }
    }
    // A question replaces the conversation controls: no command listening under it.
    LaunchedEffect(phase) { if (phase == Floating.Phase.CONFIRM || phase == Floating.Phase.PICK) listener.cancel() }

    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.45f).dp
    val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    // The window wraps this width (up to the readable width): beside it, touches reach the app.
    Box(Modifier.widthIn(max = ReadableWidth).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(c.screen)
                .border(1.dp, c.separator, shape)
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusLine(FloatingTexts.status(phase, app))
            when (phase) {
                Floating.Phase.CONFIRM -> (st as? TaskRunner.State.Confirming)?.let { s ->
                    key(s.runId, s.request) { FloatingConfirmSheet(s) }
                }
                Floating.Phase.PICK -> (st as? TaskRunner.State.ChoosingItem)?.let { s ->
                    key(s.runId, s.request) { FloatingPickSheet(s) }
                }
                Floating.Phase.ENDED, Floating.Phase.YOUR_TURN -> {
                    Text(
                        if (phase == Floating.Phase.YOUR_TURN) FloatingTexts.YOUR_TURN else "${FloatingTexts.PANEL} · $app",
                        style = Clarity.type.callout,
                        color = c.secondary,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(finished?.outcome?.message.orEmpty(), style = Clarity.type.headline.copy(fontWeight = FontWeight.Medium), color = c.label)
                    SecondaryButton(FloatingTexts.CLOSE, onClose, description = FloatingTexts.CLOSE_DESC)
                }
                Floating.Phase.HOME, Floating.Phase.WALKING -> HomeControls(
                    walkingNow = phase == Floating.Phase.WALKING,
                    app = app,
                    line = if (looking) FloatingTexts.LOOKING else homeLine,
                    listener = listener,
                    onTalk = { talk() },
                    home = home,
                    onHide = onHide,
                    onClose = onClose,
                )
                Floating.Phase.BUSY -> {
                    val running = st as? TaskRunner.State.Running
                    Text(running?.title ?: FloatingTexts.status(phase, app), style = Clarity.type.headline, color = c.label, modifier = Modifier.semantics { heading() })
                    running?.lines?.lastOrNull()?.let { Text(it, style = Clarity.type.body, color = c.secondary) }
                    DangerButton(FloatingTexts.STOP, { TaskRunner.stop() }, description = FloatingTexts.BUSY_STOP_DESC)
                    SecondaryButton(FloatingTexts.HIDE, onHide, description = FloatingTexts.HIDE_DESC)
                }
                else -> {
                    Text("${FloatingTexts.PANEL} · $app", style = Clarity.type.callout, color = c.secondary, modifier = Modifier.semantics { heading() })
                    // Saathi's latest line, big (focus order: this line, then Speak, then the rest).
                    Text(
                        lastSaathi?.text ?: FloatingTexts.GETTING_READY,
                        style = Clarity.type.headline.copy(fontWeight = FontWeight.Medium),
                        color = c.label,
                        modifier = Modifier.semantics { contentDescription = "Saathi: ${lastSaathi?.text ?: FloatingTexts.GETTING_READY}" },
                    )
                    // How to hear the rest ("Say “read more” …"): shown, never spoken.
                    lastSaathi?.hint?.let { Text(it, style = Clarity.type.callout, color = c.secondary) }
                    if (lastUser != null) {
                        Text(
                            "You: “${lastUser.text}”",
                            style = Clarity.type.callout,
                            color = c.secondary,
                            modifier = Modifier.semantics { contentDescription = "You said: ${lastUser.text}" },
                        )
                    }
                    ScreenParts.ListenHint(listener)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        FloatingSpeakButton(listener.active, enabled = Floating.speakEnabled(phase)) { talk() }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SecondaryButton(FloatingTexts.REPEAT, { send("repeat", "floating repeat button") }, description = FloatingTexts.REPEAT_DESC, height = 52.dp)
                            SecondaryButton(FloatingTexts.MORE, { send("read more", "floating read more button") }, description = FloatingTexts.MORE_DESC, height = 52.dp)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SecondaryButton(FloatingTexts.HIDE, onHide, modifier = Modifier.weight(1f), description = FloatingTexts.HIDE_DESC, height = 56.dp)
                        DangerButton(
                            FloatingTexts.STOP,
                            {
                                deps.feedback.cue(Cue.Cancelled)
                                if (guiding != null) send("done", "floating stop button") else TaskRunner.stop()
                            },
                            modifier = Modifier.weight(1f),
                            description = FloatingTexts.STOP_DESC,
                            height = 56.dp,
                        )
                    }
                }
            }
        }
    }
}

/** The 96 dp push-to-talk button: press, speak, press again (or wait) to finish. */
@Composable
private fun FloatingSpeakButton(active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = Clarity.colors
    Box(
        Modifier
            .size(96.dp)
            .clip(CircleShape)
            .background(if (active || enabled) c.accent else c.group)
            .clickable(role = Role.Button, enabled = enabled || active, onClick = onClick)
            .semantics {
                contentDescription = if (active) FloatingTexts.STOP_LISTENING else FloatingTexts.SPEAK_DESC
                stateDescription = if (active) "Listening" else if (enabled) "Not listening" else "Wait for Saathi"
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Mic, contentDescription = null, tint = if (active || enabled) c.onAccent else c.secondary, modifier = Modifier.size(36.dp))
            Text(if (active) "Listening" else FloatingTexts.SPEAK, style = Clarity.type.callout, color = if (active || enabled) c.onAccent else c.secondary)
        }
    }
}

/**
 * The home panel ("What shall we do?") and the walk panel: Saathi's last words, Speak, and big
 * buttons. Home: Help me here · Walk · What's in front · Read messages. Walking: What's in front ·
 * Stop walking (red). Every button says what it does to TalkBack.
 */
@Composable
private fun HomeControls(
    walkingNow: Boolean,
    app: String,
    line: String?,
    listener: ScreenParts.ScreenListener,
    onTalk: () -> Unit,
    home: FloatingHome?,
    onHide: () -> Unit,
    onClose: () -> Unit,
) {
    val c = Clarity.colors
    Text(
        if (walkingNow) "${FloatingTexts.PANEL} · Walking" else "${FloatingTexts.PANEL} · $app",
        style = Clarity.type.callout,
        color = c.secondary,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        line ?: if (walkingNow) "Walking. I'll warn you about things ahead." else FloatingTexts.HOME_TITLE,
        style = Clarity.type.headline.copy(fontWeight = FontWeight.Medium),
        color = c.label,
    )
    ScreenParts.ListenHint(listener)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        FloatingSpeakButton(listener.active, enabled = true) { onTalk() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (walkingNow) {
                SecondaryButton(FloatingTexts.LOOK, { home?.look() }, description = FloatingTexts.LOOK_DESC, height = 52.dp)
                DangerButton(FloatingTexts.STOP_WALK, { home?.stopAll() }, description = FloatingTexts.STOP_WALK_DESC, height = 52.dp)
            } else {
                SecondaryButton(FloatingTexts.HELP_HERE, { home?.helpHereNow() }, description = FloatingTexts.HELP_HERE_DESC, height = 52.dp)
                SecondaryButton(FloatingTexts.WALK, { home?.startWalk() }, description = FloatingTexts.WALK_DESC, height = 52.dp)
            }
        }
    }
    if (!walkingNow) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton(FloatingTexts.LOOK, { home?.look() }, modifier = Modifier.weight(1f), description = FloatingTexts.LOOK_DESC, height = 52.dp)
            SecondaryButton(FloatingTexts.MESSAGES, { home?.readMessages() }, modifier = Modifier.weight(1f), description = FloatingTexts.MESSAGES_DESC, height = 52.dp)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SecondaryButton(FloatingTexts.HIDE, onHide, modifier = Modifier.weight(1f), description = FloatingTexts.HIDE_DESC, height = 52.dp)
        if (!walkingNow) SecondaryButton(FloatingTexts.CLOSE, onClose, modifier = Modifier.weight(1f), description = FloatingTexts.CLOSE_DESC, height = 52.dp)
    }
}

package app.saathi.ui.screens

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.saathi.agent.GuideTurn
import app.saathi.agent.Heard
import app.saathi.agent.Outcome
import app.saathi.agent.PushToTalk
import app.saathi.agent.TaskRunner
import app.saathi.feedback.Cue
import app.saathi.ui.AppNav
import app.saathi.ui.LocalDeps
import app.saathi.ui.Route
import app.saathi.ui.components.DangerButton
import app.saathi.ui.components.Group
import app.saathi.ui.components.GroupDivider
import app.saathi.ui.components.SaathiScreen
import app.saathi.ui.components.SecondaryButton
import app.saathi.ui.theme.Clarity

/**
 * Guide mode ("let's do it together"): a conversation about the app the user is working in.
 * Saathi says what it sees (verbatim labels), the user says what to do, Saathi does it through
 * the safety gate and says what changed. Saathi stays in front while talking; the other app comes
 * forward only for each action (the session brings it back and forth).
 *
 * Push-to-talk ONLY: listening starts when the user presses Speak (or the small talk button, or
 * holds Volume-down). Saathi never opens the microphone after speaking, because what it says
 * contains the very words a command uses (13 Oct incidents; 14 Oct 00:40 "read more"). Every
 * start goes through [PushToTalk]: a press arms one session, a new Saathi turn disarms it and
 * cancels any listening still open, so no listener survives into the next turn. Each transcript
 * carries its timing; the session refuses anything heard under 1.5 s after Saathi spoke. The
 * "read more" hint is shown here, never spoken. Not an answer sheet: send / order still go to
 * the Confirm sheet, and nothing said here can answer it.
 */
@Composable
fun GuideScreen(nav: AppNav) {
    val deps = LocalDeps.current
    val c = Clarity.colors
    val still = ScreenParts.rememberStill()
    val st by TaskRunner.state.collectAsState()
    var leaving by remember { mutableStateOf(false) }

    val guiding = st as? TaskRunner.State.Guiding
    val turns = guiding?.turns.orEmpty()
    val lastSaathi = turns.lastOrNull { !it.fromUser }
    val lastUser = turns.lastOrNull { it.fromUser }
    val working = st is TaskRunner.State.Running
    // [brain hook] The on-device AI is working out what the user meant: "Thinking…" + a soft tick.
    val thinking by app.saathi.brain.LlmEngine.thinking.collectAsState()
    LaunchedEffect(thinking) { if (thinking) deps.feedback.tick() }

    val listener = ScreenParts.rememberCommandListener { heard -> TaskRunner.guideCommand(heard, "voice") }
    val ptt = remember { PushToTalk { SystemClock.elapsedRealtime() } }

    /** The user's own press (Speak, talk button, Volume-down at [at]): listen once, or finish listening. */
    fun talk(at: Long = SystemClock.elapsedRealtime()) {
        if (listener.active) { listener.finish(); return }
        ptt.press(at)
        if (ptt.mayListen()) listener.start()
    }

    fun send(text: String, source: String) {
        listener.cancel()
        deps.voice.stopSpeaking()
        TaskRunner.guideCommand(Heard(text), source) // a button: no speech timing to check
    }

    fun stop() {
        if (leaving) return
        deps.feedback.cue(Cue.Cancelled)
        send("done", "stop button")
    }

    // A new Saathi turn: it waits for the user (the cue; the words are spoken by the runner). No
    // listening survives into it, and a press made before it no longer counts.
    LaunchedEffect(turns.size) {
        val last = turns.lastOrNull() ?: return@LaunchedEffect
        if (!last.fromUser) {
            ptt.turnSpoken()
            listener.cancel()
            deps.feedback.cue(if (last.kind == GuideTurn.Kind.REFUSED) Cue.Cancelled else Cue.NeedsAnswer)
        }
    }
    // Volume-down held while guiding: the user's own push-to-talk (only a fresh hold counts).
    LaunchedEffect(Unit) { TaskRunner.guideTalk.collect { at -> if (!listener.active) talk(at) } }

    // Follow the session: answer sheets, the end.
    LaunchedEffect(st) {
        if (leaving) return@LaunchedEffect
        // The floating panel took this session over (or runs its own): it is answered there, not here.
        if (TaskRunner.isFloatingRun(st.runId) || TaskRunner.wasSuperseded(st.runId)) { leaving = true; listener.cancel(); nav.home(); return@LaunchedEffect }
        when (val s = st) {
            is TaskRunner.State.Confirming -> { leaving = true; listener.cancel(); nav.replace(Route.Confirm) }
            is TaskRunner.State.ChoosingItem -> { leaving = true; listener.cancel(); nav.replace(Route.OrderPick) }
            is TaskRunner.State.Finished -> {
                leaving = true
                listener.cancel()
                nav.replace(if (s.outcome is Outcome.HandedOver) Route.Handover else Route.Result)
            }
            TaskRunner.State.Idle -> { leaving = true; nav.home() }
            else -> Unit
        }
    }

    BackHandler { stop() }

    val status = when {
        listener.active -> "Listening"
        thinking -> "Thinking…" // [brain hook]
        working -> "Working in ${guiding?.app ?: "the app"}."
        else -> "Doing it together. Press Speak to tell Saathi what to do."
    }

    SaathiScreen(
        status = status,
        youCanSay = listOf("Add …", "Tap …", "Scroll down", "Read more", "Done"),
        onTalk = { talk() },
        talkLabel = "Speak",
        listening = listener.active,
        bottom = {
            ScreenParts.ListenHint(listener)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SpeakButton(listener.active) { talk() }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("Repeat", { send("repeat", "repeat button") }, description = "Repeat what Saathi said")
                    SecondaryButton("Read more", { send("read more", "read more button") }, description = "Read more of the screen")
                }
            }
            DangerButton("Stop", { stop() }, description = "Stop doing it together. Saathi tells you where things are left.")
        },
    ) {
        Text(
            "Doing it together" + (guiding?.app?.let { " · $it" } ?: ""),
            style = Clarity.type.headline,
            color = c.secondary,
            modifier = ScreenParts.rise(0, still).semantics { heading() },
        )
        // The latest Saathi turn, big; the user's command under it.
        Text(
            lastSaathi?.text ?: if (working) "Working…" else "Getting ready…",
            style = Clarity.type.title.copy(fontWeight = FontWeight.Medium),
            color = c.label,
            modifier = ScreenParts.rise(1, still).semantics { contentDescription = "Saathi: ${lastSaathi?.text ?: "getting ready"}" },
        )
        // How to hear the rest ("Say “read more” …"): shown, never spoken, so it can't be heard back.
        lastSaathi?.hint?.let { hint ->
            Text(hint, style = Clarity.type.body, color = c.secondary, modifier = ScreenParts.rise(2, still))
        }
        if (lastUser != null) {
            Text(
                "You: “${lastUser.text}”",
                style = Clarity.type.body,
                color = c.secondary,
                modifier = ScreenParts.rise(2, still).semantics { contentDescription = "You said: ${lastUser.text}" },
            )
        }
        val earlier = turns.dropLast(if (lastUser != null && turns.last().fromUser) 1 else 0).filter { it !== lastSaathi && it !== lastUser }.takeLast(6)
        if (earlier.isNotEmpty()) {
            Group(ScreenParts.rise(3, still)) {
                earlier.forEachIndexed { i, line ->
                    if (i > 0) GroupDivider()
                    Text(
                        (if (line.fromUser) "You: " else "Saathi: ") + line.text,
                        style = Clarity.type.body,
                        color = if (line.fromUser) c.secondary else c.label,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/** The 120 dp push-to-talk button: press, speak, press again (or wait) to finish. */
@Composable
private fun SpeakButton(active: Boolean, onClick: () -> Unit) {
    val c = Clarity.colors
    Box(
        Modifier
            .size(120.dp)
            .clip(CircleShape)
            .background(if (active) c.accent else c.group)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = if (active) "Stop listening" else "Speak. Tell Saathi what to do"
                stateDescription = if (active) "Listening" else "Not listening"
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Mic, contentDescription = null, tint = if (active) c.onAccent else c.label, modifier = Modifier.size(44.dp))
            Text(if (active) "Listening" else "Speak", style = Clarity.type.callout, color = if (active) c.onAccent else c.label)
        }
    }
}

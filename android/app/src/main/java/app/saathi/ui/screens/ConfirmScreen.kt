package app.saathi.ui.screens

import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.saathi.agent.AfterCancel
import app.saathi.agent.ConfirmDialog
import app.saathi.agent.Lang
import app.saathi.agent.Requests
import app.saathi.agent.TaskRunner
import app.saathi.agent.Texts
import app.saathi.feedback.Cue
import app.saathi.safety.Answer
import app.saathi.ui.components.readableWidth
import app.saathi.ui.AppNav
import app.saathi.ui.LocalDeps
import app.saathi.ui.Route
import app.saathi.ui.components.AskCapsule
import app.saathi.ui.components.Body
import app.saathi.ui.components.Footnote
import app.saathi.ui.components.PrimaryButton
import app.saathi.ui.components.SecondaryButton
import app.saathi.ui.components.StatusLine
import app.saathi.ui.components.YouCanSay
import app.saathi.ui.theme.Clarity
import androidx.compose.ui.platform.LocalContext
import app.saathi.agent.Outcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Screen 6 — Confirm sheet over the dimmed working screen. Cancel is first, focused and the default.
 * Shows the safety gate's read-back: recipient and message exactly as they are on screen
 * (hard rule 4). Nothing confirms on its own; an unclear voice answer is asked once more, then
 * cancels (hard rule 5). Cancel also removes the message Saathi typed; what is said and shown
 * afterwards is the runner's real outcome (removed, or NOT removed and remembered), never assumed.
 *
 * Saathi must never confirm its own question (13 Oct): the mic never listens while Saathi speaks
 * ([app.saathi.voice.Voice]); a voice yes must be a short explicit yes from the user
 * ([ConfirmDialog]); a reply that repeats the question or the sheet's words is unclear; the
 * spoken question and every text on this sheet contain no yes word; and the Send button ignores
 * a tap in the first [SEND_ARM_MS] after the sheet appears (a touch meant for the app underneath).
 */
@Composable
fun ConfirmScreen(nav: AppNav) {
    val deps = LocalDeps.current
    val c = Clarity.colors
    val still = ScreenParts.rememberStill()
    val scope = rememberCoroutineScope()
    val t = remember { Texts(Requests.lang(deps)) }
    val confirming = remember { TaskRunner.state.value as? TaskRunner.State.Confirming }
    val asked = confirming?.request
    val context = LocalContext.current
    val confirmTo = asked?.recipient.orEmpty()
    val confirmText = asked?.message.orEmpty()
    // Hindi: the gate's own read-back. English: the same on-screen strings in an English question.
    // Blinkit order (R3) / emptying the cart: the same sheet and rules, order wording (OrderConfirmWords.kt).
    val orderWords = remember { OrderConfirmWords.of(asked, t.lang) }
    val confirmQuestion = when {
        asked == null -> ""
        orderWords != null -> orderWords.question
        t.lang == Lang.HI -> asked.readback
        else -> t.confirmQuestion(confirmTo, confirmText)
    }
    val dialog = remember { ConfirmDialog(extraFillers = if (orderWords != null) ConfirmDialog.ORDER_FILLERS else emptySet()) }
    // What Cancel does to the box (Saathi's own text removed, the user's earlier text put back, or untouched).
    val afterCancel = asked?.afterCancel ?: AfterCancel.REMOVE_TYPED
    val undoing = orderWords?.undoing ?: when (afterCancel) {
        AfterCancel.REMOVE_TYPED -> "I'm removing the message I typed."
        AfterCancel.RESTORE_ORIGINAL -> "I'm putting your earlier text back."
        AfterCancel.LEAVE_AS_IS -> "Your text stays as it was."
    }

    var cancelled by rememberSaveable { mutableStateOf(asked == null) }
    var sheetGone by rememberSaveable { mutableStateOf(false) }
    var unclear by rememberSaveable { mutableIntStateOf(0) }
    var finished by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) } // the runner's final words for this run
    val shownAt = remember { SystemClock.uptimeMillis() }
    val hint = t.confirmHint
    val sheet = remember { Animatable(if (still || cancelled) 0f else 1f) } // 1 = below the screen, 0 = up
    val cancelFocus = remember { FocusRequester() }
    val homeFocus = remember { FocusRequester() }
    val ease = remember { CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f) }

    lateinit var listener: ScreenParts.ScreenListener

    fun cancel(spoken: String = orderWords?.cancelSpoken ?: "Cancelling. Not sending.") {
        if (finished) return
        finished = true
        listener.cancel()
        deps.feedback.cue(Cue.Cancelled)
        deps.voice.speak(spoken)
        // The runner removes the typed draft, records the outcome, and its result is said below.
        TaskRunner.answer(Answer.NO, "cancel")
        cancelled = true
        scope.launch {
            if (!still) sheet.animateTo(1f, tween(300, easing = ease))
            sheetGone = true
        }
    }

    fun send(source: String) {
        if (finished) return
        if (source == "tap" && SystemClock.uptimeMillis() - shownAt < SEND_ARM_MS) {
            Log.i("SaathiConfirm", "send tap ignored: sheet appeared ${SystemClock.uptimeMillis() - shownAt} ms ago")
            return
        }
        finished = true
        listener.cancel()
        deps.voice.stopSpeaking()
        // A clear yes (tap or voice): the runner re-checks the screen and the gate, then sends.
        TaskRunner.answer(Answer.YES, source)
        nav.replace(Route.Working)
    }

    fun askAgainOrCancel(next: ConfirmDialog.Next) {
        unclear = dialog.unclear
        if (next == ConfirmDialog.Next.CANCEL) {
            cancel(orderWords?.unsureSpoken ?: "I wasn't sure, so I'm cancelling. Not sending.")
            return
        }
        asking = true
        scope.launch {
            deps.feedback.cue(Cue.NeedsAnswer)
            deps.voice.speak("Sorry, I'm not sure. $confirmQuestion")
            ScreenParts.awaitSpeechEnd(deps.voice)
            asking = false
            if (!finished) listener.start()
        }
    }

    // Everything Saathi says or shows while waiting: a reply that repeats it is an echo, not an answer.
    val sheetWords = listOf(
        confirmQuestion,
        "Send to $confirmTo?",
        confirmText,
        "Exactly as typed on screen",
        "Safety check: ${asked?.readback.orEmpty()}",
        "Needs your answer. Send to $confirmTo: $confirmText? Cancel is selected.",
        "Cancel. This is the default. Nothing will be sent. $undoing",
        "Send “$confirmText” to $confirmTo. It will really be sent.",
        hint,
        "Cancelling. Not sending.",
        "I wasn't sure, so I'm cancelling. Not sending.",
        "Sorry, I'm not sure. $confirmQuestion",
        "Cancel", "Send", "Working", "WhatsApp", "Blinkit",
        undoing,
    ) + orderWords?.sheetWords.orEmpty() + confirming?.lines.orEmpty()

    // The shared answer pipeline (VoiceAnswer): timing, length, echo of everything above, yes word never in the prompt.
    listener = ScreenParts.rememberAnswerListener(
        sheetTexts = sheetWords,
        onUnclear = { askAgainOrCancel(dialog.silence()) },
    ) { heard, sources ->
        val next = dialog.hear(heard, sources)
        Log.i("SaathiConfirm", "voice answer: $next (${dialog.lastWhy}, unclear so far ${dialog.unclear}), length ${heard.text.length}, ${ScreenParts.afterSpeech(heard)}")
        when (next) {
            ConfirmDialog.Next.SEND -> send("voice")
            ConfirmDialog.Next.CANCEL -> cancel()
            ConfirmDialog.Next.ASK_AGAIN -> askAgainOrCancel(next)
        }
    }

    ScreenParts.OnFirstShow {
        if (asked == null) return@OnFirstShow
        deps.feedback.cue(Cue.NeedsAnswer)
        deps.voice.speak(confirmQuestion)
    }
    // The run ended: after our Cancel, or elsewhere (Volume-down, service restart, no answer in
    // time). Show and say what really happened to the typed text.
    val st by TaskRunner.state.collectAsState()
    LaunchedEffect(st) {
        // Guide mode: after the answer the conversation goes on (Cancel = the tap didn't run).
        val g = st as? TaskRunner.State.Guiding
        if (g != null && confirming != null && g.runId == confirming.runId) {
            finished = true
            listener.cancel()
            nav.replace(Route.Guide)
            return@LaunchedEffect
        }
        val f = st as? TaskRunner.State.Finished ?: return@LaunchedEffect
        if (asked == null || f.runId != confirming.runId) return@LaunchedEffect
        val first = result == null
        result = f.outcome.message
        if (!first) return@LaunchedEffect // a later update (draft removed after reconnect) is spoken by the runner
        if (!finished) {
            finished = true
            listener.cancel()
            cancelled = true
            sheetGone = true
            // Volume-down: the user is about to talk, stay quiet. Service gone / no answer in
            // time: nobody else says it.
            if (f.outcome !is Outcome.Stopped || TaskRunner.stoppedByServiceLoss) deps.voice.speak(f.outcome.message)
        } else if (f.outcome is Outcome.Cancelled) {
            ScreenParts.awaitSpeechEnd(deps.voice) // let "Cancelling" finish
            if ((f.outcome as Outcome.Cancelled).restored) deps.feedback.cue(Cue.Done) // the user's text is back
            deps.voice.speak(f.outcome.message)
        }
    }
    // Leaving the screen without an answer (not a rotation) = cancel; never left waiting.
    DisposableEffect(Unit) {
        onDispose {
            val rotating = (context as? android.app.Activity)?.isChangingConfigurations == true
            if (!finished && asked != null && !rotating) TaskRunner.answer(Answer.NO, "left the screen")
        }
    }

    LaunchedEffect(Unit) {
        if (!still && !cancelled) sheet.animateTo(0f, tween(300, easing = ease))
    }
    LaunchedEffect(sheetGone) {
        delay(50)
        runCatching { if (sheetGone) homeFocus.requestFocus() else cancelFocus.requestFocus() }
    }

    BackHandler { if (cancelled) nav.home() else cancel() }

    val status = when {
        listener.active -> "Listening"
        cancelled -> result ?: orderWords?.cancellingStatus ?: "Cancelling — nothing was sent. $undoing"
        else -> orderWords?.status ?: "Needs your answer. Send to $confirmTo: $confirmText? Cancel is selected."
    }

    Box(Modifier.fillMaxSize().background(c.screen)) {
        StatusLine(status)

        // The working screen behind, dimmed and hidden from TalkBack.
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 28.dp)
                .graphicsLayer { alpha = 0.45f }
                .clearAndSetSemantics {},
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Working", style = Clarity.type.headline, color = c.secondary)
            Text(if (orderWords != null) "Blinkit" else "WhatsApp", style = Clarity.type.title, color = c.label, modifier = Modifier.align(Alignment.CenterHorizontally))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.group).padding(horizontal = 16.dp)) {
                (if (orderWords != null) confirming?.lines.orEmpty().takeLast(3) else listOf(t.opening("WhatsApp"), t.openingChat(confirmTo), t.typing)).forEach {
                    Box(Modifier.heightIn(min = 64.dp), contentAlignment = Alignment.CenterStart) {
                        Text(it, style = Clarity.type.body, color = c.label)
                    }
                }
            }
        }

        if (!cancelled) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 1f - sheet.value }
                    .background(c.dim)
                    .clearAndSetSemantics {},
            )
        }

        if (!sheetGone) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .graphicsLayer { translationY = sheet.value * size.height }
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(c.screen)
                    .then(
                        if (c.isDark) Modifier.border(1.dp, c.separator, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                        else Modifier,
                    )
                    .semantics { paneTitle = "Needs your answer" }
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 20.dp)
                    .readableWidth(),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(40.dp)
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(c.separator)
                        .clearAndSetSemantics {},
                )
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AskCapsule()
                    Text(
                        orderWords?.title ?: "Send to $confirmTo?",
                        style = Clarity.type.title,
                        color = c.label,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (orderWords != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            orderWords.lines.forEach { Text(it, style = Clarity.type.title.copy(fontWeight = FontWeight.Medium), color = c.label) }
                            Text(orderWords.total, style = Clarity.type.headline, color = c.label)
                            Footnote(orderWords.note)
                        }
                    } else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("“$confirmText”", style = Clarity.type.title.copy(fontWeight = FontWeight.Medium), color = c.label)
                        Footnote("Exactly as typed on screen")
                    }
                    if (asked != null) {
                        // The safety gate's read-back, verbatim (it is the source of the words above).
                        Footnote("Safety check: ${asked.readback}")
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecondaryButton(
                        "Cancel",
                        { cancel() },
                        modifier = Modifier.focusRequester(cancelFocus),
                        description = orderWords?.cancelDescription ?: "Cancel. This is the default. Nothing will be sent. $undoing",
                        tag = "Default",
                    )
                    PrimaryButton(
                        orderWords?.confirmLabel ?: "Send",
                        { send("tap") },
                        description = orderWords?.confirmDescription ?: "Send “$confirmText” to $confirmTo. It will really be sent.",
                    )
                }

                ScreenParts.ListenHint(listener)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Footnote(
                        if (asking) "Asking again…" else hint,
                        Modifier.weight(1f),
                    )
                    ScreenParts.TalkButton(
                        description = if (listener.active) "Stop listening" else "Answer by voice",
                        active = listener.active,
                        onClick = { if (!asking) listener.toggle() },
                    )
                }
            }
        }

        if (sheetGone) {
            Column(
                ScreenParts.rise(0, still)
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(c.screen)
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(c.group).clearAndSetSemantics {},
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.Close, contentDescription = null, tint = c.label) }
                    Text(if (result == null && asked != null) "Cancelling…" else "Cancelled", style = Clarity.type.title, color = c.label, modifier = Modifier.semantics { heading() })
                }
                Body(
                    when {
                        asked == null -> "Nothing to confirm. Nothing was sent."
                        result != null -> result!!
                        orderWords != null -> "${orderWords.nothingDone} $undoing"
                        else -> "Nothing was sent. $undoing"
                    },
                    secondary = true,
                )
                PrimaryButton("Home", { nav.home() }, modifier = Modifier.focusRequester(homeFocus))
                YouCanSay(listOf("Go home", "Help")) { nav.replace(Route.Listening) }
            }
        }
    }
}

/** The Send button ignores taps this soon after the sheet appears (a touch meant for the app below). */
private const val SEND_ARM_MS = 700L

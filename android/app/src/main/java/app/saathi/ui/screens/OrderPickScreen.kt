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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.saathi.agent.Outcome
import app.saathi.agent.PickDialog
import app.saathi.agent.TaskRunner
import app.saathi.feedback.Cue
import app.saathi.ui.AppNav
import app.saathi.ui.LocalDeps
import app.saathi.ui.Route
import app.saathi.ui.components.AskCapsule
import app.saathi.ui.components.Footnote
import app.saathi.ui.components.SecondaryButton
import app.saathi.ui.components.StatusLine
import app.saathi.ui.components.readableWidth
import app.saathi.ui.theme.Clarity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Grocery "which one?" sheet (a close call between products, or the size). Cancel is first,
 * focused and the default. Each option is the product's name, size and price exactly as Blinkit
 * shows them (hard rule 4). Voice: a name or "first / second" ([PickDialog]); unclear is asked once
 * more, then cancels; silence twice, Back, or leaving the screen = Cancel. Nothing is ordered from
 * here: ordering still needs the read-back and a clear yes. Option taps are ignored in the first
 * [PICK_ARM_MS] after the sheet appears (a touch meant for the app underneath).
 */
@Composable
fun OrderPickScreen(nav: AppNav) {
    val deps = LocalDeps.current
    val c = Clarity.colors
    val still = ScreenParts.rememberStill()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val choosing = remember { TaskRunner.state.value as? TaskRunner.State.ChoosingItem }
    val req = choosing?.request
    val options = req?.options.orEmpty()
    val question = req?.question.orEmpty()
    val dialog = remember { PickDialog(options) }

    var finished by remember { mutableStateOf(req == null) }
    var asking by remember { mutableStateOf(false) }
    val shownAt = remember { SystemClock.uptimeMillis() }
    val sheet = remember { Animatable(if (still) 0f else 1f) }
    val cancelFocus = remember { FocusRequester() }
    val ease = remember { CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f) }

    lateinit var listener: ScreenParts.ScreenListener

    fun answer(index: Int?, source: String) {
        if (finished) return
        if (source == "tap" && index != null && SystemClock.uptimeMillis() - shownAt < PICK_ARM_MS) {
            Log.i("SaathiConfirm", "item pick tap ignored: sheet appeared ${SystemClock.uptimeMillis() - shownAt} ms ago")
            return
        }
        finished = true
        listener.cancel()
        deps.voice.stopSpeaking()
        if (index == null) deps.feedback.cue(Cue.Cancelled)
        TaskRunner.pickItem(index, source)
        // A choice carries on in Blinkit; Cancel ends the task and the Result screen says so.
        if (index != null) nav.replace(Route.Working)
    }

    fun askAgainOrCancel(next: PickDialog.Next) {
        if (next == PickDialog.Next.Cancel) {
            answer(null, "unclear")
            return
        }
        asking = true
        scope.launch {
            deps.feedback.cue(Cue.NeedsAnswer)
            deps.voice.speak("Sorry, I'm not sure. $question")
            ScreenParts.awaitSpeechEnd(deps.voice)
            asking = false
            if (!finished) listener.start()
        }
    }

    val hint = req?.hint.orEmpty()
    // What the sheet says besides the options (an echo of these is not an answer).
    val sheetWords = listOf(
        question,
        "Sorry, I'm not sure. $question",
        "Needs your answer. $question Cancel is selected.",
        "Cancel. This is the default. Nothing will be added.",
        hint,
    )

    listener = ScreenParts.rememberScreenListener(
        onFailed = { reason -> if (reason == ScreenParts.NOT_CAUGHT) askAgainOrCancel(dialog.silence()) },
        genericEchoCheck = false, // the dialog's own check below
    ) { text ->
        val next = dialog.hear(text, echoSources = deps.voice.recentPrompts(30_000) + sheetWords)
        Log.i("SaathiConfirm", "item pick voice answer: ${next::class.simpleName} (unclear so far ${dialog.unclear}), length ${text.length}")
        when (next) {
            is PickDialog.Next.Picked -> answer(next.index, "voice")
            PickDialog.Next.Cancel -> answer(null, "voice")
            PickDialog.Next.AskAgain -> askAgainOrCancel(next)
        }
    }

    ScreenParts.OnFirstShow {
        if (req == null) return@OnFirstShow
        deps.feedback.cue(Cue.NeedsAnswer)
        deps.voice.speak(question)
    }

    // The run ended elsewhere (no answer in time, the service went away): show what happened.
    val st by TaskRunner.state.collectAsState()
    LaunchedEffect(st) {
        val f = st as? TaskRunner.State.Finished ?: return@LaunchedEffect
        if (choosing == null || f.runId != choosing.runId) return@LaunchedEffect
        finished = true
        listener.cancel()
        if (f.outcome is Outcome.Stopped && !TaskRunner.stoppedByServiceLoss) return@LaunchedEffect
        nav.replace(Route.Result)
    }
    LaunchedEffect(Unit) { if (req == null) nav.home() }

    // Leaving the screen without an answer (not a rotation) = Cancel; never left waiting.
    DisposableEffect(Unit) {
        onDispose {
            val rotating = (context as? android.app.Activity)?.isChangingConfigurations == true
            if (!finished && req != null && !rotating) TaskRunner.pickItem(null, "left the screen")
        }
    }

    LaunchedEffect(Unit) { if (!still) sheet.animateTo(0f, tween(300, easing = ease)) }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { cancelFocus.requestFocus() }
    }

    BackHandler { if (req == null) nav.home() else answer(null, "back") }

    val status = if (listener.active) "Listening" else "Needs your answer. $question Cancel is selected."

    Box(Modifier.fillMaxSize().background(c.screen)) {
        StatusLine(status)

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
            Text("Blinkit", style = Clarity.type.title, color = c.label, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 1f - sheet.value }
                .background(c.dim)
                .clearAndSetSemantics {},
        )

        Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 24.dp), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer { translationY = sheet.value * size.height }
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(c.screen)
                    .then(
                        if (c.isDark) Modifier.border(1.dp, c.separator, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                        else Modifier,
                    )
                    .semantics { paneTitle = "Needs your answer" }
                    .verticalScroll(rememberScrollState())
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
                    Text(question, style = Clarity.type.title, color = c.label, modifier = Modifier.semantics { heading() })
                    Footnote("Names, sizes and prices exactly as Blinkit shows them. Nothing is ordered without your yes.")
                }

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecondaryButton(
                        "Cancel",
                        { answer(null, "tap") },
                        modifier = Modifier.focusRequester(cancelFocus),
                        description = "Cancel. This is the default. Nothing will be added.",
                        tag = "Default",
                    )
                    options.forEachIndexed { i, label ->
                        SecondaryButton(
                            label,
                            { answer(i, "tap") },
                            description = "Option ${i + 1}: $label. Adds it to the cart; I'll still ask before ordering.",
                        )
                    }
                }

                ScreenParts.ListenHint(listener)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Footnote(if (asking) "Asking again…" else hint, Modifier.weight(1f))
                    ScreenParts.TalkButton(
                        description = if (listener.active) "Stop listening" else "Answer by voice",
                        active = listener.active,
                        onClick = { if (!asking) listener.toggle() },
                    )
                }
            }
        }
    }
}

/** Option buttons ignore taps this soon after the sheet appears (a touch meant for the app below). */
private const val PICK_ARM_MS = 700L

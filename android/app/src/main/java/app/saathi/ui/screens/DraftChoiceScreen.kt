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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.saathi.agent.DraftChoice
import app.saathi.agent.DraftChoiceDialog
import app.saathi.agent.Outcome
import app.saathi.agent.Requests
import app.saathi.agent.TaskRunner
import app.saathi.agent.Texts
import app.saathi.feedback.Cue
import app.saathi.ui.AppNav
import app.saathi.ui.LocalDeps
import app.saathi.ui.Route
import app.saathi.ui.components.AskCapsule
import app.saathi.ui.components.Body
import app.saathi.ui.components.Footnote
import app.saathi.ui.components.SecondaryButton
import app.saathi.ui.components.StatusLine
import app.saathi.ui.components.readableWidth
import app.saathi.ui.theme.Clarity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The chat's message box already holds the user's OWN unsent text. A sheet in the Confirm style
 * asks what to do with it: Keep (first, focused, the default), Replace, Add, or Send it. The text
 * is shown and spoken exactly as it is in the box (hard rule 4); text that looks private never
 * gets here (the runner leaves it alone without reading it). An unclear voice answer is asked
 * once more, then Keep; silence twice, Back, or leaving the screen = Keep. Nothing is sent from
 * here: Replace / Add / Send it all go on to the normal read-back and need a clear yes.
 *
 * The choice must come from the user, not from Saathi's own question (13 Oct): the mic never
 * listens while Saathi speaks ([app.saathi.voice.Voice]); a reply that repeats the question or the
 * sheet's words is unclear ([DraftChoiceDialog]); a change by voice must be a short reply naming
 * it; and the change buttons ignore a tap in the first [CHANGE_ARM_MS] after the sheet appears.
 */
@Composable
fun DraftChoiceScreen(nav: AppNav) {
    val deps = LocalDeps.current
    val c = Clarity.colors
    val still = ScreenParts.rememberStill()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val t = remember { Texts(Requests.lang(deps)) }
    val choosing = remember { TaskRunner.state.value as? TaskRunner.State.Choosing }
    val req = choosing?.request
    val chat = req?.chat.orEmpty()
    val existing = req?.existing.orEmpty()
    val canChange = req?.canChange == true
    val question = req?.let { t.draftQuestion(it.chat, it.existing, it.canChange) }.orEmpty()
    val dialog = remember { DraftChoiceDialog() }

    var finished by remember { mutableStateOf(req == null) }
    var asking by remember { mutableStateOf(false) }
    val shownAt = remember { SystemClock.uptimeMillis() }
    val sheet = remember { Animatable(if (still) 0f else 1f) } // 1 = below the screen, 0 = up
    val keepFocus = remember { FocusRequester() }
    val ease = remember { CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f) }

    lateinit var listener: ScreenParts.ScreenListener

    fun answer(choice: DraftChoice, source: String) {
        if (finished) return
        if (source == "tap" && choice != DraftChoice.KEEP && SystemClock.uptimeMillis() - shownAt < CHANGE_ARM_MS) {
            Log.i("SaathiConfirm", "draft choice tap ignored: sheet appeared ${SystemClock.uptimeMillis() - shownAt} ms ago")
            return
        }
        finished = true
        listener.cancel()
        deps.voice.stopSpeaking()
        TaskRunner.choose(choice, source)
        // Keep: the runner ends the task and the Result screen says so (with the Cancelled cue).
        // Anything else carries on in WhatsApp, then the normal read-back asks before sending.
        if (choice != DraftChoice.KEEP) nav.replace(Route.Working)
    }

    fun askAgainOrKeep(next: DraftChoiceDialog.Next) {
        if (next == DraftChoiceDialog.Next.KEEP) {
            answer(DraftChoice.KEEP, "unclear")
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

    val sayHint = when {
        canChange -> "Say “Keep”, “Replace”, “Add” or “Send it” (or rehne do, badal do, jod do, bhej do). If I'm not sure, I'll ask again, then keep it."
        else -> "Say “Keep” or “Send it” (or rehne do, bhej do). If I'm not sure, I'll ask again, then keep it."
    }
    // TalkBack reads the status line aloud (maybe while the mic is open): it doesn't repeat the
    // choices; Saathi's spoken question does.
    val draftStatus = "Needs your answer: $chat's chat already has unsent text. Keep is selected."
    // Everything Saathi says or shows while waiting: a reply that repeats it is an echo, not a choice.
    val sheetWords = listOf(
        question,
        "Sorry, I'm not sure. $question",
        "$chat's chat already has unsent text",
        existing,
        "Exactly as it is in the message box. Nothing has been changed.",
        "Your new message: “${req?.newMessage.orEmpty()}”",
        t.draftCantChange,
        "Needs your answer. $question Keep is selected.",
        draftStatus,
        "Keep it. This is the default. I won't change or send anything.",
        "Replace with my message",
        "Replace it with your new message. I'll read it to you before sending. If you say no, I'll put your text back.",
        "Add my message after it",
        "Add your new message after it. I'll read the whole text to you before sending. If you say no, I'll put your text back as it was.",
        "Send it as it is",
        "Send this text as it is. I'll read it to you and ask before sending.",
        sayHint,
        "Keep it", "Working", "WhatsApp",
    )

    // The shared answer pipeline (VoiceAnswer): a change is named on this sheet, so it must be said on its own.
    listener = ScreenParts.rememberAnswerListener(
        sheetTexts = sheetWords,
        onUnclear = { askAgainOrKeep(dialog.silence()) },
    ) { heard, sources ->
        val next = dialog.hear(heard, canChange, sources)
        Log.i("SaathiConfirm", "draft voice answer: $next (${dialog.lastWhy}, unclear so far ${dialog.unclear}), length ${heard.text.length}, ${ScreenParts.afterSpeech(heard)}")
        when (next) {
            DraftChoiceDialog.Next.KEEP -> answer(DraftChoice.KEEP, "voice")
            DraftChoiceDialog.Next.REPLACE -> answer(DraftChoice.REPLACE, "voice")
            DraftChoiceDialog.Next.ADD -> answer(DraftChoice.ADD, "voice")
            DraftChoiceDialog.Next.SEND -> answer(DraftChoice.SEND, "voice")
            DraftChoiceDialog.Next.ASK_AGAIN -> askAgainOrKeep(next)
        }
    }

    ScreenParts.OnFirstShow {
        if (req == null) return@OnFirstShow
        deps.feedback.cue(Cue.NeedsAnswer)
        deps.voice.speak(question)
    }

    // The run ended (Keep, no answer in time, the service went away): show what really happened.
    val st by TaskRunner.state.collectAsState()
    LaunchedEffect(st) {
        val f = st as? TaskRunner.State.Finished ?: return@LaunchedEffect
        if (choosing == null || f.runId != choosing.runId) return@LaunchedEffect
        finished = true
        listener.cancel()
        // Volume-down: the user is about to talk (Listening opens); stay out of the way.
        if (f.outcome is Outcome.Stopped && !TaskRunner.stoppedByServiceLoss) return@LaunchedEffect
        nav.replace(Route.Result)
    }
    LaunchedEffect(Unit) { if (req == null) nav.home() }

    // Leaving the screen without an answer (not a rotation) = Keep; never left waiting.
    DisposableEffect(Unit) {
        onDispose {
            val rotating = (context as? android.app.Activity)?.isChangingConfigurations == true
            if (!finished && req != null && !rotating) TaskRunner.choose(DraftChoice.KEEP, "left the screen")
        }
    }

    LaunchedEffect(Unit) {
        if (!still) sheet.animateTo(0f, tween(300, easing = ease))
    }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { keepFocus.requestFocus() }
    }

    BackHandler { if (req == null) nav.home() else answer(DraftChoice.KEEP, "back") }

    val status = when {
        listener.active -> "Listening"
        else -> draftStatus
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
            Text("WhatsApp", style = Clarity.type.title, color = c.label, modifier = Modifier.align(Alignment.CenterHorizontally))
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
                    Text(
                        "$chat's chat already has unsent text",
                        style = Clarity.type.title,
                        color = c.label,
                        modifier = Modifier.semantics { heading() },
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("“$existing”", style = Clarity.type.title.copy(fontWeight = FontWeight.Medium), color = c.label)
                        Footnote("Exactly as it is in the message box. Nothing has been changed.")
                    }
                    if (req != null) Body("Your new message: “${req.newMessage}”", secondary = true)
                    if (!canChange) Footnote(t.draftCantChange)
                }

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecondaryButton(
                        "Keep it",
                        { answer(DraftChoice.KEEP, "tap") },
                        modifier = Modifier.focusRequester(keepFocus),
                        description = "Keep it. This is the default. I won't change or send anything.",
                        tag = "Default",
                    )
                    if (canChange) {
                        SecondaryButton(
                            "Replace with my message",
                            { answer(DraftChoice.REPLACE, "tap") },
                            description = "Replace it with your new message. I'll read it to you before sending. If you say no, I'll put your text back.",
                        )
                        SecondaryButton(
                            "Add my message after it",
                            { answer(DraftChoice.ADD, "tap") },
                            description = "Add your new message after it. I'll read the whole text to you before sending. If you say no, I'll put your text back as it was.",
                        )
                    }
                    SecondaryButton(
                        "Send it as it is",
                        { answer(DraftChoice.SEND, "tap") },
                        description = "Send this text as it is. I'll read it to you and ask before sending.",
                    )
                }

                ScreenParts.ListenHint(listener)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Footnote(
                        if (asking) "Asking again…" else sayHint,
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
    }
}

/** Replace / Add / Send it ignore taps this soon after the sheet appears (a touch meant for the app below). */
private const val CHANGE_ARM_MS = 700L

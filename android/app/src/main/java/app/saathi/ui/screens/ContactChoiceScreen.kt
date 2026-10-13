package app.saathi.ui.screens

import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.saathi.agent.ContactChoiceDialog
import app.saathi.agent.Outcome
import app.saathi.agent.Requests
import app.saathi.agent.TaskRunner
import app.saathi.agent.Texts
import app.saathi.agent.contactChoiceHint
import app.saathi.agent.whichContact
import app.saathi.feedback.Cue
import app.saathi.ui.AppNav
import app.saathi.ui.LocalDeps
import app.saathi.ui.Route
import app.saathi.ui.components.AskCapsule
import app.saathi.ui.components.Body
import app.saathi.ui.components.Footnote
import app.saathi.ui.components.LargeTitle
import app.saathi.ui.components.SaathiScreen
import app.saathi.ui.components.SecondaryButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Which Rahul: Rahul Sharma or Rahul Verma?" Several people / groups in WhatsApp match the name
 * the user said. The names are shown and spoken exactly as WhatsApp shows them (hard rule 4).
 * Cancel is first, focused and the default; an unclear voice answer is asked once more, then
 * Saathi stops; Back or leaving the screen = Cancel. Picking someone sends nothing: the message
 * is still read back and needs a clear yes.
 *
 * Saathi's own question isn't taken as the answer: the mic only opens after Saathi stops
 * speaking, a reply repeating the question is unclear ([ContactChoiceDialog]), and the name
 * buttons ignore a tap in the first [PICK_ARM_MS] (a touch meant for the app underneath).
 */
@Composable
fun ContactChoiceScreen(nav: AppNav) {
    val deps = LocalDeps.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val t = remember { Texts(Requests.lang(deps)) }
    val picking = remember { TaskRunner.state.value as? TaskRunner.State.Picking }
    val req = picking?.request
    val options = req?.options.orEmpty()
    val spoken = req?.spoken.orEmpty()
    val question = req?.let { t.whichContact(it.spoken, it.options) }.orEmpty()
    val dialog = remember { ContactChoiceDialog(options) }

    var finished by remember { mutableStateOf(req == null) }
    var asking by remember { mutableStateOf(false) }
    val shownAt = remember { SystemClock.uptimeMillis() }
    val cancelFocus = remember { FocusRequester() }

    lateinit var listener: ScreenParts.ScreenListener

    fun answer(name: String?, source: String) {
        if (finished) return
        if (source == "tap" && name != null && SystemClock.uptimeMillis() - shownAt < PICK_ARM_MS) {
            Log.i("SaathiConfirm", "contact pick tap ignored: screen appeared ${SystemClock.uptimeMillis() - shownAt} ms ago")
            return
        }
        finished = true
        listener.cancel()
        deps.voice.stopSpeaking()
        if (name == null) deps.feedback.cue(Cue.Cancelled)
        TaskRunner.pick(name, source)
        // A pick carries on in WhatsApp; Cancel ends the task and the Result screen says so.
        if (name != null) nav.replace(Route.Working)
    }

    fun askAgainOrCancel(next: ContactChoiceDialog.Next) {
        if (next == ContactChoiceDialog.Next.Cancel) {
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

    val title = "Which $spoken?"
    // Everything Saathi says or shows while waiting: a reply that repeats it is an echo, not a pick.
    val sheetWords = listOf(
        question,
        "Sorry, I'm not sure. $question",
        title,
        "I found more than one match for “$spoken” in WhatsApp. Names exactly as WhatsApp shows them.",
        t.contactChoiceHint,
        "Cancel. This is the default. Nothing will be sent.",
        "Needs your answer. $question Cancel is selected.",
    )

    listener = ScreenParts.rememberScreenListener(
        onFailed = { reason -> if (reason == ScreenParts.NOT_CAUGHT) askAgainOrCancel(dialog.silence()) },
        genericEchoCheck = false, // the dialog's own check below
    ) { text ->
        val next = dialog.hear(text, echoSources = deps.voice.recentPrompts(30_000) + sheetWords)
        Log.i("SaathiConfirm", "contact pick voice: ${next::class.simpleName} (unclear so far ${dialog.unclear}), length ${text.length}")
        when (next) {
            is ContactChoiceDialog.Next.Pick -> answer(next.name, "voice")
            ContactChoiceDialog.Next.Cancel -> answer(null, "voice")
            ContactChoiceDialog.Next.AskAgain -> askAgainOrCancel(next)
        }
    }

    ScreenParts.OnFirstShow {
        if (req == null) return@OnFirstShow
        deps.feedback.cue(Cue.NeedsAnswer)
        deps.voice.speak(question)
    }

    // The run ended (Cancel, no answer in time, the service went away): show what really happened.
    val st by TaskRunner.state.collectAsState()
    LaunchedEffect(st) {
        val f = st as? TaskRunner.State.Finished ?: return@LaunchedEffect
        if (picking == null || f.runId != picking.runId) return@LaunchedEffect
        finished = true
        listener.cancel()
        if (f.outcome is Outcome.Stopped && !TaskRunner.stoppedByServiceLoss) return@LaunchedEffect
        nav.replace(Route.Result)
    }
    LaunchedEffect(Unit) { if (req == null) nav.home() }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { cancelFocus.requestFocus() }
    }

    // Leaving the screen without an answer (not a rotation) = Cancel; never left waiting.
    DisposableEffect(Unit) {
        onDispose {
            val rotating = (context as? android.app.Activity)?.isChangingConfigurations == true
            if (!finished && req != null && !rotating) TaskRunner.pick(null, "left the screen")
        }
    }

    BackHandler { if (req == null) nav.home() else answer(null, "back") }

    SaathiScreen(
        status = if (listener.active) "Listening" else "Needs your answer. $question Cancel is selected.",
        youCanSay = listOf("Cancel"),
        onTalk = { if (!asking) listener.toggle() },
        listening = listener.active,
        bottom = {
            ScreenParts.ListenHint(listener)
            SecondaryButton(
                "Cancel",
                { answer(null, "tap") },
                modifier = Modifier.focusRequester(cancelFocus),
                description = "Cancel. This is the default. Nothing will be sent.",
                tag = "Default",
            )
        },
    ) {
        AskCapsule()
        LargeTitle(title, Modifier.semantics { heading() })
        Body("I found more than one match for “$spoken” in WhatsApp. Names exactly as WhatsApp shows them.", secondary = true)
        options.forEachIndexed { i, name ->
            SecondaryButton(
                name,
                { answer(name, "tap") },
                description = "${i + 1} of ${options.size}: $name",
            )
        }
        Footnote(if (asking) "Asking again…" else t.contactChoiceHint)
    }
}

/** Name buttons ignore taps this soon after the screen appears (a touch meant for the app below). */
private const val PICK_ARM_MS = 700L

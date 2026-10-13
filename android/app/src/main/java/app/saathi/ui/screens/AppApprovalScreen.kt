package app.saathi.ui.screens

import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import app.saathi.agent.ConfirmDialog
import app.saathi.agent.LaunchRequests
import app.saathi.agent.Requests
import app.saathi.agent.Texts
import app.saathi.agent.appApprovalHint
import app.saathi.agent.appApprovalQuestion
import app.saathi.feedback.Cue
import app.saathi.ui.AppNav
import app.saathi.ui.LocalDeps
import app.saathi.ui.components.AskCapsule
import app.saathi.ui.components.Body
import app.saathi.ui.components.Footnote
import app.saathi.ui.components.LargeTitle
import app.saathi.ui.components.PrimaryButton
import app.saathi.ui.components.SaathiScreen
import app.saathi.ui.components.SecondaryButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Saathi hasn't opened Instagram before. Open it now?" — asked the first time Saathi would open
 * an app outside its own list. "Don't open" is first, focused and the default. Only a tap on Open
 * or a short, explicit spoken yes ([ConfirmDialog], same echo checks as the send confirmation)
 * opens it and remembers the approval (Settings > Apps). No, unclear twice, Back or leaving the
 * screen = not opened, nothing remembered. Nothing said or shown here contains a yes word.
 */
@Composable
fun AppApprovalScreen(nav: AppNav) {
    val deps = LocalDeps.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val t = remember { Texts(Requests.lang(deps)) }
    val pending = remember { LaunchRequests.pending }
    val label = pending?.app?.label.orEmpty()
    val question = t.appApprovalQuestion(label)
    val dialog = remember { ConfirmDialog() }

    var finished by remember { mutableStateOf(pending == null) }
    var asking by remember { mutableStateOf(false) }
    val shownAt = remember { SystemClock.uptimeMillis() }
    val cancelFocus = remember { FocusRequester() }

    lateinit var listener: ScreenParts.ScreenListener

    fun decline(unsure: Boolean, source: String) {
        if (finished) return
        finished = true
        listener.cancel()
        deps.voice.stopSpeaking()
        deps.feedback.cue(Cue.Cancelled)
        LaunchRequests.decline(deps, nav, unsure, source)
    }

    fun open(source: String) {
        if (finished) return
        if (source == "tap" && SystemClock.uptimeMillis() - shownAt < OPEN_ARM_MS) {
            Log.i("SaathiConfirm", "open tap ignored: screen appeared ${SystemClock.uptimeMillis() - shownAt} ms ago")
            return
        }
        finished = true
        listener.cancel()
        deps.voice.stopSpeaking()
        deps.feedback.cue(Cue.Done)
        LaunchRequests.approve(deps, nav, source)
    }

    fun askAgainOrDecline(next: ConfirmDialog.Next) {
        if (next == ConfirmDialog.Next.CANCEL) {
            decline(unsure = true, source = "unclear")
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

    val note = "Saathi will only open it. It can't use $label for you offline yet. If you allow it, Saathi won't ask again; change it in Settings, Apps."
    // Everything Saathi says or shows while waiting: a reply that repeats it is an echo, not an answer.
    val sheetWords = listOf(
        question,
        "Sorry, I'm not sure. $question",
        "Open $label?",
        note,
        t.appApprovalHint,
        "Don't open. This is the default. Nothing will happen.",
        "Open $label now and remember that it's allowed.",
        "Needs your answer. $question Don't open is selected.",
    )

    listener = ScreenParts.rememberScreenListener(
        onFailed = { reason -> if (reason == ScreenParts.NOT_CAUGHT) askAgainOrDecline(dialog.silence()) },
        genericEchoCheck = false, // the dialog's own, stricter check below
    ) { text ->
        val next = dialog.hear(text, echoSources = deps.voice.recentPrompts(30_000) + sheetWords)
        Log.i("SaathiConfirm", "open-app voice answer: $next (unclear so far ${dialog.unclear}), length ${text.length}")
        when (next) {
            ConfirmDialog.Next.SEND -> open("voice")
            ConfirmDialog.Next.CANCEL -> decline(unsure = false, source = "voice")
            ConfirmDialog.Next.ASK_AGAIN -> askAgainOrDecline(next)
        }
    }

    ScreenParts.OnFirstShow {
        if (pending == null) return@OnFirstShow
        deps.feedback.cue(Cue.NeedsAnswer)
        deps.voice.speak(question)
    }
    LaunchedEffect(Unit) { if (pending == null) nav.home() }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { cancelFocus.requestFocus() }
    }

    // Leaving the screen without an answer (not a rotation) = not opened.
    DisposableEffect(Unit) {
        onDispose {
            val rotating = (context as? android.app.Activity)?.isChangingConfigurations == true
            if (!finished && pending != null && !rotating) LaunchRequests.decline(deps, null, unsure = false, source = "left the screen")
        }
    }

    BackHandler { if (pending == null) nav.home() else decline(unsure = false, source = "back") }

    SaathiScreen(
        status = if (listener.active) "Listening" else "Needs your answer. $question Don't open is selected.",
        youCanSay = listOf("Don't open"),
        onTalk = { if (!asking) listener.toggle() },
        listening = listener.active,
        bottom = {
            ScreenParts.ListenHint(listener)
            SecondaryButton(
                "Don't open",
                { decline(unsure = false, source = "tap") },
                modifier = Modifier.focusRequester(cancelFocus),
                description = "Don't open. This is the default. Nothing will happen.",
                tag = "Default",
            )
            PrimaryButton("Open $label", { open("tap") }, description = "Open $label now and remember that it's allowed.")
        },
    ) {
        AskCapsule()
        LargeTitle("Open $label?", Modifier.semantics { heading() })
        Body(question)
        ScreenParts.NoteBox(note)
        Footnote(if (asking) "Asking again…" else t.appApprovalHint)
    }
}

/** Open ignores taps this soon after the screen appears (a touch meant for the screen before). */
private const val OPEN_ARM_MS = 700L

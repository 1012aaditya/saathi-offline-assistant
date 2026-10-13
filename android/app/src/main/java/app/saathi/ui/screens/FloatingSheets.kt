package app.saathi.ui.screens

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.saathi.agent.AfterCancel
import app.saathi.agent.AnswerArming
import app.saathi.agent.ConfirmDialog
import app.saathi.agent.FloatingTexts
import app.saathi.agent.Lang
import app.saathi.agent.PickDialog
import app.saathi.agent.Requests
import app.saathi.agent.TaskRunner
import app.saathi.agent.Texts
import app.saathi.feedback.Cue
import app.saathi.safety.Answer
import app.saathi.ui.LocalDeps
import app.saathi.ui.components.AskCapsule
import app.saathi.ui.components.Footnote
import app.saathi.ui.components.PrimaryButton
import app.saathi.ui.components.SecondaryButton
import app.saathi.ui.theme.Clarity
import kotlinx.coroutines.launch

/**
 * The Confirm and "which one?" questions on the FLOATING panel (Floating Saathi), over the app
 * being worked on. The same safety as the full-screen sheets, not a copy of it:
 *  - answers are heard only through [ScreenParts.rememberAnswerListener] -> [ConfirmDialog] /
 *    [PickDialog] -> VoiceAnswer (timing, length, echo of everything shown or said, decisive words);
 *  - the words come from the same places ([Texts.confirmQuestion], [OrderConfirmWords], the
 *    request's verbatim options), and no floating text has a yes word or a position (tested);
 *  - Cancel is first and the default; an unclear answer is asked once more, then cancels;
 *  - Send / Place order / an option ignore a TAP in the first [AnswerArming.TAP_ARM_MS] (a touch
 *    meant for the app underneath); closing or hiding the panel without an answer = Cancel.
 * Answers go to the runner exactly as from the full-screen sheets ([TaskRunner.answer] /
 * [TaskRunner.pickItem]), which re-checks the screen and the gate before anything runs.
 */
private const val TAG = "SaathiFloating"

@Composable
fun FloatingConfirmSheet(confirming: TaskRunner.State.Confirming) {
    val deps = LocalDeps.current
    val c = Clarity.colors
    val scope = rememberCoroutineScope()
    val t = remember { Texts(Requests.lang(deps)) }
    val asked = confirming.request
    val confirmTo = asked.recipient
    val confirmText = asked.message
    val orderWords = remember(asked) { OrderConfirmWords.of(asked, t.lang) }
    val confirmQuestion = when {
        orderWords != null -> orderWords.question
        t.lang == Lang.HI -> asked.readback
        else -> t.confirmQuestion(confirmTo, confirmText)
    }
    val dialog = remember(asked) { ConfirmDialog(extraFillers = if (orderWords != null) ConfirmDialog.ORDER_FILLERS else emptySet()) }
    val undoing = orderWords?.undoing ?: when (asked.afterCancel) {
        AfterCancel.REMOVE_TYPED -> "I'm removing the message I typed."
        AfterCancel.RESTORE_ORIGINAL -> "I'm putting your earlier text back."
        AfterCancel.LEAVE_AS_IS -> "Your text stays as it was."
    }
    val title = orderWords?.title ?: if (confirmTo.isNotEmpty()) "Send to $confirmTo?" else FloatingTexts.CONFIRM_TITLE_SEND
    val cancelDescription = orderWords?.cancelDescription ?: FloatingTexts.cancelSendDesc(undoing)
    val sendLabel = orderWords?.confirmLabel ?: FloatingTexts.SEND
    val sendDescription = orderWords?.confirmDescription ?: FloatingTexts.sendDesc(confirmText, confirmTo)
    val hint = t.confirmHint

    var finished by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    val shownAt = remember { SystemClock.uptimeMillis() }

    lateinit var listener: ScreenParts.ScreenListener

    fun cancel(source: String) {
        if (finished) return
        finished = true
        listener.cancel()
        deps.voice.stopSpeaking()
        deps.feedback.cue(Cue.Cancelled)
        // The session says what happened next ("Cancelled. Not sent …").
        TaskRunner.answer(Answer.NO, source)
    }

    fun send(source: String) {
        if (finished) return
        if (source == "floating tap" && !AnswerArming.armed(shownAt, SystemClock.uptimeMillis())) {
            Log.i(TAG, "send tap ignored: sheet appeared ${SystemClock.uptimeMillis() - shownAt} ms ago")
            return
        }
        finished = true
        listener.cancel()
        deps.voice.stopSpeaking()
        // A clear yes (tap or voice): the runner re-checks the screen and the gate, then sends.
        TaskRunner.answer(Answer.YES, source)
    }

    fun askAgainOrCancel(next: ConfirmDialog.Next) {
        if (next == ConfirmDialog.Next.CANCEL) {
            cancel("floating unclear")
            return
        }
        asking = true
        scope.launch {
            deps.feedback.cue(Cue.NeedsAnswer)
            deps.voice.speak("${FloatingTexts.NOT_SURE} $confirmQuestion")
            ScreenParts.awaitSpeechEnd(deps.voice)
            asking = false
            if (!finished) listener.start()
        }
    }

    // Everything this sheet says or shows (and the panel around it): a reply repeating it is an echo.
    val sheetWords = listOf(
        confirmQuestion, title, confirmText, confirmTo, FloatingTexts.CONFIRM_EXACT, "Safety check: ${asked.readback}",
        cancelDescription, sendLabel, sendDescription, hint, "${FloatingTexts.NOT_SURE} $confirmQuestion", undoing,
        "WhatsApp", "Blinkit",
    ) + orderWords?.sheetWords.orEmpty() + confirming.lines + FloatingTexts.all

    listener = ScreenParts.rememberAnswerListener(
        sheetTexts = sheetWords,
        onUnclear = { askAgainOrCancel(dialog.silence()) },
    ) { heard, sources ->
        val next = dialog.hear(heard, sources)
        Log.i(TAG, "confirm voice answer: $next (${dialog.lastWhy}, unclear so far ${dialog.unclear}), length ${heard.text.length}, ${ScreenParts.afterSpeech(heard)}")
        when (next) {
            ConfirmDialog.Next.SEND -> send("floating voice")
            ConfirmDialog.Next.CANCEL -> cancel("floating voice")
            ConfirmDialog.Next.ASK_AGAIN -> askAgainOrCancel(next)
        }
    }

    ScreenParts.OnFirstShow {
        deps.feedback.cue(Cue.NeedsAnswer)
        deps.voice.speak(confirmQuestion)
    }
    // Panel hidden / closed / replaced without an answer: Cancel (never left waiting, never a yes).
    DisposableEffect(Unit) { onDispose { if (!finished) TaskRunner.answer(Answer.NO, "floating sheet closed") } }

    Column(
        Modifier.fillMaxWidth().semantics { paneTitle = "Needs your answer" },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AskCapsule()
        Text(title, style = Clarity.type.headline, color = c.label, modifier = Modifier.semantics { heading() })
        if (orderWords != null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                orderWords.lines.forEach { Text(it, style = Clarity.type.body.copy(fontWeight = FontWeight.Medium), color = c.label) }
                Text(orderWords.total, style = Clarity.type.body, color = c.label)
                Footnote(orderWords.note)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("“$confirmText”", style = Clarity.type.body.copy(fontWeight = FontWeight.Medium), color = c.label)
                Footnote(FloatingTexts.CONFIRM_EXACT)
            }
        }
        // Cancel first: the default (TalkBack reaches it before the acting button).
        SecondaryButton(FloatingTexts.CANCEL, { cancel("floating tap cancel") }, description = cancelDescription, tag = FloatingTexts.CANCEL_DEFAULT)
        PrimaryButton(sendLabel, { send("floating tap") }, description = sendDescription)
        // The safety gate's read-back, verbatim (the source of the words above).
        Footnote("Safety check: ${asked.readback}")
        ScreenParts.ListenHint(listener)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Footnote(if (asking) FloatingTexts.ASKING_AGAIN else hint, Modifier.weight(1f))
            ScreenParts.TalkButton(
                description = if (listener.active) FloatingTexts.STOP_LISTENING else "Answer by voice",
                active = listener.active,
                onClick = { if (!asking) listener.toggle() },
            )
        }
    }
}

@Composable
fun FloatingPickSheet(choosing: TaskRunner.State.ChoosingItem) {
    val deps = LocalDeps.current
    val c = Clarity.colors
    val scope = rememberCoroutineScope()
    val req = choosing.request
    val options = req.options
    val question = req.question
    val dialog = remember(req) { PickDialog(options) }

    var finished by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    val shownAt = remember { SystemClock.uptimeMillis() }

    lateinit var listener: ScreenParts.ScreenListener

    fun answer(index: Int?, source: String) {
        if (finished) return
        if (source == "floating tap" && index != null && !AnswerArming.armed(shownAt, SystemClock.uptimeMillis())) {
            Log.i(TAG, "item pick tap ignored: sheet appeared ${SystemClock.uptimeMillis() - shownAt} ms ago")
            return
        }
        finished = true
        listener.cancel()
        deps.voice.stopSpeaking()
        if (index == null) deps.feedback.cue(Cue.Cancelled)
        TaskRunner.pickItem(index, source)
    }

    fun askAgainOrCancel(next: PickDialog.Next) {
        if (next == PickDialog.Next.Cancel) {
            answer(null, "floating unclear")
            return
        }
        asking = true
        scope.launch {
            deps.feedback.cue(Cue.NeedsAnswer)
            deps.voice.speak("${FloatingTexts.NOT_SURE} $question")
            ScreenParts.awaitSpeechEnd(deps.voice)
            asking = false
            if (!finished) listener.start()
        }
    }

    val hint = req.hint
    // No position in these ("Option 1"): a "first" must never be in what the sheet says.
    val optionDescriptions = options.map { "$it. ${FloatingTexts.PICK_OPTION_SUFFIX}" }
    val sheetWords = listOf(
        question, "${FloatingTexts.NOT_SURE} $question", hint, FloatingTexts.PICK_FOOTNOTE, FloatingTexts.PICK_CANCEL_DESC, "Blinkit",
    ) + options + optionDescriptions + choosing.lines + FloatingTexts.all

    listener = ScreenParts.rememberAnswerListener(
        sheetTexts = sheetWords,
        onUnclear = { askAgainOrCancel(dialog.silence()) },
    ) { heard, sources ->
        val next = dialog.hear(heard, sources)
        Log.i(TAG, "item pick voice answer: ${next::class.simpleName} (${dialog.lastWhy}, unclear so far ${dialog.unclear}), length ${heard.text.length}, ${ScreenParts.afterSpeech(heard)}")
        when (next) {
            is PickDialog.Next.Picked -> answer(next.index, "floating voice")
            PickDialog.Next.Cancel -> answer(null, "floating voice")
            PickDialog.Next.AskAgain -> askAgainOrCancel(next)
        }
    }

    ScreenParts.OnFirstShow {
        deps.feedback.cue(Cue.NeedsAnswer)
        deps.voice.speak(question)
    }
    DisposableEffect(Unit) { onDispose { if (!finished) TaskRunner.pickItem(null, "floating sheet closed") } }

    Column(
        Modifier.fillMaxWidth().semantics { paneTitle = "Needs your answer" },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AskCapsule()
        Text(question, style = Clarity.type.headline, color = c.label, modifier = Modifier.semantics { heading() })
        SecondaryButton(FloatingTexts.CANCEL, { answer(null, "floating tap") }, description = FloatingTexts.PICK_CANCEL_DESC, tag = FloatingTexts.CANCEL_DEFAULT)
        options.forEachIndexed { i, label ->
            SecondaryButton(label, { answer(i, "floating tap") }, description = optionDescriptions[i])
        }
        Footnote(FloatingTexts.PICK_FOOTNOTE)
        ScreenParts.ListenHint(listener)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Footnote(if (asking) FloatingTexts.ASKING_AGAIN else hint, Modifier.weight(1f))
            ScreenParts.TalkButton(
                description = if (listener.active) FloatingTexts.STOP_LISTENING else "Answer by voice",
                active = listener.active,
                onClick = { if (!asking) listener.toggle() },
            )
        }
    }
}

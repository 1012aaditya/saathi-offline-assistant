package app.saathi.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.core.content.ContextCompat
import app.saathi.agent.EchoGuard
import app.saathi.agent.Heard
import app.saathi.agent.Requests
import app.saathi.feedback.Cue
import app.saathi.feedback.Feedback
import app.saathi.feedback.reducedMotion
import app.saathi.ui.AppNav
import app.saathi.ui.Deps
import app.saathi.ui.LocalDeps
import app.saathi.ui.Route
import app.saathi.ui.routeForPhrase
import app.saathi.ui.theme.Clarity
import app.saathi.voice.Voice
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Helpers shared by the step-1 screens (Welcome … Handover). Everything lives in one object so
 * names can't clash with other files in this package.
 */
object ScreenParts {

    /** Honest label for anything that is still a practice (sample data), e.g. Walk mode demos. */
    const val PRACTICE_NOTE = "Practice run — sample data. Saathi can't use your apps yet in this version."

    const val MIC_NEEDED = "Saathi needs the microphone to hear you. You can allow it in app settings."

    private val Ease = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

    // ---------------------------------------------------------------- motion

    /** Android "Remove animations". Read once per screen; motion off, final states shown. */
    @Composable
    fun rememberStill(): Boolean {
        val context = LocalContext.current
        return remember { reducedMotion(context) }
    }

    /** Content fades and rises 12dp over 350ms, staggered 40ms per [index] (DESIGN.md). */
    fun rise(index: Int, still: Boolean): Modifier = Modifier.composed {
        val p = remember { Animatable(if (still) 1f else 0f) }
        LaunchedEffect(Unit) {
            if (!still) {
                delay(40L * index)
                p.animateTo(1f, tween(350, easing = Ease))
            }
        }
        Modifier.graphicsLayer {
            alpha = p.value
            translationY = (1f - p.value) * 12.dp.toPx()
        }
    }

    /** A checkmark that draws itself in. Decorative: the row around it carries the words. */
    @Composable
    fun DrawnCheck(
        color: Color,
        size: Dp,
        stroke: Dp,
        still: Boolean,
        modifier: Modifier = Modifier,
        delayMs: Int = 100,
        durationMs: Int = 400,
    ) {
        val p = remember { Animatable(if (still) 1f else 0f) }
        LaunchedEffect(Unit) {
            if (!still) {
                delay(delayMs.toLong())
                p.animateTo(1f, tween(durationMs, easing = LinearOutSlowInEasing))
            }
        }
        Canvas(modifier.size(size)) {
            val s = this.size.width / 22f
            val path = Path().apply {
                moveTo(4f * s, 11.5f * s)
                lineTo(8.5f * s, 16f * s)
                lineTo(18f * s, 6.5f * s)
            }
            val measure = PathMeasure()
            measure.setPath(path, false)
            val part = Path()
            measure.getSegment(0f, measure.length * p.value, part, true)
            drawPath(part, color, style = Stroke(width = stroke.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    /** Accent circle that pops in, then its checkmark draws (Setup done, Result). */
    @Composable
    fun CheckBadge(size: Dp, still: Boolean, modifier: Modifier = Modifier) {
        val c = Clarity.colors
        val pop = remember { Animatable(if (still) 1f else 0f) }
        LaunchedEffect(Unit) { if (!still) pop.animateTo(1f, tween(400, easing = Ease)) }
        Box(
            modifier
                .size(size)
                .graphicsLayer {
                    val s = 0.6f + 0.4f * pop.value
                    scaleX = s
                    scaleY = s
                    alpha = pop.value
                }
                .clip(CircleShape)
                .background(c.accent)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            DrawnCheck(c.onAccent, size * 0.55f, size / 18, still, delayMs = 250, durationMs = 500)
        }
    }

    /** Small bar meter. Animates only while [active] and motion is allowed. Decorative. */
    @Composable
    fun LevelBars(
        active: Boolean,
        still: Boolean,
        color: Color,
        modifier: Modifier = Modifier,
        count: Int = 5,
        barWidth: Dp = 4.dp,
        minHeight: Dp = 6.dp,
        maxHeight: Dp = 20.dp,
    ) {
        Row(
            modifier.height(maxHeight).clearAndSetSemantics {},
            horizontalArrangement = Arrangement.spacedBy(barWidth),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (active && !still) {
                val t = rememberInfiniteTransition(label = "bars")
                repeat(count) { i ->
                    val f = t.animateFloat(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(500, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse,
                            initialStartOffset = StartOffset(i * 120),
                        ),
                        label = "bar$i",
                    ).value
                    Box(Modifier.width(barWidth).height(lerp(minHeight, maxHeight, f)).clip(RoundedCornerShape(barWidth / 2)).background(color))
                }
            } else {
                repeat(count) {
                    Box(Modifier.width(barWidth).height((minHeight + maxHeight) / 2).clip(RoundedCornerShape(barWidth / 2)).background(color))
                }
            }
        }
    }

    // ---------------------------------------------------------------- honest labels

    /** "Practice run — sample data …" — on every practice screen. */
    @Composable
    fun PracticeLabel(modifier: Modifier = Modifier, text: String = PRACTICE_NOTE) {
        val c = Clarity.colors
        val shape = RoundedCornerShape(12.dp)
        Row(
            modifier
                .fillMaxWidth()
                .clip(shape)
                .border(1.dp, c.separator, shape)
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = c.secondary, modifier = Modifier.padding(top = 1.dp).size(22.dp))
            Text(text, style = Clarity.type.footnote, color = c.label)
        }
    }

    /** Grey rounded note (e.g. "Arrives in the next update", "Without this, …"). */
    @Composable
    fun NoteBox(
        text: String,
        modifier: Modifier = Modifier,
        icon: ImageVector = Icons.Outlined.Info,
        iconTint: Color? = null,
        extra: (@Composable () -> Unit)? = null,
    ) {
        val c = Clarity.colors
        Row(
            modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.group).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(icon, contentDescription = null, tint = iconTint ?: c.secondary, modifier = Modifier.padding(top = 2.dp).size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(text, style = Clarity.type.callout, color = c.label, modifier = Modifier.semantics(mergeDescendants = true) {})
                extra?.invoke()
            }
        }
    }

    // ---------------------------------------------------------------- once-per-screen

    /** Runs [block] the first time a screen shows (not again on rotation). */
    @Composable
    fun OnFirstShow(block: () -> Unit) {
        var shown by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            if (!shown) {
                shown = true
                block()
            }
        }
    }

    // ---------------------------------------------------------------- permissions

    fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    // ---------------------------------------------------------------- on-screen listening

    /**
     * Listening without leaving the screen (Confirm's yes/no, "Repeat" on Result, "Continue" on
     * Welcome …). Asks for the microphone itself if needed. Only reacts to sessions it started.
     *
     * Never hears Saathi itself: [Voice.startListening] stops Saathi's speech and opens the mic
     * only after the speech-to-listen gap; Saathi speaking meanwhile cancels the session; a
     * result too close to Saathi's speech is discarded (arrives here as "I didn't catch that.").
     * The listening tone plays when the mic actually opens.
     */
    @Stable
    class ScreenListener internal constructor(private val voice: Voice, private val feedback: Feedback) {
        var active by mutableStateOf(false)
            internal set
        var problem by mutableStateOf<String?>(null)
            internal set
        internal var hasMic: () -> Boolean = { false }
        internal var askMic: () -> Unit = {}

        fun start() {
            problem = null
            if (!hasMic()) {
                askMic()
                return
            }
            begin()
        }

        internal fun begin() {
            problem = null
            voice.reset()
            active = true
            voice.startListening(onOpen = { feedback.cue(Cue.StartListening) })
        }

        /** Talk button: start, or finish the current utterance. */
        fun toggle() {
            if (active) voice.stopListening() else start()
        }

        /** Finish the current utterance (never starts listening). */
        fun finish() {
            if (active) voice.stopListening()
        }

        fun cancel() {
            if (active) {
                active = false
                voice.cancelListening()
                feedback.cue(Cue.StopListening)
            }
        }
    }

    /** What every unclear / discarded hearing is reported as (the answer screens count it as unclear). */
    const val NOT_CAUGHT = "I didn't catch that."

    /**
     * For screens whose spoken words don't answer a question (Welcome, Result, Working, Photo …):
     * a transcript that repeats what Saathi said in the last 15 s ([EchoGuard.looksLikeEcho]) is
     * dropped and reported as [NOT_CAUGHT]. Short commands ("continue", "repeat") always pass;
     * timing guards them.
     *
     * NEVER for a sheet that waits for an answer (yes / no, a choice, a pick): those use
     * [rememberAnswerListener]. AnswerSheetsTest enforces it.
     */
    @Composable
    fun rememberScreenListener(
        onFailed: (String) -> Unit = {},
        onHeard: (String) -> Unit,
    ): ScreenListener {
        val deps = LocalDeps.current
        val heard by rememberUpdatedState(onHeard)
        return rememberListener(onFailed) { l ->
            if (EchoGuard.looksLikeEcho(l.text, deps.voice.recentPrompts(15_000))) {
                Log.i("SaathiVoice", "screen listener: dropped an echo of Saathi's own words, length ${l.text.length}")
                NOT_CAUGHT
            } else {
                heard(l.text)
                null
            }
        }
    }

    /**
     * Guide mode's push-to-talk: the transcript WITH how long after Saathi's speech it was heard
     * ([Heard.msAfterSpeech]), so the session can refuse anything heard under
     * [EchoGuard.MIN_HEARD_AFTER_SPEECH_MS] (and long echoes of its own description). Only ever
     * started by the user (Speak button, Volume-down): the guide never listens by itself.
     * Never for an answer sheet (those use [rememberAnswerListener]).
     */
    @Composable
    fun rememberCommandListener(
        onFailed: (String) -> Unit = {},
        onHeard: (Heard) -> Unit,
    ): ScreenListener {
        val heard by rememberUpdatedState(onHeard)
        return rememberListener(onFailed) { l ->
            heard(Heard(l.text, l.msAfterSpeech))
            null
        }
    }

    /**
     * Settings > "Listen after Saathi speaks" ([app.saathi.agent.AutoListen]): call from the guide's
     * new-turn effect, so the next turn cancels the wait. Waits for Saathi's words to start (the
     * runner speaks them) and then to end; [Voice.startListening] adds the speech-to-listen gap and
     * cancels the session if Saathi speaks again. [stillOk] is checked last (e.g. a question sheet
     * took over). Never asks for the microphone permission by itself.
     */
    suspend fun listenAfterSpeech(voice: Voice, listener: ScreenListener, stillOk: () -> Boolean = { true }) {
        withTimeoutOrNull(SPEECH_START_WAIT_MS) { voice.speaking.first { it } }
        voice.speaking.first { !it }
        if (listener.active || !listener.hasMic() || !stillOk()) return
        Log.i("SaathiVoice", "guide: listening after Saathi's turn (Listen after Saathi speaks is on)")
        listener.begin()
    }

    private const val SPEECH_START_WAIT_MS = 1_500L

    /**
     * THE way an answer sheet (Confirm, order / empty-cart confirm, Draft choice, "Which Rahul?",
     * "Open Camera?", grocery "which one?") hears its answer. Listening goes through Voice's
     * interlock (never while Saathi speaks, and only [EchoGuard.LISTEN_AFTER_SPEECH_MS] after);
     * each transcript comes with how long after Saathi's speech it was heard ([Heard]) and with
     * the echo sources: what Saathi said in the last 30 s + [sheetTexts] (every title, question,
     * option, button label, content description and hint the sheet shows) + the listening chrome.
     * The sheet passes both to its dialog's `hear`, i.e. the shared [app.saathi.agent.VoiceAnswer]
     * pipeline. [onUnclear]: nothing usable was heard (silence, an error, a discarded echo).
     */
    @Composable
    fun rememberAnswerListener(
        sheetTexts: List<String>,
        onUnclear: () -> Unit,
        onAnswer: (heard: Heard, echoSources: List<String>) -> Unit,
    ): ScreenListener {
        val deps = LocalDeps.current
        val answer by rememberUpdatedState(onAnswer)
        val unclear by rememberUpdatedState(onUnclear)
        val texts by rememberUpdatedState(sheetTexts)
        return rememberListener(onFailed = { reason -> if (reason == NOT_CAUGHT) unclear() }) { l ->
            val sources = deps.voice.recentPrompts(30_000) + texts + ANSWER_CHROME
            answer(Heard(l.text, l.msAfterSpeech), sources)
            null
        }
    }

    /** "2300 ms after Saathi spoke" for the answer logs (never the transcript). */
    fun afterSpeech(heard: Heard): String =
        if (heard.msAfterSpeech == Heard.LONG_AFTER) "Saathi hadn't spoken" else "${heard.msAfterSpeech} ms after Saathi spoke"

    /** What every answer sheet shows / TalkBack says around listening (echo sources too). */
    val ANSWER_CHROME: List<String> = listOf(
        "Needs your answer", "Listening", "Listening…", "Not listening", "Answer by voice", "Stop listening",
        "Asking again…", "Default", NOT_CAUGHT, "Listening stopped.", "Sorry, I'm not sure.", "You can say",
    )

    /**
     * Listening without leaving the screen. Asks for the microphone itself if needed. Only reacts
     * to sessions it started. [onText] returns null when the transcript was used, or the reason
     * to report it as not caught.
     */
    @Composable
    private fun rememberListener(
        onFailed: (String) -> Unit,
        onText: (Voice.Listen.Heard) -> String?,
    ): ScreenListener {
        val deps = LocalDeps.current
        val context = LocalContext.current
        val handle by rememberUpdatedState(onText)
        val failed by rememberUpdatedState(onFailed)
        val listener = remember { ScreenListener(deps.voice, deps.feedback) }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) listener.begin() else listener.problem = MIC_NEEDED
        }
        listener.hasMic = { hasPermission(context, Manifest.permission.RECORD_AUDIO) }
        listener.askMic = { launcher.launch(Manifest.permission.RECORD_AUDIO) }

        val listen by deps.voice.listen.collectAsState()
        LaunchedEffect(listen) {
            if (!listener.active) return@LaunchedEffect
            when (val l = listen) {
                is Voice.Listen.Heard -> {
                    listener.active = false
                    deps.voice.reset()
                    deps.feedback.cue(Cue.StopListening)
                    handle(l)?.let { why ->
                        listener.problem = why
                        failed(why)
                    }
                }
                is Voice.Listen.Failed -> {
                    listener.active = false
                    deps.voice.reset()
                    listener.problem = l.reason
                    deps.feedback.cue(Cue.StopListening)
                    failed(l.reason)
                }
                else -> Unit
            }
        }
        DisposableEffect(Unit) { onDispose { listener.cancel() } }
        return listener
    }

    /** One line under the actions: "Listening…" or why listening stopped. Polite live region. */
    @Composable
    fun ListenHint(listener: ScreenListener) {
        val text = when {
            listener.active -> "Listening…"
            else -> listener.problem
        } ?: return
        Text(
            text,
            style = Clarity.type.callout,
            color = Clarity.colors.label,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }

    /** 56dp talk button with a listening state (used where the screen has its own voice answer). */
    @Composable
    fun TalkButton(description: String, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
        val c = Clarity.colors
        Box(
            modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(if (active) c.accent else c.group)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics {
                    contentDescription = description
                    stateDescription = if (active) "Listening" else "Not listening"
                },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Mic, contentDescription = null, tint = if (active) c.onAccent else c.label) }
    }

    /** Wait until the current TTS line has been spoken (so the mic doesn't cut it off). */
    suspend fun awaitSpeechEnd(voice: Voice) {
        withTimeoutOrNull(2_000) { voice.speaking.first { it } } ?: return
        withTimeoutOrNull(15_000) { voice.speaking.first { !it } }
    }

    /** Anything said on a screen that isn't one of its own phrases is treated as a new request. */
    fun handleAsRequest(text: String, deps: Deps, nav: AppNav) = Requests.handle(text, deps, nav)

    private val wordSplit = Regex("[^\\p{L}\\p{M}\\p{N}']+")

    fun words(text: String): Set<String> = text.lowercase().split(wordSplit).filter { it.isNotBlank() }.toSet()
}

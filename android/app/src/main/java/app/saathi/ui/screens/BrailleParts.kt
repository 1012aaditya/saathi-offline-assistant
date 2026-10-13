package app.saathi.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.saathi.braille.BrailleHaptics
import app.saathi.braille.Cell
import app.saathi.braille.Document
import app.saathi.braille.Pace
import app.saathi.braille.Position
import app.saathi.braille.RhythmPlayer
import app.saathi.braille.Word
import app.saathi.ui.LocalDeps
import app.saathi.ui.components.Footnote
import app.saathi.ui.components.Group
import app.saathi.ui.components.PrimaryButton
import app.saathi.ui.components.SecondaryButton
import app.saathi.ui.theme.Clarity
import app.saathi.voice.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import java.util.Locale
import kotlin.math.abs

/**
 * Word-by-word rhythm reading. Each word plays as rhythm braille; the reader moves by word or by
 * sentence, repeats just the missed word, and the pace adapts (repeats slow it, smooth reading
 * speeds it up). Private text (codes/passwords): never spoken, never drawn.
 */
@Stable
class RhythmReaderState internal constructor(
    val doc: Document,
    val private: Boolean,
    private val player: RhythmPlayer,
    private val haptics: BrailleHaptics,
    private val voice: Voice,
    private val scope: CoroutineScope,
    private val pace: Pace,
    private val speakWords: () -> Boolean,
    private val onPace: (Float) -> Unit,
) {
    var pos by mutableStateOf(Position(0, 0)); private set
    /** Cell being played in the current word, or -1. */
    var cell by mutableIntStateOf(-1); private set
    var playing by mutableStateOf(false); private set
    var continuous by mutableStateOf(false); private set
    var speed by mutableFloatStateOf(pace.value); private set

    private var job: Job? = null

    val word: Word get() = doc.word(pos)

    private fun syncPace() {
        speed = pace.value
        onPace(pace.value)
    }

    private suspend fun playOnce() {
        player.playWord(word, doc.endsSentence(pos), pace.value) { cell = it }
        if (!private && speakWords()) {
            voice.speak(word.text)
            ScreenParts.awaitSpeechEnd(voice)
        }
    }

    private fun launch(block: suspend () -> Unit) {
        job = scope.replaceJob(job) {
            playing = true
            try { block() } finally {
                // Only the latest playback resets the state (a replaced one finishes quietly).
                if (job == coroutineContext[Job]) {
                    playing = false
                    continuous = false
                    cell = -1
                }
            }
        }
    }

    fun play() = launch { playOnce() }

    fun next() {
        val n = doc.next(pos) ?: return end()
        pace.advanced(); syncPace()
        pos = n
        play()
    }

    fun previous() {
        val p = doc.previous(pos) ?: return haptics.end()
        pos = p
        play()
    }

    /** Repeat only this word (recovery: no need to restart the sentence). */
    fun repeat() {
        pace.repeated(); syncPace()
        play()
    }

    fun nextSentence() {
        val n = doc.nextSentence(pos) ?: return end()
        pos = n
        play()
    }

    fun previousSentence() {
        val p = doc.previousSentence(pos) ?: return haptics.end()
        pos = p
        play()
    }

    /** Keep reading word after word until the end, or until Stop. */
    fun readOn() {
        if (continuous) return stop()
        launch {
            continuous = true
            while (true) {
                playOnce()
                pos = doc.next(pos) ?: break
            }
            end()
        }
    }

    fun stop() {
        job?.cancel()
        haptics.stopPulses()
    }

    /** Hold: say the word and spell it. Public text only. */
    fun hear() {
        if (private) return
        stop()
        val letters = word.text.filter { it.isLetterOrDigit() }.toList().joinToString(", ")
        voice.speak("${word.text}. $letters")
    }

    fun faster() { pace.faster(); syncPace(); voice.speak("Faster") }
    fun slower() { pace.slower(); syncPace(); voice.speak("Slower") }

    private fun end() {
        haptics.end()
        voice.speak("End of text.")
    }
}

/**
 * Cancel [prev] and wait until it has fully stopped before running [block], so the old playback's
 * cleanup (which silences the motor/speaker) can't cut off the new one.
 */
fun CoroutineScope.replaceJob(prev: Job?, block: suspend CoroutineScope.() -> Unit): Job = launch {
    prev?.cancelAndJoin()
    block()
}

@Composable
fun rememberRhythmReader(doc: Document, private: Boolean, haptics: BrailleHaptics): RhythmReaderState {
    val deps = LocalDeps.current
    val scope = rememberCoroutineScope()
    val state = remember(doc) {
        RhythmReaderState(
            doc = doc,
            private = private,
            player = RhythmPlayer(haptics),
            haptics = haptics,
            voice = deps.voice,
            scope = scope,
            pace = Pace(deps.state.braillePace),
            speakWords = { deps.state.brailleSpeak },
            onPace = { deps.state.updateBraillePace(it) },
        )
    }
    DisposableEffect(state) { onDispose { state.stop() } }
    return state
}

/** The reading area + controls. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun RhythmReaderView(r: RhythmReaderState, modifier: Modifier = Modifier) {
    val c = Clarity.colors
    val sentence = r.pos.sentence + 1
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Word ${r.doc.ordinal(r.pos)} of ${r.doc.wordCount} · Sentence $sentence of ${r.doc.sentences.size}",
            style = Clarity.type.headline,
            color = c.label,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Footnote("Speed ${String.format(Locale.US, "%.1f", r.speed)}× · " + if (r.playing) "Playing…" else "Ready")

        val area = if (r.private) "Reading area. Double-tap to repeat this word."
        else "Reading area. Double-tap to repeat this word. Double-tap and hold to hear and spell it."
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 220.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(c.group)
                .combinedClickable(
                    role = Role.Button,
                    onClick = { r.repeat() },
                    onLongClick = { r.hear() },
                )
                // Swipes work when TalkBack is off (TalkBack keeps swipes for itself; use the buttons then).
                .pointerInput(r) {
                    var dx = 0f
                    var dy = 0f
                    detectDragGestures(
                        onDragStart = { dx = 0f; dy = 0f },
                        onDragEnd = {
                            val t = 56.dp.toPx()
                            when {
                                abs(dx) > abs(dy) && abs(dx) > t -> if (dx > 0) r.next() else r.previous()
                                abs(dy) > t -> if (dy > 0) r.nextSentence() else r.previousSentence()
                            }
                        },
                    ) { change, drag ->
                        change.consume()
                        dx += drag.x
                        dy += drag.y
                    }
                }
                .clearAndSetSemantics { contentDescription = area }
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (r.private) {
                    Text("Private word", style = Clarity.type.title, color = c.label)
                    Footnote("${r.word.cells.size} cells · dots hidden", align = TextAlign.Center)
                } else {
                    Text(r.word.text, style = Clarity.type.largeTitle, color = c.label, textAlign = TextAlign.Center)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        r.word.cells.forEachIndexed { i, cell -> MiniCell(cell, highlighted = i == r.cell) }
                    }
                }
                Footnote("Tap: repeat word" + if (r.private) "" else " · Hold: hear and spell", align = TextAlign.Center)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("Back", { r.previous() }, Modifier.weight(1f), description = "Previous word")
            PrimaryButton("Next", { r.next() }, Modifier.weight(1f), description = "Next word")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("Repeat", { r.repeat() }, Modifier.weight(1f), description = "Repeat this word")
            SecondaryButton(
                if (r.continuous) "Stop" else "Read on",
                { r.readOn() },
                Modifier.weight(1f),
                description = if (r.continuous) "Stop reading" else "Read on, word after word",
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("‹ Sentence", { r.previousSentence() }, Modifier.weight(1f), description = "Previous sentence")
            SecondaryButton("Sentence ›", { r.nextSentence() }, Modifier.weight(1f), description = "Next sentence")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("Slower", { r.slower() }, Modifier.weight(1f), description = "Slower rhythm")
            SecondaryButton("Faster", { r.faster() }, Modifier.weight(1f), description = "Faster rhythm")
        }
    }
}

/** Small drawn braille cell (for sighted helpers and low vision). Decorative for TalkBack. */
@Composable
fun MiniCell(cell: Cell, highlighted: Boolean = false, size: Float = 1f) {
    val c = Clarity.colors
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .clip(shape)
            .then(if (highlighted) Modifier.background(c.accent).border(2.dp, c.accent, shape) else Modifier.border(1.dp, c.separator, shape))
            .padding((6 * size).dp)
            .clearAndSetSemantics {},
    ) {
        val fg = if (highlighted) c.onAccent else c.label
        val outline = if (highlighted) c.onAccent else c.secondary
        Canvas(Modifier.size((22 * size).dp, (34 * size).dp)) {
            val r = this.size.width / 5.5f
            for (col in 0..1) for (row in 0..2) {
                val center = Offset(r * 1.2f + col * (this.size.width - r * 2.4f), r * 1.2f + row * ((this.size.height - r * 2.4f) / 2))
                if (cell.raised(col * 3 + row + 1)) drawCircle(fg, r, center)
                else drawCircle(outline, r * 0.85f, center, style = Stroke(width = r * 0.35f))
            }
        }
    }
}

/** A labelled on/off row (braille settings). */
@Composable
fun BrailleSwitch(title: String, subtitle: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val c = Clarity.colors
    Group {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = Clarity.type.body, color = c.label)
                Text(subtitle, style = Clarity.type.footnote, color = c.secondary)
            }
            Switch(
                checked = on,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = c.onAccent,
                    checkedTrackColor = c.accent,
                    checkedBorderColor = c.accent,
                    uncheckedThumbColor = c.secondary,
                    uncheckedTrackColor = c.screen,
                    uncheckedBorderColor = c.secondary,
                ),
            )
        }
    }
}

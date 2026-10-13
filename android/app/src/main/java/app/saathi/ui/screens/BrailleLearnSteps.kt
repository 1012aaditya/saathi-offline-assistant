package app.saathi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.saathi.braille.BrailleCellView
import app.saathi.braille.BrailleHaptics
import app.saathi.braille.Cell
import app.saathi.braille.Document
import app.saathi.braille.Mastery
import app.saathi.braille.Pace
import app.saathi.braille.RhythmPlayer
import app.saathi.braille.Step
import app.saathi.braille.Word
import app.saathi.braille.describeDots
import app.saathi.braille.encode
import app.saathi.braille.itemName
import app.saathi.braille.memoryHint
import app.saathi.feedback.Cue
import app.saathi.ui.Deps
import app.saathi.ui.components.Body
import app.saathi.ui.components.Footnote
import app.saathi.ui.components.LargeTitle
import app.saathi.ui.components.PrimaryButton
import app.saathi.ui.components.SecondaryButton
import app.saathi.ui.theme.Clarity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.util.Locale

/** Shared things every lesson step needs. */
class LearnKit(val deps: Deps, val haptics: BrailleHaptics) {
    val player = RhythmPlayer(haptics)
}

private fun cellOf(ch: Char): Cell = encode(ch.toString()).last()

private fun wordOf(text: String) = Word(text, encode(text))

/** One step of a lesson. Calls [onReady] when the learner may continue. */
@Composable
fun StepView(step: Step, kit: LearnKit, onReady: () -> Unit) {
    when (step) {
        is Step.Say -> SayStep(step.text, kit, onReady)
        is Step.FindDot -> FindDotStep(step.dot, kit, onReady)
        is Step.Meet -> MeetStep(step.char, kit, onReady)
        is Step.Compare -> CompareStep(step.from, step.to, kit, onReady)
        is Step.Listen -> ListenStep(step.text, step.explain, kit, onReady)
        is Step.Quiz -> QuizStep(step.pool, step.pool, step.questions, step.pass, kit, onReady)
        is Step.Build -> BuildStep(step.char, kit, onReady)
        is Step.Read -> ReadStep(step.text, step.explain, kit, onReady)
    }
}

/** Plays rhythm in a coroutine that a newer play replaces cleanly. */
@Composable
internal fun rememberPlayback(): Playback {
    val scope = rememberCoroutineScope()
    val p = remember { Playback(scope) }
    DisposableEffect(Unit) { onDispose { p.stop() } }
    return p
}

internal class Playback(private val scope: kotlinx.coroutines.CoroutineScope) {
    private var job: Job? = null
    fun run(block: suspend () -> Unit) { job = scope.replaceJob(job) { block() } }
    fun stop() { job?.cancel() }
}

private suspend fun sayThenWait(kit: LearnKit, text: String) {
    kit.deps.voice.speak(text)
    ScreenParts.awaitSpeechEnd(kit.deps.voice)
}

// ------------------------------------------------------------------ Say

@Composable
private fun SayStep(text: String, kit: LearnKit, onReady: () -> Unit) {
    LaunchedEffect(text) {
        onReady()
        kit.deps.voice.speak(text)
    }
    Text(text, style = Clarity.type.title, color = Clarity.colors.label)
}

// ------------------------------------------------------------------ Find the dot

/** Big touch cell for learning. [cell] = which dots buzz; [onZone] = finger moved onto a dot place. */
@Composable
fun LearnCell(cell: Cell, kit: LearnKit, hidden: Boolean = false, height: Int = 360, onZone: (Int) -> Unit = {}) {
    val c = Clarity.colors
    val raised = c.label.toArgb()
    val flat = c.secondary.toArgb()
    val divider = c.separator.toArgb()
    AndroidView(
        factory = { ctx -> BrailleCellView(ctx, kit.haptics) },
        update = { v ->
            v.private = hidden
            v.cell = cell
            v.colors = BrailleCellView.Colors(raised = raised, divider = divider, flat = flat)
            v.onDot = { d -> if (d != null) onZone(d) }
        },
        modifier = Modifier.fillMaxWidth().height(height.dp),
    )
}

@Composable
private fun FindDotStep(dot: Int, kit: LearnKit, onReady: () -> Unit) {
    var found by remember(dot) { mutableStateOf(false) }
    LaunchedEffect(dot) { kit.deps.voice.speak("Find dot $dot. Slide one finger slowly; I'll say each dot you touch.") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LargeTitle("Find dot $dot")
        Body(
            if (found) "Found it." else "Left column: 1, 2, 3 from the top. Right column: 4, 5, 6.",
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    // Only the target dot buzzes, so the finger can also find it by feel.
    LearnCell(Cell(1 shl (dot - 1), Cell.Kind.Unknown, ""), kit, hidden = !found) { d ->
        if (found) return@LearnCell
        if (d == dot) {
            found = true
            kit.deps.feedback.cue(Cue.Done)
            kit.deps.voice.speak("Yes, that's dot $dot.")
            onReady()
        } else {
            kit.deps.voice.speak("$d")
        }
    }
}

// ------------------------------------------------------------------ Meet a letter

private fun introLine(ch: Char): String {
    val cell = cellOf(ch)
    val name = if (ch.isLetter()) "Letter $ch" else itemName(ch.toString()).replaceFirstChar { it.uppercase() }
    val hint = memoryHint(ch)?.let { " Remember it as: $it." } ?: ""
    return "$name: ${describeDots(cell)}.$hint"
}

@Composable
private fun MeetStep(ch: Char, kit: LearnKit, onReady: () -> Unit) {
    val play = rememberPlayback()
    val cell = cellOf(ch)
    var lit by remember(ch) { mutableIntStateOf(-1) }
    fun playRhythm(intro: Boolean) = play.run {
        if (intro) sayThenWait(kit, introLine(ch) + " Here is its rhythm.")
        kit.player.playWord(wordOf(ch.toString()), false, kit.deps.state.braillePace) { lit = it }
    }
    LaunchedEffect(ch) {
        onReady()
        playRhythm(intro = true)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            LargeTitle(ch.toString())
            MiniCell(cell, highlighted = lit >= 0, size = 1.6f)
        }
        Body(introLine(ch))
    }
    SecondaryButton("Play rhythm", { playRhythm(intro = false) }, description = "Play the rhythm of $ch again")
    Footnote("Or feel it: slide a finger over the box. Raised dots buzz.")
    LearnCell(cell, kit, height = 300) { d -> if (cell.raised(d)) kit.deps.voice.speak("$d") }
}

// ------------------------------------------------------------------ Compare (a → k)

@Composable
private fun CompareStep(from: Char, to: Char, kit: LearnKit, onReady: () -> Unit) {
    val play = rememberPlayback()
    val a = cellOf(from)
    val b = cellOf(to)
    val added = (1..6).filter { b.raised(it) && !a.raised(it) }
    val addText = if (added.size == 1) "dot ${added[0]}" else "dots " + added.joinToString(" and ")
    val line = "$from is ${describeDots(a)}. Add $addText, and it becomes $to: ${describeDots(b)}."
    var lit by remember(from) { mutableIntStateOf(-1) }
    fun playBoth(intro: Boolean) = play.run {
        if (intro) sayThenWait(kit, line)
        val pace = kit.deps.state.braillePace
        lit = 0
        kit.player.playWord(wordOf(from.toString()), false, pace)
        lit = 1
        kit.player.playWord(wordOf(to.toString()), false, pace)
        lit = -1
    }
    LaunchedEffect(from, to) {
        onReady()
        playBoth(intro = true)
    }
    Row(
        Modifier.semantics(mergeDescendants = true) { contentDescription = line },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LargeTitle(from.toString())
        MiniCell(a, highlighted = lit == 0, size = 1.5f)
        Text("→", style = Clarity.type.largeTitle, color = Clarity.colors.secondary)
        LargeTitle(to.toString())
        MiniCell(b, highlighted = lit == 1, size = 1.5f)
    }
    Body(line)
    SecondaryButton("Play both again", { playBoth(intro = false) }, description = "Play $from then $to again")
}

// ------------------------------------------------------------------ Listen

@Composable
private fun ListenStep(text: String, explain: String, kit: LearnKit, onReady: () -> Unit) {
    val play = rememberPlayback()
    val word = remember(text) { wordOf(text) }
    val endsSentence = text.endsWith(".") || text.endsWith("!") || text.endsWith("?")
    var lit by remember(text) { mutableIntStateOf(-1) }
    fun run(intro: Boolean) = play.run {
        if (intro) sayThenWait(kit, explain)
        kit.player.playWord(word, endsSentence && text.length > 1, kit.deps.state.braillePace) { lit = it }
    }
    LaunchedEffect(text) {
        onReady()
        run(intro = true)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LargeTitle("“$text”")
        Body(explain)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            word.cells.forEachIndexed { i, cell -> MiniCell(cell, highlighted = i == lit, size = 1.4f) }
        }
    }
    SecondaryButton("Play again", { run(intro = false) }, description = "Play “$text” again")
}

// ------------------------------------------------------------------ Quiz

/**
 * Listen, then pick 1 of 4. Right answers and mistakes are remembered per item (Mastery) and the
 * speed adapts: 3 right in a row → faster, a mistake → slower. [pass] right answers to continue.
 */
@Composable
fun QuizStep(targets: List<String>, choices: List<String>, questions: Int, pass: Int, kit: LearnKit, onReady: () -> Unit) {
    val deps = kit.deps
    val play = rememberPlayback()
    val pace = remember { Pace(deps.state.braillePace) }
    val mastery = remember { Mastery(deps.state.brailleMastery) }
    var q by remember { mutableIntStateOf(0) }
    var score by remember { mutableIntStateOf(0) }
    var round by remember { mutableIntStateOf(0) }
    var answered by remember { mutableStateOf<String?>(null) }
    var speed by remember { mutableFloatStateOf(pace.value) }
    var lit by remember { mutableIntStateOf(-1) }
    val target = remember(q, round) { targets.random() }
    val options = remember(q, round) { (choices.filter { it != target }.distinct().shuffled().take(3) + target).shuffled() }
    val finished = q >= questions

    fun playTarget(intro: String?) = play.run {
        if (intro != null) sayThenWait(kit, intro)
        kit.player.playWord(wordOf(target), false, pace.value) { lit = it }
    }

    fun answer(choice: String) {
        if (answered != null) return
        play.stop()
        answered = choice
        val right = choice == target
        mastery.record(target, right)
        deps.state.updateBrailleMastery(mastery.serialize())
        if (right) {
            score++
            pace.correct()
            deps.feedback.cue(Cue.Done)
            deps.voice.speak("Right, ${itemName(target)}.")
        } else {
            pace.wrong()
            deps.feedback.cue(Cue.Cancelled)
            val how = if (target.length == 1) ": ${describeDots(encode(target).last())}" else ""
            deps.voice.speak("Not quite. That was ${itemName(target)}$how.")
        }
        speed = pace.value
        deps.state.updateBraillePace(pace.value)
    }

    LaunchedEffect(q, round) {
        answered = null
        when {
            !finished -> playTarget(if (q == 0) "Listen, then choose. Question 1 of $questions." else "Question ${q + 1}.")
            pass == 0 -> { onReady(); deps.voice.speak("Round done: $score of $questions right. Your results are saved.") }
            score >= pass -> { onReady(); deps.voice.speak("You got $score of $questions. Well done. Press Continue.") }
            else -> deps.voice.speak("You got $score of $questions. You need $pass. Try again; it gets easier.")
        }
    }

    if (finished) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LargeTitle("$score of $questions")
            Body(
                when {
                    pass == 0 -> "Round done. Your results are saved."
                    score >= pass -> "Well done. Press Continue."
                    else -> "You need $pass. Try again — each round gets easier."
                },
                secondary = true,
            )
            Footnote("Speed ${String.format(Locale.US, "%.1f", speed)}× — set from your answers")
        }
        if (score < pass || pass == 0) PrimaryButton(if (pass == 0) "Another round" else "Try again", { score = 0; q = 0; round++ }, description = "Start this quiz again")
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Footnote("Question ${q + 1} of $questions · Score $score · Speed ${String.format(Locale.US, "%.1f", speed)}×")
        LargeTitle("What is it?")
        answered?.let { a ->
            val right = a == target
            Row(
                Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                encode(target).forEach { MiniCell(it, size = 1.2f) }
                Text(
                    if (right) "Right: $target" else "It was $target",
                    style = Clarity.type.headline,
                    color = if (right) Clarity.colors.accentText else Clarity.colors.dangerText,
                )
            }
        } ?: Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.clearAndSetSemantics {}) {
            // While answering, show only which cell is playing (not its dots).
            encode(target).forEachIndexed { i, _ ->
                Box(
                    Modifier.size(28.dp, 42.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (i == lit) Clarity.colors.accent else Clarity.colors.group),
                )
            }
        }
    }
    SecondaryButton("Play again", { playTarget(null) }, description = "Play it again")
    options.chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            pair.forEach { o -> SecondaryButton(o, { answer(o) }, Modifier.weight(1f).heightIn(min = 72.dp), description = itemName(o)) }
        }
    }
    if (answered != null) PrimaryButton(if (q == questions - 1) "See score" else "Next question", { q++ })
}

// ------------------------------------------------------------------ Build

@Composable
private fun BuildStep(ch: Char, kit: LearnKit, onReady: () -> Unit) {
    val deps = kit.deps
    val play = rememberPlayback()
    val target = cellOf(ch)
    val on = remember(ch) { mutableStateListOf<Int>() }
    var tries by remember(ch) { mutableIntStateOf(0) }
    var solved by remember(ch) { mutableStateOf(false) }
    var showAnswer by remember(ch) { mutableStateOf(false) }
    val mastery = remember { Mastery(deps.state.brailleMastery) }

    LaunchedEffect(ch) { deps.voice.speak("Make letter $ch. Turn its dots on, then press Check.") }

    fun check() {
        val mask = on.fold(0) { acc, d -> acc or (1 shl (d - 1)) }
        val right = mask == target.dots
        mastery.record(ch.toString(), right)
        deps.state.updateBrailleMastery(mastery.serialize())
        if (right) {
            solved = true
            deps.feedback.cue(Cue.Done)
            onReady()
            play.run {
                sayThenWait(kit, "Yes! $ch is ${describeDots(target)}.")
                kit.player.playWord(wordOf(ch.toString()), false, deps.state.braillePace)
            }
        } else {
            tries++
            deps.feedback.cue(Cue.Cancelled)
            val have = if (on.isEmpty()) "no dots" else describeDots(Cell(mask, Cell.Kind.Unknown, ""))
            if (tries >= 2) {
                showAnswer = true
                onReady()
                deps.voice.speak("Not yet. You have $have. Letter $ch is ${describeDots(target)}. Fix it, or press Continue.")
            } else {
                deps.voice.speak("Not yet. You have $have. Try again." + (memoryHint(ch)?.let { " Hint: $it." } ?: ""))
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LargeTitle("Make “$ch”")
        Body(
            when {
                solved -> "Yes! $ch is ${describeDots(target)}."
                showAnswer -> "Letter $ch is ${describeDots(target)}."
                else -> "Turn on the dots of $ch, then press Check."
            },
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    // The six dot switches, laid out like the cell: 1 2 3 down the left, 4 5 6 down the right.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
        for (col in 0..1) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (row in 0..2) {
                    val d = col * 3 + row + 1
                    DotSwitch(d, d in on) { now ->
                        if (now) on.add(d) else on.remove(d)
                        deps.voice.speak("Dot $d ${if (now) "on" else "off"}")
                    }
                }
            }
        }
    }
    if (!solved) PrimaryButton("Check", { check() }, description = "Check letter $ch")
    if (showAnswer && !solved) Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        MiniCell(target, size = 1.4f)
        Footnote("The answer")
    }
}

@Composable
private fun DotSwitch(dot: Int, on: Boolean, onChange: (Boolean) -> Unit) {
    val c = Clarity.colors
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .size(88.dp)
            .clip(shape)
            .background(if (on) c.accent else c.group)
            .border(2.dp, if (on) c.accent else c.separator, shape)
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = "Dot $dot"; stateDescription = if (on) "On" else "Off" },
        contentAlignment = Alignment.Center,
    ) {
        Text("$dot", style = Clarity.type.title, color = if (on) c.onAccent else c.label)
    }
}

// ------------------------------------------------------------------ Read

@Composable
private fun ReadStep(text: String, explain: String, kit: LearnKit, onReady: () -> Unit) {
    val doc = remember(text) { Document(text) }
    val reader = rememberRhythmReader(doc, private = false, haptics = kit.haptics)
    LaunchedEffect(text) {
        onReady()
        sayThenWait(kit, explain)
        delay(200)
        reader.play()
    }
    Body(explain, secondary = true)
    RhythmReaderView(reader)
}

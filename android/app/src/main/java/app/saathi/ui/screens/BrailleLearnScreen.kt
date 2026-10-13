package app.saathi.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.saathi.braille.BrailleHaptics
import app.saathi.braille.LESSONS
import app.saathi.braille.Lesson
import app.saathi.braille.Mastery
import app.saathi.braille.Word
import app.saathi.braille.describeDots
import app.saathi.braille.encode
import app.saathi.braille.itemName
import app.saathi.braille.memoryHint
import app.saathi.feedback.Cue
import app.saathi.ui.AppNav
import app.saathi.ui.LocalDeps
import app.saathi.ui.Route
import app.saathi.ui.components.Body
import app.saathi.ui.components.Footnote
import app.saathi.ui.components.Group
import app.saathi.ui.components.GroupDivider
import app.saathi.ui.components.GroupRow
import app.saathi.ui.components.LargeTitle
import app.saathi.ui.components.NavRow
import app.saathi.ui.components.PrimaryButton
import app.saathi.ui.components.SaathiScreen
import app.saathi.ui.components.SecondaryButton
import app.saathi.ui.components.SectionHeader
import app.saathi.ui.theme.Clarity

val LESSON_COUNT = LESSONS.size

private val ALPHABET = ('a'..'z').map { it.toString() }
private val EXTRAS = ('1'..'9').map { it.toString() } + listOf("0", ".", ",", "?", "!", "-")

/** Where the learner is inside Learn braille. Saved across rotation as a string. */
private sealed interface Mode {
    data object Hub : Mode
    data class InLesson(val id: Int) : Mode
    data class Done(val id: Int) : Mode
    data object Review : Mode
    data object Alphabet : Mode
    data class Letter(val item: String) : Mode

    fun save(): String = when (this) {
        Hub -> "hub"
        is InLesson -> "lesson:$id"
        is Done -> "done:$id"
        Review -> "review"
        Alphabet -> "alphabet"
        is Letter -> "letter:$item"
    }

    companion object {
        fun load(s: String): Mode = when {
            s.startsWith("lesson:") -> InLesson(s.removePrefix("lesson:").toInt())
            s.startsWith("done:") -> Done(s.removePrefix("done:").toInt())
            s == "review" -> Review
            s == "alphabet" -> Alphabet
            s.startsWith("letter:") -> Letter(s.removePrefix("letter:"))
            else -> Hub
        }
    }
}

private fun lesson(id: Int): Lesson = LESSONS.first { it.id == id }

/**
 * Learn braille: a 13-lesson course (six dots → a–j → rules for k–z → numbers and signs → words →
 * a story), plus Review (your weakest letters), an Alphabet explorer, and progress per letter.
 * Every step is read aloud and has one big Continue; voice works throughout.
 */
@Composable
fun BrailleLearnScreen(nav: AppNav) {
    val deps = LocalDeps.current
    val context = LocalContext.current
    val haptics = remember { BrailleHaptics(context) }
    haptics.sound = deps.state.brailleSound ?: !haptics.canVibrate
    val kit = remember { LearnKit(deps, haptics) }
    DisposableEffect(Unit) { onDispose { haptics.release() } }

    var saved by rememberSaveable { mutableStateOf("hub") }
    val mode = Mode.load(saved)
    var step by rememberSaveable { mutableIntStateOf(0) }
    var ready by remember { mutableStateOf(false) }
    var replay by remember { mutableIntStateOf(0) }

    fun go(m: Mode) {
        haptics.stopPulses()
        deps.voice.stopSpeaking()
        if (m is Mode.InLesson) { step = 0; ready = false }
        saved = m.save()
    }

    val nextLesson = LESSONS.firstOrNull { it.id !in deps.state.brailleLessons }

    fun finishLesson(id: Int) {
        deps.state.finishBrailleLesson(id)
        deps.feedback.cue(Cue.Done)
        go(Mode.Done(id))
    }

    fun goStep(to: Int) {
        val m = mode as? Mode.InLesson ?: return
        val l = lesson(m.id)
        when {
            to < 0 -> go(Mode.Hub)
            to >= l.steps.size -> finishLesson(l.id)
            else -> { haptics.stopPulses(); deps.voice.stopSpeaking(); ready = false; step = to }
        }
    }

    BackHandler(enabled = mode != Mode.Hub) {
        when (mode) {
            is Mode.InLesson -> if (step > 0) goStep(step - 1) else go(Mode.Hub)
            is Mode.Letter -> go(Mode.Alphabet)
            else -> go(Mode.Hub)
        }
    }

    val listener = ScreenParts.rememberScreenListener { text ->
        val w = ScreenParts.words(text)
        val inLesson = mode is Mode.InLesson
        when {
            inLesson && ("continue" in w || "next" in w || "aage" in w) -> if (ready) goStep(step + 1) else deps.voice.speak("Finish this step first, or say skip.")
            inLesson && "skip" in w -> goStep(step + 1)
            inLesson && ("back" in w || "previous" in w) -> goStep(step - 1)
            inLesson && ("again" in w || "repeat" in w) -> replay++
            "review" in w -> go(Mode.Review)
            "alphabet" in w -> go(Mode.Alphabet)
            "lessons" in w || "menu" in w -> go(Mode.Hub)
            "start" in w || "continue" in w -> nextLesson?.let { go(Mode.InLesson(it.id)) }
            "home" in w -> nav.home()
            else -> {
                val n = w.firstNotNullOfOrNull { it.toIntOrNull() }
                if ("lesson" in w && n != null && n in 1..LESSON_COUNT) go(Mode.InLesson(n)) else ScreenParts.handleAsRequest(text, deps, nav)
            }
        }
    }

    val status = when {
        listener.active -> "Listening"
        mode is Mode.InLesson -> "Lesson ${mode.id}, step ${step + 1} of ${lesson(mode.id).steps.size}"
        else -> "Learn braille"
    }

    SaathiScreen(
        status = status,
        youCanSay = when (mode) {
            is Mode.InLesson -> listOf("Continue", "Again", "Back", "Skip")
            else -> listOf("Continue", "Review", "Alphabet", "Lesson 3")
        },
        onTalk = { listener.toggle() },
        navRow = {
            when (mode) {
                Mode.Hub -> NavRow("Braille", { nav.back() })
                is Mode.Letter -> NavRow("Alphabet", { go(Mode.Alphabet) })
                else -> NavRow("Lessons", { go(Mode.Hub) })
            }
        },
        bottom = {
            ScreenParts.ListenHint(listener)
            if (mode is Mode.InLesson) {
                val last = step == lesson(mode.id).steps.lastIndex
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecondaryButton("Back", { goStep(step - 1) }, Modifier.weight(1f), description = "Previous step")
                    if (ready) {
                        PrimaryButton(if (last) "Finish" else "Continue", { goStep(step + 1) }, Modifier.weight(2f), description = if (last) "Finish the lesson" else "Continue to the next step")
                    } else {
                        SecondaryButton("Skip", { goStep(step + 1) }, Modifier.weight(2f), description = "Skip this step")
                    }
                }
            }
        },
    ) {
        when (mode) {
            Mode.Hub -> Hub(kit, nextLesson, { go(it) }, { nav.go(Route.Braille) })
            is Mode.InLesson -> {
                val l = lesson(mode.id)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Footnote("${l.unit} · Lesson ${l.id} of $LESSON_COUNT · Step ${step + 1} of ${l.steps.size}")
                    LinearProgressIndicator(
                        progress = { (step + 1f) / l.steps.size },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = Clarity.colors.accent,
                        trackColor = Clarity.colors.group,
                    )
                }
                key(mode.id, step, replay) {
                    StepView(l.steps[step], kit) { ready = true }
                }
            }
            is Mode.Done -> LessonDone(kit, lesson(mode.id), { go(it) })
            Mode.Review -> Review(kit)
            Mode.Alphabet -> Alphabet(kit) { go(Mode.Letter(it)) }
            is Mode.Letter -> LetterDetail(kit, mode.item) { go(Mode.Letter(it)) }
        }
    }
}

// ------------------------------------------------------------------ Hub

@Composable
private fun Hub(kit: LearnKit, next: Lesson?, go: (Mode) -> Unit, openReader: () -> Unit) {
    val state = kit.deps.state
    val mastery = remember(state.brailleMastery) { Mastery(state.brailleMastery) }
    val done = state.brailleLessons.size
    val known = mastery.knownCount(ALPHABET)
    val weak = mastery.weakest(10)

    ScreenParts.OnFirstShow {
        kit.deps.voice.speak(
            if (next == null) "Learn braille. Course finished. Say review to practise your weakest letters."
            else "Learn braille. $done of $LESSON_COUNT lessons done. Say continue to start lesson ${next.id}, ${next.title}.",
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LargeTitle("Learn braille")
        Body("$done of $LESSON_COUNT lessons · $known of 26 letters known", secondary = true)
        LinearProgressIndicator(
            progress = { done.toFloat() / LESSON_COUNT },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = Clarity.colors.accent,
            trackColor = Clarity.colors.group,
        )
    }

    if (next != null) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PrimaryButton(
                (if (done == 0) "Start: " else "Continue: ") + "Lesson ${next.id} · ${next.title}",
                { go(Mode.InLesson(next.id)) },
                description = "${if (done == 0) "Start" else "Continue"} with lesson ${next.id}, ${next.title}. ${next.summary}",
            )
            Footnote(next.summary)
        }
    } else {
        PrimaryButton("Review weak letters", { go(Mode.Review) }, description = "Course finished. Review your weakest letters.")
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Practise")
        Group {
            GroupRow(
                "Review weak letters",
                modifier = Modifier.semantics { contentDescription = "Review weak letters. " + if (weak.isEmpty()) "Do a quiz first." else "${weak.size} to review." },
                value = if (weak.isEmpty()) "Quiz first" else "${weak.size} to review",
                chevron = true,
                onClick = { go(Mode.Review) },
            )
            GroupDivider()
            GroupRow(
                "Alphabet and signs",
                modifier = Modifier.semantics { contentDescription = "Alphabet and signs. Feel and hear any letter. $known letters known." },
                value = "$known known",
                chevron = true,
                onClick = { go(Mode.Alphabet) },
            )
            GroupDivider()
            GroupRow(
                "Read your own text",
                modifier = Modifier.semantics { contentDescription = "Read your own text. Opens the braille reader." },
                chevron = true,
                onClick = openReader,
            )
        }
    }

    LESSONS.groupBy { it.unit }.forEach { (unit, lessons) ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader(unit)
            Group {
                lessons.forEachIndexed { i, l ->
                    if (i > 0) GroupDivider()
                    val isDone = l.id in state.brailleLessons
                    val isNext = l.id == next?.id
                    GroupRow(
                        "${l.id}. ${l.title}",
                        modifier = Modifier.semantics {
                            contentDescription = "Lesson ${l.id}, ${l.title}. ${l.summary}." + when {
                                isDone -> " Done."
                                isNext -> " Up next."
                                else -> ""
                            }
                        },
                        value = when {
                            isDone -> "Done"
                            isNext -> "Next"
                            else -> null
                        },
                        chevron = true,
                        onClick = { go(Mode.InLesson(l.id)) },
                    )
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("How to practise")
        Group {
            listOf(
                "A little every day beats a lot once. Ten minutes is plenty.",
                "Rest the phone flat on a table, or loosely in your palm, so you feel every pulse.",
                "No vibration on your device? Saathi plays tones instead. Use headphones.",
                "With TalkBack: slide slowly to feel dots, double-tap to press. Swipes belong to TalkBack, so use the buttons.",
                "Stuck on a step? Say “again” to hear it again, or “skip”.",
            ).forEachIndexed { i, tip ->
                if (i > 0) GroupDivider()
                Text(tip, style = Clarity.type.body, color = Clarity.colors.label, modifier = Modifier.padding(16.dp).semantics(mergeDescendants = true) {})
            }
        }
    }
}

@Composable
private fun LessonDone(kit: LearnKit, l: Lesson, go: (Mode) -> Unit) {
    val next = LESSONS.firstOrNull { it.id > l.id }
    LaunchedEffect(l.id) {
        kit.deps.voice.speak("Lesson ${l.id} done." + (next?.let { " Next: ${it.title}. Say continue." } ?: " That was the last lesson."))
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenParts.CheckBadge(72.dp, still = false)
        LargeTitle("Lesson ${l.id} done")
        Body(l.title, secondary = true)
    }
    if (next != null) {
        PrimaryButton("Next: ${next.title}", { go(Mode.InLesson(next.id)) }, description = "Next lesson: ${next.title}. ${next.summary}")
    }
    SecondaryButton("Review weak letters", { go(Mode.Review) })
    SecondaryButton("Back to lessons", { go(Mode.Hub) })
}

// ------------------------------------------------------------------ Review

@Composable
private fun Review(kit: LearnKit) {
    val mastery = remember { Mastery(kit.deps.state.brailleMastery) }
    // Weakest tried letters first; if there's nothing to review yet, practise a–j.
    val targets = remember { mastery.weakest(8).ifEmpty { ('a'..'j').map { it.toString() } } }
    val choices = remember { (targets + ALPHABET).distinct() }
    var finished by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LargeTitle("Review")
        Body(
            if (mastery.weakest(1).isEmpty()) "Nothing to review yet, so here are a to j."
            else "Your weakest: " + targets.joinToString(", "),
            secondary = true,
        )
    }
    QuizStep(targets, choices, questions = 10, pass = 0, kit = kit) { finished = true }
    if (finished) Footnote("Your results are saved. Come back tomorrow for another round.")
}

// ------------------------------------------------------------------ Alphabet

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Alphabet(kit: LearnKit, open: (String) -> Unit) {
    val mastery = remember(kit.deps.state.brailleMastery) { Mastery(kit.deps.state.brailleMastery) }
    LaunchedEffect(Unit) { kit.deps.voice.speak("Alphabet and signs. Choose a letter to feel and hear it.") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LargeTitle("Alphabet")
        Body("Tap any letter to feel it, hear its rhythm and its memory hint. A ring means you know it well.", secondary = true)
    }
    listOf("Letters" to ALPHABET, "Numbers and signs" to EXTRAS).forEach { (title, items) ->
        SectionHeader(title)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items.forEach { item -> Tile(item, mastery.known(item), mastery.accuracy(item)) { open(item) } }
        }
    }
}

@Composable
private fun Tile(item: String, known: Boolean, accuracy: Float?, onClick: () -> Unit) {
    val c = Clarity.colors
    val shape = RoundedCornerShape(14.dp)
    val state = when {
        known -> "known"
        accuracy != null -> "${(accuracy * 100).toInt()}% right"
        else -> "not tried"
    }
    Column(
        Modifier
            .width(72.dp)
            .clip(shape)
            .background(c.group)
            .border(if (known) 3.dp else 1.dp, if (known) c.accent else c.separator, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "${itemName(item)}, $state" }
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(item, style = Clarity.type.title, color = c.label)
        MiniCell(encode(item).last(), size = 0.8f)
    }
}

@Composable
private fun LetterDetail(kit: LearnKit, item: String, open: (String) -> Unit) {
    val deps = kit.deps
    val all = ALPHABET + EXTRAS
    val i = all.indexOf(item)
    val cells = encode(item)
    val mastery = remember(deps.state.brailleMastery) { Mastery(deps.state.brailleMastery) }
    val acc = mastery.accuracy(item)
    val line = buildString {
        append(itemName(item).replaceFirstChar { it.uppercase() })
        append(": ")
        append(cells.joinToString(", then ") { describeDots(it) })
        append(".")
        memoryHint(item.first())?.let { append(" Remember it as: $it.") }
    }
    val play = rememberPlayback()
    var lit by remember(item) { mutableIntStateOf(-1) }
    fun playIt(intro: Boolean) = play.run {
        if (intro) {
            deps.voice.speak(line)
            ScreenParts.awaitSpeechEnd(deps.voice)
        }
        kit.player.playWord(Word(item, cells), false, deps.state.braillePace) { lit = it }
    }
    LaunchedEffect(item) { playIt(intro = true) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LargeTitle(item)
            cells.forEachIndexed { k, c -> MiniCell(c, highlighted = k == lit, size = 1.6f) }
        }
        Body(line)
        Footnote(
            when {
                acc == null -> "Not in a quiz yet."
                mastery.known(item) -> "You know this one well."
                else -> "Recent quizzes: ${(acc * 100).toInt()}% right."
            },
        )
    }
    SecondaryButton("Play rhythm", { playIt(intro = false) }, description = "Play the rhythm of ${itemName(item)}")
    Footnote("Feel it: slide a finger over the box.")
    LearnCell(cells.last(), kit, height = 300) { d -> if (cells.last().raised(d)) deps.voice.speak("$d") }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SecondaryButton("‹ ${all.getOrNull(i - 1) ?: ""}", { if (i > 0) open(all[i - 1]) }, Modifier.weight(1f), description = "Previous: ${all.getOrNull(i - 1)?.let(::itemName) ?: "none"}")
        SecondaryButton("${all.getOrNull(i + 1) ?: ""} ›", { if (i < all.lastIndex) open(all[i + 1]) }, Modifier.weight(1f), description = "Next: ${all.getOrNull(i + 1)?.let(::itemName) ?: "none"}")
    }
}

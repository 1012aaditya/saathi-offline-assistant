package app.saathi.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.saathi.braille.BrailleCellView
import app.saathi.braille.BrailleHaptics
import app.saathi.braille.Cell
import app.saathi.braille.Document
import app.saathi.braille.OtpWaiter
import app.saathi.braille.encode
import app.saathi.braille.rememberOtpWaiter
import app.saathi.ui.AppNav
import app.saathi.ui.LocalDeps
import app.saathi.ui.Route
import app.saathi.ui.components.Body
import app.saathi.ui.components.Footnote
import app.saathi.ui.components.Group
import app.saathi.ui.components.GroupRow
import app.saathi.ui.components.LargeTitle
import app.saathi.ui.components.NavRow
import app.saathi.ui.components.PrimaryButton
import app.saathi.ui.components.SaathiScreen
import app.saathi.ui.components.SecondaryButton
import app.saathi.ui.components.TextLink
import app.saathi.ui.theme.Clarity
import kotlinx.coroutines.delay

/** Private text (codes, passwords) is wiped after this long, or as soon as the app leaves the screen. */
private const val PRIVATE_WIPE_MS = 3 * 60_000L

private const val PRACTICE_TEXT = "The cat sat. A dog ran 2 km!"

/**
 * What's being read. [private] = an OTP or a typed password: never spoken, never shown as dots,
 * never in TalkBack descriptions, never saved (plain `remember`, not `rememberSaveable`).
 */
private class Reading(text: String, val private: Boolean, val what: String) {
    val doc = Document(text)
    /** Touch style reads cell by cell across the whole text. */
    val cells: List<Cell> = encode(text)
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/**
 * Braille reader. Two styles:
 * - Rhythm (default): each word plays as timed pulses, dot 1→6; read word by word.
 * - Touch: one big 6-dot cell; slide a finger, a buzz is a raised dot.
 * Reads one-time codes (with Android's per-message consent), typed text, Result answers and practice text.
 */
@Composable
fun BrailleScreen(nav: AppNav) {
    val deps = LocalDeps.current
    val context = LocalContext.current
    val c = Clarity.colors
    val haptics = remember { BrailleHaptics(context) }
    // Sound on raised dots: the user's choice, or automatic (on when this device can't vibrate).
    val soundOn = deps.state.brailleSound ?: !haptics.canVibrate
    haptics.sound = soundOn
    val rhythmStyle = deps.state.brailleRhythm

    var reading by remember { mutableStateOf<Reading?>(null) }
    var index by remember { mutableIntStateOf(0) }
    var typing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }

    fun show(r: Reading) {
        typing = false
        typed = ""
        note = null
        if (r.doc.isEmpty) return
        reading = r
        index = 0
        deps.voice.stopSpeaking()
        // Length is fine to say; the content never is.
        val overheard = if (r.private && haptics.sound && !haptics.headphonesOn()) " No headphones: people nearby can hear the dots." else ""
        val how = if (rhythmStyle) "${r.doc.wordCount} words. Tap the reading area to repeat a word."
        else "${r.cells.size} cells. Slide one finger slowly over the screen."
        deps.voice.speak("${r.what} ready. $how$overheard")
    }

    fun clear(say: String? = null) {
        haptics.off()
        haptics.stopPulses()
        reading = null
        index = 0
        typed = ""
        if (say != null) {
            note = say
            deps.voice.speak(say)
        }
    }

    val waiter = rememberOtpWaiter { code -> show(Reading(code, private = true, what = "Code")) }

    // Opened from Result with an answer that was already read aloud: public text, "Hold to hear" allowed.
    LaunchedEffect(Unit) {
        deps.state.brailleText?.let { text ->
            deps.state.brailleText = null
            show(Reading(text.take(280), private = false, what = "Text"))
        }
    }

    // Touch style: move cell by cell.
    fun move(by: Int) {
        val r = reading ?: return
        val next = index + by
        when {
            next >= r.cells.size -> { haptics.end(); deps.voice.speak("End. Say again to start over.") }
            next < 0 -> haptics.end()
            else -> { index = next; haptics.nextCell() }
        }
    }

    val current = reading
    val rhythm = if (current != null && rhythmStyle) rememberRhythmReader(current.doc, current.private, haptics) else null
    // Start playing the first word once the "ready" line has been spoken.
    LaunchedEffect(rhythm) {
        if (rhythm != null) {
            ScreenParts.awaitSpeechEnd(deps.voice)
            rhythm.play()
        }
    }

    // Private text: block screenshots/screen sharing, wipe after a while and when the app goes away.
    val isPrivate = current?.private == true
    DisposableEffect(isPrivate) {
        val window = context.activity()?.window
        if (isPrivate) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    LaunchedEffect(current) {
        if (current?.private == true) {
            delay(PRIVATE_WIPE_MS)
            clear("Cleared for your safety.")
        }
    }
    var headphones by remember { mutableStateOf(true) }
    LaunchedEffect(current, soundOn) {
        while (current?.private == true && soundOn) {
            headphones = haptics.headphonesOn()
            delay(2_000)
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP && reading?.private == true) clear() }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            waiter.cancel()
            haptics.release()
        }
    }

    val listener = ScreenParts.rememberScreenListener { text ->
        val w = ScreenParts.words(text)
        when {
            "learn" in w -> nav.go(Route.BrailleLearn)
            rhythm != null && "next" in w && "sentence" in w -> rhythm.nextSentence()
            rhythm != null && "next" in w -> rhythm.next()
            rhythm != null && ("back" in w || "previous" in w) -> rhythm.previous()
            rhythm != null && ("again" in w || "repeat" in w) -> rhythm.repeat()
            rhythm != null && ("faster" in w) -> rhythm.faster()
            rhythm != null && ("slower" in w) -> rhythm.slower()
            rhythm != null && ("stop" in w) -> rhythm.stop()
            rhythm != null && ("read" in w || "continue" in w) -> rhythm.readOn()
            rhythm != null && ("spell" in w || "hear" in w) -> rhythm.hear()
            "next" in w -> move(1)
            "back" in w || "previous" in w -> move(-1)
            "again" in w || "start" in w || "restart" in w -> if (reading != null) { index = 0; haptics.nextCell() }
            "code" in w || "otp" in w -> waiter.begin()
            "clear" in w || "done" in w -> clear("Cleared.")
            "home" in w -> nav.home()
            else -> ScreenParts.handleAsRequest(text, deps, nav)
        }
    }

    val waitingText = when (waiter.state) {
        OtpWaiter.State.Waiting -> "Waiting for a code message, up to 5 minutes. When Android asks, choose Allow."
        OtpWaiter.State.Asking -> "Android is asking if Saathi may read that one message."
        OtpWaiter.State.TimedOut -> "No code arrived in 5 minutes."
        OtpWaiter.State.Declined -> "You chose not to share the message. Nothing was read."
        OtpWaiter.State.NoCode -> "I couldn't find a code in that message."
        OtpWaiter.State.Unavailable -> "Code reading needs Google Play services. It isn't available here."
        OtpWaiter.State.Idle -> null
    }
    LaunchedEffect(waiter.state) { waitingText?.let { deps.voice.speak(it) } }

    val status = when {
        listener.active -> "Listening"
        rhythm != null -> "Word ${current!!.doc.ordinal(rhythm.pos)} of ${current.doc.wordCount}"
        current != null -> "Cell ${index + 1} of ${current.cells.size}"
        else -> waitingText ?: note ?: "Braille"
    }

    SaathiScreen(
        status = status,
        youCanSay = when {
            rhythm != null -> listOf("Next", "Repeat", "Read on", "Slower", "Clear")
            current != null -> listOf("Next", "Back", "Again", "Clear")
            else -> listOf("Code", "Learn", "Home")
        },
        onTalk = { listener.toggle() },
        navRow = { NavRow("Home", { nav.home() }) },
        bottom = {
            ScreenParts.ListenHint(listener)
            if (current != null && rhythm == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecondaryButton("Back", { move(-1) }, Modifier.weight(1f), description = "Previous cell")
                    PrimaryButton("Next", { move(1) }, Modifier.weight(1f), description = "Next cell")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecondaryButton("Again", { index = 0; haptics.nextCell() }, Modifier.weight(1f), description = "Start again from the first cell")
                    if (!current.private) {
                        SecondaryButton("Say it", { deps.voice.speak(current.cells[index].spoken) }, Modifier.weight(1f), description = "Say this cell out loud")
                    }
                    SecondaryButton("Clear", { clear("Cleared.") }, Modifier.weight(1f), description = "Clear and finish")
                }
            }
        },
    ) {
        if (current == null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LargeTitle("Braille")
                Body("Read codes and short text without sound or sight: the dots play as pulses you feel.", secondary = true)
            }
            Group {
                val done = deps.state.brailleLessons.size
                GroupRow(
                    "Learn braille",
                    modifier = Modifier.semantics { contentDescription = "Learn braille. $done of $LESSON_COUNT lessons done" },
                    value = "$done of $LESSON_COUNT",
                    icon = Icons.Outlined.School,
                    chevron = true,
                    onClick = { nav.go(Route.BrailleLearn) },
                )
            }
            if (waitingText != null || note != null) {
                Text(
                    waitingText ?: note.orEmpty(),
                    style = Clarity.type.callout,
                    color = c.label,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (waiter.state == OtpWaiter.State.Waiting || waiter.state == OtpWaiter.State.Asking) {
                SecondaryButton("Stop waiting", { waiter.cancel() }, description = "Stop waiting for a code")
            } else {
                PrimaryButton("Wait for a code", { waiter.begin() }, description = "Wait for a one-time code message")
            }
            if (typing) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it.take(64) },
                    label = { Text("Text to feel (kept private)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    // Password keyboard: no suggestions, no learning what was typed.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                PrimaryButton("Feel it", {
                    if (typed.isNotBlank()) show(Reading(typed, private = true, what = "Text"))
                }, description = "Read the typed text in braille")
            } else {
                SecondaryButton("Type text to feel", { typing = true }, description = "Type text, like a Wi-Fi password, to feel in braille")
            }
            SecondaryButton("Practice", { show(Reading(PRACTICE_TEXT, private = false, what = "Practice text")) }, description = "Practice with sample text")

            BrailleSwitch(
                "Read by rhythm",
                if (rhythmStyle) "Dots play one after another, word by word." else "Off: slide a finger over one big cell instead.",
                rhythmStyle,
            ) { on ->
                deps.state.updateBrailleRhythm(on)
                deps.voice.speak(if (on) "Rhythm reading" else "Touch reading")
            }
            BrailleSwitch("Say each word", "After its rhythm, while you learn. Never for codes or passwords.", deps.state.brailleSpeak) { on ->
                deps.state.updateBrailleSpeak(on)
                deps.voice.speak(if (on) "Words spoken" else "Silent reading")
            }
            BrailleSwitch(
                "Sound on dots",
                if (haptics.canVibrate) "A tone plays with the buzz. Use headphones for codes."
                else "This device can't vibrate, so dots play a tone. Use headphones for codes.",
                soundOn,
            ) { on ->
                deps.state.updateBrailleSound(on)
                deps.voice.speak(if (on) "Sound on" else "Sound off")
            }
            Footnote(
                "Codes need your OK: Android asks before Saathi can see a message. Saathi never reads your inbox, " +
                    "never says a code out loud, and never saves it. Private text clears after 3 minutes or when you leave.",
            )
        } else {
            if (current.private) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Footnote("Private — never spoken, dots hidden on screen.")
                    if (soundOn && !headphones) {
                        Text(
                            "No headphones — people nearby can hear the dots.",
                            style = Clarity.type.callout,
                            color = c.dangerText,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            }
            if (rhythm != null) {
                RhythmReaderView(rhythm)
            } else {
                Text(
                    "Cell ${index + 1} of ${current.cells.size}",
                    style = Clarity.type.headline,
                    color = c.label,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                val raised = c.label.toArgb()
                val flat = c.secondary.toArgb()
                val divider = c.separator.toArgb()
                val cell = current.cells.getOrNull(index)
                AndroidView(
                    factory = { ctx -> BrailleCellView(ctx, haptics) },
                    update = { v ->
                        v.private = current.private
                        v.cell = cell
                        v.colors = BrailleCellView.Colors(raised = raised, divider = divider, flat = flat)
                        v.onNext = { move(1) }
                    },
                    modifier = Modifier.fillMaxWidth().height(420.dp),
                )
            }
            TextLink("Clear and go back", { clear("Cleared.") })
        }
    }
}

package app.saathi.ui.screens

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.saathi.data.Outcome
import app.saathi.feedback.Cue
import app.saathi.ui.AppNav
import app.saathi.ui.LocalDeps
import app.saathi.ui.Route
import app.saathi.ui.components.AskCapsule
import app.saathi.ui.components.Body
import app.saathi.ui.components.Footnote
import app.saathi.ui.components.PrimaryButton
import app.saathi.ui.components.SecondaryButton
import app.saathi.ui.components.StatusLine
import app.saathi.ui.components.YouCanSay
import app.saathi.ui.theme.Clarity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Practice sample. Read-backs in the real agent may only use text that is on screen (hard rule 4).
private const val ConfirmTo = "Mom"
private const val ConfirmText = "I'll be home by 7"
private const val ConfirmQuestion = "Send to $ConfirmTo: $ConfirmText? Say yes or no."
private const val ConfirmTitle = "Practice: reply to Mom"

private enum class Answer { Yes, No, Unclear }

/** Yes only when it's clearly yes. Anything mixed or unknown is unclear (→ ask once more → cancel). */
private fun classifyAnswer(text: String): Answer {
    val t = text.lowercase()
    val w = ScreenParts.words(text)
    val yes = w.any { it in setOf("yes", "yeah", "yep", "haan", "han", "ha", "haa", "send", "bhejo", "हाँ", "हां", "भेजो") }
    val no = w.any { it in setOf("no", "nope", "nahi", "nahin", "na", "cancel", "stop", "mat", "नहीं", "ना", "मत") } ||
        "don't" in t || "dont" in w || "do not" in t
    return when {
        yes && !no -> Answer.Yes
        no && !yes -> Answer.No
        else -> Answer.Unclear
    }
}

/**
 * Screen 6 — Confirm sheet over the dimmed working screen. Cancel is first, focused and the default.
 * Nothing confirms on its own; an unclear voice answer is asked once more, then cancels.
 * Practice: pressing Send sends nothing.
 */
@Composable
fun ConfirmScreen(nav: AppNav) {
    val deps = LocalDeps.current
    val c = Clarity.colors
    val still = ScreenParts.rememberStill()
    val scope = rememberCoroutineScope()

    var cancelled by rememberSaveable { mutableStateOf(false) }
    var sheetGone by rememberSaveable { mutableStateOf(false) }
    var unclear by rememberSaveable { mutableIntStateOf(0) }
    var finished by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    val sheet = remember { Animatable(if (still || cancelled) 0f else 1f) } // 1 = below the screen, 0 = up
    val cancelFocus = remember { FocusRequester() }
    val homeFocus = remember { FocusRequester() }
    val ease = remember { CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f) }

    lateinit var listener: ScreenParts.ScreenListener

    fun cancel(spoken: String = "Cancelled. Nothing was sent.") {
        if (finished) return
        finished = true
        listener.cancel()
        deps.feedback.cue(Cue.Cancelled)
        deps.voice.speak(spoken)
        deps.state.record(ConfirmTitle, Outcome.Cancelled)
        cancelled = true
        scope.launch {
            if (!still) sheet.animateTo(1f, tween(300, easing = ease))
            sheetGone = true
        }
    }

    fun send() {
        if (finished) return
        finished = true
        listener.cancel()
        deps.voice.stopSpeaking()
        // Practice: nothing is sent anywhere. Result says so out loud.
        deps.state.record(ConfirmTitle, Outcome.Done)
        nav.replace(Route.Result)
    }

    fun askAgainOrCancel() {
        unclear += 1
        if (unclear >= 2) {
            cancel("I wasn't sure, so I cancelled. Nothing was sent.")
            return
        }
        asking = true
        scope.launch {
            deps.voice.speak("Sorry, I'm not sure. $ConfirmQuestion")
            ScreenParts.awaitSpeechEnd(deps.voice)
            asking = false
            if (!finished) listener.start()
        }
    }

    listener = ScreenParts.rememberScreenListener(
        onFailed = { reason -> if (reason == "I didn't catch that.") askAgainOrCancel() },
    ) { text ->
        when (classifyAnswer(text)) {
            Answer.Yes -> send()
            Answer.No -> cancel()
            Answer.Unclear -> askAgainOrCancel()
        }
    }

    ScreenParts.OnFirstShow {
        deps.feedback.cue(Cue.NeedsAnswer)
        deps.voice.speak(ConfirmQuestion)
    }

    LaunchedEffect(Unit) {
        if (!still && !cancelled) sheet.animateTo(0f, tween(300, easing = ease))
    }
    LaunchedEffect(sheetGone) {
        delay(50)
        runCatching { if (sheetGone) homeFocus.requestFocus() else cancelFocus.requestFocus() }
    }

    BackHandler { if (cancelled) nav.home() else cancel() }

    val status = when {
        listener.active -> "Listening"
        cancelled -> "Cancelled — nothing was sent"
        else -> "Needs your answer. Send to $ConfirmTo: $ConfirmText? Cancel is selected."
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
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.group).padding(horizontal = 16.dp)) {
                listOf("Opening WhatsApp", "Finding Mom's chat", "Typing your reply…").forEach {
                    Box(Modifier.heightIn(min = 64.dp), contentAlignment = Alignment.CenterStart) {
                        Text(it, style = Clarity.type.body, color = c.label)
                    }
                }
            }
        }

        if (!cancelled) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 1f - sheet.value }
                    .background(c.dim)
                    .clearAndSetSemantics {},
            )
        }

        if (!sheetGone) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .graphicsLayer { translationY = sheet.value * size.height }
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(c.screen)
                    .then(
                        if (c.isDark) Modifier.border(1.dp, c.separator, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                        else Modifier,
                    )
                    .semantics { paneTitle = "Needs your answer" }
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 20.dp),
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
                        "Send to $ConfirmTo?",
                        style = Clarity.type.title,
                        color = c.label,
                        modifier = Modifier.semantics { heading() },
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("“$ConfirmText”", style = Clarity.type.title.copy(fontWeight = FontWeight.Medium), color = c.label)
                        Footnote("Exactly as typed on screen")
                    }
                    ScreenParts.PracticeLabel(text = "Practice run — sample data. Nothing will really be sent. Saathi can't use your apps yet in this version.")
                }

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecondaryButton(
                        "Cancel",
                        { cancel() },
                        modifier = Modifier.focusRequester(cancelFocus),
                        description = "Cancel. This is the default. Nothing will be sent.",
                        tag = "Default",
                    )
                    PrimaryButton(
                        "Send",
                        { send() },
                        description = "Send “$ConfirmText” to $ConfirmTo. Practice run — nothing is really sent.",
                    )
                }

                ScreenParts.ListenHint(listener)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Footnote(
                        if (asking) "Asking again…" else "Say “Yes” or “No”. If I'm not sure, I'll ask again, then cancel.",
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

        if (sheetGone) {
            Column(
                ScreenParts.rise(0, still)
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(c.screen)
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(c.group).clearAndSetSemantics {},
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.Close, contentDescription = null, tint = c.label) }
                    Text("Cancelled", style = Clarity.type.title, color = c.label, modifier = Modifier.semantics { heading() })
                }
                Body("Nothing was sent.", secondary = true)
                PrimaryButton("Home", { nav.home() }, modifier = Modifier.focusRequester(homeFocus))
                YouCanSay(listOf("Go home", "Help")) { nav.replace(Route.Listening) }
            }
        }
    }
}

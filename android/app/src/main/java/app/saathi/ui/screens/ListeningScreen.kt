package app.saathi.ui.screens

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.saathi.feedback.Cue
import app.saathi.ui.AppNav
import app.saathi.ui.LocalDeps
import app.saathi.ui.Route
import app.saathi.ui.components.Body
import app.saathi.ui.components.LargeTitle
import app.saathi.ui.components.NavRow
import app.saathi.ui.components.PrimaryButton
import app.saathi.ui.components.SaathiScreen
import app.saathi.ui.components.SecondaryButton
import app.saathi.ui.routeForPhrase
import app.saathi.ui.theme.Clarity
import app.saathi.voice.Voice

private enum class ListenPhase { Starting, Hearing, Failed, NoMic }

/** Screen 4 — Listening: real speech recognition, orb follows your voice, words appear as heard. */
@Composable
fun ListeningScreen(nav: AppNav) {
    val deps = LocalDeps.current
    val voice = deps.voice
    val feedback = deps.feedback
    val context = LocalContext.current
    val c = Clarity.colors
    val still = ScreenParts.rememberStill()

    var phase by remember { mutableStateOf(ListenPhase.Starting) }
    var active by remember { mutableStateOf(false) }
    var failReason by remember { mutableStateOf("") }
    var transcript by remember { mutableStateOf("") }
    var finished by remember { mutableStateOf(false) }

    fun begin() {
        if (finished) return
        failReason = ""
        transcript = ""
        voice.reset()
        voice.startListening()
        feedback.cue(Cue.StartListening)
        active = true
        phase = ListenPhase.Hearing
    }

    fun cancel() {
        if (finished) return
        finished = true
        if (active) {
            active = false
            voice.cancelListening()
        }
        feedback.cue(Cue.Cancelled)
        nav.home()
    }

    fun handle(text: String) {
        val t = text.trim().lowercase()
        when {
            t in setOf("stop", "cancel", "ruko", "bas") -> cancel()
            "start again" in t -> begin()
            else -> {
                finished = true
                deps.state.lastRequest = text
                when (val r = routeForPhrase(text)) {
                    Route.Home -> nav.home()
                    null -> nav.replace(Route.Working)
                    else -> nav.replace(r)
                }
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) begin() else phase = ListenPhase.NoMic
    }

    LaunchedEffect(Unit) {
        if (ScreenParts.hasPermission(context, Manifest.permission.RECORD_AUDIO)) begin()
        else launcher.launch(Manifest.permission.RECORD_AUDIO)
    }
    // Back from app settings with the mic now allowed: start straight away.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (phase == ListenPhase.NoMic && ScreenParts.hasPermission(context, Manifest.permission.RECORD_AUDIO)) begin()
    }

    val listen by voice.listen.collectAsState()
    LaunchedEffect(listen) {
        if (!active) return@LaunchedEffect
        when (val l = listen) {
            is Voice.Listen.Hearing -> if (l.partial.isNotBlank()) transcript = l.partial
            is Voice.Listen.Heard -> {
                active = false
                transcript = l.text
                feedback.cue(Cue.StopListening)
                voice.reset()
                handle(l.text)
            }
            is Voice.Listen.Failed -> {
                active = false
                voice.reset()
                feedback.cue(Cue.StopListening)
                failReason = l.reason
                phase = ListenPhase.Failed
            }
            Voice.Listen.Idle -> Unit
        }
    }
    DisposableEffect(Unit) { onDispose { if (active) voice.cancelListening() } }
    BackHandler { cancel() }

    val title = when (phase) {
        ListenPhase.Starting -> "Getting ready…"
        ListenPhase.Hearing -> if (active) "Listening…" else "Got it"
        ListenPhase.Failed -> failReason.ifBlank { "Listening stopped." }
        ListenPhase.NoMic -> "Microphone is off"
    }
    val level = (listen as? Voice.Listen.Hearing)?.level ?: 0f

    SaathiScreen(
        status = title,
        youCanSay = if (phase == ListenPhase.Hearing) listOf("Stop", "Start again") else listOf("Read my messages", "Go home"),
        onTalk = {
            when {
                active -> voice.stopListening()
                phase == ListenPhase.NoMic -> launcher.launch(Manifest.permission.RECORD_AUDIO)
                else -> begin()
            }
        },
        navRow = { NavRow("Home", { cancel() }) },
        bottom = {
            when (phase) {
                ListenPhase.Starting, ListenPhase.Hearing -> {
                    PrimaryButton("Done", { if (active) voice.stopListening() }, description = "Done. Saathi starts working on it")
                    SecondaryButton("Cancel", { cancel() }, description = "Cancel. Nothing will happen")
                }
                ListenPhase.Failed -> {
                    PrimaryButton("Try again", { begin() }, description = "Try again. Start listening")
                    SecondaryButton("Home", { nav.home() })
                }
                ListenPhase.NoMic -> {
                    PrimaryButton("Open app settings", { ScreenParts.openAppSettings(context) }, description = "Open app settings to allow the microphone")
                    SecondaryButton("Home", { nav.home() })
                }
            }
        },
    ) {
        LargeTitle(title, ScreenParts.rise(1, still))

        if (phase == ListenPhase.NoMic) {
            Body(
                "Saathi needs the microphone to hear you. In app settings, choose Permissions, then Microphone, then Allow. " +
                    "You can still use every button without it.",
                ScreenParts.rise(2, still),
                secondary = true,
            )
        } else {
            VoiceOrb(level = if (active) level else 0f, still = still, live = active, modifier = ScreenParts.rise(2, still))

            Box(ScreenParts.rise(3, still).fillMaxWidth().heightIn(min = 104.dp)) {
                if (transcript.isBlank()) {
                    Text(
                        if (phase == ListenPhase.Failed) "Tap Try again, or Home." else "Say what you need — for example, “Read my messages”.",
                        style = Clarity.type.body,
                        color = c.secondary,
                        modifier = Modifier.semantics { contentDescription = "Nothing heard yet" },
                    )
                } else {
                    Text(
                        transcript,
                        style = Clarity.type.title.copy(fontWeight = FontWeight.Medium),
                        color = c.label,
                        modifier = Modifier.semantics { contentDescription = "You said: $transcript" },
                    )
                }
            }
        }
    }
}

/** Accent orb that grows with your voice, plus a 5-bar meter. Decorative (TalkBack hears the words). */
@Composable
private fun VoiceOrb(level: Float, still: Boolean, live: Boolean, modifier: Modifier = Modifier) {
    val c = Clarity.colors
    val target = if (still) 1f else 1f + 0.12f * level
    val scale by animateFloatAsState(target, tween(if (still) 0 else 140), label = "orb")
    Column(
        modifier.fillMaxWidth().padding(top = 12.dp).clearAndSetSemantics {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(160.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        alpha = if (live || still) 1f else 0.6f
                    }
                    .clip(CircleShape)
                    .background(c.accent),
            )
        }
        val weights = listOf(0.55f, 0.85f, 1f, 0.75f, 0.6f)
        Row(Modifier.height(36.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            weights.forEachIndexed { i, w ->
                val barTarget = if (still || !live) 0.5f else (0.25f + 0.75f * (level * w)).coerceIn(0.25f, 1f)
                val h by animateFloatAsState(barTarget, tween(if (still) 0 else 120), label = "bar$i")
                Box(Modifier.width(6.dp).height(32.dp * h).clip(RoundedCornerShape(3.dp)).background(c.accentText))
            }
        }
    }
}

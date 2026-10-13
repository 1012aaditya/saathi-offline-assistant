package app.saathi.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.saathi.agent.Outcome
import app.saathi.agent.TaskRunner
import app.saathi.feedback.Cue
import app.saathi.ui.AppNav
import app.saathi.ui.LocalDeps
import app.saathi.ui.Route
import app.saathi.ui.components.Group
import app.saathi.ui.components.GroupDivider
import app.saathi.ui.components.LargeTitle
import app.saathi.ui.components.PrimaryButton
import app.saathi.ui.components.SaathiScreen
import app.saathi.ui.components.SecondaryButton
import app.saathi.ui.components.SectionHeader
import app.saathi.ui.theme.Clarity
import kotlinx.coroutines.delay

/** Bell swing, as (fraction of 900 ms, degrees). Played twice. */
private val BellKeys = listOf(0f to 0f, 0.15f to 14f, 0.30f to -12f, 0.45f to 8f, 0.60f to -5f, 0.75f to 2f, 1f to 0f)

private fun bellAngle(t: Float): Float {
    for (i in 1 until BellKeys.size) {
        val (t1, a1) = BellKeys[i]
        if (t <= t1) {
            val (t0, a0) = BellKeys[i - 1]
            return a0 + (a1 - a0) * ((t - t0) / (t1 - t0))
        }
    }
    return 0f
}

/**
 * Screen 8 — Your turn. Saathi stopped (PIN, password, CAPTCHA) and hands control back. The reason
 * is the real one from the safety gate. Saathi never reads or types PINs and never touches TalkBack.
 */
@Composable
fun HandoverScreen(nav: AppNav) {
    val deps = LocalDeps.current
    val c = Clarity.colors
    val still = ScreenParts.rememberStill()

    val reason = remember {
        ((TaskRunner.state.value as? TaskRunner.State.Finished)?.outcome as? Outcome.HandedOver)?.message
            ?: "I've stopped. Please finish this step yourself."
    }

    ScreenParts.OnFirstShow {
        deps.feedback.cue(Cue.HandOver)
        deps.voice.speak("Your turn. $reason") // history was recorded by the task runner
    }

    val ring = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (still) return@LaunchedEffect
        delay(250)
        repeat(2) {
            ring.snapTo(0f)
            ring.animateTo(1f, tween(900, easing = LinearEasing))
        }
        ring.snapTo(0f)
    }

    fun done() {
        deps.voice.stopSpeaking()
        nav.home()
    }

    fun callSaathi() {
        deps.voice.stopSpeaking()
        nav.replace(Route.Listening)
    }

    val listener = ScreenParts.rememberScreenListener { text ->
        val w = ScreenParts.words(text)
        when {
            "done" in w || "home" in w -> done()
            "saathi" in w || "sathi" in w || "साथी" in w -> callSaathi()
            else -> ScreenParts.handleAsRequest(text, deps, nav)
        }
    }

    val status = if (listener.active) "Listening"
    else "Your turn. $reason TalkBack is unchanged — Saathi never turns it off."

    SaathiScreen(
        status = status,
        youCanSay = listOf("Saathi", "I'm done"),
        onTalk = { listener.toggle() },
        bottom = {
            ScreenParts.ListenHint(listener)
            PrimaryButton("I'm done", { done() }, description = "I'm done. Go home.")
            SecondaryButton("Call Saathi", { callSaathi() }, description = "Call Saathi. Start listening.")
        },
    ) {
        Box(ScreenParts.rise(0, still).padding(top = 24.dp).clearAndSetSemantics {}) {
            Icon(
                Icons.Outlined.NotificationsActive,
                contentDescription = null,
                tint = c.label,
                modifier = Modifier
                    .size(40.dp)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0.5f, 0.08f)
                        rotationZ = bellAngle(ring.value)
                    },
            )
        }

        Column(ScreenParts.rise(1, still), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LargeTitle("Your turn")
            Text(
                reason,
                style = Clarity.type.headline.copy(fontWeight = FontWeight.Normal),
                color = c.secondary,
            )
        }

        Group(ScreenParts.rise(3, still)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .semantics(mergeDescendants = true) {}
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "TalkBack is unchanged — Saathi never turns it off",
                    style = Clarity.type.body,
                    color = c.label,
                    modifier = Modifier.weight(1f),
                )
                ScreenParts.DrawnCheck(c.accentText, 24.dp, 2.6.dp, still, delayMs = 450)
            }
        }

        Column(ScreenParts.rise(4, still), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader("What to do now")
            Group {
                listOf(
                    "Finish this step yourself (PIN, password or CAPTCHA)",
                    "When you're done, say “Saathi” or hold Volume-down",
                ).forEachIndexed { i, text ->
                    if (i > 0) GroupDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .semantics(mergeDescendants = true) { contentDescription = "Step ${i + 1}: $text" }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(c.screen),
                            contentAlignment = Alignment.Center,
                        ) { Text("${i + 1}", style = Clarity.type.footnote.copy(fontWeight = FontWeight.Bold), color = c.label) }
                        Text(text, style = Clarity.type.body, color = c.label)
                    }
                }
            }
        }
    }
}

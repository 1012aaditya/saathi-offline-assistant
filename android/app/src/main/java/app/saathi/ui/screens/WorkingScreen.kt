package app.saathi.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.saathi.data.Outcome
import app.saathi.feedback.Cue
import app.saathi.ui.AppNav
import app.saathi.ui.LocalDeps
import app.saathi.ui.Route
import app.saathi.ui.components.DangerButton
import app.saathi.ui.components.Group
import app.saathi.ui.components.GroupDivider
import app.saathi.ui.components.SaathiScreen
import app.saathi.ui.theme.Clarity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

private const val StepMillis = 1200L

/**
 * Screen 5 — Working. In step 1 this is a clearly labelled PRACTICE run with sample data:
 * Saathi can't operate other apps yet, so nothing here touches WhatsApp, payments or anything else.
 */
@Composable
fun WorkingScreen(nav: AppNav) {
    val deps = LocalDeps.current
    val c = Clarity.colors
    val still = ScreenParts.rememberStill()
    val request = deps.state.lastRequest
    val practice = remember(request) { ScreenParts.practiceFor(request) }
    val steps = practice.steps

    var current by rememberSaveable { mutableIntStateOf(0) }
    var completed by rememberSaveable { mutableIntStateOf(0) }
    // Set once we leave (Stop, Back, or moving on) so no late timer can navigate again.
    var leaving by remember { mutableStateOf(false) }

    fun stop() {
        if (leaving) return
        leaving = true
        deps.feedback.cue(Cue.Cancelled)
        deps.voice.speak("Stopped.")
        val label = request.trim().ifBlank { "task" }.take(60)
        deps.state.record("Practice: $label", Outcome.Cancelled)
        nav.home()
    }

    val listener = ScreenParts.rememberScreenListener { text ->
        val w = ScreenParts.words(text)
        if (w.any { it in setOf("stop", "cancel", "ruko", "bas", "home") }) {
            stop()
        } else {
            val now = if (completed >= steps.size) "Finished." else steps[current] + "."
            deps.voice.speak("Practice run. $now Saathi can't use your apps yet in this version.")
        }
    }

    LaunchedEffect(Unit) {
        while (completed < steps.size) {
            current = completed
            delay(StepMillis)
            // Hold still while the user is talking to us.
            snapshotFlow { listener.active }.first { !it }
            if (leaving) return@LaunchedEffect
            completed += 1
            deps.feedback.cue(Cue.Tick)
        }
        delay(500)
        if (leaving) return@LaunchedEffect
        leaving = true
        when (practice) {
            ScreenParts.Practice.Send -> nav.replace(Route.Confirm)
            ScreenParts.Practice.Pay -> nav.replace(Route.Handover)
            else -> nav.replace(Route.Result)
        }
    }

    BackHandler { stop() }

    val nowText = if (completed >= steps.size) "Finished" else steps[current]
    val status = if (listener.active) "Listening" else "Practice run in ${practice.app}. $nowText."
    val progress = (completed + if (completed < steps.size) 0.35f else 0f) / steps.size
    val ring by animateFloatAsState(progress, tween(if (still) 0 else 600), label = "ring")

    SaathiScreen(
        status = status,
        youCanSay = listOf("Stop", "What are you doing?"),
        onTalk = { listener.toggle() },
        bottom = {
            ScreenParts.ListenHint(listener)
            DangerButton("Stop", { stop() }, description = "Stop. Ends this practice run and goes home.")
        },
    ) {
        Text(
            "Working",
            style = Clarity.type.headline,
            color = c.secondary,
            modifier = ScreenParts.rise(0, still),
        )

        Text(
            "You asked: “${request.ifBlank { "…" }}”",
            style = Clarity.type.body,
            color = c.label,
            modifier = ScreenParts.rise(1, still),
        )

        ScreenParts.PracticeLabel(ScreenParts.rise(1, still))

        Column(
            ScreenParts.rise(2, still).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(120.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = 3.dp.toPx()
                    val inset = w / 2
                    val arcSize = Size(size.width - w, size.height - w)
                    drawCircle(c.separator, radius = (size.minDimension - w) / 2, style = Stroke(w))
                    drawArc(
                        color = c.accentText,
                        startAngle = -90f,
                        sweepAngle = 360f * ring,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(w, cap = StrokeCap.Round),
                    )
                }
                Icon(
                    when (practice) {
                        ScreenParts.Practice.Read, ScreenParts.Practice.Send -> Icons.Outlined.ChatBubbleOutline
                        ScreenParts.Practice.Pay -> Icons.Outlined.Payments
                        else -> Icons.Outlined.Info
                    },
                    contentDescription = null,
                    tint = c.label,
                    modifier = Modifier.size(40.dp),
                )
            }
            Text(practice.app, style = Clarity.type.title, color = c.label)
        }

        Group(ScreenParts.rise(3, still)) {
            for (i in 0..minOf(current, steps.size - 1)) {
                if (i > 0) GroupDivider()
                val isDone = i < completed
                val label = if (isDone) steps[i] else steps[i] + "…"
                Row(
                    ScreenParts.rise(0, still)
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .semantics(mergeDescendants = true) { contentDescription = (if (isDone) "Done: " else "Now: ") + steps[i] }
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                        if (isDone) ScreenParts.DrawnCheck(c.accentText, 22.dp, 2.6.dp, still)
                        else NowDot(still)
                    }
                    Text(label, style = Clarity.type.body.copy(fontWeight = if (isDone) FontWeight.Normal else FontWeight.Medium), color = c.label)
                }
            }
        }
    }
}

/** The "in progress" dot; breathes unless motion is off. */
@Composable
private fun NowDot(still: Boolean) {
    val c = Clarity.colors
    val a = if (still) 1f else {
        val t = rememberInfiniteTransition(label = "dot")
        t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "dotAlpha").value
    }
    Box(Modifier.size(10.dp).graphicsLayer { alpha = a }.clip(CircleShape).background(c.accentText))
}

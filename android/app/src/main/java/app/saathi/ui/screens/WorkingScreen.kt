package app.saathi.ui.screens

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import app.saathi.agent.PhoneBook
import app.saathi.agent.contactsWhy
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.saathi.agent.Command
import app.saathi.agent.Outcome
import app.saathi.agent.Requests
import app.saathi.agent.SaathiAccessibilityService
import app.saathi.agent.TaskRunner
import app.saathi.agent.Texts
import app.saathi.feedback.Cue
import app.saathi.ui.AppNav
import app.saathi.ui.LocalDeps
import app.saathi.ui.Route
import app.saathi.ui.components.Body
import app.saathi.ui.components.DangerButton
import app.saathi.ui.components.Group
import app.saathi.ui.components.GroupDivider
import app.saathi.ui.components.LargeTitle
import app.saathi.ui.components.PrimaryButton
import app.saathi.ui.components.SaathiScreen
import app.saathi.ui.components.SecondaryButton
import app.saathi.ui.theme.Clarity

/**
 * Screen 5 — Working. Runs the real offline task through the accessibility service (every step
 * gated by the safety rules) and shows Saathi's narration lines as they happen. If the service is
 * off, explains that and opens Android's Accessibility settings — Saathi never switches it on itself.
 */
@Composable
fun WorkingScreen(nav: AppNav) {
    val deps = LocalDeps.current
    val context = LocalContext.current
    val c = Clarity.colors
    val still = ScreenParts.rememberStill()
    val t = remember { Texts(Requests.lang(deps)) }

    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val serviceOn = remember(refresh) { SaathiAccessibilityService.isEnabled(context) && TaskRunnerReady.ready() }
    var leaving by remember { mutableStateOf(false) }

    val st by TaskRunner.state.collectAsState()
    // Ignore results of earlier tasks: only this screen's run (or the one already under way) counts.
    val firstRun = remember { TaskRunner.state.value.runId + if (TaskRunner.pending != null) 1 else 0 }

    // Start the waiting command once the service is on (also right after the owner switched it on).
    fun startPending() {
        val cmd = TaskRunner.pending ?: return
        if (TaskRunner.start(context, cmd, Requests.lang(deps), deps.state) { line -> deps.voice.speak(line) }) {
            TaskRunner.pending = null
            deps.feedback.cue(Cue.Tick)
        }
    }
    // Phone contacts (optional): asked once, the first time a task names a person. Android shows its
    // own dialog and the user taps Allow (or not) themselves; the task goes on either way.
    val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        if (!leaving) startPending()
    }
    LaunchedEffect(serviceOn) {
        val cmd = TaskRunner.pending ?: return@LaunchedEffect
        if (!serviceOn) return@LaunchedEffect
        if (PhoneBook.shouldAsk(context, cmd)) {
            PhoneBook.markAsked(context)
            deps.voice.speak(t.contactsWhy)
            ScreenParts.awaitSpeechEnd(deps.voice)
            if (leaving || TaskRunner.pending == null) return@LaunchedEffect
            if (runCatching { askContacts.launch(Manifest.permission.READ_CONTACTS) }.isSuccess) return@LaunchedEffect
        }
        startPending()
    }

    // Follow the task: confirm before sending, then the result (or hand over).
    LaunchedEffect(st) {
        if (leaving || TaskRunner.pending != null || st.runId < firstRun) return@LaunchedEffect
        // Floating Saathi's sessions are shown and answered on the overlay, never here.
        if (TaskRunner.isFloatingRun(st.runId)) return@LaunchedEffect
        when (val s = st) {
            is TaskRunner.State.Confirming -> { leaving = true; nav.replace(Route.Confirm) }
            is TaskRunner.State.Choosing -> { leaving = true; nav.replace(Route.DraftChoice) }
            is TaskRunner.State.ChoosingItem -> { leaving = true; nav.replace(Route.OrderPick) }
            is TaskRunner.State.Picking -> { leaving = true; nav.replace(Route.ContactChoice) }
            is TaskRunner.State.Finished -> {
                // Our own Stop went home already; a vanished service shows (and says) the result.
                if (s.outcome is Outcome.Stopped && !TaskRunner.stoppedByServiceLoss) return@LaunchedEffect
                leaving = true
                nav.replace(if (s.outcome is Outcome.HandedOver) Route.Handover else Route.Result)
            }
            is TaskRunner.State.Guiding -> { leaving = true; nav.replace(Route.Guide) }
            is TaskRunner.State.Running -> if (s.lines.isNotEmpty()) deps.feedback.cue(Cue.Tick)
            TaskRunner.State.Idle -> Unit
        }
    }

    fun stop() {
        if (leaving) return
        leaving = true
        TaskRunner.pending = null
        TaskRunner.stop()
        deps.feedback.cue(Cue.Cancelled)
        deps.voice.speak(t.stopped)
        nav.home()
    }

    val running = st as? TaskRunner.State.Running
    val lines = running?.lines.orEmpty()
    val request = deps.state.lastRequest

    val listener = ScreenParts.rememberScreenListener { text ->
        val w = ScreenParts.words(text)
        if (w.any { it in setOf("stop", "cancel", "ruko", "bas", "home", "रुको", "बस") }) stop()
        else deps.voice.speak(lines.lastOrNull() ?: t.serviceOff)
    }

    BackHandler { stop() }

    if (!serviceOn) {
        val status = if (listener.active) "Listening" else "${t.serviceOff} Open Accessibility settings, choose Saathi, and switch it on."
        SaathiScreen(
            status = status,
            youCanSay = listOf("Stop", "Go home"),
            onTalk = { listener.toggle() },
            bottom = {
                ScreenParts.ListenHint(listener)
                PrimaryButton(
                    "Open Accessibility settings",
                    { SaathiAccessibilityService.openSettings(context) },
                    description = "Open Accessibility settings. Choose Saathi there and switch it on yourself.",
                )
                SecondaryButton("Home", { stop() }, description = "Home. Nothing will happen.")
            },
        ) {
            LargeTitle("Switch on Saathi first", ScreenParts.rise(0, still))
            Body(
                "To use WhatsApp for you, Saathi needs its Accessibility service. Only you can switch it on: " +
                    "in Accessibility settings, choose Installed apps (or Downloaded apps), then Saathi, then switch it on. " +
                    "Android will show a warning — that's normal. Come back here and I'll start.",
                ScreenParts.rise(1, still),
                secondary = true,
            )
            ScreenParts.NoteBox(
                "Saathi works offline. Screen text stays on your phone. It always asks before sending, never reads or types PINs, " +
                    "and never turns TalkBack off.",
                ScreenParts.rise(2, still),
            )
            if (request.isNotBlank()) Body("You asked: “$request”", ScreenParts.rise(3, still))
        }
        return
    }

    val app = running?.app ?: "Saathi"
    val nowText = lines.lastOrNull() ?: "Starting"
    val status = if (listener.active) "Listening" else "Working in $app. $nowText."

    SaathiScreen(
        status = status,
        youCanSay = listOf("Stop", "What are you doing?"),
        onTalk = { listener.toggle() },
        bottom = {
            ScreenParts.ListenHint(listener)
            DangerButton("Stop", { stop() }, description = "Stop. Saathi stops right away and goes home.")
        },
    ) {
        Text("Working", style = Clarity.type.headline, color = c.secondary, modifier = ScreenParts.rise(0, still))
        Text(
            "You asked: “${request.ifBlank { "…" }}”",
            style = Clarity.type.body,
            color = c.label,
            modifier = ScreenParts.rise(1, still),
        )

        Column(
            ScreenParts.rise(2, still).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(120.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                Spinner(still)
                Icon(
                    when (running?.command) {
                        is Command.ScreenSummary -> Icons.Outlined.Visibility
                        is Command.ReadLast, is Command.Reply, is Command.UnreadSummary -> Icons.Outlined.ChatBubbleOutline
                        else -> Icons.Outlined.Info
                    },
                    contentDescription = null,
                    tint = c.label,
                    modifier = Modifier.size(40.dp),
                )
            }
            Text(app, style = Clarity.type.title, color = c.label)
        }

        if (lines.isNotEmpty()) {
            Group(ScreenParts.rise(3, still)) {
                lines.takeLast(6).forEachIndexed { i, line ->
                    if (i > 0) GroupDivider()
                    val isLast = i == lines.takeLast(6).lastIndex
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .semantics(mergeDescendants = true) { contentDescription = (if (isLast) "Now: " else "Done: ") + line }
                            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                            if (isLast) NowDot(still) else ScreenParts.DrawnCheck(c.accentText, 22.dp, 2.6.dp, still)
                        }
                        Text(line, style = Clarity.type.body.copy(fontWeight = if (isLast) FontWeight.Medium else FontWeight.Normal), color = c.label)
                    }
                }
            }
        }
    }
}

/** The service is bound and the bridge has its device. */
private object TaskRunnerReady {
    fun ready(): Boolean = app.saathi.agent.DeviceBridge.connected
}

/** Indeterminate progress ring (decorative); a still arc when motion is off. */
@Composable
private fun Spinner(still: Boolean) {
    val c = Clarity.colors
    val angle = if (still) 0f else {
        val tr = rememberInfiniteTransition(label = "spin")
        tr.animateFloat(0f, 360f, infiniteRepeatable(tween(1400)), label = "angle").value
    }
    Canvas(Modifier.fillMaxSize()) {
        val w = 3.dp.toPx()
        val inset = w / 2
        drawCircle(c.separator, radius = (size.minDimension - w) / 2, style = Stroke(w))
        drawArc(
            color = c.accentText,
            startAngle = angle - 90f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(size.width - w, size.height - w),
            style = Stroke(w, cap = StrokeCap.Round),
        )
    }
}

/** The "in progress" dot; breathes unless motion is off. */
@Composable
private fun NowDot(still: Boolean) {
    val c = Clarity.colors
    val a = if (still) 1f else {
        val tr = rememberInfiniteTransition(label = "dot")
        tr.animateFloat(0.35f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "dotAlpha").value
    }
    Box(Modifier.size(10.dp).graphicsLayer { alpha = a }.clip(CircleShape).background(c.accentText))
}

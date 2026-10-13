package app.saathi.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.East
import androidx.compose.material.icons.filled.West
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.saathi.data.WalkSensitivity
import app.saathi.feedback.Cue
import app.saathi.feedback.WalkLevel
import app.saathi.feedback.reducedMotion
import app.saathi.ui.AppNav
import app.saathi.ui.LocalDeps
import app.saathi.ui.Route
import app.saathi.ui.components.DangerButton
import app.saathi.ui.components.NavRow
import app.saathi.ui.components.PrimaryButton
import app.saathi.ui.components.SaathiScreen
import app.saathi.ui.components.SecondaryButton
import app.saathi.ui.theme.Clarity
import app.saathi.ui.theme.DarkClarity
import app.saathi.ui.theme.LocalClarity
import app.saathi.walk.OverlayBox
import app.saathi.walk.Phrasebook
import app.saathi.walk.SteerHint
import app.saathi.walk.WalkEngine
import app.saathi.walk.WalkOutput
import app.saathi.walk.WalkSnapshot
import kotlinx.coroutines.delay
import java.text.NumberFormat

/*
 * Screen 9 — Walk mode, live.
 *
 * Back camera → the same two YOLOv8n models as the web walk mode, on the phone (no internet) →
 * spoken warnings + vibration (WalkEngine). Starts PAUSED: no camera, sound or vibration until
 * Start. Everything stops when the screen is left or the app goes to the background.
 * It only ever warns — it never says the way is clear.
 */

// Warning colours (DESIGN.md walk row). Always paired with an icon + word, never colour alone.
private val NoticeColor = Color(0xFFFFD60A)
private val CarefulColor = Color(0xFFFF9F0A)
private val StopColor = Color(0xFFFF453A)
private val QuietColor = Color(0xFF8E8E93)

/** A warning line stays on screen this long; the direction hint too. */
private const val LINE_MS = 6000L

private fun levelOf(level: Int): WalkLevel? = when (level) {
    1 -> WalkLevel.Notice
    2 -> WalkLevel.Careful
    3 -> WalkLevel.Stop
    else -> null
}

private fun levelColor(level: WalkLevel) = when (level) {
    WalkLevel.Notice -> NoticeColor
    WalkLevel.Careful -> CarefulColor
    WalkLevel.Stop -> StopColor
}

private fun levelWord(level: WalkLevel) = when (level) {
    WalkLevel.Notice -> "Notice"
    WalkLevel.Careful -> "Careful"
    WalkLevel.Stop -> "Stop"
}

/** Low warns later, High earlier: multiplies every distance threshold. */
private fun scaleFor(s: WalkSensitivity) = when (s) {
    WalkSensitivity.Low -> 0.75
    WalkSensitivity.Medium -> 1.0
    WalkSensitivity.High -> 1.3
}

private fun fmtMetres(m: Double): String =
    NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(m)

/** Heat map colour by estimated distance: warm = near, cool = far. */
private fun heatColor(d: Double): Color = when {
    d < 1.0 -> StopColor
    d < 2.0 -> CarefulColor
    d < 3.5 -> NoticeColor
    d < 6.0 -> Color(0xFF64D2FF)
    else -> Color(0xFF5E5CE6)
}

@Composable
fun WalkScreen(nav: AppNav) {
    // Walk mode is always dark, whatever the app theme.
    CompositionLocalProvider(LocalClarity provides DarkClarity) { WalkContent(nav) }
}

@Composable
private fun WalkContent(nav: AppNav) {
    val deps = LocalDeps.current
    val state = deps.state
    val c = Clarity.colors
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val still = remember { reducedMotion(context) }

    val engine = remember { WalkEngine(context) }
    val snap by engine.state.collectAsState()
    var heatOn by remember { mutableStateOf(false) }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER // whole frame visible, so the boxes line up
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
    }

    // Settings apply live: language of the spoken lines, sensitivity.
    val words = Phrasebook.forLanguage(state.languageTag?.substringBefore('-') ?: deps.voice.locale.language)
    SideEffect {
        engine.words = words
        engine.distScale = scaleFor(state.walkSensitivity)
    }

    val output = remember {
        object : WalkOutput {
            // Obstacle warnings are safety output: always spoken while walk mode runs. The Sounds
            // setting only mutes earcons (a device without a vibration motor has nothing else).
            override fun speak(text: String) = deps.voice.speak(text)

            override fun vibrate(level: Int) {
                levelOf(level)?.let(deps.feedback::walk)
            }

            override fun isSpeaking() = deps.voice.speaking.value
        }
    }

    // Camera permission: asked only when the user presses Start; never granted by the app itself.
    fun hasCamera() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var cameraOk by remember { mutableStateOf(hasCamera()) }
    var cameraRefused by remember { mutableStateOf(false) }
    val startEngine = {
        deps.feedback.cue(Cue.StartListening)
        engine.start(lifecycleOwner, previewView, output)
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        cameraOk = ok
        cameraRefused = !ok
        if (ok) {
            startEngine()
        } else {
            deps.voice.speak("Walk mode needs the camera. You can allow it in the phone's settings.")
        }
    }
    val start: () -> Unit = {
        if (engine.state.value.phase == WalkSnapshot.Phase.Failed) {
            deps.feedback.cue(Cue.Cancelled)
            deps.voice.speak("Walk mode couldn't start on this phone.")
        } else if (hasCamera()) {
            cameraOk = true
            startEngine()
        } else {
            askCamera.launch(Manifest.permission.CAMERA)
        }
    }
    val stop: () -> Unit = {
        engine.stop()
        deps.feedback.stopVibration()
        deps.feedback.cue(Cue.StopListening)
    }

    // Light status/nav bar icons on black; restore on leave. Leaving stops the camera, sound and vibration.
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        val ctrl = window?.let { WindowCompat.getInsetsController(it, view) }
        val prevStatus = ctrl?.isAppearanceLightStatusBars
        val prevNav = ctrl?.isAppearanceLightNavigationBars
        ctrl?.isAppearanceLightStatusBars = false
        ctrl?.isAppearanceLightNavigationBars = false
        onDispose {
            engine.release()
            deps.feedback.stopVibration()
            deps.voice.stopSpeaking()
            view.keepScreenOn = false
            if (ctrl != null) {
                prevStatus?.let { ctrl.isAppearanceLightStatusBars = it }
                prevNav?.let { ctrl.isAppearanceLightNavigationBars = it }
            }
        }
    }

    // App to the background (screen off, another app): stop and say so — never warn silently from a dead camera.
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP && engine.state.value.running) {
                engine.stop()
                deps.feedback.stopVibration()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Keep the screen awake while walking (like the web version's wake lock).
    SideEffect { view.keepScreenOn = snap.running }

    // Clock for fading out old lines/hints.
    var nowMs by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(snap.running) {
        while (snap.running) {
            nowMs = SystemClock.uptimeMillis()
            delay(500)
        }
    }

    val running = snap.running
    val lineFresh = running && snap.line.isNotEmpty() && nowMs - snap.lineAt < LINE_MS
    val lineLevel = if (lineFresh) levelOf(snap.lineLevel) else null
    val hint = if (running && nowMs - snap.hintAt < LINE_MS) snap.hint else null
    val visibleStatus = when {
        snap.phase == WalkSnapshot.Phase.Failed -> "Walk mode couldn't start on this phone"
        !running -> "Walk mode is paused"
        snap.phase == WalkSnapshot.Phase.Loading -> "Getting ready…"
        lineFresh -> snap.line
        else -> "Watching ahead"
    }
    // Polite live region. Warnings are always spoken aloud, so TalkBack only hears on/off changes
    // (no double speech).
    val liveStatus = if (running) "Walk mode on" else visibleStatus

    Box(Modifier.fillMaxSize().background(c.screen)) {
        SaathiScreen(
            status = liveStatus,
            youCanSay = listOf("Stop", "Go home"),
            onTalk = { nav.go(Route.Listening) },
            navRow = {
                NavRow("Home", { nav.home() }, trailing = {
                    HeatToggle(heatOn) {
                        heatOn = !heatOn
                        deps.feedback.cue(Cue.Tick)
                    }
                })
            },
            bottom = {
                if (running) {
                    DangerButton("Stop", stop, modifier = Modifier.heightIn(min = 72.dp), description = "Stop walk mode")
                } else {
                    PrimaryButton(
                        "Start",
                        start,
                        modifier = Modifier.heightIn(min = 72.dp),
                        description = "Start walk mode. Uses the back camera.",
                    )
                }
            },
        ) {
            StatusCard(running, snap.phase, lineLevel, visibleStatus)
            CameraStage(previewView, snap, running, heatOn)
            if (hint != null) HintCard(hint, words.steer(hint).trimEnd('.', '।'))
            if (!cameraOk && !running) CameraCard(refused = cameraRefused) { openAppSettings(context) }
            Notes(canVibrate = deps.feedback.canVibrate, extraOn = snap.extraOn, error = snap.error)
            if (running && snap.fps > 0) {
                Text(
                    "${fmtMetres(snap.fps)} checks a second · ${snap.msPerFrame.toInt()} ms each · ${snap.backend}",
                    style = Clarity.type.footnote,
                    color = c.secondary,
                )
            }
        }

        // Red edge flash on a fresh "stop". Reduced motion: steady red edge instead of flashing.
        if (lineLevel == WalkLevel.Stop && nowMs - snap.lineAt < 1500) {
            val alpha = if (still) {
                1f
            } else {
                val flash = rememberInfiniteTransition(label = "flash")
                val a by flash.animateFloat(0.15f, 1f, infiniteRepeatable(tween(300), RepeatMode.Reverse), label = "flashAlpha")
                a
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { this.alpha = alpha }
                    .border(8.dp, StopColor)
                    .clearAndSetSemantics {},
            )
        }
    }
}

@Composable
private fun HeatToggle(on: Boolean, onToggle: () -> Unit) {
    val c = Clarity.colors
    val fg = if (on) c.onAccent else c.label
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (on) c.accent else c.group)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics {
                contentDescription = "Depth heat map"
                stateDescription = if (on) "On" else "Off"
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.Layers, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
        Text("Heat map", style = Clarity.type.footnote.copy(fontWeight = FontWeight.SemiBold), color = fg, modifier = Modifier.clearAndSetSemantics {})
        Text(if (on) "On" else "Off", style = Clarity.type.footnote.copy(fontWeight = FontWeight.SemiBold), color = fg, modifier = Modifier.clearAndSetSemantics {})
    }
}

@Composable
private fun StatusCard(running: Boolean, phase: WalkSnapshot.Phase, level: WalkLevel?, text: String) {
    val c = Clarity.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(c.group)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            !running || phase == WalkSnapshot.Phase.Failed -> PauseGlyph(c.label, Modifier.size(26.dp))
            level != null -> LevelGlyph(level, levelColor(level), Modifier.size(26.dp))
        }
        Text(text, style = Clarity.type.body.copy(fontWeight = FontWeight.SemiBold), color = c.label)
    }
}

/**
 * Live camera picture (whole frame, letterboxed) with the tracked objects drawn on top.
 * Decorative for TalkBack: the status line and speech carry the meaning.
 */
@Composable
private fun CameraStage(previewView: PreviewView, snap: WalkSnapshot, running: Boolean, heatOn: Boolean) {
    val aspect = snap.frameAspect.toFloat().coerceIn(0.4f, 2.5f)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        BoxWithConstraints(
            Modifier
                .heightIn(max = 560.dp)
                .aspectRatio(aspect)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF0B0B0D))
                .clearAndSetSemantics {},
        ) {
            // Always composed (the same PreviewView is reused across Start/Stop); covered while paused.
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            if (running) {
                val boxes = snap.boxes
                Canvas(Modifier.fillMaxSize()) {
                    if (heatOn) drawHeat(boxes)
                    for (b in boxes) {
                        val lv = levelOf(b.level)
                        drawRoundRect(
                            color = lv?.let(::levelColor) ?: QuietColor,
                            topLeft = Offset((b.box.x * size.width).toFloat(), (b.box.y * size.height).toFloat()),
                            size = Size((b.box.w * size.width).toFloat(), (b.box.h * size.height).toFloat()),
                            cornerRadius = CornerRadius(12f),
                            style = Stroke(width = if (lv == null) 2.dp.toPx() else if (lv == WalkLevel.Stop) 5.dp.toPx() else 3.dp.toPx()),
                        )
                    }
                }
                val density = LocalDensity.current
                val wPx = with(density) { maxWidth.toPx() }
                val hPx = with(density) { maxHeight.toPx() }
                for (b in boxes) {
                    val lv = levelOf(b.level) ?: continue
                    Chip(lv, b, (b.box.x * wPx).toInt(), (b.box.y * hPx).toInt())
                }
                if (heatOn) {
                    Text(
                        "Warm = near · Cool = far",
                        style = Clarity.type.footnote,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(12.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.Black.copy(alpha = 0.72f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            } else {
                Column(
                    Modifier.fillMaxSize().background(Color(0xFF0B0B0D)),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Outlined.PhotoCamera, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
                    Text("Camera off", style = Clarity.type.headline, color = Color.White)
                    Text("Press Start", style = Clarity.type.footnote, color = Color(0xFFD1D1D6))
                }
            }
        }
    }
}

/** Icon + level word + object + distance above the box. */
@Composable
private fun Chip(level: WalkLevel, b: OverlayBox, xPx: Int, boxTopPx: Int) {
    val closer = if (b.approaching) " · closer" else ""
    Row(
        Modifier
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(p.width, p.height) {
                    val x = xPx.coerceIn(0, maxOf(0, constraints.maxWidth - p.width))
                    val y = (boxTopPx - p.height - 4).coerceIn(0, maxOf(0, constraints.maxHeight - p.height))
                    p.place(x, y)
                }
            }
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(levelColor(level))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LevelGlyph(level, Color.Black, Modifier.size(18.dp))
        Text(
            "${levelWord(level)} · ${b.word} · ${fmtMetres(b.dist)} m$closer",
            style = Clarity.type.footnote.copy(fontWeight = FontWeight.Bold),
            color = Color.Black,
        )
    }
}

/** Depth-ish heat map from the boxes' estimated distances (no depth sensor): warm near, cool far. */
private fun DrawScope.drawHeat(boxes: List<OverlayBox>) {
    drawRect(Color(0x335E5CE6), size = size) // everything else: unknown / far
    for (b in boxes.sortedByDescending { it.dist }) { // nearest drawn last, on top
        drawRoundRect(
            color = heatColor(b.dist).copy(alpha = 0.42f),
            topLeft = Offset((b.box.x * size.width).toFloat(), (b.box.y * size.height).toFloat()),
            size = Size((b.box.w * size.width).toFloat(), (b.box.h * size.height).toFloat()),
            cornerRadius = CornerRadius(12f),
        )
    }
}

@Composable
private fun HintCard(hint: SteerHint, text: String) {
    val c = Clarity.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(c.group)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(c.accent),
            contentAlignment = Alignment.Center,
        ) {
            // Physical left/right: never mirrored for right-to-left languages.
            val icon = when (hint) {
                SteerHint.Left -> Icons.Filled.West
                SteerHint.Right -> Icons.Filled.East
                SteerHint.Both -> Icons.Outlined.WarningAmber
            }
            Icon(icon, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(32.dp))
        }
        Text(text, style = Clarity.type.headline, color = c.label)
    }
}

@Composable
private fun CameraCard(refused: Boolean, openSettings: () -> Unit) {
    val c = Clarity.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(c.group)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (refused) "Camera is off for Saathi" else "Walk mode uses the back camera",
            style = Clarity.type.headline,
            color = c.label,
        )
        Text(
            if (refused) {
                "Walk mode can't warn you without it. You can allow the camera in the phone's Settings › Apps › Saathi › Permissions."
            } else {
                "When you press Start, the phone will ask to allow the camera. Pictures are checked on this phone and never saved or sent."
            },
            style = Clarity.type.callout,
            color = c.label,
        )
        if (refused) SecondaryButton("Open phone settings", openSettings, description = "Open Saathi's settings to allow the camera")
    }
}

@Composable
private fun Notes(canVibrate: Boolean, extraOn: Boolean, error: String?) {
    val c = Clarity.colors
    val lines = buildList {
        add("Warnings only — not for crossing roads.")
        add("No depth sensor: distances are rough estimates from the picture. It can't see kerbs, potholes, wires or wet floors. Keep using your cane.")
        add("Works offline. Pictures stay on this phone.")
        if (!canVibrate) add("This device can't vibrate, so warnings are spoken only.")
        if (!extraOn) add("Door and stairs detection is off on this phone.")
        if (error != null) add(error)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        lines.forEachIndexed { i, line ->
            Row(
                Modifier.padding(horizontal = 4.dp).semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = c.label, modifier = Modifier.size(22.dp))
                Text(
                    line,
                    style = if (i == 0) Clarity.type.footnote.copy(fontWeight = FontWeight.SemiBold) else Clarity.type.footnote,
                    color = c.label,
                )
            }
        }
    }
}

private fun path(vararg pts: Pair<Float, Float>, close: Boolean = true) = Path().apply {
    pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x, y) else lineTo(x, y) }
    if (close) close()
}

private fun DrawScope.line(x1: Float, y1: Float, x2: Float, y2: Float, color: Color, width: Float) =
    drawLine(color, Offset(x1, y1), Offset(x2, y2), strokeWidth = width, cap = StrokeCap.Round)

/** Level icons (24-unit space): Notice = circle-i, Careful = triangle-!, Stop = octagon-bar. */
@Composable
private fun LevelGlyph(level: WalkLevel, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val k = size.minDimension / 24f
        withTransform({ scale(k, k, pivot = Offset.Zero) }) {
            val outline = Stroke(width = 2.2f, join = StrokeJoin.Round)
            when (level) {
                WalkLevel.Notice -> {
                    drawCircle(color, radius = 9f, center = Offset(12f, 12f), style = outline)
                    line(12f, 11f, 12f, 16.5f, color, 2.6f)
                    line(12f, 7.6f, 12f, 7.61f, color, 2.6f)
                }
                WalkLevel.Careful -> {
                    drawPath(path(12f to 3.8f, 21.2f to 19.6f, 2.8f to 19.6f), color, style = outline)
                    line(12f, 10f, 12f, 14.4f, color, 2.6f)
                    line(12f, 17f, 12f, 17.01f, color, 2.6f)
                }
                WalkLevel.Stop -> {
                    drawPath(
                        path(8.3f to 3f, 15.7f to 3f, 21f to 8.3f, 21f to 15.7f, 15.7f to 21f, 8.3f to 21f, 3f to 15.7f, 3f to 8.3f),
                        color,
                        style = outline,
                    )
                    line(8.5f, 12f, 15.5f, 12f, color, 2.6f)
                }
            }
        }
    }
}

@Composable
private fun PauseGlyph(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val k = size.minDimension / 24f
        withTransform({ scale(k, k, pivot = Offset.Zero) }) {
            line(9f, 6f, 9f, 18f, color, 2.4f)
            line(15f, 6f, 15f, 18f, color, 2.4f)
        }
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

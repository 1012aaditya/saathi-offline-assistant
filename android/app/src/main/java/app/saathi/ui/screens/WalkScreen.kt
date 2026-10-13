package app.saathi.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
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
import app.saathi.ui.theme.Clarity
import app.saathi.ui.theme.DarkClarity
import app.saathi.ui.theme.LocalClarity
import kotlinx.coroutines.delay
import java.text.NumberFormat

/*
 * Screen 9 — Walk mode, PRACTICE version.
 *
 * Step 1 has no camera detection: this screen runs a clearly-labelled simulation of the three
 * warning levels so people can feel the sounds, haptics and visuals. It starts paused (nothing
 * plays until Start) and stops vibrating when the screen is left.
 */

// Warning colours (DESIGN.md walk row). Always paired with an icon + word, never colour alone.
private val NoticeColor = Color(0xFFFFD60A)
private val CarefulColor = Color(0xFFFF9F0A)
private val StopColor = Color(0xFFFF453A)

private const val LOOP_MS = 6000
private const val START_M = 4.5f // simulated chair starts this far away…
private const val END_M = 0.5f // …and ends this close, each loop.

/** Distances (metres) at which each level starts. Low warns later, High earlier. */
private data class Thresholds(val notice: Float, val careful: Float, val stop: Float)

private fun thresholdsFor(s: WalkSensitivity) = when (s) {
    WalkSensitivity.Low -> Thresholds(notice = 2f, careful = 1.5f, stop = 1f)
    WalkSensitivity.Medium -> Thresholds(notice = 3f, careful = 2f, stop = 1f)
    WalkSensitivity.High -> Thresholds(notice = 4f, careful = 3f, stop = 1.5f)
}

private fun levelAt(distance: Float, t: Thresholds): WalkLevel? = when {
    distance <= t.stop -> WalkLevel.Stop
    distance <= t.careful -> WalkLevel.Careful
    distance <= t.notice -> WalkLevel.Notice
    else -> null
}

private fun Thresholds.metresFor(level: WalkLevel) = when (level) {
    WalkLevel.Notice -> notice
    WalkLevel.Careful -> careful
    WalkLevel.Stop -> stop
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

private fun fmtMetres(m: Float): String =
    NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(m.toDouble())

private fun metresWord(m: Float) = if (m == 1f) "metre" else "metres"

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
    val still = remember { reducedMotion(context) }

    var running by remember { mutableStateOf(false) } // starts PAUSED
    var elapsed by remember { mutableIntStateOf(0) }
    var heatOn by remember { mutableStateOf(false) }

    // Light status/nav bar icons on the black background; restore on leave. Stop vibrating on leave.
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        val ctrl = window?.let { WindowCompat.getInsetsController(it, view) }
        val prevStatus = ctrl?.isAppearanceLightStatusBars
        val prevNav = ctrl?.isAppearanceLightNavigationBars
        ctrl?.isAppearanceLightStatusBars = false
        ctrl?.isAppearanceLightNavigationBars = false
        onDispose {
            deps.feedback.stopVibration()
            if (ctrl != null) {
                prevStatus?.let { ctrl.isAppearanceLightStatusBars = it }
                prevNav?.let { ctrl.isAppearanceLightNavigationBars = it }
            }
        }
    }

    // 6 s practice loop, only while running.
    LaunchedEffect(running) {
        while (running) {
            delay(100)
            elapsed = (elapsed + 100) % LOOP_MS
        }
    }

    val t = thresholdsFor(state.walkSensitivity)
    val p = elapsed / LOOP_MS.toFloat()
    val distance = START_M - (START_M - END_M) * p
    val level = if (running) levelAt(distance, t) else null

    // On each level change: haptic pattern always; short spoken warning only if sounds are on.
    LaunchedEffect(running, level) {
        val lv = level ?: return@LaunchedEffect
        if (!running) return@LaunchedEffect
        deps.feedback.walk(lv)
        if (state.soundOn) {
            val m = t.metresFor(lv)
            deps.voice.speak("${levelWord(lv)}, chair, ${fmtMetres(m)} ${metresWord(m)}")
        }
    }

    val visibleStatus = when {
        !running -> "Walk mode is paused"
        level == null -> "Path ahead is clear"
        level == WalkLevel.Stop -> "Stop. Chair about ${fmtMetres(t.stop)} ${metresWord(t.stop)} ahead"
        else -> {
            val m = t.metresFor(level)
            "Chair ahead, about ${fmtMetres(m)} ${metresWord(m)}"
        }
    }
    // The live region says "Practice" so a TalkBack user is never misled into trusting it.
    val liveStatus = if (running) "Practice: $visibleStatus" else "Walk mode practice is paused"

    val start: () -> Unit = {
        elapsed = 0
        running = true
        deps.feedback.cue(Cue.StartListening)
        if (state.soundOn) deps.voice.speak("Practice walk. No camera yet.")
    }
    val stop: () -> Unit = {
        running = false
        elapsed = 0
        deps.feedback.stopVibration()
        deps.voice.stopSpeaking()
        deps.feedback.cue(Cue.StopListening)
    }

    Box(Modifier.fillMaxSize().background(c.screen)) {
        SaathiScreen(
            status = liveStatus,
            youCanSay = listOf("Stop", "What's ahead?"),
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
                    DangerButton("Stop", stop, description = "Stop walk mode practice")
                } else {
                    PrimaryButton("Start", start, modifier = Modifier.heightIn(min = 72.dp), description = "Start walk mode practice")
                }
            },
        ) {
            PracticeBanner()
            StatusCard(running, level, visibleStatus)
            Scene(running = running, level = level, progress = p, heatOn = heatOn, still = still, t = t)
            if (running) SpaceCard(still) else PausedCard()
            Row(
                Modifier.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = c.label, modifier = Modifier.size(22.dp))
                Text("Warnings only — not for crossing roads", style = Clarity.type.footnote, color = c.label)
            }
        }

        // Red edge flash at Stop. Reduced motion: steady red edge instead of flashing.
        if (level == WalkLevel.Stop) {
            val alpha = if (still) {
                1f
            } else {
                val flash = rememberInfiniteTransition(label = "flash")
                val a by flash.animateFloat(
                    initialValue = 0.15f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(300), RepeatMode.Reverse),
                    label = "flashAlpha",
                )
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
private fun PracticeBanner() {
    val c = Clarity.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.group)
            .border(1.dp, c.secondary, RoundedCornerShape(16.dp))
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = c.label, modifier = Modifier.size(24.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Practice — no camera yet.") }
                append(" Real obstacle detection arrives in a later update.")
            },
            style = Clarity.type.callout,
            color = c.label,
        )
    }
}

@Composable
private fun StatusCard(running: Boolean, level: WalkLevel?, text: String) {
    val c = Clarity.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(c.group)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            !running -> PauseGlyph(c.label, Modifier.size(26.dp))
            level != null -> LevelGlyph(level, levelColor(level), Modifier.size(26.dp))
        }
        Text(text, style = Clarity.type.body.copy(fontWeight = FontWeight.SemiBold), color = c.label)
    }
}

@Composable
private fun SpaceCard(still: Boolean) {
    val c = Clarity.colors
    val nudge = if (still) {
        0f
    } else {
        val tr = rememberInfiniteTransition(label = "nudge")
        val x by tr.animateFloat(
            initialValue = 0f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                keyframes {
                    durationMillis = 1200
                    0f at 0
                    -8f at 540
                    0f at 1200
                },
            ),
            label = "nudgeX",
        )
        x
    }
    val density = LocalDensity.current
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
            Modifier
                .graphicsLayer { translationX = with(density) { nudge.dp.toPx() } }
                .size(56.dp)
                .clip(CircleShape)
                .background(c.accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(32.dp))
        }
        Column {
            Text("More space on the left", style = Clarity.type.headline, color = c.label)
            Text("Step a little to your left", style = Clarity.type.footnote, color = c.secondary)
        }
    }
}

@Composable
private fun PausedCard() {
    val c = Clarity.colors
    Text(
        "Paused. Press Start to feel the practice warnings.",
        style = Clarity.type.body,
        color = c.label,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(c.group)
            .padding(16.dp),
    )
}

/**
 * Corridor + obstacle, drawn in a 350 x 300 "scene" space (same as the design) and scaled.
 * Decorative for TalkBack: the status line carries the meaning.
 */
@Composable
private fun Scene(running: Boolean, level: WalkLevel?, progress: Float, heatOn: Boolean, still: Boolean, t: Thresholds) {
    // Box growth: smooth with motion; discrete sizes per level with reduced motion.
    val q = if (still) {
        when (level) {
            null -> 0f
            WalkLevel.Notice -> 0.15f
            WalkLevel.Careful -> 0.55f
            WalkLevel.Stop -> 0.95f
        }
    } else {
        progress
    }
    val qAnim by animateFloatAsState(
        targetValue = q,
        animationSpec = if (still) snap() else tween(100, easing = LinearEasing),
        label = "boxGrow",
    )
    val bob = if (running && !still) {
        val tr = rememberInfiniteTransition(label = "bob")
        val y by tr.animateFloat(0f, 3f, infiniteRepeatable(tween(550), RepeatMode.Reverse), label = "bobY")
        y
    } else {
        0f
    }

    val w = 100f + 100f * qAnim
    val h = w * 1.1f
    val bottomY = 180f + 90f * qAnim
    val top = bottomY - h
    val cx = 205f
    val boxColor = level?.let(::levelColor) ?: Color(0xFF8E8E93)
    val density = LocalDensity.current

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(300.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF0B0B0D))
            .clearAndSetSemantics {},
    ) {
        val sxDp = maxWidth.value / 350f
        Canvas(Modifier.fillMaxSize().graphicsLayer { translationY = with(density) { bob.dp.toPx() } }) {
            sceneSpace { drawCorridor() }
        }
        Canvas(Modifier.fillMaxSize()) {
            sceneSpace {
                if (heatOn) drawHeat(cx, top + h / 2)
                // Vignette.
                drawRect(
                    Brush.radialGradient(
                        0f to Color.Transparent,
                        0.5f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.72f),
                        center = Offset(175f, 165f),
                        radius = 230f,
                    ),
                    size = Size(350f, 300f),
                )
                if (running) {
                    drawChair(cx - w / 2, top, w)
                    drawRoundRect(
                        color = boxColor,
                        topLeft = Offset(cx - w / 2, top),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(16f),
                        style = Stroke(width = if (level == WalkLevel.Stop) 4f else 3f),
                    )
                }
            }
        }

        if (running && level != null) {
            val chipTop = maxOf(6f, top - 44f)
            val xPx = with(density) { (cx * sxDp).dp.roundToPx() }
            val yPx = with(density) { chipTop.dp.roundToPx() }
            Row(
                Modifier
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                        layout(placeable.width, placeable.height) {
                            val x = (xPx - placeable.width / 2).coerceIn(0, maxOf(0, constraints.maxWidth - placeable.width))
                            placeable.place(x, yPx)
                        }
                    }
                    .heightIn(min = 36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(levelColor(level))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LevelGlyph(level, Color.Black, Modifier.size(20.dp))
                Text(
                    "${levelWord(level)} · Chair · ${fmtMetres(t.metresFor(level))} m",
                    style = Clarity.type.footnote.copy(fontWeight = FontWeight.Bold),
                    color = Color.Black,
                )
            }
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

        if (!running) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                Text("Paused", style = Clarity.type.headline, color = Color.White)
            }
        }
    }
}

/** Run [block] with the canvas scaled so (0,0)-(350,300) fills it. */
private inline fun DrawScope.sceneSpace(crossinline block: DrawScope.() -> Unit) {
    withTransform({ scale(size.width / 350f, size.height / 300f, pivot = Offset.Zero) }) { block() }
}

private fun path(vararg pts: Pair<Float, Float>, close: Boolean = true) = Path().apply {
    pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x, y) else lineTo(x, y) }
    if (close) close()
}

private fun DrawScope.line(x1: Float, y1: Float, x2: Float, y2: Float, color: Color, width: Float) =
    drawLine(color, Offset(x1, y1), Offset(x2, y2), strokeWidth = width, cap = StrokeCap.Round)

private fun DrawScope.drawCorridor() {
    val faint = Color.White
    drawRect(Brush.verticalGradient(listOf(Color(0xFF2A2A2E), Color(0xFF151518)), startY = 0f, endY = 300f), size = Size(350f, 300f))
    drawPath(path(0f to 0f, 130f to 108f, 130f to 172f, 0f to 300f), Color(0xFF17171A))
    drawPath(path(350f to 0f, 220f to 108f, 220f to 172f, 350f to 300f), Color(0xFF121215))
    drawRect(Color(0xFF26262B), topLeft = Offset(130f, 108f), size = Size(90f, 64f))
    drawPath(
        path(0f to 300f, 130f to 172f, 220f to 172f, 350f to 300f),
        Brush.verticalGradient(listOf(Color(0xFF1D1D21), Color(0xFF34343A)), startY = 172f, endY = 300f),
    )
    // Door on the back wall.
    drawPath(path(150f to 172f, 150f to 128f, 178f to 128f, 178f to 172f, close = false), faint.copy(alpha = 0.14f), style = Stroke(2f))
    // Wall edges.
    line(30f, 60f, 30f, 250f, faint.copy(alpha = 0.08f), 2f)
    line(70f, 92f, 70f, 214f, faint.copy(alpha = 0.08f), 2f)
    // Floor perspective lines.
    val floor = faint.copy(alpha = 0.08f)
    line(175f, 172f, 175f, 300f, floor, 1.5f)
    line(152f, 172f, 80f, 300f, floor, 1.5f)
    line(198f, 172f, 270f, 300f, floor, 1.5f)
    line(140f, 172f, 10f, 300f, floor, 1.5f)
    line(210f, 172f, 340f, 300f, floor, 1.5f)
    val cross = faint.copy(alpha = 0.09f)
    line(113f, 190f, 237f, 190f, cross, 1.5f)
    line(88f, 215f, 262f, 215f, cross, 1.5f)
    line(52f, 250f, 298f, 250f, cross, 1.5f)
    // Ceiling edges + light.
    drawPath(path(0f to 0f, 130f to 108f, 220f to 108f, 350f to 0f, close = false), faint.copy(alpha = 0.06f), style = Stroke(1.5f))
    drawRoundRect(faint.copy(alpha = 0.18f), topLeft = Offset(150f, 24f), size = Size(50f, 8f), cornerRadius = CornerRadius(4f))
}

/** Depth heat map stand-in: warm near the floor and the obstacle, cool far away. */
private fun DrawScope.drawHeat(cx: Float, cy: Float) {
    drawRect(
        Brush.verticalGradient(
            0f to Color(0x615E5CE6), // indigo, far
            0.38f to Color(0x38409CFF), // blue
            0.52f to Color(0x29FFD60A), // yellow
            0.70f to Color(0x52FF9F0A), // orange
            1f to Color(0x6BFF453A), // red, near
            startY = 0f,
            endY = 300f,
        ),
        size = Size(350f, 300f),
    )
    drawRect(
        Brush.radialGradient(listOf(StopColor.copy(alpha = 0.55f), StopColor.copy(alpha = 0f)), center = Offset(cx, cy), radius = 115f),
        size = Size(350f, 300f),
    )
}

/** Chair from the design (viewBox 100 x 110), drawn inside the obstacle box. */
private fun DrawScope.drawChair(left: Float, top: Float, width: Float) {
    val s = width / 100f
    withTransform({
        translate(left, top)
        scale(s, s, pivot = Offset.Zero)
    }) {
        drawRoundRect(Color(0xFF4A4A50), topLeft = Offset(30f, 10f), size = Size(40f, 42f), cornerRadius = CornerRadius(5f))
        drawRoundRect(Color(0xFF6C6C72), topLeft = Offset(30f, 10f), size = Size(40f, 42f), cornerRadius = CornerRadius(5f), style = Stroke(2f))
        drawRoundRect(Color(0xFF56565C), topLeft = Offset(24f, 52f), size = Size(52f, 12f), cornerRadius = CornerRadius(4f))
        drawRoundRect(Color(0xFF7A7A80), topLeft = Offset(24f, 52f), size = Size(52f, 12f), cornerRadius = CornerRadius(4f), style = Stroke(2f))
        val leg = Color(0xFF7A7A80)
        line(30f, 64f, 30f, 98f, leg, 4f)
        line(70f, 64f, 70f, 98f, leg, 4f)
        line(38f, 64f, 38f, 90f, leg, 4f)
        line(62f, 64f, 62f, 90f, leg, 4f)
    }
}

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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

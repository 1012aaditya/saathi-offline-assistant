package app.saathi.ui.screens

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.saathi.ui.AppNav
import app.saathi.ui.Route
import app.saathi.ui.components.Body
import app.saathi.ui.components.Group
import app.saathi.ui.components.GroupDivider
import app.saathi.ui.components.GroupRow
import app.saathi.ui.components.LargeTitle
import app.saathi.ui.components.NavRow
import app.saathi.ui.components.SaathiScreen
import app.saathi.ui.components.TextLink
import app.saathi.ui.theme.Clarity
import java.util.Calendar

private fun greetingNow(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when {
        hour < 12 -> "Good morning"
        hour < 17 -> "Good afternoon"
        else -> "Good evening"
    }
}

/** Screen 3 — Home: greeting, "Ready", the big Speak circle, Walk mode and Activity. */
@Composable
fun HomeScreen(nav: AppNav) {
    val c = Clarity.colors
    val still = ScreenParts.rememberStill()
    val greeting = remember { greetingNow() }
    val floatingOn = app.saathi.ui.LocalDeps.current.state.floatingSaathi

    // The start-listening chime plays on the Listening screen, once the mic is really on.
    val speak = { nav.go(Route.Listening) }

    SaathiScreen(
        status = "Ready",
        youCanSay = listOf("Read my messages", "Walk mode", "Help"),
        onTalk = speak,
        navRow = { NavRow(trailing = { TextLink("Settings", { nav.go(Route.Settings) }) }) },
    ) {
        Column(ScreenParts.rise(1, still), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LargeTitle(greeting)
            Body("Ready", secondary = true)
        }

        Column(
            ScreenParts.rise(2, still).fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Gentle 2.4 s breathing pulse; off with "Remove animations".
            val scale = if (still) 1f else {
                val t = rememberInfiniteTransition(label = "pulse")
                t.animateFloat(
                    initialValue = 1f,
                    targetValue = 1.04f,
                    animationSpec = infiniteRepeatable(tween(1200, easing = EaseInOut), RepeatMode.Reverse),
                    label = "pulseScale",
                ).value
            }
            Box(
                Modifier
                    .size(200.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(CircleShape)
                    .background(c.accent)
                    .clickable(role = Role.Button, onClick = speak)
                    .semantics { contentDescription = "Speak. Start talking to Saathi" },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.Mic, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(64.dp))
                    Text("Speak", style = Clarity.type.headline, color = c.onAccent)
                }
            }
            Text("or hold Volume-down", style = Clarity.type.callout, color = c.secondary)
        }

        // Floating Saathi: the short explainer (Settings has the switch).
        if (floatingOn) {
            ScreenParts.NoteBox(
                "In any app, tap the round Saathi button to do it together right there: Saathi says what it sees, " +
                    "you press Speak and say what to do. Holding Volume-down in that app does the same.",
                ScreenParts.rise(3, still),
                icon = Icons.Outlined.TouchApp,
            )
        }

        Group(ScreenParts.rise(3, still)) {
            GroupRow(
                "Walk mode",
                modifier = Modifier.semantics { contentDescription = "Walk mode. Warns you about obstacles ahead" },
                icon = Icons.AutoMirrored.Outlined.DirectionsWalk,
                chevron = true,
                onClick = { nav.go(Route.Walk) },
            )
            GroupDivider()
            GroupRow(
                "Activity",
                modifier = Modifier.semantics { contentDescription = "Activity. What Saathi did for you" },
                icon = Icons.Outlined.Schedule,
                chevron = true,
                onClick = { nav.go(Route.Activity) },
            )
            GroupDivider()
            GroupRow(
                "Braille",
                modifier = Modifier.semantics { contentDescription = "Braille. Feel codes and short text with your finger" },
                icon = Icons.Outlined.TouchApp,
                chevron = true,
                onClick = { nav.go(Route.Braille) },
            )
        }
    }
}

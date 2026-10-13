package app.saathi.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.saathi.agent.SaathiAccessibilityService
import app.saathi.feedback.Cue
import app.saathi.ui.AppNav
import app.saathi.ui.LocalDeps
import app.saathi.ui.Route
import app.saathi.ui.components.Body
import app.saathi.ui.components.Footnote
import app.saathi.ui.components.Group
import app.saathi.ui.components.GroupDivider
import app.saathi.ui.components.LargeTitle
import app.saathi.ui.components.NavRow
import app.saathi.ui.components.PrimaryButton
import app.saathi.ui.components.SaathiScreen
import app.saathi.ui.components.SecondaryButton
import app.saathi.ui.components.SectionHeader
import app.saathi.ui.components.TextLink
import app.saathi.ui.theme.Clarity

/** Privacy copy — exactly as in DESIGN.md "Privacy copy". */
private const val PrivacyBase =
    "History and settings stay on your phone. Saathi's app tasks work offline: screen text stays on your phone and isn't stored."
private const val PrivacyA11y =
    "Saathi always asks before sending, never reads or types PINs or passwords, never grants permissions, and never turns TalkBack off."
private const val PrivacyVoice = "Your voice is sent to a speech service to turn it into text."
private const val PrivacyWalk = "Walk mode warnings are made on your phone."
private const val PrivacyLocal = "History and settings stay on your phone."

private class SetupStep(
    val name: String,
    val title: String,
    val lead: String,
    val reasons: List<Pair<ImageVector, String>>,
    val notes: List<String>,
    val without: String,
    /** Runtime permission to request; null = nothing to ask on this phone. */
    val permission: String?,
)

private fun setupSteps(): List<SetupStep> = listOf(
    SetupStep(
        "Accessibility", "Let Saathi use your phone",
        "Saathi will use Android's Accessibility service to read your screen and tap for you.",
        listOf(
            Icons.Outlined.Visibility to "Read what's on your screen",
            Icons.Outlined.TouchApp to "Tap and type in apps when you ask",
            Icons.Outlined.VerifiedUser to "Always ask you before sending, paying or ordering",
        ),
        listOf(PrivacyBase, PrivacyA11y), "Saathi can't use your apps for you", null,
    ),
    SetupStep(
        "Microphone", "Let Saathi hear you",
        "Android will ask if Saathi can use the microphone.",
        listOf(
            Icons.Outlined.Mic to "Talk instead of tapping",
            Icons.Outlined.RecordVoiceOver to "Listens only after you tap Speak or hold Volume-down",
        ),
        listOf(PrivacyBase, PrivacyVoice), "voice commands won't work", Manifest.permission.RECORD_AUDIO,
    ),
    SetupStep(
        "Camera", "Let Saathi see the way",
        "Used only in Walk mode. Android will ask if Saathi can use the camera.",
        listOf(
            Icons.AutoMirrored.Outlined.DirectionsWalk to "Warn you about obstacles ahead",
            Icons.Outlined.CameraAlt to "On only while Walk mode is running",
        ),
        listOf(PrivacyWalk), "Walk mode won't work", Manifest.permission.CAMERA,
    ),
    SetupStep(
        "Notifications", "Let Saathi reach you",
        "Android will ask if Saathi can send notifications.",
        listOf(
            Icons.Outlined.NotificationsNone to "Tell you when a task is done",
            Icons.Outlined.ChatBubbleOutline to "Let you know when it needs your answer",
            Icons.Outlined.StopCircle to "Show that Saathi is working, so you can stop it",
        ),
        listOf(PrivacyLocal), "updates while your screen is off won't work",
        // Before Android 13 notifications need no permission: treat as granted.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null,
    ),
)

/** Screen 2 — Setup: four steps in place, never auto-advances. */
@Composable
fun SetupScreen(nav: AppNav) {
    val deps = LocalDeps.current
    val feedback = deps.feedback
    val context = LocalContext.current
    val c = Clarity.colors
    val still = ScreenParts.rememberStill()
    val steps = remember { setupSteps() }

    var step by rememberSaveable { mutableIntStateOf(0) }
    var declined by rememberSaveable { mutableStateOf(false) }
    var askedAndDenied by rememberSaveable { mutableStateOf(false) }
    var done by rememberSaveable { mutableStateOf(false) }
    // Bumped on resume so we notice permissions changed in app settings.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    val cur = steps[step]
    val isAccessibility = step == 0
    fun granted(i: Int): Boolean =
        if (i == 0) SaathiAccessibilityService.isEnabled(context)
        else steps[i].permission?.let { ScreenParts.hasPermission(context, it) } ?: true
    val grantedNow = remember(step, refresh) { granted(step) }

    fun go(n: Int) {
        declined = false
        askedAndDenied = false
        if (n >= steps.size) {
            done = true
            feedback.cue(Cue.Done)
        } else {
            done = false
            step = n.coerceAtLeast(0)
        }
    }

    fun back() {
        when {
            done -> { done = false; declined = false }
            step == 0 -> nav.replace(Route.Welcome)
            else -> go(step - 1)
        }
    }

    fun finish() {
        deps.voice.stopSpeaking()
        deps.state.finishSetup()
        // replace (not home): Home isn't on the stack yet, and Setup must not sit under it.
        nav.replace(Route.Home)
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        refresh++
        if (ok) {
            feedback.cue(Cue.Done)
            go(step + 1)
        } else {
            feedback.cue(Cue.Cancelled)
            declined = true
            askedAndDenied = true
        }
    }

    fun allow() {
        val p = steps[step].permission
        if (step == 0) {
            // Only the owner can switch the service on: open Android's settings (never done for them).
            if (SaathiAccessibilityService.isEnabled(context)) {
                feedback.cue(Cue.Done)
                go(step + 1)
            } else {
                SaathiAccessibilityService.openSettings(context)
            }
        } else if (p == null || ScreenParts.hasPermission(context, p)) {
            feedback.cue(Cue.Done)
            go(step + 1)
        } else {
            launcher.launch(p)
        }
    }

    fun notNow() {
        feedback.cue(Cue.Cancelled)
        declined = true
    }

    BackHandler { back() }

    val showAsk = !done && !grantedNow && !declined
    val say = when {
        done -> listOf("Start", "Go back")
        showAsk -> listOf("Allow", "Not now")
        declined -> listOf("Next", "Allow")
        else -> listOf("Next", "Go back")
    }

    val listener = ScreenParts.rememberScreenListener { text ->
        val w = ScreenParts.words(text)
        val t = text.lowercase()
        when {
            "back" in w -> back()
            done && ("start" in w || "home" in w || "done" in w || "next" in w) -> finish()
            "not now" in t || "skip" in w || "later" in w -> if (showAsk) notNow() else go(step + 1)
            "allow" in w || "yes" in w || "ok" in w || "okay" in w -> if (!done) allow()
            "next" in w || "continue" in w -> if (done) finish() else go(step + 1)
            else -> deps.voice.speak("Sorry, I didn't catch that. You can say ${say[0]}, or ${say[1]}.")
        }
    }

    val declineText = "Without this, ${cur.without}. You can turn it on later in Settings."
    val status = when {
        listener.active -> "Listening"
        done -> "You're all set. Start when you're ready."
        declined -> "Not now. $declineText"
        isAccessibility && !grantedNow -> "Step 1 of 4. ${cur.title}. Off. Allow opens Accessibility settings: choose Saathi and switch it on."
        grantedNow -> "Step ${step + 1} of 4. ${cur.title}. Already allowed."
        else -> "Step ${step + 1} of 4. ${cur.title}."
    }
    val nextDesc = if (step < steps.size - 1) "Next, step ${step + 2} of 4, ${steps[step + 1].name}" else "Next, finish setup"
    val backLabel = when {
        done -> steps.last().name
        step == 0 -> "Welcome"
        else -> steps[step - 1].name
    }

    SaathiScreen(
        status = status,
        youCanSay = say,
        onTalk = { listener.toggle() },
        navRow = { NavRow(backLabel, { back() }) },
        bottom = {
            ScreenParts.ListenHint(listener)
            when {
                done -> PrimaryButton("Start", { finish() }, description = "Start using Saathi")
                showAsk -> {
                    PrimaryButton(
                        if (isAccessibility) "Open settings" else "Allow",
                        { allow() },
                        description = if (isAccessibility) "Open Accessibility settings. Choose Saathi and switch it on." else "Allow ${cur.name}",
                    )
                    SecondaryButton("Not now", { notNow() }, description = "Not now, skip ${cur.name}")
                }
                declined -> {
                    PrimaryButton("Next", { go(step + 1) }, description = nextDesc)
                    SecondaryButton("Allow", { allow() }, description = "Allow ${cur.name}")
                }
                else -> PrimaryButton("Next", { go(step + 1) }, description = nextDesc)
            }
        },
    ) {
        if (!done) {
            // Step counter + segmented progress. The words carry the meaning; the bar is decoration.
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Step ${step + 1} of 4", style = Clarity.type.footnote, color = c.secondary)
                Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(steps.size) { i ->
                        val spec: AnimationSpec<androidx.compose.ui.graphics.Color> = if (still) snap() else tween(350)
                        val color by animateColorAsState(if (i <= step) c.accent else c.separator, spec, label = "seg$i")
                        Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(color))
                    }
                }
            }
        }

        key(step, done) {
            if (!done) {
                Column(ScreenParts.rise(0, still), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LargeTitle(cur.title)
                    Body(cur.lead, secondary = true)
                }

                Column(ScreenParts.rise(1, still), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader("Why")
                    Group {
                        cur.reasons.forEachIndexed { i, (icon, text) ->
                            if (i > 0) GroupDivider()
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 64.dp)
                                    .semantics(mergeDescendants = true) {}
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Icon(icon, contentDescription = null, tint = c.accentText, modifier = Modifier.size(26.dp))
                                Text(text, style = Clarity.type.body, color = c.label)
                            }
                        }
                    }
                    cur.notes.forEach { Footnote(it, Modifier.padding(horizontal = 16.dp)) }
                }

                Box(ScreenParts.rise(2, still)) {
                    when {
                        isAccessibility && !grantedNow && !declined -> ScreenParts.NoteBox(
                            "In Accessibility settings, choose Installed apps (or Downloaded apps), then Saathi, and switch it on. " +
                                "Android shows a warning — that's normal. Then come back here.",
                        )
                        declined -> ScreenParts.NoteBox(
                            declineText,
                            extra = if (askedAndDenied) {
                                {
                                    Footnote("If Android doesn't ask again, you can allow it in app settings.")
                                    TextLink("Open app settings", { ScreenParts.openAppSettings(context) })
                                }
                            } else null,
                        )
                        grantedNow -> ScreenParts.NoteBox("Allowed", icon = Icons.Outlined.CheckCircle, iconTint = c.accentText)
                        else -> Unit
                    }
                }
            } else {
                ScreenParts.CheckBadge(88.dp, still)
                Column(ScreenParts.rise(1, still), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LargeTitle("You're all set")
                    Body("Tap Speak or hold Volume-down any time to talk to Saathi.", secondary = true)
                }
                Column(ScreenParts.rise(2, still), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader("Permissions")
                    val summary = remember(refresh) {
                        steps.mapIndexed { i, s ->
                            val value = when {
                                i == 0 -> if (granted(0)) "On" else "Off"
                                granted(i) -> "Allowed"
                                else -> "Not now"
                            }
                            Triple(s.name, value, granted(i))
                        }
                    }
                    Group {
                        summary.forEachIndexed { i, (name, value, on) ->
                            if (i > 0) GroupDivider()
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 64.dp)
                                    .semantics(mergeDescendants = true) { contentDescription = "$name, $value" }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(name, style = Clarity.type.body, color = c.label, modifier = Modifier.weight(1f))
                                if (on) ScreenParts.DrawnCheck(c.accentText, 22.dp, 2.6.dp, still)
                                Text(value, style = Clarity.type.callout, color = if (on) c.label else c.secondary)
                            }
                        }
                    }
                    Footnote("You can change these later in Settings.", Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

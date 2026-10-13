package app.saathi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.saathi.data.Outcome
import app.saathi.feedback.Cue
import app.saathi.ui.AppNav
import app.saathi.ui.LocalDeps
import app.saathi.ui.Route
import app.saathi.ui.components.Body
import app.saathi.ui.components.PrimaryButton
import app.saathi.ui.components.SaathiScreen
import app.saathi.ui.components.SecondaryButton
import app.saathi.ui.components.SectionHeader
import app.saathi.ui.components.TextLink
import app.saathi.ui.theme.Clarity

/** What the Result screen shows for each practice path. */
private class ResultAnswer(
    val eyebrow: String,
    val section: String,
    val quote: String?,
    val detail: String?,
    val body: String?,
    val spoken: String,
    val practice: Boolean,
    val success: Boolean,
    val record: Pair<String, Outcome>?,
)

private fun answerFor(kind: ScreenParts.Practice, request: String): ResultAnswer = when (kind) {
    ScreenParts.Practice.Read -> ResultAnswer(
        eyebrow = "Done",
        section = "Mom's last message",
        quote = "Did you eat? Come home early.",
        detail = "Today, 6:42 PM",
        body = null,
        spoken = "Practice run with sample data. Mom's last message: Did you eat? Come home early. Today, 6:42 PM.",
        practice = true,
        success = true,
        record = "Practice: read Mom's last message" to Outcome.Done,
    )
    // Reached only from Confirm → Send. Confirm already recorded it.
    ScreenParts.Practice.Send -> ResultAnswer(
        eyebrow = "Done",
        section = "Practice reply to Mom",
        quote = "I'll be home by 7",
        detail = null,
        body = "Practice: the message was not really sent.",
        spoken = "Practice: the message was not really sent.",
        practice = true,
        success = true,
        record = null,
    )
    ScreenParts.Practice.Help -> ResultAnswer(
        eyebrow = "Help",
        section = "What I can do now",
        quote = null,
        detail = null,
        body = "In this version I can listen to you, open Walk mode, Activity and Settings, and run practice tasks with sample data. " +
            "Using your apps arrives in the next update. Try saying “Read my messages”.",
        spoken = "In this version I can listen to you, open Walk mode, Activity and Settings, and run practice tasks with sample data. " +
            "Using your apps arrives in the next update. Try saying: Read my messages.",
        practice = false,
        success = true,
        record = null,
    )
    // Pay goes to Handover, never here; anything unknown is answered honestly.
    ScreenParts.Practice.Pay, ScreenParts.Practice.Other -> ResultAnswer(
        eyebrow = "Not yet",
        section = "I can't do that yet",
        quote = request.ifBlank { null },
        detail = if (request.isBlank()) null else "What you asked",
        body = "Saathi can't use your apps in this version, so I didn't try. Say “Read my messages” to hear a practice run.",
        spoken = "I can't do that yet. Saathi can't use your apps in this version, so I didn't try.",
        practice = false,
        success = false,
        record = if (request.isBlank()) null else "Couldn't do yet: ${request.trim().take(60)}" to Outcome.Failed,
    )
}

/** Screen 7 — Result: checkmark draws in, the answer card is read aloud. Practice data is labelled. */
@Composable
fun ResultScreen(nav: AppNav) {
    val deps = LocalDeps.current
    val c = Clarity.colors
    val still = ScreenParts.rememberStill()
    val request = deps.state.lastRequest
    val kind = remember(request) { ScreenParts.practiceFor(request) }
    val answer = remember(kind, request) { answerFor(kind, request) }
    val speaking by deps.voice.speaking.collectAsState()

    ScreenParts.OnFirstShow {
        deps.feedback.cue(if (answer.success) Cue.Done else Cue.Cancelled)
        deps.voice.speak(answer.spoken)
        answer.record?.let { (title, outcome) -> deps.state.record(title, outcome) }
    }

    fun repeatAnswer() {
        deps.feedback.cue(Cue.StartListening)
        deps.voice.speak(answer.spoken)
    }

    fun somethingElse() {
        deps.voice.stopSpeaking()
        nav.replace(Route.Listening)
    }

    fun home() {
        deps.voice.stopSpeaking()
        nav.home()
    }

    val listener = ScreenParts.rememberScreenListener { text ->
        val w = ScreenParts.words(text)
        val t = text.lowercase()
        when {
            w.any { it in setOf("repeat", "again", "dobara", "phir") } -> repeatAnswer()
            "something else" in t || "another" in w || "new" in w -> somethingElse()
            kind == ScreenParts.Practice.Read && w.any { it.startsWith("repl") } -> {
                deps.voice.stopSpeaking()
                deps.state.lastRequest = "Reply to Mom"
                nav.replace(Route.Working)
            }
            "home" in w -> home()
            else -> ScreenParts.handleAsRequest(text, deps, nav)
        }
    }

    val say = if (kind == ScreenParts.Practice.Read) listOf("Repeat", "Reply", "Go home") else listOf("Repeat", "Something else", "Go home")
    val status = when {
        listener.active -> "Listening"
        else -> "${answer.eyebrow}. ${answer.spoken}"
    }

    SaathiScreen(
        status = status,
        youCanSay = say,
        onTalk = { listener.toggle() },
        bottom = {
            ScreenParts.ListenHint(listener)
            PrimaryButton("Something else", { somethingElse() }, description = "Something else. Start a new request.")
            SecondaryButton("Repeat", { repeatAnswer() }, description = "Repeat. Read it again.")
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TextLink("Home", { home() }) }
        },
    ) {
        Text(answer.eyebrow, style = Clarity.type.headline, color = c.secondary, modifier = ScreenParts.rise(0, still))

        if (answer.success) {
            ScreenParts.CheckBadge(72.dp, still)
        } else {
            Box(
                ScreenParts.rise(1, still).size(72.dp).clip(CircleShape).background(c.group).clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Info, contentDescription = null, tint = c.label, modifier = Modifier.size(40.dp)) }
        }

        Column(ScreenParts.rise(3, still), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader(answer.section)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(c.group)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (answer.quote != null) {
                        Text("“${answer.quote}”", style = Clarity.type.title.copy(fontWeight = FontWeight.SemiBold), color = c.label)
                    }
                    if (answer.detail != null) Text(answer.detail, style = Clarity.type.footnote, color = c.secondary)
                    if (answer.body != null) Body(answer.body)
                }
                HorizontalDivider(thickness = 1.dp, color = c.separator)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ScreenParts.LevelBars(active = speaking, still = still, color = c.accentText, barWidth = 4.dp, maxHeight = 22.dp)
                    Text(
                        if (speaking) "Reading it to you…" else "Read aloud. Say “Repeat” to hear it again.",
                        style = Clarity.type.footnote,
                        color = c.secondary,
                    )
                }
            }
        }

        if (answer.practice) ScreenParts.PracticeLabel(ScreenParts.rise(4, still))
    }
}

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
import app.saathi.agent.Command
import app.saathi.agent.Outcome
import app.saathi.agent.TaskRunner
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

/** What the Result screen shows, built from the real task outcome. */
private class ResultAnswer(
    val eyebrow: String,
    val section: String,
    val quote: String?,
    val detail: String?,
    val body: String?,
    val spoken: String,
    val success: Boolean,
)

private fun answerFor(state: TaskRunner.State): ResultAnswer {
    val f = state as? TaskRunner.State.Finished
        ?: return ResultAnswer("Nothing yet", "No result", null, null, "There's nothing to show yet. Say what you need.", "There's nothing to show yet.", false)
    val o = f.outcome
    return when (o) {
        is Outcome.Answered -> ResultAnswer(
            eyebrow = if (f.command is Command.Help) "Help" else "Done",
            section = o.title.ifBlank { f.title },
            quote = o.quote,
            detail = if (o.quote != null && f.command is Command.ReadLast) "Exactly as it appears on screen" else null,
            // A read message shows the quote; an extra note ("There's unsent text in this chat.") goes under it.
            body = if (o.quote != null && f.command is Command.ReadLast) o.note else o.message,
            spoken = o.message,
            success = true,
        )
        is Outcome.Asked -> ResultAnswer("Needs more", "I need one more thing", null, null, o.message, o.message, false)
        is Outcome.Cancelled -> ResultAnswer("Cancelled", f.title, null, null, o.message, o.message, false)
        is Outcome.Stopped -> ResultAnswer("Stopped", f.title, null, null, o.message, o.message, false)
        is Outcome.Failed -> ResultAnswer(
            eyebrow = "Didn't work",
            section = if (f.command is Command.Unsupported) "I can't do that offline yet" else f.title,
            quote = if (f.command is Command.Unsupported) f.command.utterance.ifBlank { null } else null,
            detail = if (f.command is Command.Unsupported && f.command.utterance.isNotBlank()) "What you asked" else null,
            body = o.message,
            spoken = o.message,
            success = false,
        )
        is Outcome.HandedOver -> ResultAnswer("Your turn", f.title, null, null, o.message, o.message, false)
    }
}

/** Screen 7 — Result: checkmark draws in, the real answer card is read aloud (quotes verbatim). */
@Composable
fun ResultScreen(nav: AppNav) {
    val deps = LocalDeps.current
    val c = Clarity.colors
    val still = ScreenParts.rememberStill()
    val state = remember { TaskRunner.state.value }
    val answer = remember(state) { answerFor(state) }
    val command = (state as? TaskRunner.State.Finished)?.command
    val readContact = (command as? Command.ReadLast)?.contact?.takeIf { (state as TaskRunner.State.Finished).outcome is Outcome.Answered }
    val needsAnswer = (state as? TaskRunner.State.Finished)?.outcome is Outcome.Asked
    val speaking by deps.voice.speaking.collectAsState()

    ScreenParts.OnFirstShow {
        deps.feedback.cue(if (answer.success) Cue.Done else Cue.Cancelled)
        deps.voice.speak(answer.spoken) // history was recorded by the task runner
    }

    fun repeatAnswer() {
        deps.feedback.tick()
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
            readContact != null && (w.any { it.startsWith("repl") } || w.any { it in setOf("jawab", "जवाब") }) && w.size <= 2 -> {
                // "Reply": the next thing said becomes the message to this contact (confirmed before sending).
                deps.voice.stopSpeaking()
                TaskRunner.session.pendingReplyTo = readContact
                nav.replace(Route.Listening)
            }
            "home" in w -> home()
            else -> ScreenParts.handleAsRequest(text, deps, nav)
        }
    }

    val say = if (readContact != null) listOf("Repeat", "Reply", "Go home") else listOf("Repeat", "Something else", "Go home")
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
            if (needsAnswer) {
                PrimaryButton("Answer", { somethingElse() }, description = "Answer. Saathi listens for your reply.")
            } else {
                PrimaryButton("Something else", { somethingElse() }, description = "Something else. Start a new request.")
            }
            if (readContact != null) {
                SecondaryButton(
                    "Reply to $readContact",
                    {
                        deps.voice.stopSpeaking()
                        TaskRunner.session.pendingReplyTo = readContact
                        nav.replace(Route.Listening)
                    },
                    description = "Reply to $readContact. Say your message next; I'll read it back before sending.",
                )
            }
            SecondaryButton("Repeat", { repeatAnswer() }, description = "Repeat. Read it again.")
            // Only real answers (e.g. a message read verbatim). Never hand-overs: no OTP/PIN path from here.
            val feelText = if (answer.success) answer.quote ?: answer.body else null
            if (feelText != null) {
                SecondaryButton(
                    "Feel it in braille",
                    {
                        deps.voice.stopSpeaking()
                        deps.state.brailleText = feelText
                        nav.go(Route.Braille)
                    },
                    description = "Feel it in braille. Opens the braille reader with this text.",
                )
            }
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

    }
}

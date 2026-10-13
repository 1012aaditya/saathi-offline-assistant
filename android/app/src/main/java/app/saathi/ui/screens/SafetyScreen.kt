package app.saathi.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.saathi.ui.AppNav
import app.saathi.ui.Route
import app.saathi.ui.components.Body
import app.saathi.ui.components.Footnote
import app.saathi.ui.components.Group
import app.saathi.ui.components.GroupDivider
import app.saathi.ui.components.LargeTitle
import app.saathi.ui.components.NavRow
import app.saathi.ui.components.SaathiScreen
import app.saathi.ui.components.SectionHeader
import app.saathi.ui.components.TextLink
import app.saathi.ui.theme.Clarity

/** Screen 12 — Safety centre. Read-only: these rules are always on and have no toggles. */

private data class Rule(val rule: String, val why: String)

private val Rules = listOf(
    Rule("Asks before sending, paying or ordering", "Nothing is sent and nothing costs money until you say yes."),
    Rule("Reads back the exact text on screen", "You hear what the app really shows, not my guess."),
    Rule("Never reads or types PINs, passwords or OTPs", "Your secret codes stay with you alone."),
    Rule("Hands CAPTCHAs to you", "These checks are meant for a person, so I step aside."),
    Rule("Stays under your spending limit", "A mistake can never cost more than you allowed."),
    Rule("Always turns TalkBack back on", "You're never left without your screen reader."),
    Rule("Never grants app permissions itself", "Only you decide what an app can use on your phone."),
    Rule("Blocks calls and taps hidden under pay or send buttons", "A stray tap can't start a call, a payment or a message."),
    Rule("Stops after repeated failures", "If something keeps going wrong, I stop and tell you instead of guessing."),
    Rule("An unclear answer means cancel", "If I can't tell yes from no, I ask once more, then cancel."),
)

@Composable
fun SafetyScreen(nav: AppNav) {
    val c = Clarity.colors
    var open by remember { mutableIntStateOf(-1) }
    val status = Rules.getOrNull(open)?.let { "Why: ${it.why}" } ?: "Safety centre. 10 rules, always on."

    SaathiScreen(
        status = status,
        youCanSay = listOf("Why?", "Go home"),
        onTalk = { nav.go(Route.Listening) },
        navRow = {
            NavRow("Settings", { nav.back() }, trailing = { TextLink("Home", { nav.home() }, contentDescription = "Go to Home") })
        },
    ) {
        Icon(Icons.Outlined.Shield, contentDescription = null, tint = c.accentText, modifier = Modifier.size(64.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LargeTitle("Safety centre")
            Body("Always on. These can't be turned off.", secondary = true)
        }
        Column {
            SectionHeader("10 rules")
            Group {
                Rules.forEachIndexed { i, r ->
                    if (i > 0) GroupDivider()
                    val isOpen = i == open
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .clickable(role = Role.Button) { open = if (isOpen) -1 else i }
                            .semantics(mergeDescendants = true) {
                                stateDescription = if (isOpen) "Expanded" else "Collapsed"
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(Icons.Outlined.Lock, contentDescription = "Always on", tint = c.accentText, modifier = Modifier.size(24.dp))
                        Text(r.rule, style = Clarity.type.body, color = c.label, modifier = Modifier.weight(1f))
                        ExpandChevron(isOpen)
                    }
                    if (isOpen) {
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("Why: ") }
                                append(r.why)
                            },
                            style = Clarity.type.callout,
                            color = c.secondary,
                            modifier = Modifier.padding(start = 54.dp, end = 16.dp, bottom = 14.dp),
                        )
                    }
                }
            }
            Footnote("Tap a rule to find out why it's there.", Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
        }
    }
}

package app.saathi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.saathi.data.HistoryItem
import app.saathi.data.Outcome
import app.saathi.feedback.Cue
import app.saathi.ui.AppNav
import app.saathi.ui.LocalDeps
import app.saathi.ui.Route
import app.saathi.ui.components.Group
import app.saathi.ui.components.GroupDivider
import app.saathi.ui.components.GroupRow
import app.saathi.ui.components.LargeTitle
import app.saathi.ui.components.NavRow
import app.saathi.ui.components.SaathiScreen
import app.saathi.ui.components.SectionHeader
import app.saathi.ui.theme.Clarity
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Screen 10 — Activity: what Saathi did, grouped Today / Earlier, read aloud, delete with a Cancel-first sheet. */

private fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"

private fun isSafetyStop(o: Outcome) = o == Outcome.Cancelled || o == Outcome.StoppedBeforeOrder || o == Outcome.HandedOver

private fun outcomeIcon(o: Outcome): ImageVector = when (o) {
    Outcome.Done -> Icons.Outlined.CheckCircle
    Outcome.Cancelled -> Icons.Outlined.Cancel
    Outcome.StoppedBeforeOrder -> Icons.Outlined.StopCircle
    Outcome.HandedOver -> Icons.Outlined.PanTool
    Outcome.Failed -> Icons.Outlined.ErrorOutline
}

private fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

@Composable
fun ActivityScreen(nav: AppNav) {
    val deps = LocalDeps.current
    val state = deps.state
    val c = Clarity.colors
    val context = LocalContext.current
    val timeFmt: DateFormat = remember { android.text.format.DateFormat.getTimeFormat(context) }
    val dateFmt = remember {
        val locale = Locale.getDefault()
        SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMM"), locale)
    }

    var sheetOpen by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    val todayStart = startOfToday()
    val all = state.history.toList()
    val today = all.filter { it.timeMillis >= todayStart }
    val earlier = all.filter { it.timeMillis < todayStart }
    val stops = today.count { isSafetyStop(it.outcome) }
    val summary = "Today: ${plural(today.size, "task", "tasks")}, ${plural(stops, "safety stop", "safety stops")}"

    fun timeOf(item: HistoryItem): String = timeFmt.format(Date(item.timeMillis))
    fun whenOf(item: HistoryItem, isToday: Boolean): String =
        if (isToday) timeOf(item) else "${dateFmt.format(Date(item.timeMillis))}, ${timeOf(item)}"

    val status = msg ?: if (all.isEmpty()) "Activity. No activity yet." else "Activity. $summary."

    SaathiScreen(
        status = status,
        youCanSay = listOf("Read today", "Delete history"),
        onTalk = { nav.go(Route.Listening) },
        navRow = { NavRow("Home", { nav.home() }) },
    ) {
        LargeTitle("Activity")

        if (all.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(top = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Outlined.Schedule, contentDescription = null, tint = c.secondary, modifier = Modifier.size(44.dp))
                Text("No activity yet.", style = Clarity.type.headline, color = c.label, textAlign = TextAlign.Center)
                Text(
                    "Things Saathi does for you will show here.",
                    style = Clarity.type.body,
                    color = c.secondary,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            // Summary + Read aloud.
            Group {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(summary, style = Clarity.type.headline, color = c.label)
                }
                GroupDivider()
                GroupRow(
                    title = "Read aloud",
                    icon = Icons.AutoMirrored.Outlined.VolumeUp,
                    onClick = {
                        deps.feedback.cue(Cue.StartListening)
                        val lines = if (today.isEmpty()) {
                            "Nothing yet today."
                        } else {
                            today.joinToString(" ") { "${it.title}, ${it.outcome.label}, at ${timeOf(it)}." }
                        }
                        deps.voice.speak("$summary. $lines")
                        msg = "Reading today's activity aloud"
                    },
                )
            }

            if (today.isNotEmpty()) HistorySection("Today", today) { whenOf(it, true) }
            if (earlier.isNotEmpty()) HistorySection("Earlier", earlier) { whenOf(it, false) }

            Group {
                GroupRow(
                    title = "Delete history",
                    icon = Icons.Outlined.Delete,
                    danger = true,
                    onClick = {
                        sheetOpen = true
                        msg = "Delete history? Cancel is first."
                        deps.feedback.cue(Cue.NeedsAnswer)
                    },
                )
            }
        }
    }

    if (sheetOpen) {
        val n = state.history.size
        ConfirmSheet(
            title = "Delete history?",
            body = "This removes all ${plural(n, "entry", "entries")} from this phone. It can't be undone.",
            confirmLabel = "Delete",
            confirmDescription = "Delete all history",
            cancelDescription = "Cancel, keep history",
            onCancel = {
                sheetOpen = false
                msg = "Cancelled — nothing was deleted"
                deps.feedback.cue(Cue.Cancelled)
            },
            onConfirm = {
                sheetOpen = false
                state.clearHistory()
                msg = "History deleted"
                deps.feedback.cue(Cue.Done)
            },
        )
    }
}

@Composable
private fun HistorySection(header: String, items: List<HistoryItem>, timeText: (HistoryItem) -> String) {
    val c = Clarity.colors
    Column {
        SectionHeader(header)
        Group {
            items.forEachIndexed { i, item ->
                if (i > 0) GroupDivider()
                val time = timeText(item)
                val done = item.outcome == Outcome.Done
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .clearAndSetSemantics { contentDescription = "${item.title}, ${item.outcome.label}, $time" }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(outcomeIcon(item.outcome), contentDescription = null, tint = if (done) c.accentText else c.label, modifier = Modifier.size(28.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = Clarity.type.body, color = c.label)
                        Text(
                            item.outcome.label,
                            style = Clarity.type.footnote.copy(fontWeight = FontWeight.SemiBold),
                            color = if (done) c.accentText else c.secondary,
                        )
                    }
                    Text(time, style = Clarity.type.footnote, color = c.secondary)
                }
            }
        }
    }
}

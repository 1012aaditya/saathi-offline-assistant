package app.saathi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.saathi.brain.LlmEngine
import app.saathi.feedback.Cue
import app.saathi.ui.LocalDeps
import app.saathi.ui.components.Footnote
import app.saathi.ui.components.Group
import app.saathi.ui.components.GroupDivider
import app.saathi.ui.components.GroupRow
import app.saathi.ui.components.SectionHeader
import app.saathi.ui.theme.Clarity

/** What Settings says about the on-device model. */
internal fun brainStatusText(s: LlmEngine.Status): String = when (s) {
    LlmEngine.Status.Absent -> "No model file yet"
    is LlmEngine.Status.Off -> "Off"
    is LlmEngine.Status.TooSlow -> "Too slow on this phone · using quick commands"
    is LlmEngine.Status.Installed -> "On · loads when you do it together (${s.mb} MB)"
    is LlmEngine.Status.Loading -> "Loading the model…"
    is LlmEngine.Status.Ready -> "On · ready (${s.mb} MB)"
    is LlmEngine.Status.Failed -> "Couldn't load: ${s.reason}"
}

/**
 * Settings > "Smart understanding (on-device AI)": status, an on/off switch (on by default once a
 * model file is there), where the file goes, and "Remove AI model" behind a Cancel-first sheet.
 * Everything runs on the phone; nothing is sent anywhere.
 */
@Composable
fun SmartUnderstandingSection(changed: (String) -> Unit, say: (String) -> Unit) {
    val deps = LocalDeps.current
    val context = LocalContext.current
    val c = Clarity.colors
    LaunchedEffect(Unit) { LlmEngine.attach(context) }
    val status by LlmEngine.status.collectAsState()
    val hasFile = status !is LlmEngine.Status.Absent
    var on by remember { mutableStateOf(LlmEngine.isOn(context)) }
    var confirmRemove by remember { mutableStateOf(false) }

    Column {
        SectionHeader("Smart understanding")
        Group {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .toggleable(
                        value = on && hasFile,
                        enabled = hasFile,
                        role = Role.Switch,
                        onValueChange = { v ->
                            on = v
                            LlmEngine.setOn(context, v)
                            changed(if (v) "Smart understanding on" else "Smart understanding off. I'll use simple commands only.")
                        },
                    )
                    .semantics { stateDescription = brainStatusText(status) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RowTitle("Smart understanding (on-device AI)", brainStatusText(status), Modifier.weight(1f))
                Switch(
                    checked = on && hasFile,
                    onCheckedChange = null,
                    enabled = hasFile,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = c.onAccent,
                        checkedTrackColor = c.accent,
                        checkedBorderColor = c.accent,
                        uncheckedThumbColor = c.secondary,
                        uncheckedTrackColor = c.screen,
                        uncheckedBorderColor = c.secondary,
                    ),
                )
            }
            if (status is LlmEngine.Status.TooSlow) {
                GroupDivider()
                GroupRow(
                    title = "Try the AI model again",
                    onClick = {
                        LlmEngine.clearTooSlow(context)
                        changed("I'll try the AI model again next time we do it together")
                    },
                )
            }
            if (hasFile) {
                GroupDivider()
                GroupRow(
                    title = "Remove AI model",
                    icon = Icons.Outlined.Delete,
                    danger = true,
                    onClick = {
                        confirmRemove = true
                        say("Remove the AI model? Cancel is first.")
                        deps.feedback.cue(Cue.NeedsAnswer)
                    },
                )
            }
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Footnote("Understands everyday sentences in “do it together”, like “mujhe doodh chahiye”. It runs on your phone, offline: nothing is sent anywhere. Saathi still asks before sending or ordering.")
            if (status is LlmEngine.Status.TooSlow) {
                Footnote("This phone took too long to answer with the AI model, so Saathi isn't loading it. Everyday commands still work.")
            }
            if (!hasFile) {
                Footnote("To turn it on, copy a Gemma 3 1B .task file into ${LlmEngine.MODELS_PATH} on this phone (for example with adb push).")
            }
        }
    }

    if (confirmRemove) {
        ConfirmSheet(
            title = "Remove the AI model?",
            body = "This deletes the model file from this phone. Saathi goes back to simple commands. You can copy the file again later.",
            confirmLabel = "Remove AI model",
            onCancel = {
                confirmRemove = false
                say("Cancelled — nothing changed")
                deps.feedback.cue(Cue.Cancelled)
            },
            onConfirm = {
                confirmRemove = false
                val ok = LlmEngine.removeModel(context)
                if (ok) changed("AI model removed") else say("Couldn't remove all of the model file")
            },
        )
    }
}

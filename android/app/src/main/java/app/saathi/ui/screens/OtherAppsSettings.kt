package app.saathi.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.saathi.agent.AppApprovals
import app.saathi.agent.Approval
import app.saathi.agent.PhoneBook
import app.saathi.ui.components.Footnote
import app.saathi.ui.components.Group
import app.saathi.ui.components.GroupDivider
import app.saathi.ui.components.GroupRow
import app.saathi.ui.components.SectionHeader
import app.saathi.ui.theme.Clarity

/**
 * Settings > Apps, part two: the other apps the user let Saathi open (Allow / Not allowed; a Not
 * allowed app is never opened), and the optional phone-contacts help for "message anyone".
 * [changed]: a setting changed (Done cue + status line); [say]: a status line only.
 */
@Composable
fun OtherAppsSection(changed: (String) -> Unit, say: (String) -> Unit) {
    val context = LocalContext.current
    val c = Clarity.colors
    remember { AppApprovals.load(context); true } // load once, before the list draws
    var open by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val contactsOn = remember(tick) { PhoneBook.allowed(context) }
    val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        tick++
        if (granted) changed("Phone contacts: allowed") else say("Phone contacts not allowed. You can allow them in Android's settings for Saathi.")
    }

    Column {
        SectionHeader("Other apps")
        Group {
            if (AppApprovals.entries.isEmpty()) {
                Text(
                    "None yet. When you ask Saathi to open another app, it asks you first.",
                    style = Clarity.type.body,
                    color = c.secondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                )
            }
            AppApprovals.entries.toList().forEachIndexed { i, e ->
                if (i > 0) GroupDivider()
                val expanded = open == e.packageName
                val allowed = e.approval == Approval.ALLOWED
                GroupRow(
                    title = e.label,
                    icon = Icons.Outlined.Apps,
                    value = if (allowed) "Allow" else "Not allowed",
                    state = if (expanded) "Expanded" else "Collapsed",
                    onClick = { open = if (expanded) null else e.packageName },
                    trailing = { ExpandChevron(expanded) },
                )
                if (expanded) {
                    ChoiceRow(
                        options = listOf("Allow", "Not allowed"),
                        selected = if (allowed) 0 else 1,
                        onPick = { idx ->
                            val a = if (idx == 0) Approval.ALLOWED else Approval.NOT_ALLOWED
                            AppApprovals.set(context, e.packageName, e.label, a)
                            changed("${e.label}: ${if (a == Approval.ALLOWED) "Allow" else "Not allowed"}")
                        },
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                    )
                }
            }
            GroupDivider()
            GroupRow(
                title = "Phone contacts",
                icon = Icons.Outlined.Contacts,
                value = if (contactsOn) "Allowed" else "Not allowed",
                onClick = {
                    if (contactsOn) {
                        say("Phone contacts are allowed. To stop it, use Android's settings for Saathi.")
                        ScreenParts.openAppSettings(context)
                    } else {
                        PhoneBook.markAsked(context)
                        askContacts.launch(Manifest.permission.READ_CONTACTS)
                    }
                },
            )
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
            Footnote(
                "Saathi only opens these apps; it can't use them for you offline yet. Payment apps never open while Payments is Off. " +
                    "Phone contacts help Saathi match the names you say; they stay on this phone.",
            )
        }
    }
}

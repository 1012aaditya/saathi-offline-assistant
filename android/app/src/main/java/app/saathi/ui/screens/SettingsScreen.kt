package app.saathi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.saathi.data.AppLevel
import app.saathi.data.WalkSensitivity
import app.saathi.feedback.Cue
import app.saathi.ui.AppNav
import app.saathi.ui.LocalDeps
import app.saathi.ui.Route
import app.saathi.ui.components.Footnote
import app.saathi.ui.components.Group
import app.saathi.ui.components.GroupDivider
import app.saathi.ui.components.GroupRow
import app.saathi.ui.components.LargeTitle
import app.saathi.ui.components.NavRow
import app.saathi.ui.components.SaathiScreen
import app.saathi.ui.components.SectionHeader
import app.saathi.ui.components.TextLink
import app.saathi.ui.theme.Clarity
import app.saathi.ui.theme.ThemeChoice
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

/** Screen 11 — Settings. Every change gives the Done cue and a spoken status line. */

private val Languages: List<Pair<String?, String>> = listOf(
    null to "Phone's language",
    "en" to "English",
    "hi" to "हिन्दी",
    "es" to "Español",
    "fr" to "Français",
    "ar" to "العربية",
    "pt" to "Português",
)

private fun appIcon(app: String): ImageVector = when (app) {
    "WhatsApp" -> Icons.AutoMirrored.Outlined.Chat
    "Payments" -> Icons.Outlined.CreditCard
    else -> Icons.Outlined.ShoppingBag
}

private fun fmtNumber(n: Int): String = NumberFormat.getIntegerInstance().format(n)

private fun fmtRate(r: Float) = String.format(Locale.US, "%.1f", r)

private const val MIN_LIMIT = 50
private const val MAX_LIMIT = 10_000_000

private sealed interface SettingsSheet {
    data class Raise(val to: Int) : SettingsSheet
    data object DeleteAll : SettingsSheet
    data object EnableOrders : SettingsSheet
}

@Composable
fun SettingsScreen(nav: AppNav) {
    val deps = LocalDeps.current
    val state = deps.state
    val c = Clarity.colors

    var msg by remember { mutableStateOf<String?>(null) }
    var openApp by remember { mutableStateOf<String?>(null) }
    var langOpen by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<SettingsSheet?>(null) }
    var addingContact by remember { mutableStateOf(false) }

    fun changed(message: String) {
        msg = message
        deps.feedback.cue(Cue.Done)
    }

    SaathiScreen(
        status = msg ?: "Settings",
        youCanSay = listOf("Slower", "Change language"),
        onTalk = { nav.go(Route.Listening) },
        navRow = { NavRow("Home", { nav.home() }) },
    ) {
        LargeTitle("Settings")

        // FLOATING SAATHI (the bubble over other apps, drawn by Saathi's accessibility service)
        Column {
            SectionHeader("Floating Saathi")
            Group {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .toggleable(
                            value = state.floatingSaathi,
                            role = Role.Switch,
                            onValueChange = { on ->
                                state.updateFloatingSaathi(on)
                                changed(if (on) "Floating Saathi on" else "Floating Saathi off")
                            },
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RowTitle("Floating Saathi", "A round Saathi button over your other apps", Modifier.weight(1f))
                    Switch(
                        checked = state.floatingSaathi,
                        onCheckedChange = null,
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
            }
            Footnote(
                "Tap it in any app to do things together, right there: Saathi tells you what it sees and does each step you say. " +
                    "Hold it for 2 seconds to hide it; switch this off and on to bring it back. It hides itself on password and PIN screens. Needs Saathi on in Accessibility settings.",
                Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }

        // LISTEN AFTER SAATHI SPEAKS (doing it together: hands-free turns; default off)
        Column {
            SectionHeader("Doing it together")
            Group {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .toggleable(
                            value = state.listenAfterSpeaking,
                            role = Role.Switch,
                            onValueChange = { on ->
                                state.updateListenAfterSpeaking(on)
                                changed(if (on) "Saathi listens after it speaks" else "Press Speak to talk")
                            },
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RowTitle("Listen after Saathi speaks", "No need to press Speak each time", Modifier.weight(1f))
                    Switch(
                        checked = state.listenAfterSpeaking,
                        onCheckedChange = null,
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
            }
            Footnote(
                "When Saathi finishes talking, you hear the listening tone and can answer right away. " +
                    "In a noisy room it may hear other people; then turn this off and press Speak instead. " +
                    "Sending, ordering and calling still ask you first.",
                Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }

        // APPS
        Column {
            SectionHeader("Apps")
            Group {
                state.appLevels.forEachIndexed { i, (app, level) ->
                    if (i > 0) GroupDivider()
                    val open = openApp == app
                    GroupRow(
                        title = app,
                        icon = appIcon(app),
                        value = level.label,
                        state = if (open) "Expanded" else "Collapsed",
                        onClick = { openApp = if (open) null else app },
                        trailing = { ExpandChevron(open) },
                    )
                    if (open) {
                        val options = AppLevel.entries
                        ChoiceRow(
                            options = options.map { it.label },
                            selected = options.indexOf(level),
                            onPick = { idx ->
                                state.updateAppLevel(app, options[idx])
                                changed("$app: ${options[idx].label}")
                            },
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                        )
                    }
                }
            }
            Footnote(
                "Saathi always asks before sending, ordering or paying. You always enter PINs yourself.",
                Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }

        // OTHER APPS (opened only with the user's approval) + phone contacts for "message anyone"
        OtherAppsSection(changed = { changed(it) }, say = { msg = it })

        // [brain hook] SMART UNDERSTANDING (on-device AI): status, switch, remove model (BrainSettings.kt).
        SmartUnderstandingSection(changed = { changed(it) }, say = { msg = it })

        // MONEY
        Column {
            SectionHeader("Money")
            Group {
                val limit = state.spendingLimit
                val raiseTo = (limit.toLong() * 2).coerceAtMost(MAX_LIMIT.toLong()).toInt()
                val lowerTo = (limit / 2).coerceAtLeast(MIN_LIMIT)
                Column(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(
                        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RowTitle("Spending limit", "Per payment or order", Modifier.weight(1f))
                        Text(fmtNumber(limit), style = Clarity.type.headline, color = c.label)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (limit > MIN_LIMIT) {
                            TextLink(
                                "Lower",
                                {
                                    state.updateSpendingLimit(lowerTo)
                                    changed("Spending limit is now ${fmtNumber(lowerTo)}")
                                },
                                contentDescription = "Lower spending limit to ${fmtNumber(lowerTo)}",
                            )
                        }
                        if (limit < MAX_LIMIT) {
                            TextLink(
                                "Raise",
                                {
                                    sheet = SettingsSheet.Raise(raiseTo)
                                    msg = "Raise limit to ${fmtNumber(raiseTo)}? Cancel is first."
                                    deps.feedback.cue(Cue.NeedsAnswer)
                                },
                                contentDescription = "Raise spending limit to ${fmtNumber(raiseTo)}",
                            )
                        }
                    }
                }
            }
            Footnote("Raising the limit always needs your confirmation.", Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
        }
        Column {
            // Blinkit orders (OrderSettings.kt): default off; turning on asks first (Cancel is first).
            AllowOrdersSetting(state.allowOrders) { on ->
                if (on) {
                    sheet = SettingsSheet.EnableOrders
                    msg = "Allow placing orders? Cancel is first."
                    deps.feedback.cue(Cue.NeedsAnswer)
                } else {
                    state.updateAllowOrders(false)
                    changed("Placing orders is off")
                }
            }
        }

        // TRUSTED CONTACTS
        Column {
            SectionHeader("Trusted contacts")
            Group {
                if (state.trustedContacts.isEmpty()) {
                    Text(
                        "No trusted contacts yet",
                        style = Clarity.type.body,
                        color = c.secondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                    )
                    GroupDivider()
                }
                state.trustedContacts.toList().forEach { name ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(c.accent).clearAndSetSemantics {},
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(name.take(1).uppercase(), style = Clarity.type.callout.copy(fontWeight = FontWeight.Bold), color = c.onAccent)
                        }
                        Text(name, style = Clarity.type.body, color = c.label, modifier = Modifier.weight(1f))
                        TextLink(
                            "Remove",
                            {
                                state.removeContact(name)
                                changed("Removed $name")
                            },
                            contentDescription = "Remove $name",
                        )
                    }
                    GroupDivider()
                }
                GroupRow(
                    title = "Add contact",
                    icon = Icons.Outlined.PersonAdd,
                    onClick = { addingContact = true },
                )
            }
        }

        // VOICE
        Column {
            SectionHeader("Voice")
            Group {
                val current = Languages.firstOrNull { it.first == state.languageTag } ?: Languages.first()
                GroupRow(
                    title = "Language",
                    value = current.second,
                    state = if (langOpen) "Expanded" else "Collapsed",
                    onClick = { langOpen = !langOpen },
                    trailing = { ExpandChevron(langOpen) },
                )
                if (langOpen) {
                    Column(Modifier.selectableGroup()) {
                        Languages.forEach { (tag, name) ->
                            RadioListRow(
                                selected = tag == state.languageTag,
                                onClick = {
                                    state.updateLanguage(tag)
                                    langOpen = false
                                    changed("Language: $name")
                                },
                            ) {
                                Text(
                                    buildAnnotatedString {
                                        if (tag != null) {
                                            withStyle(SpanStyle(localeList = LocaleList(tag))) { append(name) }
                                        } else {
                                            append(name)
                                        }
                                    },
                                    style = Clarity.type.body,
                                    color = c.label,
                                )
                            }
                        }
                    }
                }
                GroupDivider()

                // Speaking rate: − 1.0× +, also adjustable with TalkBack's slider gestures.
                val rate = state.speechRate
                val atMin = rate <= 0.7f + 1e-4f
                val atMax = rate >= 1.5f - 1e-4f
                fun setRate(r: Float) {
                    val v = (r * 10).roundToInt() / 10f
                    state.updateSpeechRate(v)
                    changed("Speaking rate ${fmtRate(state.speechRate)} times")
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "Speaking rate",
                        style = Clarity.type.body,
                        color = c.label,
                        modifier = Modifier
                            .weight(1f)
                            .semantics {
                                stateDescription = "${fmtRate(rate)} times"
                                progressBarRangeInfo = ProgressBarRangeInfo(rate, 0.7f..1.5f, steps = 7)
                                setProgress { target ->
                                    val v = target.coerceIn(0.7f, 1.5f)
                                    if (v == rate) false else { setRate(v); true }
                                }
                            },
                    )
                    RoundIconButton(Icons.Filled.Remove, "Slower", enabled = !atMin) {
                        if (atMin) msg = "Slowest rate is 0.7 times" else setRate(rate - 0.1f)
                    }
                    Text("${fmtRate(rate)}×", style = Clarity.type.callout.copy(fontWeight = FontWeight.SemiBold), color = c.label, modifier = Modifier.clearAndSetSemantics {})
                    RoundIconButton(Icons.Filled.Add, "Faster", enabled = !atMax) {
                        if (atMax) msg = "Fastest rate is 1.5 times" else setRate(rate + 0.1f)
                    }
                }
                GroupDivider()
                GroupRow(
                    title = "Play sample",
                    icon = Icons.AutoMirrored.Outlined.VolumeUp,
                    onClick = {
                        deps.voice.speak("Hi, I'm Saathi. This is how I sound.")
                        msg = "Playing voice sample"
                    },
                )
            }
        }

        // APPEARANCE
        Column {
            SectionHeader("Appearance")
            Group {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Theme", style = Clarity.type.body, color = c.label)
                    val themes = listOf(ThemeChoice.System to "Automatic", ThemeChoice.Light to "Light", ThemeChoice.Dark to "Dark")
                    ChoiceRow(
                        options = themes.map { it.second },
                        selected = themes.indexOfFirst { it.first == state.theme },
                        onPick = { idx ->
                            state.updateTheme(themes[idx].first)
                            changed("Theme: ${themes[idx].second}")
                        },
                    )
                }
                GroupDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .toggleable(
                            value = state.soundOn,
                            role = Role.Switch,
                            onValueChange = { on ->
                                // Update the cue player first so turning sounds off is silent at once.
                                deps.feedback.soundOn = on
                                state.updateSound(on)
                                changed(if (on) "Sounds on" else "Sounds off")
                            },
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RowTitle("Sounds", "Vibrations always stay on", Modifier.weight(1f))
                    Switch(
                        checked = state.soundOn,
                        onCheckedChange = null,
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
            }
        }

        // WALK MODE
        Column {
            SectionHeader("Walk mode")
            Group {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Sensitivity", style = Clarity.type.body, color = c.label)
                    val levels = WalkSensitivity.entries
                    ChoiceRow(
                        options = levels.map { it.name },
                        selected = levels.indexOf(state.walkSensitivity),
                        onPick = { idx ->
                            state.updateWalkSensitivity(levels[idx])
                            changed("Walk sensitivity: ${levels[idx].name}")
                        },
                    )
                }
            }
            Footnote("Higher warns earlier, Low warns later.", Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
        }

        // PRIVACY
        Column {
            SectionHeader("Privacy")
            Group {
                GroupRow(
                    title = "Delete all data",
                    icon = Icons.Outlined.Delete,
                    danger = true,
                    onClick = {
                        sheet = SettingsSheet.DeleteAll
                        msg = "Delete all data? Cancel is first."
                        deps.feedback.cue(Cue.NeedsAnswer)
                    },
                )
            }
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Footnote("History and settings stay on your phone. Saathi's app tasks work offline: screen text stays on your phone and isn't stored.")
                Footnote("Your voice is sent to a speech service to turn it into text.")
                Footnote("Walk mode warnings are made on your phone.")
            }
        }

        // Safety centre (last)
        Group {
            GroupRow(
                title = "Safety centre",
                value = "Always on",
                icon = Icons.Outlined.Shield,
                chevron = true,
                onClick = { nav.go(Route.Safety) },
            )
        }
    }

    when (val s = sheet) {
        is SettingsSheet.Raise -> ConfirmSheet(
            title = "Raise limit to ${fmtNumber(s.to)}?",
            body = "I'll allow payments and orders up to ${fmtNumber(s.to)}. I'll still ask before every one.",
            confirmLabel = "Raise to ${fmtNumber(s.to)}",
            danger = false,
            onCancel = {
                sheet = null
                msg = "Cancelled — nothing changed"
                deps.feedback.cue(Cue.Cancelled)
            },
            onConfirm = {
                sheet = null
                state.updateSpendingLimit(s.to)
                changed("Spending limit is now ${fmtNumber(s.to)}")
            },
        )
        SettingsSheet.DeleteAll -> ConfirmSheet(
            title = "Delete all data?",
            body = "This removes your history, settings and trusted contacts from this phone. It can't be undone. " +
                "The on-device AI model file stays; remove it under Smart understanding.",
            confirmLabel = "Delete all data",
            onCancel = {
                sheet = null
                msg = "Cancelled — nothing changed"
                deps.feedback.cue(Cue.Cancelled)
            },
            onConfirm = {
                sheet = null
                state.deleteAll()
                app.saathi.agent.SaathiContext.app?.let {
                    app.saathi.agent.AppApprovals.clear(it)
                    app.saathi.agent.PhoneBook.forget(it)
                    app.saathi.brain.LlmEngine.resetSetting(it) // [brain hook] the switch, not the model file
                }
                deps.feedback.soundOn = state.soundOn
                deps.feedback.cue(Cue.Done)
                nav.restart(Route.Welcome)
            },
        )
        SettingsSheet.EnableOrders -> ConfirmSheet(
            title = ALLOW_ORDERS_TITLE,
            body = ALLOW_ORDERS_BODY,
            confirmLabel = "Allow placing orders",
            danger = false,
            onCancel = {
                sheet = null
                msg = "Cancelled — nothing changed"
                deps.feedback.cue(Cue.Cancelled)
            },
            onConfirm = {
                sheet = null
                state.updateAllowOrders(true)
                changed("Placing orders is on. I'll still ask before every order.")
            },
        )
        null -> Unit
    }

    if (addingContact) {
        TextFieldDialog(
            title = "Add a trusted contact",
            fieldLabel = "Contact name",
            confirmLabel = "Add",
            onCancel = {
                addingContact = false
                deps.feedback.cue(Cue.Cancelled)
            },
            onConfirm = { name ->
                addingContact = false
                if (name in state.trustedContacts) {
                    msg = "$name is already a trusted contact"
                } else {
                    state.addContact(name)
                    changed("Added $name")
                }
            },
        )
    }
}

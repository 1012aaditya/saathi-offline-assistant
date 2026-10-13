package app.saathi.ui.screens

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.saathi.feedback.Cue
import app.saathi.ui.AppNav
import app.saathi.ui.LocalDeps
import app.saathi.ui.Route
import app.saathi.ui.components.Footnote
import app.saathi.ui.components.Gap
import app.saathi.ui.components.Group
import app.saathi.ui.components.GroupDivider
import app.saathi.ui.components.LargeTitle
import app.saathi.ui.components.PrimaryButton
import app.saathi.ui.components.SaathiScreen
import app.saathi.ui.components.SectionHeader
import app.saathi.ui.theme.Clarity
import java.util.Locale

private class WelcomeLang(val tag: String, val name: String, val spoken: List<String>)

private val WelcomeLangs = listOf(
    WelcomeLang("en", "English", listOf("english")),
    WelcomeLang("hi", "हिन्दी", listOf("hindi", "हिन्दी", "हिंदी")),
    WelcomeLang("es", "Español", listOf("spanish", "español", "espanol")),
    WelcomeLang("fr", "Français", listOf("french", "français", "francais")),
    WelcomeLang("ar", "العربية", listOf("arabic", "العربية")),
    WelcomeLang("pt", "Português", listOf("portuguese", "português", "portugues")),
)

/** Language names are tagged so TalkBack reads "हिन्दी" with a Hindi voice, etc. */
private fun inLanguage(text: String, tag: String?): AnnotatedString =
    if (tag == null) AnnotatedString(text)
    else buildAnnotatedString { withStyle(SpanStyle(localeList = LocaleList(tag))) { append(text) } }

/** Screen 1 — Welcome: greeting, promise, language choice, Continue. */
@Composable
fun WelcomeScreen(nav: AppNav) {
    val deps = LocalDeps.current
    val state = deps.state
    val c = Clarity.colors
    val still = ScreenParts.rememberStill()
    var open by rememberSaveable { mutableStateOf(false) }

    val phoneName = remember {
        val l = Locale.getDefault()
        l.getDisplayLanguage(l).replaceFirstChar { it.titlecase(l) }
    }
    val chosen = WelcomeLangs.firstOrNull { it.tag == state.languageTag }
    val currentName = chosen?.name ?: phoneName
    val currentNote = if (chosen == null) "From your phone" else "Chosen by you"

    ScreenParts.OnFirstShow {
        deps.voice.speak("Hi, I'm Saathi. I read your screen and use your apps for you, just by voice. Press Continue to set up.")
    }

    fun choose(tag: String?) {
        state.updateLanguage(tag)
        open = false
        deps.feedback.cue(Cue.Done)
    }

    fun next() {
        deps.voice.stopSpeaking()
        // replace, not go: Welcome leaves the back stack so Setup → Home ends with Home alone.
        nav.replace(Route.Setup)
    }

    val listener = ScreenParts.rememberScreenListener { text ->
        val t = text.lowercase()
        val lang = WelcomeLangs.firstOrNull { l -> l.spoken.any { it in t } }
        when {
            lang != null -> choose(lang.tag)
            "phone" in t -> choose(null)
            "language" in t || "change" in t || "bhasha" in t -> open = true
            "continue" in t || "next" in t || "start" in t || "aage" in t -> next()
            else -> deps.voice.speak("Sorry, I didn't catch that. You can say Continue, or Change language.")
        }
    }

    val chevronSpec: AnimationSpec<Float> = if (still) snap() else tween(250)
    val chevron by animateFloatAsState(if (open) 90f else 0f, chevronSpec, label = "chevron")

    SaathiScreen(
        status = if (listener.active) "Listening" else "Saathi is ready. Welcome.",
        youCanSay = listOf("Continue", "Change language"),
        onTalk = { listener.toggle() },
        bottom = {
            ScreenParts.ListenHint(listener)
            PrimaryButton("Continue", { next() }, modifier = ScreenParts.rise(4, still))
        },
    ) {
        Gap(24.dp)

        // App mark: four bars in an accent tile. Decorative.
        Box(
            ScreenParts.rise(0, still)
                .size(64.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(c.accent)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) { ScreenParts.LevelBars(active = true, still = still, color = c.onAccent, count = 4) }

        Column(ScreenParts.rise(1, still), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LargeTitle("Hi, I'm Saathi")
            Text(
                "I read your screen and use your apps for you — just by voice.",
                style = Clarity.type.headline.copy(fontWeight = FontWeight.Normal),
                color = c.secondary,
            )
        }

        Column(ScreenParts.rise(2, still), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader("Language")
            Group {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .clickable(role = Role.Button, onClickLabel = if (open) "Close the list" else "Change language") { open = !open }
                        .semantics(mergeDescendants = true) {
                            contentDescription = "Language, $currentName, $currentNote"
                            stateDescription = if (open) "Expanded" else "Collapsed"
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(inLanguage(currentName, chosen?.tag), style = Clarity.type.body, color = c.label)
                        Text(currentNote, style = Clarity.type.footnote, color = c.secondary)
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = c.secondary,
                        modifier = Modifier.rotate(chevron),
                    )
                }
                if (open) {
                    val options: List<Pair<String?, String>> =
                        listOf<Pair<String?, String>>(null to "Same as my phone ($phoneName)") + WelcomeLangs.map { it.tag to it.name }
                    Column(Modifier.selectableGroup()) {
                        options.forEachIndexed { i, (tag, name) ->
                            GroupDivider()
                            val selected = state.languageTag == tag
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 60.dp)
                                    .then(ScreenParts.rise(i, still))
                                    .selectable(selected = selected, role = Role.RadioButton, onClick = { choose(tag) })
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(inLanguage(name, tag), style = Clarity.type.body, color = c.label, modifier = Modifier.weight(1f))
                                if (selected) ScreenParts.DrawnCheck(c.accentText, 22.dp, 2.6.dp, still)
                            }
                        }
                    }
                }
            }
            Footnote(
                "Saathi speaks the same language as your phone. You can change it any time.",
                Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

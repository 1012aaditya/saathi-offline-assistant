package app.saathi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.saathi.ui.theme.Clarity

/**
 * Standard Clarity screen: optional nav row, scrolling content, pinned bottom area with the
 * actions and the "You can say …" line + talk button. Every screen uses this so voice is everywhere.
 */
@Composable
fun SaathiScreen(
    status: String,
    youCanSay: List<String>,
    onTalk: () -> Unit,
    navRow: (@Composable () -> Unit)? = null,
    bottom: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Clarity.colors
    Column(
        Modifier.fillMaxSize().background(c.screen).statusBarsPadding().navigationBarsPadding(),
    ) {
        StatusLine(status)
        navRow?.invoke()
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            content = content,
        )
        Column(
            Modifier.fillMaxWidth().background(c.screen).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            bottom()
            YouCanSay(youCanSay, onTalk)
        }
    }
}

/** Invisible polite live region: TalkBack announces it whenever it changes. */
@Composable
fun StatusLine(text: String) {
    Box(
        Modifier.size(1.dp).semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = text
        },
    )
}

@Composable
fun NavRow(backLabel: String? = null, onBack: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (backLabel != null && onBack != null) TextLink("‹ $backLabel", onBack, contentDescription = "Back to $backLabel") else Spacer(Modifier.width(1.dp))
        trailing?.invoke()
    }
}

@Composable
fun LargeTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Clarity.type.largeTitle, color = Clarity.colors.label, modifier = modifier.semantics { heading() })
}

@Composable
fun Body(text: String, modifier: Modifier = Modifier, secondary: Boolean = false, align: TextAlign? = null) {
    val c = Clarity.colors
    Text(text, style = Clarity.type.body, color = if (secondary) c.secondary else c.label, modifier = modifier, textAlign = align)
}

@Composable
fun Footnote(text: String, modifier: Modifier = Modifier, align: TextAlign? = null) {
    Text(text, style = Clarity.type.footnote, color = Clarity.colors.secondary, modifier = modifier, textAlign = align)
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = Clarity.type.sectionHeader,
        color = Clarity.colors.secondary,
        modifier = Modifier.padding(start = 16.dp, bottom = 6.dp).semantics { heading() },
    )
}

private fun Modifier.focusRing(color: Color, shape: RoundedCornerShape, focused: Boolean) =
    if (focused) this.border(3.dp, color, shape) else this

@Composable
private fun BaseButton(
    text: String,
    onClick: () -> Unit,
    bg: Color,
    fg: Color,
    modifier: Modifier,
    height: Dp,
    description: String?,
    tag: String? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .clip(shape)
            .background(bg)
            .focusRing(Clarity.colors.focus, shape, focused)
            .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), role = Role.Button, onClick = onClick)
            .semantics { if (description != null) contentDescription = description }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Clarity.type.button, color = fg, textAlign = TextAlign.Center)
        if (tag != null) {
            Text(
                tag,
                style = Clarity.type.footnote,
                color = fg,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, description: String? = null) =
    BaseButton(text, onClick, Clarity.colors.accent, Clarity.colors.onAccent, modifier, 64.dp, description)

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, description: String? = null, tag: String? = null) =
    BaseButton(text, onClick, Clarity.colors.group, Clarity.colors.label, modifier, 64.dp, description, tag)

/** Stop / destructive: 72dp, dark red with white text (9.3:1). */
@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, description: String? = null) =
    BaseButton(text, onClick, Clarity.colors.danger, Clarity.colors.onDanger, modifier, 72.dp, description)

@Composable
fun TextLink(text: String, onClick: () -> Unit, contentDescription: String? = null, danger: Boolean = false) {
    val c = Clarity.colors
    Text(
        text,
        style = Clarity.type.body,
        color = if (danger) c.dangerText else c.accentText,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/** iOS-style inset grouped list. Put [GroupRow]s inside; separators are drawn between rows. */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Clarity.colors.group), content = content)
}

@Composable
fun GroupDivider() = HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 1.dp, color = Clarity.colors.separator)

@Composable
fun GroupRow(
    title: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    icon: ImageVector? = null,
    chevron: Boolean = false,
    danger: Boolean = false,
    state: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Clarity.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { if (state != null) stateDescription = state }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = if (danger) c.dangerText else c.accentText, modifier = Modifier.size(26.dp))
        Text(title, style = Clarity.type.body, color = if (danger) c.dangerText else c.label, modifier = Modifier.weight(1f))
        if (value != null) Text(value, style = Clarity.type.callout, color = c.secondary)
        trailing?.invoke()
        if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.secondary)
    }
}

/** "You can say …" footer + the 56dp talk button. On every screen. */
@Composable
fun YouCanSay(phrases: List<String>, onTalk: () -> Unit) {
    val c = Clarity.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "You can say " + phrases.joinToString(" · ") { "“$it”" },
            style = Clarity.type.footnote,
            color = c.secondary,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(c.group)
                .clickable(role = Role.Button, onClick = onTalk)
                .semantics { contentDescription = "Talk to Saathi" },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Mic, contentDescription = null, tint = c.label) }
    }
}

/** Yellow "Needs your answer" capsule (14.9:1). */
@Composable
fun AskCapsule(text: String = "Needs your answer") {
    val c = Clarity.colors
    Text(
        text,
        style = Clarity.type.callout,
        color = c.onAsk,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(c.ask).padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
fun Gap(h: Dp) = Spacer(Modifier.size(h))

package app.saathi.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.saathi.feedback.reducedMotion
import app.saathi.ui.components.DangerButton
import app.saathi.ui.components.PrimaryButton
import app.saathi.ui.components.SecondaryButton
import app.saathi.ui.components.TextLink
import app.saathi.ui.theme.Clarity
import kotlinx.coroutines.delay

/*
 * Small helpers shared by Walk, Activity, Settings and Safety.
 * Kept here (not in Components.kt) so the shared skeleton stays untouched.
 */

/**
 * Bottom confirm sheet (DESIGN.md: "Cancel first, on top, focused and default").
 * The platform dialog dims the screen and traps TalkBack focus inside the sheet.
 * Tapping outside, Back, or Cancel all mean cancel.
 */
@Composable
fun ConfirmSheet(
    title: String,
    body: String,
    confirmLabel: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    danger: Boolean = true,
    cancelDescription: String = "Cancel, change nothing",
    confirmDescription: String? = null,
) {
    val c = Clarity.colors
    val context = LocalContext.current
    val still = remember { reducedMotion(context) }
    var shown by remember { mutableStateOf(false) }
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { shown = true }
    LaunchedEffect(shown) {
        if (shown) {
            delay(50)
            runCatching { cancelFocus.requestFocus() }
        }
    }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            // Transparent tap-catcher above the sheet (the dialog window already dims).
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCancel)
                    .clearAndSetSemantics {},
            )
            AnimatedVisibility(
                visible = shown,
                enter = if (still) EnterTransition.None else slideInVertically(tween(300, easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f))) { it },
            ) {
                val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(c.screen)
                        .then(if (c.isDark) Modifier.border(1.dp, c.separator, shape) else Modifier)
                        // Swallow taps so they don't reach the cancel tap-catcher.
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                        .semantics { paneTitle = title }
                        .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .size(width = 40.dp, height = 5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(c.separator),
                    )
                    Text(title, style = Clarity.type.title, color = c.label, modifier = Modifier.semantics { heading() })
                    Text(body, style = Clarity.type.body, color = c.secondary)
                    SecondaryButton("Cancel", onCancel, modifier = Modifier.focusRequester(cancelFocus), description = cancelDescription)
                    if (danger) {
                        DangerButton(confirmLabel, onConfirm, description = confirmDescription)
                    } else {
                        PrimaryButton(confirmLabel, onConfirm, description = confirmDescription)
                    }
                }
            }
        }
    }
}

/**
 * Inline single-choice selector (radio group). Selected = filled accent + check + bold,
 * so the choice never depends on colour alone. Wraps at large font sizes.
 */
@Composable
fun ChoiceRow(
    options: List<String>,
    selected: Int,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Clarity.colors
    Row(
        modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
                    .clip(shape)
                    .background(if (on) c.accent else c.screen)
                    .border(1.dp, if (on) c.accent else c.separator, shape)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onPick(i) })
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (on) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    label,
                    style = Clarity.type.footnote.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
                    color = if (on) c.onAccent else c.label,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** One row in a vertical radio list (e.g. Language). Check mark + text, never colour alone. */
@Composable
fun RadioListRow(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val c = Clarity.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(start = 56.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) { content() }
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = c.accentText, modifier = Modifier.size(24.dp))
    }
}

/** Chevron that points right when collapsed and down when expanded. Decorative. */
@Composable
fun ExpandChevron(open: Boolean) {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = Clarity.colors.secondary,
        modifier = Modifier.rotate(if (open) 90f else 0f),
    )
}

/** 48dp round icon button (− / +). Disabled state is announced, not just greyed. */
@Composable
fun RoundIconButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Clarity.colors
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(c.screen)
            .border(1.dp, c.separator, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) c.accentText else c.secondary)
    }
}

/** Simple dialog with one labelled text field (Add contact). */
@Composable
fun TextFieldDialog(
    title: String,
    fieldLabel: String,
    confirmLabel: String,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val c = Clarity.colors
    var text by remember { mutableStateOf("") }
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { fieldFocus.requestFocus() }
    }
    val submit: () -> Unit = { if (text.isNotBlank()) onConfirm(text.trim()) }
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = c.group,
        title = { Text(title, style = Clarity.type.headline, color = c.label, modifier = Modifier.semantics { heading() }) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(40) },
                label = { Text(fieldLabel, style = Clarity.type.footnote) },
                singleLine = true,
                textStyle = Clarity.type.body,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = c.label,
                    unfocusedTextColor = c.label,
                    focusedBorderColor = c.accentText,
                    unfocusedBorderColor = c.secondary,
                    focusedLabelColor = c.accentText,
                    unfocusedLabelColor = c.secondary,
                    cursorColor = c.label,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus),
            )
        },
        dismissButton = { TextLink("Cancel", onCancel) },
        confirmButton = { TextLink(confirmLabel, { submit() }) },
    )
}

/** Grouped-list header row text + optional secondary line, used inside custom rows. */
@Composable
fun RowTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    val c = Clarity.colors
    Column(modifier) {
        Text(title, style = Clarity.type.body, color = c.label)
        if (subtitle != null) Text(subtitle, style = Clarity.type.footnote, color = c.secondary)
    }
}

/** Vertical spacer helper for inside groups. */
@Composable
fun VGap(h: Int) = Spacer(Modifier.height(h.dp))

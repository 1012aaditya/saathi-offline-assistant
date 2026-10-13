package app.saathi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** "Clarity" tokens (DESIGN.md). Every text/background pair is WCAG AAA (>= 7:1). */
@Immutable
data class ClarityColors(
    val isDark: Boolean,
    val screen: Color,
    val group: Color,
    val label: Color,
    val secondary: Color,
    val separator: Color,
    val accent: Color,
    val onAccent: Color,
    val accentText: Color,
    val danger: Color,
    val onDanger: Color,
    val dangerText: Color,
    val ask: Color,
    val onAsk: Color,
    val dim: Color,
    val focus: Color,
)

val LightClarity = ClarityColors(
    isDark = false,
    screen = Color(0xFFFFFFFF),
    group = Color(0xFFF2F2F7),
    label = Color(0xFF000000),
    secondary = Color(0xFF3A3A3C),
    separator = Color(0xFFC6C6C8),
    accent = Color(0xFF2B22B8),
    onAccent = Color(0xFFFFFFFF),
    accentText = Color(0xFF2B22B8),
    danger = Color(0xFF8E1414),
    onDanger = Color(0xFFFFFFFF),
    dangerText = Color(0xFF8E1414),
    ask = Color(0xFFFFD60A),
    onAsk = Color(0xFF000000),
    dim = Color(0x66000000),
    focus = Color(0xFF2B22B8),
)

val DarkClarity = ClarityColors(
    isDark = true,
    screen = Color(0xFF000000),
    group = Color(0xFF1C1C1E),
    label = Color(0xFFFFFFFF),
    secondary = Color(0xFFD1D1D6),
    separator = Color(0xFF38383A),
    accent = Color(0xFFC9C6FF),
    onAccent = Color(0xFF000000),
    accentText = Color(0xFFA8A4FF),
    danger = Color(0xFF8E1414),
    onDanger = Color(0xFFFFFFFF),
    dangerText = Color(0xFFFF8A80),
    ask = Color(0xFFFFD60A),
    onAsk = Color(0xFF000000),
    dim = Color(0x99000000),
    focus = Color(0xFFFFD60A),
)

/** Type scale: larger than iOS for low vision; nothing under 17sp. Uses the system font. */
@Immutable
data class ClarityType(
    val largeTitle: TextStyle,
    val title: TextStyle,
    val headline: TextStyle,
    val body: TextStyle,
    val callout: TextStyle,
    val footnote: TextStyle,
    val button: TextStyle,
    val sectionHeader: TextStyle,
)

val Type = ClarityType(
    largeTitle = TextStyle(fontFamily = FontFamily.Default, fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em),
    title = TextStyle(fontFamily = FontFamily.Default, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.01).em),
    headline = TextStyle(fontFamily = FontFamily.Default, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    body = TextStyle(fontFamily = FontFamily.Default, fontSize = 20.sp, lineHeight = 28.sp),
    callout = TextStyle(fontFamily = FontFamily.Default, fontSize = 18.sp, lineHeight = 24.sp),
    footnote = TextStyle(fontFamily = FontFamily.Default, fontSize = 17.sp, lineHeight = 23.sp),
    button = TextStyle(fontFamily = FontFamily.Default, fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    sectionHeader = TextStyle(fontFamily = FontFamily.Default, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.02.em),
)

val LocalClarity = staticCompositionLocalOf { LightClarity }

object Clarity {
    val colors: ClarityColors @Composable get() = LocalClarity.current
    val type: ClarityType get() = Type
}

enum class ThemeChoice { System, Light, Dark }

@Composable
fun SaathiTheme(choice: ThemeChoice = ThemeChoice.System, content: @Composable () -> Unit) {
    val dark = when (choice) {
        ThemeChoice.System -> isSystemInDarkTheme()
        ThemeChoice.Light -> false
        ThemeChoice.Dark -> true
    }
    val c = if (dark) DarkClarity else LightClarity
    val scheme = if (dark) {
        darkColorScheme(primary = c.accent, onPrimary = c.onAccent, background = c.screen, surface = c.screen, onBackground = c.label, onSurface = c.label, error = c.danger)
    } else {
        lightColorScheme(primary = c.accent, onPrimary = c.onAccent, background = c.screen, surface = c.screen, onBackground = c.label, onSurface = c.label, error = c.danger)
    }
    CompositionLocalProvider(LocalClarity provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

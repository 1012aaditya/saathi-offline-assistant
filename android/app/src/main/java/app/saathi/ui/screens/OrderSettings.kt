package app.saathi.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.saathi.ui.components.Footnote
import app.saathi.ui.components.Group
import app.saathi.ui.theme.Clarity

/**
 * Settings > Money > "Allow placing orders" (default OFF). Off: a Blinkit task stops at the
 * read-back ("Ordering is turned off in Settings; your cart is ready"). Turning it on goes
 * through a confirmation sheet whose default is Cancel ([onToggle] with true only asks); every
 * order still needs the read-back and a clear yes, and stays within the spending limit.
 */
@Composable
fun AllowOrdersSetting(on: Boolean, onToggle: (Boolean) -> Unit) {
    val c = Clarity.colors
    Column {
        Group {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .toggleable(value = on, role = Role.Switch, onValueChange = onToggle)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RowTitle("Allow placing orders", "Blinkit, after you say yes to the read-back", Modifier.weight(1f))
                Switch(
                    checked = on,
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
            if (on) "I read the cart and total to you and order only after your yes, within your spending limit."
            else "Off: I fill the cart and read it to you, but I never place the order.",
            Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )
    }
}

const val ALLOW_ORDERS_TITLE = "Allow placing orders?"
const val ALLOW_ORDERS_BODY =
    "Saathi will be able to place Blinkit orders. Before every order I read the items and total to you, " +
        "and I order only after your clear yes, up to your spending limit. You always pay and enter PINs yourself."

package app.saathi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.saathi.ui.DebugCommands

/**
 * Debug builds only. Lets a tester type what the user would say:
 *   adb shell am broadcast -n app.saathi/.DebugCommandReceiver --es text "read Mom's last message"
 * Protected by android.permission.DUMP (granted to `adb shell`, not to apps). The command then goes
 * through the same parser, flows and safety gate as speech; nothing is confirmed automatically.
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text")?.takeIf { it.isNotBlank() } ?: return
        DebugCommands.post(text)
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
    }
}

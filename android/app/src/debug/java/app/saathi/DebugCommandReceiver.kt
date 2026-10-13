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
        // Non-sending test answers only: keep / replace / add (draft choice) and no (confirm).
        // There is deliberately no way to answer YES or SEND from here.
        when (intent.getStringExtra("answer")) {
            "keep" -> { app.saathi.agent.TaskRunner.choose(app.saathi.agent.DraftChoice.KEEP, "debug"); return }
            "replace" -> { app.saathi.agent.TaskRunner.choose(app.saathi.agent.DraftChoice.REPLACE, "debug"); return }
            "add" -> { app.saathi.agent.TaskRunner.choose(app.saathi.agent.DraftChoice.ADD, "debug"); return }
            "no" -> { app.saathi.agent.TaskRunner.answer(app.saathi.safety.Answer.NO, "debug"); return }
        }
        val text = intent.getStringExtra("text")?.takeIf { it.isNotBlank() } ?: return
        // A running guide session (floating panel or Guide screen): hand the words straight to it,
        // like pressing Speak, without bringing Saathi's screen forward (that would close the panel).
        if (app.saathi.agent.TaskRunner.isGuiding) {
            app.saathi.agent.TaskRunner.guideCommand(app.saathi.agent.Heard(text), "request")
            return
        }
        DebugCommands.post(text)
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
    }
}

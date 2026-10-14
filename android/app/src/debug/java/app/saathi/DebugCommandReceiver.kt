package app.saathi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import app.saathi.agent.BrainInput
import app.saathi.brain.LlmEngine
import app.saathi.agent.BrainPrompt
import app.saathi.agent.Lang
import app.saathi.agent.ScreenSummary
import app.saathi.agent.SummaryProduct
import app.saathi.ui.DebugCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
        // Timing only: how long the on-device AI takes for a typical guide prompt (made-up products,
        // no screen text). Logged as SaathiBench; nothing is shown, said or done.
        intent.getStringExtra("transcripts")?.let { app.saathi.voice.VoiceDebug.transcripts = it == "on"; return }
        intent.getStringExtra("brainbench")?.let { mode -> brainBench(context.applicationContext, mode); return }
        // "Do it for me" on the app in front, as if said on the bubble's home panel (testing only).
        intent.getStringExtra("goal")?.takeIf { it.isNotBlank() }?.let { goal ->
            val svc = app.saathi.agent.SaathiAccessibilityService.running ?: return
            val front = svc.frontPackageName() ?: return
            val d = app.saathi.ui.SharedDeps.peek() ?: return
            app.saathi.agent.TaskRunner.pendingGoal = goal
            app.saathi.agent.TaskRunner.startFloatingGuide(svc, front, app.saathi.agent.Requests.lang(d), d.state) { d.voice.speak(it) }
            return
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

    /** [mode] "cpu" / "gpu": reload on that backend first; anything else: use what's loaded. */
    private fun brainBench(app: Context, mode: String) {
        val engine = LlmEngine
        engine.attach(app)
        CoroutineScope(Dispatchers.Default).launch {
            if (mode == "cpu" || mode == "gpu") {
                engine.cpuOnlyForTesting = mode == "cpu"
                engine.unloadForTesting()
                delay(3_000)
            }
            engine.prewarm()
            delay(500)
            val t0 = SystemClock.elapsedRealtime()
            while (!engine.ready && SystemClock.elapsedRealtime() - t0 < 90_000) delay(500)
            if (!engine.ready) { Log.i("SaathiBench", "model not ready after 90 s"); return@launch }
            val products = (1..6).map { SummaryProduct("Sample product $it", "500 ml", "₹${40 + it}", "") }
            val screen = ScreenSummary("Blinkit", "search results", "Search results", products, "2 items in cart", null, null, emptyList())
            // Made-up test sentences (never the user's words), so the short-prompt answers are logged.
            val samples = listOf(
                "mujhe kuch thanda peena hai", "ye wala hata do", "sabse sasta dikhao",
                "can you put two of these in my card", "go back yaar", "kitne paise ho gaye total",
            )
            fun short(said: String) =
                "<start_of_turn>user\nMap the request to one intent: search, add, remove, cart, read, cheaper, back, done. " +
                    "Answer JSON {\"intent\":...,\"query\":...}.\nRequest: $said<end_of_turn>\n<start_of_turn>model\n"
            val prompts = if (mode == "short") samples.map(::short) else List(2) { BrainPrompt.full(BrainInput("do you see anything I can drink here", screen, emptyList(), Lang.EN)) }
            prompts.forEachIndexed { i, prompt ->
                if (i > 0) delay(3_000) // let the previous answer wind down
                val t1 = SystemClock.elapsedRealtime()
                val out = engine.complete(prompt, 60_000)
                val shown = if (mode == "short") " -> ${out?.replace("\n", " ")}" else ""
                Log.i("SaathiBench", "run ${i + 1}: prompt ${prompt.length} chars, ${SystemClock.elapsedRealtime() - t1} ms, answer ${out?.length ?: -1} chars$shown")
            }
        }
    }
}

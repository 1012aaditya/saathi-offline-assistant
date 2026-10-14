package app.saathi.brain

import android.content.Context
import app.saathi.agent.Brain
import app.saathi.agent.GuideBrain
import app.saathi.agent.Lang

/** Wires smart understanding into a guide session (TaskRunner's one-line hook). */
object BrainHooks {
    /**
     * The guide's [GuideBrain], or null (rules only, exactly as before) when there is no model to
     * ask. Two possible models:
     *  - the owner's laptop ([LaptopBrain], debug/demo builds, reached through `adb reverse`), used
     *    whenever it answers the probe; private apps stay on the rules;
     *  - else the on-device Gemma file (if on, present and not marked too slow), loaded in the
     *    background. Until a model is ready, every utterance goes to the rule parser.
     */
    fun forGuide(context: Context, lang: Lang, log: (String) -> Unit): GuideBrain? {
        LlmEngine.attach(context)
        val laptop = LaptopBrain.usable(context)
        val local = LlmEngine.isOn(context) && !LlmEngine.isTooSlow(context) && LlmEngine.modelFile(context) != null
        if (!laptop && !local) return null
        if (laptop) {
            // The laptop first; the tablet's own model (~600 MB) loads only if the laptop isn't there.
            LaptopBrain.probeAsync { up ->
                log(if (up) "brain: laptop model" else "brain: laptop not reachable")
                if (!up && local) LlmEngine.prewarm()
            }
        } else {
            LlmEngine.prewarm()
        }
        return GuideBrain(
            Routed,
            lang,
            onThinking = LlmEngine::setThinking,
            log = log,
            // Only the laptop is limited: the on-device model never leaves the phone.
            mayRead = { pkg -> !LaptopBrain.ready || LaptopBrain.mayRead(pkg) },
        )
    }

    /**
     * "Do it for me" planner: only with the laptop / OpenAI (debug / demo builds); the on-device
     * model is far too slow for step-by-step planning. Private apps are never sent ([LaptopBrain.mayRead]).
     */
    fun agentFor(context: Context, log: (String) -> Unit): app.saathi.agent.AgentPlanner? {
        if (!LaptopBrain.usable(context)) return null
        LaptopBrain.probeAsync()
        return app.saathi.agent.AgentPlanner(LaptopBrain, LaptopBrain::mayRead, onThinking = LlmEngine::setThinking, log = log)
    }

    /** The laptop when it's there, else the on-device model. */
    private object Routed : Brain {
        override val ready: Boolean get() = LaptopBrain.ready || LlmEngine.ready

        override suspend fun complete(prompt: String, timeoutMs: Long): String? =
            if (LaptopBrain.ready) LaptopBrain.complete(prompt, timeoutMs) else LlmEngine.complete(prompt, timeoutMs)
    }
}

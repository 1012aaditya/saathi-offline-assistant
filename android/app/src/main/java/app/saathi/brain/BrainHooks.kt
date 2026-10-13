package app.saathi.brain

import android.content.Context
import app.saathi.agent.GuideBrain
import app.saathi.agent.Lang

/** Wires smart understanding into a guide session (TaskRunner's one-line hook). */
object BrainHooks {
    /**
     * The guide's [GuideBrain], or null (rules only, exactly as before) when Smart understanding
     * is off or there is no model file. Starts loading the model in the background; until it is
     * ready, every utterance goes to the rule parser.
     */
    fun forGuide(context: Context, lang: Lang, log: (String) -> Unit): GuideBrain? {
        LlmEngine.attach(context)
        if (!LlmEngine.isOn(context) || LlmEngine.isTooSlow(context) || LlmEngine.modelFile(context) == null) return null
        LlmEngine.prewarm()
        return GuideBrain(LlmEngine, lang, onThinking = LlmEngine::setThinking, log = log)
    }
}

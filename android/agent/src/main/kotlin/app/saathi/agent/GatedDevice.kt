package app.saathi.agent

import app.saathi.safety.Action
import app.saathi.safety.Answer
import app.saathi.safety.AskUser
import app.saathi.safety.CommandContext
import app.saathi.safety.Done
import app.saathi.safety.Element
import app.saathi.safety.Fail
import app.saathi.safety.Kind
import app.saathi.safety.Look
import app.saathi.safety.SafetyGate
import app.saathi.safety.Screen
import app.saathi.safety.Tap
import app.saathi.safety.TypeText
import app.saathi.safety.Verdict

/**
 * The raw device (the Android accessibility service). Only [GatedDevice] may call [perform] and
 * [clearText]; the app hands its actuator to a GatedDevice and keeps no other reference.
 */
interface Actuator {
    /** The current foreground screen, or null when it can't be read. */
    suspend fun snapshot(): Screen?

    /** Run one action on the elements of [screen] (the latest snapshot). False if it couldn't. */
    suspend fun perform(action: Action, screen: Screen): Boolean

    /** Empty an editable field (cancel path: remove the draft Saathi typed). */
    suspend fun clearText(element: Element, screen: Screen): Boolean

    /** True once the screen hash differs from [oldHash] (polling), false on timeout. */
    suspend fun waitForChange(oldHash: String, timeoutMs: Long): Boolean
}

/** What happened to an action at the choke point. */
sealed interface Exec {
    data class Ran(val ok: Boolean) : Exec

    /** Irreversible (send): NOT executed. Needs the read-back spoken/shown and a clear yes. */
    data class Held(val action: Tap, val target: Element, val verdict: Verdict, val packageName: String) : Exec

    data class Blocked(val verdict: Verdict) : Exec
    data class HandOff(val verdict: Verdict) : Exec
}

/**
 * THE choke point (hard rule 1): every device action passes SafetyGate.check() here before it
 * can reach the actuator. ALLOW -> runs. HOLD -> returned with its read-back, runs only via
 * [executeConfirmed] after a YES, re-checked on a fresh screen. BLOCK / HANDOFF -> never runs.
 */
class GatedDevice(val gate: SafetyGate, private val actuator: Actuator) {
    suspend fun snapshot(): Screen? = actuator.snapshot()

    suspend fun waitForChange(oldHash: String, timeoutMs: Long): Boolean = actuator.waitForChange(oldHash, timeoutMs)

    suspend fun execute(
        action: Action,
        screen: Screen,
        ctx: CommandContext,
        beforeRun: suspend () -> Unit = {},
    ): Exec {
        require(action !is Done && action !is Fail && action !is AskUser && action !is Look) {
            "${action.type} is not a device action"
        }
        val v = gate.check(action, screen, ctx)
        return when (v.kind) {
            Kind.ALLOW -> {
                beforeRun()
                Exec.Ran(actuator.perform(action, screen))
            }
            Kind.HOLD -> {
                if (action is Tap) {
                    Exec.Held(action, screen.elements[action.index], v, screen.packageName)
                } else {
                    Exec.Blocked(Verdict(Kind.BLOCK, v.rule, v.message)) // the gate only holds taps
                }
            }
            Kind.BLOCK -> Exec.Blocked(v)
            Kind.HANDOFF -> Exec.HandOff(v)
        }
    }

    /**
     * Run a held tap after the user's answer. Only [Answer.YES] proceeds. The screen is read
     * again and the gate must give the SAME hold and read-back for the same control; anything
     * different (other chat, edited text, control gone) blocks instead of sending.
     */
    suspend fun executeConfirmed(held: Exec.Held, answer: Answer, ctx: CommandContext): Exec {
        if (answer != Answer.YES) return Exec.Blocked(Verdict(Kind.BLOCK, "confirm", "पुष्टि नहीं मिली, मैं नहीं भेजूँगा।"))
        val fresh = actuator.snapshot() ?: return changed("")
        if (fresh.packageName != held.packageName) return changed(held.verdict.rule)
        val t = held.target
        val idx = fresh.elements.indexOfFirst { it.rid == t.rid && it.desc == t.desc && it.text == t.text && it.cls == t.cls }
        if (idx < 0) return changed(held.verdict.rule)
        val v = gate.check(Tap(idx), fresh, ctx)
        if (v.kind != Kind.HOLD || v.rule != held.verdict.rule || v.readback != held.verdict.readback) {
            return if (v.kind == Kind.HANDOFF) Exec.HandOff(v) else changed(held.verdict.rule)
        }
        return Exec.Ran(actuator.perform(Tap(idx), fresh))
    }

    /**
     * Cancel path: empty WhatsApp's message box, but only if it holds exactly the [draft] Saathi
     * typed (never the user's own draft) and the gate would allow typing there (not a secret field).
     */
    suspend fun clearDraft(screen: Screen, draft: String, ctx: CommandContext): Boolean =
        clearDraft(screen, PendingDraft.of("", draft), ctx)

    /**
     * Same, by the write-ahead record (hash, no text). "Holds exactly" means real typed text
     * (Send button showing, not WhatsApp's placeholder) whose hash matches.
     */
    suspend fun clearDraft(screen: Screen, draft: PendingDraft, ctx: CommandContext): Boolean {
        if (screen.packageName != WhatsApp.PKG) return false
        val entry = WhatsApp.entry(screen) ?: return false
        val typed = WhatsApp.typedText(screen)
        if (entry.password || !draft.matches(typed)) return false
        // Same check as typing that text there (R7: never touch a secret field).
        val v = gate.check(TypeText(typed, entry.index), screen, ctx)
        if (v.kind != Kind.ALLOW) return false
        return actuator.clearText(entry, screen)
    }

    private fun changed(rule: String) =
        Exec.Blocked(Verdict(Kind.BLOCK, "changed:$rule", "पढ़कर सुनाने के बाद स्क्रीन बदल गई, मैं नहीं भेजूँगा।"))
}

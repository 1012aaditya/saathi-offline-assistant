package app.saathi.agent

import app.saathi.safety.Action
import app.saathi.safety.CommandContext
import app.saathi.safety.Element
import app.saathi.safety.Kind
import app.saathi.safety.SafetyGate
import app.saathi.safety.Screen
import app.saathi.safety.Tap
import app.saathi.safety.Verdict

/**
 * The ONE choke point between Saathi and other apps (hard rule 1). The accessibility service
 * hands its raw actuator to [attach]; nothing else holds it. Every action goes through
 * [GatedDevice] -> SafetyGate.check() first: ALLOW runs, HOLD comes back with the read-back for
 * the Confirm screen (and only runs after a yes, re-checked), BLOCK / HANDOFF never run.
 */
object DeviceBridge {
    /** Default config: the protected app lists can't be changed; amount cap = 10 (dev rule 6). */
    val gate = SafetyGate()

    @Volatile
    private var device: GatedDevice? = null

    @Volatile
    private var owner: Actuator? = null

    /** Called by [SaathiAccessibilityService] only. */
    internal fun attach(actuator: Actuator) {
        owner = actuator
        device = GatedDevice(gate, actuator)
    }

    /** Only the service instance that attached may detach (an old instance's late onDestroy must not cut off a new one). */
    internal fun detach(actuator: Actuator) {
        if (owner !== actuator) return
        owner = null
        device = null
    }

    val connected: Boolean get() = device != null

    /** The gated device for the task loop, or null when the service is off. */
    fun gated(): GatedDevice? = device

    suspend fun execute(action: Action, screen: Screen, ctx: CommandContext): Exec =
        device?.execute(action, screen, ctx)
            ?: Exec.Blocked(Verdict(Kind.BLOCK, "service", "Accessibility सेवा बंद है।"))

    /**
     * When a tapped text isn't clickable itself, the service clicks its nearest clickable parent
     * (a chat row). That parent is gated too: it must be a tap the gate would ALLOW on its own.
     */
    fun allowsParentTap(parent: Element, packageName: String): Boolean {
        val one = parent.copy(index = 0)
        return gate.check(Tap(0), Screen(packageName, listOf(one), "parent")).kind == Kind.ALLOW
    }
}

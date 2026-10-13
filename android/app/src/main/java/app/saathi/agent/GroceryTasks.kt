package app.saathi.agent

import app.saathi.data.AppLevel
import app.saathi.data.AppState
import app.saathi.safety.CommandContext
import app.saathi.safety.SafetyGate

/**
 * Glue between Settings and the offline Blinkit flow ([OrderFlow]):
 *  - Settings > Apps > "Grocery app": Off = never; Read only = search and read the cart only;
 *    Ask first = the normal flow with the read-back HOLD.
 *  - Settings > Money > "Allow placing orders" (default OFF): off = stop at the read-back.
 *  - Settings > Money > "Spending limit": an order total above it is refused.
 */
object GroceryTasks {
    const val SETTINGS_NAME = "Grocery app"

    /** What [TaskRunner] needs to start a grocery task, or the answer to give instead. */
    class Prepared(val flow: OrderFlow?, val ctx: CommandContext, val early: Outcome?)

    fun policy(state: AppState): OrderPolicy = OrderPolicy(
        level = when (state.appLevels.firstOrNull { it.first == SETTINGS_NAME }?.second) {
            AppLevel.Off -> GroceryLevel.OFF
            AppLevel.ReadOnly -> GroceryLevel.READ_ONLY
            else -> GroceryLevel.ASK_FIRST // the Settings default
        },
        allowOrders = state.allowOrders,
        spendingLimit = state.spendingLimit,
    )

    fun prepare(command: GroceryCommand, state: AppState, gate: SafetyGate, lang: Lang): Prepared {
        val t = OrderTexts(lang)
        val p = policy(state)
        val ctx = OrderFlows.contextFor(command, p)
        OrderFlows.refusal(command, p, t)?.let { return Prepared(null, ctx, Outcome.Failed(it)) }
        OrderFlows.missing(command, t)?.let { return Prepared(null, ctx, Outcome.Asked(it)) }
        return Prepared(OrderFlows.forCommand(command, p, gate, ctx, t), ctx, null)
    }
}

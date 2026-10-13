package app.saathi.ui.screens

import app.saathi.agent.ConfirmRequest
import app.saathi.agent.Lang
import app.saathi.agent.OrderSummary
import app.saathi.agent.OrderTexts

/**
 * The Confirm screen's words when it confirms a Blinkit order (R3 read-back) or emptying the cart,
 * instead of a WhatsApp send. Cart rows and the total are verbatim from the screen (hard rule 4).
 * None of these contains a yes word ([app.saathi.agent.ConfirmDialog]); Cancel stays the default.
 */
class OrderConfirmWords(order: OrderSummary, lang: Lang) {
    private val t = OrderTexts(lang)
    private val empty = order.purpose == OrderSummary.Purpose.EMPTY

    val question: String = if (empty) t.emptyQuestion(order) else t.orderQuestion(order)
    val title: String = if (empty) "Empty your Blinkit cart?" else "Place this Blinkit order?"
    val lines: List<String> = order.lines.map { "${it.label} ×${it.qty} ${it.price}" }
    val total: String = when {
        order.total.isNotEmpty() -> "Total ${order.total}"
        else -> "The total isn't shown on this screen"
    } + if (order.shipment.isNotEmpty()) " · ${order.shipment}" else ""
    val note: String = "Exactly as Blinkit shows it"
    val confirmLabel: String = if (empty) "Remove all" else "Place order"
    val confirmDescription: String =
        if (empty) "Remove everything from your Blinkit cart. Nothing will be ordered."
        else "Place this order on Blinkit. It will really be ordered. You pay and enter PINs yourself."
    val undoing: String = if (empty) "Your cart stays as it is." else "Nothing will be ordered. Your cart stays as it is."
    val cancelDescription: String = "Cancel. This is the default. $undoing"
    val cancelSpoken: String = if (empty) "Cancelling. Not removing anything." else "Cancelling. Not ordering."
    val unsureSpoken: String = if (empty) "I wasn't sure, so I'm cancelling. Not removing anything." else "I wasn't sure, so I'm cancelling. Not ordering."
    val status: String = "Needs your answer. $question Cancel is selected."
    val cancellingStatus: String = "Cancelling — $undoing"
    val nothingDone: String = if (empty) "Nothing was removed." else "Nothing was ordered."

    /** Everything the sheet shows or says (a reply repeating it is an echo, not an answer). */
    val sheetWords: List<String> = listOf(question, title, note, confirmLabel, confirmDescription, cancelDescription, cancelSpoken, unsureSpoken, status, total) + lines

    companion object {
        fun of(req: ConfirmRequest?, lang: Lang): OrderConfirmWords? = req?.order?.let { OrderConfirmWords(it, lang) }
    }
}

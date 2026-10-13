package app.saathi.agent

import app.saathi.safety.Bounds
import app.saathi.safety.Element
import app.saathi.safety.Point
import app.saathi.safety.Screen
import java.security.MessageDigest

/**
 * One accessibility node, as the Android service reads it. Kept free of Android types so the
 * normaliser is tested on the JVM. `handle` is opaque (the service stores its node there).
 * The service never reads a password field's text: it passes null.
 */
class RawNode(
    val className: String?,
    val text: String?,
    val desc: String?,
    val hint: String?,
    val resourceId: String?,
    val bounds: Bounds,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val password: Boolean = false,
    val visible: Boolean = true,
    val showingHint: Boolean = false,
    val children: List<RawNode> = emptyList(),
    val handle: Any? = null,
)

/** A normalised screen plus, per element index, the node handle it came from. */
class Snapshot(val screen: Screen, val handles: List<Any?>)

/**
 * Port of saathi/device.py normalize(): flatten the tree into visible, useful elements in
 * screen-reading (pre-)order, incl. WhatsApp message sender tagging (from=me / from=them).
 */
object Normalizer {
    const val MAX_ELEMENTS = 150

    // Icon fonts put glyphs in the Unicode private use area.
    private val PRIVATE_USE = Regex("[-]")
    private val SPACES = Regex("\\s+")

    // WhatsApp message rows: a row containing a delivery-status tick (rid=status) is the user's
    // own message; rows without one were received. Verified on real chats (device.py).
    private val MESSAGE_ROW_RIDS = setOf("conversation_row_text")
    private val MESSAGE_RIDS = setOf("message_text")

    fun shortRid(rid: String?): String = (rid ?: "").substringAfterLast('/')

    fun clean(s: String?): String = PRIVATE_USE.replace(s ?: "", "").trim().split(SPACES).filter { it.isNotEmpty() }.joinToString(" ")

    fun normalize(
        roots: List<RawNode>,
        packageName: String,
        keyboardVisible: Boolean = false,
        maxElements: Int = MAX_ELEMENTS,
    ): Snapshot {
        val out = mutableListOf<Element>()
        val handles = mutableListOf<Any?>()

        fun walk(node: RawNode, sender0: String) {
            if (out.size >= maxElements) return
            var sender = sender0
            val rid = shortRid(node.resourceId)
            if (rid in MESSAGE_ROW_RIDS) sender = if (hasRid(node, "status")) "me" else "them"
            val b = node.bounds
            val visible = node.visible && b.x2 > b.x1 && b.y2 > b.y1
            if (visible) {
                val cls = (node.className ?: "").substringAfterLast('.')
                val editable = node.editable || cls == "EditText" || cls == "AutoCompleteTextView"
                // A password field's text is never read (hard rule 2). A hint is not user input.
                var text = if (node.password || node.showingHint || (node.hint != null && node.text == node.hint)) "" else clean(node.text)
                val desc = clean(node.desc).ifEmpty { if (editable) clean(node.hint) else "" }
                val clickable = node.clickable
                var wrapper = false
                if (clickable && text.isEmpty() && desc.isEmpty()) {
                    // A container wrapping other buttons is noise; a leaf card borrows its texts.
                    wrapper = hasClickableDescendant(node)
                    if (!wrapper) text = descendantText(node)
                }
                if (!wrapper && (clickable || editable || text.isNotEmpty() || desc.isNotEmpty())) {
                    out += Element(
                        index = out.size,
                        cls = cls,
                        text = text,
                        desc = desc,
                        rid = rid,
                        clickable = clickable,
                        editable = editable,
                        password = node.password,
                        bounds = b,
                        center = Point((b.x1 + b.x2) / 2, (b.y1 + b.y2) / 2),
                        sender = if (rid in MESSAGE_RIDS) sender else "",
                    )
                    handles += node.handle
                }
            }
            for (child in node.children) walk(child, sender)
        }

        for (root in roots) walk(root, "")
        return Snapshot(Screen(packageName, out.toList(), screenHash(packageName, out), keyboardVisible), handles.toList())
    }

    /** SYSTEM_DESIGN section 5: hash of (package, sorted visible texts). */
    fun screenHash(packageName: String, elements: List<Element>): String {
        val texts = elements.flatMap { listOf(it.text, it.desc) }.filter { it.isNotEmpty() }.toSortedSet()
        val md = MessageDigest.getInstance("SHA-1")
        md.update(packageName.toByteArray())
        for (t in texts) {
            md.update(0)
            md.update(t.toByteArray())
        }
        return md.digest().joinToString("") { "%02x".format(it) }.take(12)
    }

    private fun hasRid(node: RawNode, rid: String): Boolean =
        shortRid(node.resourceId) == rid || node.children.any { hasRid(it, rid) }

    private fun hasClickableDescendant(node: RawNode): Boolean =
        node.children.any { it.clickable || hasClickableDescendant(it) }

    /** Label for an unlabeled clickable container, borrowed from the texts inside it. */
    private fun descendantText(node: RawNode, limit: Int = 80): String {
        val parts = mutableListOf<String>()
        val stack = ArrayDeque(node.children)
        while (stack.isNotEmpty() && parts.joinToString(" · ").length < limit) {
            val child = stack.removeFirst()
            val label = (if (child.password) "" else clean(child.text)).ifEmpty { clean(child.desc) }
            if (label.isNotEmpty() && label !in parts) parts += label
            child.children.asReversed().forEach { stack.addFirst(it) }
        }
        return parts.joinToString(" · ").take(limit)
    }
}

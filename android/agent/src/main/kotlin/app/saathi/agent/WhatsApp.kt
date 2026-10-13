package app.saathi.agent

import app.saathi.safety.Apps
import app.saathi.safety.Element
import app.saathi.safety.Screen
import app.saathi.safety.WHATSAPP_SEND_LABELS
import app.saathi.safety.WHATSAPP_SEND_RIDS

/**
 * WhatsApp screen knowledge, from real dumps (tests/fixtures/raw, 12 Oct): resource ids only.
 * Chat list rows: rid=conversations_row_contact_name (+ conversations_row_message_count with
 * desc "<N> unread messages" if unread). Chat: rid=conversation_contact_name (title),
 * rid=entry (message box), rid=message_text inside rid=conversation_row_text rows.
 */
object WhatsApp {
    const val PKG = Apps.WHATSAPP
    const val ROW_NAME = "conversations_row_contact_name"
    const val ROW_COUNT = "conversations_row_message_count"
    const val FILTER_UNREAD = "conversations_filter_debug_view_id_unread"
    const val SEARCH_BAR = "search_bar_inner_layout"
    const val CHAT_TITLE = "conversation_contact_name"
    const val ENTRY = "entry"
    const val MESSAGE = "message_text"

    /** Rows that carry a person's / group's name (chat list, search results, contact results). */
    val NAME_RIDS = setOf(ROW_NAME, "contactpicker_row_name")

    private val COUNT = Regex("(\\d+)\\s+unread", RegexOption.IGNORE_CASE)
    private val SCROLL_BOTTOM = Regex("scroll to bottom|नीचे जाएं|सबसे नीचे", RegexOption.IGNORE_CASE)

    fun isWhatsApp(s: Screen): Boolean = s.packageName == PKG

    fun byRid(s: Screen, rid: String): Element? = s.elements.firstOrNull { it.rid == rid }

    fun onChatList(s: Screen): Boolean =
        isWhatsApp(s) && s.elements.any { it.rid == SEARCH_BAR || it.rid == FILTER_UNREAD }

    /** The open chat's title (contact or group name), or null when not inside a chat. */
    fun chatTitle(s: Screen): Element? =
        if (!isWhatsApp(s)) null else s.elements.lastOrNull { it.rid == CHAT_TITLE && it.text.isNotEmpty() }

    /** The message box; the gate reads the same element (last rid=entry wins, like safety.py). */
    fun entry(s: Screen): Element? = s.elements.lastOrNull { it.rid == ENTRY && it.editable }

    /**
     * What the user (or Saathi) has actually typed in the message box. WhatsApp reports its grey
     * placeholder ("Message", or the localised word) as the box's text without the hint flag, so
     * a non-empty box only counts as real text while the Send button is showing; an empty box
     * shows the voice-note button instead (seen on the owner's tablet, 13 Oct).
     */
    fun typedText(s: Screen): String {
        val e = entry(s) ?: return ""
        return if (e.text.isNotEmpty() && sendButton(s) != null) e.text else ""
    }

    fun sendButton(s: Screen): Element? = s.elements.firstOrNull {
        it.rid in WHATSAPP_SEND_RIDS || it.desc.lowercase() in WHATSAPP_SEND_LABELS
    }

    fun scrollToBottom(s: Screen): Element? = s.elements.firstOrNull {
        it.clickable && (SCROLL_BOTTOM.containsMatchIn(it.desc) || it.rid.contains("scroll_bottom"))
    }

    /** The bottom-most visible message received from the other side, verbatim. */
    fun lastFromThem(s: Screen): Element? =
        s.elements.filter { it.rid == MESSAGE && it.sender == "them" && it.text.isNotEmpty() }
            .maxWithOrNull(compareBy<Element> { it.bounds.y1 }.thenBy { it.index })

    fun nameRows(s: Screen): List<Element> =
        s.elements.filter { it.rid in NAME_RIDS && it.text.isNotEmpty() && !it.editable }

    // -- unread (port of saathi/whatsapp.py) ------------------------------------------------------

    fun unreadFilter(s: Screen): Element? = byRid(s, FILTER_UNREAD)

    fun filterSelected(e: Element): Boolean {
        val d = e.desc.lowercase()
        return "unselected" !in d && "selected" in d
    }

    fun allFilter(s: Screen): Element? = s.elements.firstOrNull { it.desc.startsWith("All filter") }

    /** (contact name, unread count) for visible rows, top to bottom, names verbatim. */
    fun unreadRows(s: Screen): List<Pair<String, Int>> {
        val names = s.elements.filter { it.rid == ROW_NAME && it.text.isNotEmpty() }
        val rows = mutableListOf<Pair<String, Int>>()
        for (c in s.elements) {
            if (c.rid != ROW_COUNT) continue
            val m = COUNT.find(c.desc.ifEmpty { c.text }) ?: continue
            val above = names.filter { it.bounds.y1 <= c.bounds.y1 && c.bounds.y1 - it.bounds.y1 < 140 }
            val name = above.maxByOrNull { it.bounds.y1 } ?: continue
            rows += name.text to (m.groupValues[1].toIntOrNull() ?: continue)
        }
        return rows
    }
}

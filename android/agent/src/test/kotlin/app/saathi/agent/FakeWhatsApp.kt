package app.saathi.agent

import app.saathi.safety.Action
import app.saathi.safety.Back
import app.saathi.safety.Bounds
import app.saathi.safety.Element
import app.saathi.safety.Home
import app.saathi.safety.OpenApp
import app.saathi.safety.Point
import app.saathi.safety.Screen
import app.saathi.safety.Scroll
import app.saathi.safety.Tap
import app.saathi.safety.TypeText

/** Synthetic element / screen builders. All names and texts here are made up. */
object S {
    fun el(
        i: Int,
        text: String = "",
        desc: String = "",
        rid: String = "",
        cls: String = "TextView",
        clickable: Boolean = false,
        editable: Boolean = false,
        password: Boolean = false,
        sender: String = "",
        y: Int = i * 60,
    ) = Element(
        index = i, cls = cls, text = text, desc = desc, rid = rid, clickable = clickable, editable = editable,
        password = password, bounds = Bounds(0, y, 1000, y + 50), center = Point(500, y + 25), sender = sender,
    )

    fun screen(pkg: String, els: List<Element>): Screen = Screen(pkg, els, Normalizer.screenHash(pkg, els))

    /** Re-index a list of element specs in order. */
    fun build(pkg: String, vararg specs: (Int) -> Element): Screen = screen(pkg, specs.mapIndexed { i, f -> f(i) })
}

data class Msg(val sender: String, val text: String)

/**
 * A tiny WhatsApp simulator behind the [Actuator] interface: chat list (with Unread / All filter
 * chips), search, chats with received ("them") and sent ("me") messages, a message box and send.
 * It records every performed action so tests can prove what did (and didn't) run.
 */
class FakeWhatsApp(
    val chats: MutableMap<String, MutableList<Msg>> = linkedMapOf(),
    val unread: MutableMap<String, Int> = mutableMapOf(),
) : Actuator {
    sealed interface View {
        data object List : View
        data object Search : View
        data class Chat(val name: String) : View
    }

    var pkg = "app.saathi"
    var view: View = View.List
    var unreadFilterOn = false
    var query = ""
    val drafts = mutableMapOf<String, String>()
    val sent = mutableListOf<Pair<String, String>>()
    val performed = mutableListOf<Action>()
    val cleared = mutableListOf<String>()
    var pinScreen = false
    /** Real WhatsApp (owner's tablet) reports its "Message" placeholder as the empty box's text. */
    var placeholderAsText = true

    /** Accessibility service gone (unbound / reconnecting): nothing can be read or done. */
    var unavailable = false

    /** Hook to change the world between the read-back and the send (e.g. user edits the text). */
    var onSnapshot: (FakeWhatsApp) -> Unit = {}

    fun render(): Screen {
        val els = mutableListOf<Element>()
        fun add(f: (Int) -> Element) { els += f(els.size) }
        if (pinScreen) {
            add { S.el(it, text = "Enter UPI PIN") }
            return S.screen(WhatsApp.PKG, els)
        }
        if (pkg != WhatsApp.PKG) {
            add { S.el(it, text = "Stop", cls = "Button", clickable = true) }
            return S.screen(pkg, els)
        }
        when (val v = view) {
            View.List -> {
                add { S.el(it, desc = "Search", rid = WhatsApp.SEARCH_BAR, clickable = true, y = 100) }
                add { S.el(it, desc = "All filter, ${if (unreadFilterOn) "unselected" else "selected"}", cls = "RadioButton", clickable = true, y = 200) }
                add { S.el(it, desc = "Unread filter, ${unread.values.sum()}, ${if (unreadFilterOn) "selected" else "unselected"}", rid = WhatsApp.FILTER_UNREAD, cls = "RadioButton", clickable = true, y = 200) }
                var y = 300
                for (name in chats.keys) {
                    val n = unread[name] ?: 0
                    if (unreadFilterOn && n == 0) continue
                    val rowY = y
                    add { S.el(it, text = name, rid = WhatsApp.ROW_NAME, y = rowY) }
                    if (n > 0) add { S.el(it, desc = "$n unread messages", rid = WhatsApp.ROW_COUNT, y = rowY + 45) }
                    y += 120
                }
            }
            View.Search -> {
                add { S.el(it, text = query, rid = "search_input", cls = "EditText", editable = true, clickable = true, y = 100) }
                if (query.isNotEmpty()) {
                    var y = 300
                    for (name in chats.keys.filter { it.contains(query, ignoreCase = true) }) {
                        val rowY = y
                        add { S.el(it, text = name, rid = WhatsApp.ROW_NAME, y = rowY) }
                        y += 120
                    }
                }
            }
            is View.Chat -> {
                add { S.el(it, desc = "Navigate up", rid = "whatsapp_toolbar_home", cls = "Button", clickable = true, y = 40) }
                add { S.el(it, text = v.name, rid = WhatsApp.CHAT_TITLE, y = 45) }
                var y = 200
                for (m in chats[v.name].orEmpty()) {
                    val rowY = y
                    add { S.el(it, text = m.text, rid = WhatsApp.MESSAGE, sender = m.sender, y = rowY) }
                    y += 80
                }
                val draft = drafts[v.name].orEmpty()
                val boxText = if (draft.isEmpty() && placeholderAsText) "Message" else draft
                add { S.el(it, text = boxText, rid = WhatsApp.ENTRY, cls = "EditText", editable = true, clickable = true, y = 1850) }
                if (draft.isNotEmpty()) {
                    add { S.el(it, desc = "Send", rid = "send", cls = "ImageButton", clickable = true, y = 1850) }
                } else {
                    add { S.el(it, desc = "Voice message", rid = "voice_note_btn", cls = "ImageButton", y = 1850) }
                }
            }
        }
        return S.screen(pkg, els)
    }

    override suspend fun snapshot(): Screen? {
        onSnapshot(this)
        return if (unavailable) null else render()
    }

    override suspend fun perform(action: Action, screen: Screen): Boolean {
        if (unavailable) return false
        check(screen.hash == render().hash) { "acted on a stale screen" }
        performed += action
        when (action) {
            is OpenApp -> pkg = app.saathi.safety.Apps.APP_ALIASES.getValue(action.app)
            is Back -> when (view) {
                View.List -> pkg = "launcher"
                else -> view = View.List
            }
            is Home -> pkg = "launcher"
            is Scroll -> return true
            is Tap -> {
                val e = screen.elements[action.index]
                when {
                    e.rid == WhatsApp.SEARCH_BAR -> view = View.Search
                    e.rid == WhatsApp.ROW_NAME -> {
                        view = View.Chat(e.text)
                        unread.remove(e.text)
                    }
                    e.rid == WhatsApp.FILTER_UNREAD -> unreadFilterOn = true
                    e.desc.startsWith("All filter") -> unreadFilterOn = false
                    e.rid == "send" -> {
                        val chat = (view as View.Chat).name
                        val text = drafts.remove(chat).orEmpty()
                        sent += chat to text
                        chats.getValue(chat) += Msg("me", text)
                    }
                    e.rid == "whatsapp_toolbar_home" -> view = View.List
                    else -> return false
                }
            }
            is TypeText -> {
                val e = screen.elements[action.index!!]
                when (e.rid) {
                    WhatsApp.ENTRY -> drafts[(view as View.Chat).name] = action.text
                    "search_input" -> query = action.text
                    else -> return false
                }
            }
            else -> return false
        }
        return true
    }

    override suspend fun clearText(element: Element, screen: Screen): Boolean {
        if (unavailable) return false
        val chat = (view as? View.Chat)?.name ?: return false
        cleared += chat
        drafts.remove(chat)
        return true
    }

    override suspend fun waitForChange(oldHash: String, timeoutMs: Long): Boolean = !unavailable && render().hash != oldHash
}

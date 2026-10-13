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

        /** A WhatsApp sub-screen (starred messages, settings …); [hasUp]: it has a "Navigate up" button. */
        data class Sub(val hasUp: Boolean) : View
    }

    // -- timing (13 Oct, 22:50): a virtual clock, advanced only by waitForChange ------------------

    var now = 0L

    /** ms from an OpenApp until WhatsApp is in front, per OpenApp (the last value repeats). 0 = at once. */
    var openDelaysMs: List<Long> = listOf(0L)

    /** ms WhatsApp shows its first, empty frame after coming to the front. Back there closes WhatsApp. */
    var splashMs = 0L

    /** Saathi's own screen changes ~100 ms after each OpenApp (its narration line), like the real Working screen. */
    var ownScreenChanges = false

    /** Back on a [View.Sub] closes WhatsApp (it was the root of WhatsApp's task); reopening shows it again. */
    var subIsRoot = false

    private var opensDone = 0
    private var appearAt: Long? = null
    private var splashUntil = 0L
    private var ownChangeAt: Long? = null
    private var ownTick = 0

    /** Apply what has happened by [now]. */
    private fun sync() {
        appearAt?.let { if (now >= it) { pkg = WhatsApp.PKG; splashUntil = it + splashMs; appearAt = null } }
        ownChangeAt?.let { if (now >= it) { ownTick++; ownChangeAt = null } }
    }

    private val splashing: Boolean get() = pkg == WhatsApp.PKG && now < splashUntil

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

    /** WhatsApp contacts with no chat yet: only search finds them; tapping one opens a new, empty chat. */
    val contacts = mutableListOf<String>()

    /** Search results grouped under "Chats" / "Contacts" / "Messages" headers (newer WhatsApp). */
    var searchHeaders = false

    /** Message hits shown under "Messages" in search: (chat name, message text). */
    val messageHits = mutableListOf<Pair<String, String>>()

    /** Resource id of contact rows in search (same as chat rows in the dumps seen so far). */
    var contactRid = WhatsApp.ROW_NAME

    /** Accessibility service gone (unbound / reconnecting): nothing can be read or done. */
    var unavailable = false

    /** Hook to change the world between the read-back and the send (e.g. user edits the text). */
    var onSnapshot: (FakeWhatsApp) -> Unit = {}

    fun render(): Screen {
        sync()
        val els = mutableListOf<Element>()
        fun add(f: (Int) -> Element) { els += f(els.size) }
        if (splashing) {
            add { S.el(it, text = "WhatsApp", y = 900) } // the first frame: no chat list yet
            return S.screen(WhatsApp.PKG, els)
        }
        if (pinScreen) {
            add { S.el(it, text = "Enter UPI PIN") }
            return S.screen(WhatsApp.PKG, els)
        }
        if (pkg != WhatsApp.PKG) {
            add { S.el(it, text = "Stop", cls = "Button", clickable = true) }
            if (ownScreenChanges) add { S.el(it, text = "Working, line $ownTick") }
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
                    fun header(text: String) {
                        val hy = y
                        add { S.el(it, text = text, y = hy) }
                        y += 80
                    }
                    fun rows(names: List<String>, rid: String = WhatsApp.ROW_NAME) {
                        for (name in names) {
                            val rowY = y
                            add { S.el(it, text = name, rid = rid, y = rowY) }
                            y += 120
                        }
                    }
                    val chatHits = chats.keys.filter { it.contains(query, ignoreCase = true) }
                    val contactHits = contacts.filter { it.contains(query, ignoreCase = true) && it !in chats.keys }
                    val msgHits = messageHits.filter { it.second.contains(query, ignoreCase = true) }
                    if (searchHeaders && chatHits.isNotEmpty()) header("Chats")
                    rows(chatHits)
                    if (searchHeaders && contactHits.isNotEmpty()) header("Contacts")
                    rows(contactHits, rid = contactRid)
                    if (msgHits.isNotEmpty()) {
                        header("Messages")
                        rows(msgHits.map { it.first })
                    }
                }
            }
            is View.Sub -> {
                if (v.hasUp) add { S.el(it, desc = "Navigate up", rid = "whatsapp_toolbar_home", cls = "Button", clickable = true, y = 40) }
                add { S.el(it, text = "Starred messages", y = 45) }
                add { S.el(it, desc = "More options", cls = "ImageButton", clickable = true, y = 45) }
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
                // The box holds raw text (line breaks kept); the screen shows it collapsed, like Normalizer.
                val draft = Normalizer.clean(drafts[v.name].orEmpty())
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
            is OpenApp -> {
                val target = app.saathi.safety.Apps.APP_ALIASES.getValue(action.app)
                val delay = openDelaysMs.getOrElse(opensDone) { openDelaysMs.last() }
                opensDone++
                if (ownScreenChanges && pkg != WhatsApp.PKG) ownChangeAt = now + 100
                if (target != WhatsApp.PKG || (delay == 0L && splashMs == 0L)) pkg = target
                else if (pkg != WhatsApp.PKG && appearAt == null) appearAt = now + delay
            }
            is Back -> when {
                splashing -> pkg = "app.saathi" // Back on WhatsApp's first frame closes it
                view is View.Sub && subIsRoot -> pkg = "app.saathi"
                view == View.List -> pkg = "launcher"
                else -> view = View.List
            }
            is Home -> pkg = "launcher"
            is Scroll -> return true
            is Tap -> {
                val e = screen.elements[action.index]
                when {
                    e.rid == WhatsApp.SEARCH_BAR -> view = View.Search
                    e.rid == WhatsApp.ROW_NAME || e.rid == "contactpicker_row_name" -> {
                        if (e.text !in chats) chats[e.text] = mutableListOf() // a contact without a chat: new chat
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

    /** Raw message-box text, like AccessibilityNodeInfo.getText() (placeholder when empty). */
    override suspend fun fieldText(element: Element, screen: Screen): String? {
        if (unavailable) return null
        val chat = (view as? View.Chat)?.name ?: return null
        if (element.rid != WhatsApp.ENTRY) return null
        return drafts[chat]?.takeIf { it.isNotEmpty() } ?: if (placeholderAsText) "Message" else ""
    }

    /** Advances the virtual clock to the next event (WhatsApp appearing, its first frame ending, Saathi's narration). */
    override suspend fun waitForChange(oldHash: String, timeoutMs: Long): Boolean {
        if (unavailable) return false
        if (render().hash != oldHash) return true
        val deadline = now + timeoutMs
        while (true) {
            val next = listOfNotNull(appearAt, ownChangeAt, splashUntil.takeIf { it > now }).filter { it > now && it <= deadline }.minOrNull()
            if (next == null) {
                now = deadline
                return false
            }
            now = next
            if (render().hash != oldHash) return true
        }
    }
}

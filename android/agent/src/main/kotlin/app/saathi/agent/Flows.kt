package app.saathi.agent

import app.saathi.safety.Action
import app.saathi.safety.Back
import app.saathi.safety.CommandContext
import app.saathi.safety.Direction
import app.saathi.safety.Element
import app.saathi.safety.OpenApp
import app.saathi.safety.SafetyGate
import app.saathi.safety.Screen
import app.saathi.safety.Scroll
import app.saathi.safety.Tap
import app.saathi.safety.TypeText
import app.saathi.safety.payeeMatches

/** What a flow wants next, given the current screen. Actions still go through the safety gate. */
sealed interface Plan {
    /** Do this device action (narrate `say` only once the gate allowed it). */
    data class Act(val action: Action, val say: String) : Plan

    /**
     * Finished with an answer. Every string in `quotes` must be verbatim on [source] (or on the
     * current screen when null), or the loop refuses it (hard rule 4).
     */
    data class Done(
        val answer: String,
        val quotes: List<String> = emptyList(),
        val title: String = "",
        val quote: String? = null,
        val source: Screen? = null,
        /** An extra line shown under a quoted answer (already part of [answer]). */
        val note: String? = null,
    ) : Plan

    /**
     * The chat's message box already holds the user's own text ([existing], verbatim on screen).
     * The loop asks the user (keep / replace / add / send it) and tells the flow via [Flow.chose].
     */
    data class ChooseDraft(val existing: String, val newMessage: String) : Plan

    /**
     * Several people / groups match the name the user said ([names] verbatim on screen). The loop
     * asks which one (voice or buttons) and tells the flow via [Flow.choseContact]; unclear twice = stop.
     */
    data class ChooseContact(val spoken: String, val names: List<String>) : Plan

    data class Ask(val question: String) : Plan
    data class HandOver(val reason: String) : Plan
    data class Fail(val reason: String) : Plan
}

/** A step-by-step task as a small state machine: next(screen) -> Plan. Pure: no I/O. */
interface Flow {
    /** Read-only flows (screen summary) may never act; the loop enforces it. */
    val readOnly: Boolean get() = false

    /** Text Saathi typed and hasn't sent; the loop clears it on cancel / failure / stop. */
    val draft: String? get() = null

    /** What Saathi did to the box ([DraftMode.REPLACE] / [DraftMode.ADD] change the user's own text). */
    val draftMode: DraftMode get() = DraftMode.NEW

    /** The user's own text that was in the box before Saathi changed it (REPLACE / ADD), else null. */
    val original: String? get() = null

    /** What a No / Cancel on the send confirmation does to the box. */
    val afterCancel: AfterCancel get() = AfterCancel.REMOVE_TYPED

    /** The user's answer to a [Plan.ChooseDraft] (never KEEP: the loop ends the task for that). */
    fun chose(choice: DraftChoice, original: String) {}

    /** The user's answer to a [Plan.ChooseContact]: exactly one of its names. */
    fun choseContact(name: String) {}

    /**
     * The safety names (R5) the flow works with now, when it changes them itself (a shorter
     * reading of the spoken name, the user's own chat); the loop's gate context follows them.
     * Null: the flow never changes them.
     */
    val contextNames: List<String>? get() = null

    fun next(screen: Screen): Plan

    /** Called after an action really ran (allowed by the gate, or held and confirmed). */
    fun executed(action: Action, changed: Boolean) {}
}

private fun norm(s: String): String = s.trim().split(Regex("\\s+")).joinToString(" ")

/**
 * Shared navigation: open WhatsApp -> chat list -> find the person -> open the chat. Returns null
 * once the right chat is open.
 *
 * On the chat list only an exact name / spelling is taken; anything else is searched, because a
 * partial match there ("Rahul Sharma" for "Rahul") could hide another Rahul further down or a
 * contact without a chat. WhatsApp's search shows chats AND contacts ([ContactSearch]); a contact
 * without a chat opens a new chat. [queries] are tried in turn (saved name, spoken name, other
 * spellings), then the shorter readings of the name in [fallbacks] ("Aaditya Goel" -> "Aaditya"),
 * longest first. Several matches -> [Plan.ChooseContact]; none -> an honest failure, never a guess.
 *
 * Getting there (13 Oct, 22:50: "open_app, back, open_app" then "I couldn't open that app."):
 * an unknown WhatsApp screen right after opening may still be drawing its chat list, so Saathi
 * looks again before pressing Back (Back on WhatsApp's first screen closes WhatsApp). If a Back
 * did close it, Saathi reopens it and from then on leaves sub-screens only with WhatsApp's own
 * "Navigate up" button, or stops and says so (never a loop of reopening).
 */
/** One reading of a spoken name: what to search, the safety names (R5) that go with it, and its message. */
class ChatTarget(val contact: String, val queries: List<String>, val inPhone: Boolean, val names: List<String>, val text: String? = null)

internal class ChatOpener(
    contact: String,
    private val ctx: CommandContext,
    private val t: Texts,
    queries: List<String> = listOf(contact),
    /** The phone's contacts know this person (so "not found" can say "may not be on WhatsApp"). */
    inPhone: Boolean = false,
    fallbacks: List<ChatTarget> = emptyList(),
) {
    private val targets = listOf(ChatTarget(contact, queries.ifEmpty { listOf(contact) }, inPhone, ctx.names.toList())) + fallbacks
    private var target = 0
    private var query = 0
    private var searchTapped = false
    private var searchTyped = false
    private var backs = 0
    private var opens = 0
    private var unknownWaits = 0
    private var lastWasBack = false

    /** A Back from inside WhatsApp closed it: WhatsApp's own Up button from now on. */
    private var backClosedWhatsApp = false

    /** The exact on-screen name the user picked when several matched (null: not asked). */
    private var chosen: String? = null

    /** Which reading of the name is being looked for (the first = the longest / as said). */
    val current: ChatTarget get() = targets[target]

    private val contact: String get() = targets[target].contact
    private val self: Boolean get() = contact == Names.SELF

    /** The safety names (R5) in use now; the loop's gate context follows them ([Flow.contextNames]). */
    val names: List<String> get() = ctx.names

    fun matches(name: String): Boolean =
        if (self) WhatsApp.isSelfChat(name) || ctx.names.any { it == name || it == WhatsApp.personName(name) }
        else payeeMatches(ctx, name) || payeeMatches(ctx, WhatsApp.personName(name))

    /** The user picked [name] (verbatim on screen): only that chat counts now, for R5 too. */
    fun choose(name: String) {
        chosen = name
        ctx.names = listOf(name)
    }

    fun step(screen: Screen): Plan? {
        if (!WhatsApp.isWhatsApp(screen)) {
            if (lastWasBack) backClosedWhatsApp = true // our own Back left WhatsApp
            lastWasBack = false
            unknownWaits = 0
            if (++opens > MAX_OPENS) return Plan.Fail(t.appCouldNotOpen("WhatsApp"))
            return Plan.Act(OpenApp("whatsapp"), t.opening("WhatsApp"))
        }
        lastWasBack = false
        val title = WhatsApp.chatTitle(screen)
        if (title != null) {
            unknownWaits = 0
            val right = chosen?.let { title.text == it } ?: matches(title.text)
            if (right) return null
            return leave(screen, t.leavingOtherChat)
        }
        if (searchTyped) {
            return when (val p = ContactSearch.pick(screen, contact, ctx, chosen)) {
                is ContactSearch.Pick.One -> open(p.element)
                is ContactSearch.Pick.Many -> Plan.ChooseContact(contact, p.names)
                is ContactSearch.Pick.Same -> Plan.Fail(t.sameName(p.name))
                ContactSearch.Pick.None -> nextQuery(screen) ?: Plan.Fail(notFound())
            }
        }
        if (searchTapped) {
            val field = screen.elements.firstOrNull { it.editable && !it.password }
                ?: return Plan.Fail(notFound())
            searchTyped = true
            return Plan.Act(TypeText(targets[target].queries[query], field.index), t.searching(said(contact)))
        }
        if (WhatsApp.onChatList(screen)) {
            unknownWaits = 0
            when (val p = ContactSearch.pick(screen, contact, ctx, chosen, exactOnly = true)) {
                is ContactSearch.Pick.One -> return open(p.element)
                is ContactSearch.Pick.Many -> return Plan.ChooseContact(contact, p.names)
                is ContactSearch.Pick.Same -> return Plan.Fail(t.sameName(p.name))
                ContactSearch.Pick.None -> Unit
            }
            val bar = WhatsApp.byRid(screen, WhatsApp.SEARCH_BAR) ?: return Plan.Fail(notFound())
            searchTapped = true
            return Plan.Act(Tap(bar.index), t.searching(said(contact)))
        }
        // Not the chat list, not a chat. Nothing to tap yet = WhatsApp is still drawing its first
        // screen: wait (Back there would close it), and say so if it never finishes. Otherwise look
        // once more, then back out (contact info, another tab, a sheet).
        val loading = screen.elements.none { it.clickable || it.editable }
        if (unknownWaits < (if (loading) MAX_LOADING_WAITS else MAX_UNKNOWN_WAITS)) {
            unknownWaits++
            return WaitPlan(UNKNOWN_WAIT_MS)
        }
        if (loading) return Plan.Fail(t.appCouldNotOpen("WhatsApp"))
        return leave(screen, t.backToChats)
    }

    /** Tap a person's row. The user's own chat: its exact on-screen name becomes the safety name (R5). */
    private fun open(row: Element): Plan {
        if (self && WhatsApp.isSelfChat(row.text)) ctx.names = listOf(row.text, WhatsApp.personName(row.text)).distinct()
        return Plan.Act(Tap(row.index), t.openingChat(row.text))
    }

    /** "Myself" is said as "your own chat"; other names as said. */
    private fun said(name: String): String = if (name == Names.SELF) t.yourOwnChat else name

    private fun notFound(): String {
        val first = targets.first()
        val picked = chosen
        return when {
            first.contact == Names.SELF -> t.selfChatNotFound
            picked != null -> t.couldntFind(picked)
            first.inPhone -> t.notOnWhatsApp(first.contact)
            else -> t.couldntFind(first.contact)
        }
    }

    /** Nobody under this spelling: type the next one (or the next, shorter reading of the name) into the search box. */
    private fun nextQuery(screen: Screen): Plan? {
        if (chosen != null) return null
        val field = screen.elements.firstOrNull { it.editable && !it.password } ?: return null
        if (query + 1 < targets[target].queries.size) {
            query++
        } else {
            if (target + 1 >= targets.size) return null
            target++
            query = 0
            ctx.names = targets[target].names // R5 follows the reading in use
        }
        val q = targets[target].queries[query]
        return Plan.Act(TypeText(q, field.index), t.searching(if (self) said(contact) else q))
    }

    /**
     * Leave a screen that isn't the right chat. Back, unless a Back already closed WhatsApp once:
     * then WhatsApp's own Up button, or an honest stop.
     */
    private fun leave(screen: Screen, say: String): Plan {
        if (++backs > 4) return Plan.Fail(t.stuck)
        if (backClosedWhatsApp) {
            val up = screen.elements.firstOrNull { it.clickable && it.desc.trim().lowercase() in UP_LABELS }
                ?: return Plan.Fail(t.whatsappStuck)
            return Plan.Act(Tap(up.index), say)
        }
        lastWasBack = true
        return Plan.Act(Back, say)
    }

    companion object {
        /** Opening WhatsApp: tries (the first, and once after a Back closed it) before saying it couldn't be opened. */
        const val MAX_OPENS = 2

        /** Looks at an unknown WhatsApp screen before pressing Back, and how long each look waits. */
        const val MAX_UNKNOWN_WAITS = 2
        const val UNKNOWN_WAIT_MS = 1_000L

        /** WhatsApp's first frame (nothing to tap): waits before saying it didn't open. Never Back there. */
        const val MAX_LOADING_WAITS = 6

        /** WhatsApp's toolbar Up button, as its content description says it. */
        val UP_LABELS = setOf("navigate up", "back", "go back", "ऊपर जाएं", "वापस जाएं", "पीछे जाएं")
    }
}

/** "Read Mom's last message": open the chat, read the newest message FROM THEM, verbatim. */
class ReadLastFlow(contact: String, private val ctx: CommandContext, private val t: Texts, recipient: Recipient? = null) : Flow {
    private val opener = ChatOpener(contact, ctx, t, recipient?.queries ?: Recipients.queries(contact), recipient?.inPhone == true)

    override fun choseContact(name: String) = opener.choose(name)

    override val contextNames: List<String> get() = opener.names
    private var jumped = false
    private var scrolls = 0

    override fun next(screen: Screen): Plan {
        opener.step(screen)?.let { return it }
        val title = WhatsApp.chatTitle(screen)!!
        if (!jumped) {
            WhatsApp.scrollToBottom(screen)?.let {
                jumped = true
                return Plan.Act(Tap(it.index), t.jumpingToLatest)
            }
        }
        // The user's own unsent text in this chat: mention it (never read it unless asked).
        val note = if (WhatsApp.typedText(screen).isNotEmpty()) t.unsentTextHere else null
        fun withNote(answer: String) = if (note == null) answer else "$answer $note"
        val msg = WhatsApp.lastFromThem(screen)
        if (msg != null) {
            return Plan.Done(
                answer = withNote(t.lastMessage(title.text, msg.text)),
                quotes = listOf(title.text, msg.text),
                title = t.lastMessageTitle(title.text),
                quote = msg.text,
                note = note,
            )
        }
        if (scrolls < 3) {
            scrolls++
            return Plan.Act(Scroll(Direction.UP), t.lookingFurther)
        }
        return Plan.Done(withNote(t.noMessageFromThem(title.text)), listOf(title.text), t.lastMessageTitle(title.text), note = note)
    }

    override fun executed(action: Action, changed: Boolean) {
        if (action is Scroll && !changed) scrolls = 3 // top of the chat: nothing older to see
    }
}

/**
 * "Reply to Mom I'll be home by 7": open the chat -> type into the message box -> Tap(send).
 * The gate HOLDs that tap (R2) with a read-back; it only runs after the user's yes.
 *
 * If the box already holds the user's own unsent text, it is never silently overwritten: the
 * flow asks ([Plan.ChooseDraft]) and then, per the answer, replaces it, adds after it, or sends
 * it as it is. Whatever happens, the read-back is of the box's full text, verbatim, and on
 * anything but a yes the user's original text is put back ([afterCancel]).
 */
class ReplyFlow(
    contact: String,
    text: String,
    private val ctx: CommandContext,
    private val t: Texts,
    recipient: Recipient? = null,
    /** Shorter readings of the name, each with its own message text ([Command.Reply.alternatives]). */
    fallbacks: List<ChatTarget> = emptyList(),
) : Flow {
    private val opener = ChatOpener(contact, ctx, t, recipient?.queries ?: Recipients.queries(contact), recipient?.inPhone == true, fallbacks)
    private val primaryText = text

    /** The message for the reading of the name being used ("Aaditya" + "Goel saathi test"). */
    private val text: String get() = opener.current.text ?: primaryText

    override fun choseContact(name: String) = opener.choose(name)

    override val contextNames: List<String> get() = opener.names
    private var choice: DraftChoice? = null // null: the box was empty (a plain reply)
    private var existing = "" // the user's text as the screen showed it when we asked
    private var userOriginal: String? = null // the same text exactly as typed (memory only)
    private var asked = false
    private var typed = false
    private var sendTapped = false
    private var sentTo = ""
    private var sentText = ""
    private var added: String? = null // ADD: the user's text + the new message

    private val toType: String get() = added ?: text

    override val draft: String? get() = if (typed && !sendTapped) toType else null

    override val draftMode: DraftMode
        get() = when (choice) {
            DraftChoice.REPLACE -> DraftMode.REPLACE
            DraftChoice.ADD -> DraftMode.ADD
            else -> DraftMode.NEW
        }

    override val original: String? get() = userOriginal.takeIf { draftMode != DraftMode.NEW }

    override val afterCancel: AfterCancel
        get() = when (choice) {
            DraftChoice.REPLACE, DraftChoice.ADD -> AfterCancel.RESTORE_ORIGINAL
            DraftChoice.SEND -> AfterCancel.LEAVE_AS_IS
            else -> AfterCancel.REMOVE_TYPED
        }

    override fun chose(choice: DraftChoice, original: String) {
        if (choice == DraftChoice.KEEP) return
        this.choice = choice
        userOriginal = original
        if (choice == DraftChoice.ADD) added = "$original $text"
    }

    override fun next(screen: Screen): Plan {
        if (sendTapped) {
            val entry = WhatsApp.entry(screen)
            return if (entry != null && WhatsApp.typedText(screen).isEmpty() || entry == null && WhatsApp.isWhatsApp(screen)) {
                Plan.Done(t.sent(sentTo), quotes = listOf(sentTo).filter { it.isNotEmpty() }, title = t.sentTitle(sentTo), quote = sentText.ifEmpty { text })
            } else {
                Plan.Fail(t.notSureSent)
            }
        }
        opener.step(screen)?.let { return it }
        val entry = WhatsApp.entry(screen) ?: return Plan.Fail(t.cantTypeHere)
        if (entry.password) return Plan.Fail(t.cantTypeHere)
        val box = WhatsApp.typedText(screen)
        if (choice == DraftChoice.SEND) {
            // Send the user's own text as it is: nothing typed; the gate's read-back is that text.
            if (box.isEmpty() || norm(box) != norm(existing)) return Plan.Fail(t.draftChangedMeanwhile)
            return tapSend(screen, t.readyToSendExisting)
        }
        if (!typed) {
            if (choice == null) {
                if (box.isNotEmpty()) {
                    if (asked) return Plan.Fail(t.draftExists) // never overwrite without an answer
                    asked = true
                    existing = box
                    return Plan.ChooseDraft(box, text)
                }
            } else if (box.isEmpty() || norm(box) != norm(existing)) {
                return Plan.Fail(t.draftChangedMeanwhile) // the user's text changed while we asked
            }
            typed = true
            val say = when (choice) {
                DraftChoice.REPLACE -> t.replacing
                DraftChoice.ADD -> t.adding
                else -> t.typing
            }
            return Plan.Act(TypeText(toType, entry.index), say)
        }
        if (norm(entry.text) != norm(toType)) return Plan.Fail(t.typedMismatch)
        return tapSend(screen, t.readyToSend)
    }

    private fun tapSend(screen: Screen, say: String): Plan {
        val send = WhatsApp.sendButton(screen) ?: return Plan.Fail(t.noSendButton)
        sentTo = WhatsApp.chatTitle(screen)?.text.orEmpty()
        sentText = WhatsApp.entry(screen)?.text.orEmpty()
        return Plan.Act(Tap(send.index), say)
    }

    override fun executed(action: Action, changed: Boolean) {
        if ((typed || choice == DraftChoice.SEND) && action is Tap && sentTo.isNotEmpty()) sendTapped = true
    }
}

/** "Who messaged me?" — port of saathi/whatsapp.py unread_next(). */
class UnreadFlow(private val t: Texts) : Flow {
    private var filterTurnedOn = false
    private var summary: Plan.Done? = null
    private var restoreTaps = 0
    private var opens = 0
    private var backs = 0

    override fun next(screen: Screen): Plan {
        if (!WhatsApp.isWhatsApp(screen)) {
            if (++opens > 2) return Plan.Fail(t.couldNotOpen)
            return Plan.Act(OpenApp("whatsapp"), t.opening("WhatsApp"))
        }
        if (!WhatsApp.onChatList(screen)) {
            if (++backs > 4) return Plan.Fail(t.stuck)
            return Plan.Act(Back, t.backToChats) // inside a chat / contact info / search
        }
        val chip = WhatsApp.unreadFilter(screen)
        summary?.let { done ->
            if (filterTurnedOn && chip != null && WhatsApp.filterSelected(chip)) {
                // Filters are a radio group: tapping Unread again doesn't clear it; tap "All".
                val all = WhatsApp.allFilter(screen)
                if (all != null && restoreTaps < 2) {
                    restoreTaps++
                    return Plan.Act(Tap(all.index), t.turningOffUnread)
                }
            }
            return done
        }
        if (chip != null && !WhatsApp.filterSelected(chip)) {
            filterTurnedOn = true
            return Plan.Act(Tap(chip.index), t.turningOnUnread)
        }
        val rows = WhatsApp.unreadRows(screen)
        // Grounded against the screen the names were read from (the filter is undone after).
        summary = Plan.Done(t.unreadSummary(rows), rows.map { it.first }, t.unreadTitle, source = screen)
        return next(screen)
    }
}

/**
 * "What's on screen?" — read-only: list visible texts verbatim, never act. Uses the screen
 * captured when the user held Volume-down in the other app ([captured]), not Saathi's own.
 */
class ScreenSummaryFlow(
    private val captured: Screen?,
    private val appLabel: String,
    private val gate: SafetyGate,
    private val t: Texts,
    private val maxItems: Int = 12,
) : Flow {
    override val readOnly: Boolean get() = true

    override fun next(screen: Screen): Plan {
        val s = captured ?: return Plan.Done(t.noCapturedScreen)
        if (gate.isPinScreen(s)) return Plan.HandOver(t.pinScreenSummary)
        val texts = s.elements.filter { !it.password }
            .flatMap { listOf(it.text, it.desc) }
            .filter { it.isNotBlank() }
            .distinct()
        val shown = texts.take(maxItems)
        return Plan.Done(
            answer = t.screenSummary(appLabel, shown, texts.size - shown.size),
            quotes = shown,
            title = t.screenTitle(appLabel),
            source = s,
        )
    }
}

/** "Open WhatsApp": launch it (alias must be in the safety app list), confirm it's in front. */
class OpenAppFlow(private val alias: String, private val packageName: String, private val label: String, private val t: Texts) : Flow {
    private var tries = 0

    override fun next(screen: Screen): Plan {
        if (screen.packageName == packageName) return Plan.Done(t.opened(label), title = t.opened(label))
        if (++tries > 2) return Plan.Fail(t.couldNotOpen)
        return Plan.Act(OpenApp(alias), t.opening(label))
    }
}

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

    fun next(screen: Screen): Plan

    /** Called after an action really ran (allowed by the gate, or held and confirmed). */
    fun executed(action: Action, changed: Boolean) {}
}

private fun norm(s: String): String = s.trim().split(Regex("\\s+")).joinToString(" ")

/**
 * Shared navigation: open WhatsApp -> chat list -> find the contact (fuzzy, safety port's
 * matcher) -> open the chat. Returns null once the right chat is open.
 */
internal class ChatOpener(private val contact: String, private val ctx: CommandContext, private val t: Texts) {
    private var searchTapped = false
    private var searchTyped = false
    private var backs = 0
    private var opens = 0

    fun matches(name: String): Boolean = payeeMatches(ctx, name)

    fun step(screen: Screen): Plan? {
        if (!WhatsApp.isWhatsApp(screen)) {
            if (++opens > 2) return Plan.Fail(t.couldNotOpen)
            return Plan.Act(OpenApp("whatsapp"), t.opening("WhatsApp"))
        }
        val title = WhatsApp.chatTitle(screen)
        if (title != null) {
            if (matches(title.text)) return null
            return back(t.leavingOtherChat)
        }
        if (searchTyped) {
            return when (val p = pick(screen)) {
                is Pick.One -> Plan.Act(Tap(p.e.index), t.openingChat(p.e.text))
                is Pick.Many -> Plan.Ask(t.whichOne(p.names))
                Pick.None -> Plan.Fail(t.notFound(contact))
            }
        }
        if (searchTapped) {
            val field = screen.elements.firstOrNull { it.editable && !it.password }
                ?: return Plan.Fail(t.notFound(contact))
            searchTyped = true
            return Plan.Act(TypeText(contact, field.index), t.searching(contact))
        }
        if (WhatsApp.onChatList(screen)) {
            when (val p = pick(screen)) {
                is Pick.One -> return Plan.Act(Tap(p.e.index), t.openingChat(p.e.text))
                is Pick.Many -> return Plan.Ask(t.whichOne(p.names))
                Pick.None -> Unit
            }
            val bar = WhatsApp.byRid(screen, WhatsApp.SEARCH_BAR) ?: return Plan.Fail(t.notFound(contact))
            searchTapped = true
            return Plan.Act(Tap(bar.index), t.searching(contact))
        }
        return back(t.backToChats) // contact info, another tab, a sheet: back out to the list
    }

    private fun back(say: String): Plan {
        if (++backs > 4) return Plan.Fail(t.stuck)
        return Plan.Act(Back, say)
    }

    private sealed interface Pick {
        data class One(val e: Element) : Pick
        data class Many(val names: List<String>) : Pick
        data object None : Pick
    }

    /** Exact spoken spelling first, then any exact spelling, then one fuzzy match; several -> ask. */
    private fun pick(screen: Screen): Pick {
        val rows = WhatsApp.nameRows(screen)
        fun one(list: List<Element>): Pick? {
            val names = list.map { it.text }.distinct()
            return when {
                names.isEmpty() -> null
                names.size == 1 -> Pick.One(list.first())
                else -> Pick.Many(names.take(4))
            }
        }
        val spoken = contact.lowercase()
        one(rows.filter { it.text.lowercase() == spoken })?.let { return it }
        val spellings = ctx.names.map { it.lowercase() }.toSet()
        one(rows.filter { it.text.lowercase() in spellings })?.let { return it }
        return one(rows.filter { matches(it.text) }) ?: Pick.None
    }
}

/** "Read Mom's last message": open the chat, read the newest message FROM THEM, verbatim. */
class ReadLastFlow(contact: String, private val ctx: CommandContext, private val t: Texts) : Flow {
    private val opener = ChatOpener(contact, ctx, t)
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
class ReplyFlow(contact: String, private val text: String, private val ctx: CommandContext, private val t: Texts) : Flow {
    private val opener = ChatOpener(contact, ctx, t)
    private var choice: DraftChoice? = null // null: the box was empty (a plain reply)
    private var existing = "" // the user's text as the screen showed it when we asked
    private var userOriginal: String? = null // the same text exactly as typed (memory only)
    private var asked = false
    private var typed = false
    private var sendTapped = false
    private var sentTo = ""
    private var sentText = ""
    private var toType = text

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
        if (choice == DraftChoice.ADD) toType = "$original $text"
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

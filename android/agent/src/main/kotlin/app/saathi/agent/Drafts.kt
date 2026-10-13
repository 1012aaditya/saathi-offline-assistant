package app.saathi.agent

import app.saathi.safety.Back
import app.saathi.safety.CommandContext
import app.saathi.safety.OpenApp
import app.saathi.safety.Screen
import java.security.MessageDigest

/**
 * Write-ahead record of text Saathi typed into a WhatsApp chat and has not sent. Saved BEFORE the
 * text is typed and removed only once the text was sent, cleared, or is provably gone, so a stop,
 * a service disconnect or even process death can't leave a draft the user doesn't know about.
 * Holds no message text: only the chat's exact on-screen title, a hash and the length.
 */
data class PendingDraft(val chat: String, val hash: String, val length: Int, val attempts: Int = 0) {
    /** True when [boxText] is exactly what Saathi typed (whitespace-normalised, like clearDraft). */
    fun matches(boxText: String): Boolean = boxText.isNotEmpty() && hashOf(boxText) == hash

    companion object {
        /** After this many failed later attempts the record is dropped (the user was told each time). */
        const val MAX_ATTEMPTS = 3

        fun of(chat: String, text: String) = PendingDraft(chat, hashOf(text), norm(text).length)

        fun hashOf(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(norm(text).toByteArray()).joinToString("") { "%02x".format(it) }

        internal fun norm(s: String): String = s.trim().split(Regex("\\s+")).joinToString(" ")
    }
}

/**
 * Where pending drafts live: memory in tests, SharedPreferences on the phone. One record per
 * (chat, hash); [put] replaces the record with the same key.
 */
interface DraftStore {
    fun all(): List<PendingDraft>
    fun put(draft: PendingDraft)
    fun remove(draft: PendingDraft)
}

fun PendingDraft.sameAs(o: PendingDraft): Boolean = chat == o.chat && hash == o.hash

class MemoryDraftStore(vararg initial: PendingDraft) : DraftStore {
    private val list = initial.toMutableList()
    override fun all(): List<PendingDraft> = synchronized(list) { list.toList() }
    override fun put(draft: PendingDraft) = synchronized(list) { list.removeAll { it.sameAs(draft) }; list += draft }
    override fun remove(draft: PendingDraft) = synchronized(list) { list.removeAll { it.sameAs(draft) }; Unit }
}

/** How removing a typed-but-unsent draft went. */
enum class Cleanup {
    /** Saathi's text was in the box and is now removed. */
    CLEARED,

    /** The chat's box is already empty (placeholder / voice button): nothing left behind. */
    GONE,

    /** The box holds other text now (the user's): left alone, it's theirs. */
    NOT_OURS,

    /** The screen can't be read (service disconnected): remembered for later. */
    UNAVAILABLE,

    /** Couldn't reach the chat or the clear didn't take: remembered for later. */
    FAILED,
    ;

    /** True when nothing Saathi typed is left behind (record can be forgotten). */
    val resolved: Boolean get() = this == CLEARED || this == GONE || this == NOT_OURS
}

/**
 * Removes ONLY the text Saathi typed: opens WhatsApp if needed, gets to the chat with the exact
 * recorded title (backing out of other chats, using the chat list / search), and empties the box
 * only if it holds exactly that text (hash match) and the gate would allow typing there. Every
 * action goes through the [GatedDevice]. Logs are lengths and booleans only, never text.
 */
class DraftCleaner(
    private val device: GatedDevice,
    private val t: Texts,
    private val log: (String) -> Unit = {},
    private val maxSteps: Int = 12,
) {
    suspend fun clean(draft: PendingDraft): Cleanup {
        log("cleanup: start draftLen=${draft.length} attempts=${draft.attempts}")
        val ctx: CommandContext = Names.contextFor(draft.chat, draft.chat)
        val opener = ChatOpener(draft.chat, ctx, t)
        var unreadable = 0
        var opens = 0
        var backs = 0
        for (step in 1..maxSteps) {
            val s = device.snapshot()
            if (s == null) {
                log("cleanup: snapshot=null step=$step")
                if (++unreadable >= 2) return done(Cleanup.UNAVAILABLE)
                device.waitForChange("", 500)
                continue
            }
            if (!WhatsApp.isWhatsApp(s)) {
                if (++opens > 2) return done(Cleanup.FAILED)
                log("cleanup: bringBack whatsapp (foreground is ${if (s.packageName.isEmpty()) "unknown" else "other"})")
                if (!act(OpenApp("whatsapp"), s, ctx, AgentLoop.OPEN_WAIT_MS)) return done(Cleanup.FAILED)
                continue
            }
            val title = WhatsApp.chatTitle(s)
            if (title != null) {
                if (title.text == draft.chat) return clearHere(s, draft, ctx)
                if (++backs > 4) return done(Cleanup.FAILED)
                log("cleanup: other chat open, back")
                if (!act(Back, s, ctx, AgentLoop.TAP_WAIT_MS)) return done(Cleanup.FAILED)
                continue
            }
            when (val plan = opener.step(s)) {
                is Plan.Act -> {
                    log("cleanup: navigate ${plan.action.type}")
                    if (!act(plan.action, s, ctx, if (plan.action is OpenApp) AgentLoop.OPEN_WAIT_MS else AgentLoop.TAP_WAIT_MS)) {
                        return done(Cleanup.FAILED)
                    }
                }
                else -> {
                    log("cleanup: chat not reachable (${plan?.let { it::class.simpleName } ?: "none"})")
                    return done(Cleanup.FAILED)
                }
            }
        }
        return done(Cleanup.FAILED)
    }

    private suspend fun clearHere(s: Screen, draft: PendingDraft, ctx: CommandContext): Cleanup {
        val box = WhatsApp.entry(s)
        val typed = WhatsApp.typedText(s)
        val ours = draft.matches(typed)
        log("cleanup: in chat boxLen=${box?.text?.length ?: -1} send=${WhatsApp.sendButton(s) != null} typedLen=${typed.length} ours=$ours")
        if (typed.isEmpty()) return done(Cleanup.GONE)
        if (!ours) return done(Cleanup.NOT_OURS)
        val ok = device.clearDraft(s, draft, ctx)
        log("cleanup: clear ok=$ok")
        if (!ok) return done(Cleanup.FAILED)
        device.waitForChange(s.hash, AgentLoop.TAP_WAIT_MS)
        val after = device.snapshot() ?: return done(Cleanup.UNAVAILABLE)
        val inChat = WhatsApp.chatTitle(after)?.text == draft.chat
        val left = WhatsApp.typedText(after)
        log("cleanup: after boxLen=${WhatsApp.entry(after)?.text?.length ?: -1} send=${WhatsApp.sendButton(after) != null} inChat=$inChat")
        return done(if (inChat && left.isEmpty()) Cleanup.CLEARED else Cleanup.FAILED)
    }

    private suspend fun act(action: app.saathi.safety.Action, s: Screen, ctx: CommandContext, waitMs: Long): Boolean {
        val r = device.execute(action, s, ctx)
        if (r !is Exec.Ran || !r.ok) {
            log("cleanup: ${action.type} not run (${r::class.simpleName})")
            return false
        }
        device.waitForChange(s.hash, waitMs)
        return true
    }

    private fun done(r: Cleanup): Cleanup {
        log("cleanup: result=$r")
        return r
    }
}

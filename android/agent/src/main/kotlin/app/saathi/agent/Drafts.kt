package app.saathi.agent

import app.saathi.safety.Back
import app.saathi.safety.CommandContext
import app.saathi.safety.OpenApp
import app.saathi.safety.Screen
import java.security.MessageDigest

/** What Saathi did to a chat's message box (persisted with the record). */
enum class DraftMode {
    /** Typed into an empty box. Undo = remove exactly that text. */
    NEW,

    /** Replaced the user's own unsent text with the new message. Undo = put the user's text back. */
    REPLACE,

    /** Added " " + the new message after the user's text. Undo = put the user's text back. */
    ADD,
}

/**
 * Write-ahead record of text Saathi typed into a WhatsApp chat and has not sent. Saved BEFORE the
 * text is typed and removed only once the text was sent, cleared / put back, or is provably gone,
 * so a stop, a service disconnect or even process death can't leave a draft the user doesn't know
 * about. Holds no message text: only the chat's exact on-screen title, hashes and lengths.
 *
 * For [DraftMode.REPLACE] / [DraftMode.ADD] it also holds the hash and length of the user's
 * ORIGINAL text, so the undo can prove what it puts back. The original text itself is kept only in
 * memory ([DraftStore.original]); it never touches disk. After process death an ADD is still undone
 * exactly (the original is the start of what's in the box, checked against the hash); a REPLACE
 * can't be, and the user is told honestly that their earlier text needs retyping.
 */
data class PendingDraft(
    val chat: String,
    val hash: String,
    val length: Int,
    val attempts: Int = 0,
    val mode: DraftMode = DraftMode.NEW,
    val originalHash: String = "",
    val originalLength: Int = 0,
) {
    /** True when [boxText] is exactly what Saathi typed (whitespace-normalised, like clearDraft). */
    fun matches(boxText: String): Boolean = boxText.isNotEmpty() && hashOf(boxText) == hash

    /** True when [boxText] is the user's original text again (REPLACE / ADD only). */
    fun originalMatches(boxText: String): Boolean =
        mode != DraftMode.NEW && originalHash.isNotEmpty() && boxText.isNotEmpty() && hashOf(boxText) == originalHash

    /**
     * ADD only: the user's original text recovered from the box (it is the start of what Saathi
     * typed), proven by its hash. Null when it can't be proven, or for REPLACE (it's no longer there).
     */
    fun recoverOriginal(boxText: String): String? {
        if (mode != DraftMode.ADD || !matches(boxText)) return null
        val n = norm(boxText)
        if (originalLength <= 0 || n.length <= originalLength || n[originalLength] != ' ') return null
        val candidate = n.substring(0, originalLength)
        return candidate.takeIf { hashOf(it) == originalHash }
    }

    companion object {
        /** After this many failed later attempts the record is dropped (the user was told each time). */
        const val MAX_ATTEMPTS = 3

        fun of(chat: String, text: String) = PendingDraft(chat, hashOf(text), norm(text).length)

        /** Saathi's [typed] text replaced / extended the user's [original] text. */
        fun changing(chat: String, typed: String, mode: DraftMode, original: String) =
            PendingDraft(chat, hashOf(typed), norm(typed).length, mode = mode, originalHash = hashOf(original), originalLength = norm(original).length)

        fun hashOf(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(norm(text).toByteArray()).joinToString("") { "%02x".format(it) }

        internal fun norm(s: String): String = s.trim().split(Regex("\\s+")).joinToString(" ")
    }
}

/**
 * Where pending drafts live: memory in tests, SharedPreferences on the phone. One record per
 * (chat, hash); [put] replaces the record with the same key. The user's original text for a
 * REPLACE / ADD record is kept in memory only ([rememberOriginal] / [original]) and forgotten
 * with the record.
 */
interface DraftStore {
    fun all(): List<PendingDraft>
    fun put(draft: PendingDraft)
    fun remove(draft: PendingDraft)

    /** Keep the user's original text for [draft] in memory (never written to disk). */
    fun rememberOriginal(draft: PendingDraft, text: String)

    /** The user's original text for [draft], if this process still has it. */
    fun original(draft: PendingDraft): String?
}

fun PendingDraft.sameAs(o: PendingDraft): Boolean = chat == o.chat && hash == o.hash

/** Process-memory map of originals, keyed like the records (chat + hash). */
class Originals {
    private val map = HashMap<Pair<String, String>, String>()
    fun put(d: PendingDraft, text: String) = synchronized(map) { map[d.chat to d.hash] = text }
    fun get(d: PendingDraft): String? = synchronized(map) { map[d.chat to d.hash] }
    fun remove(d: PendingDraft) = synchronized(map) { map.remove(d.chat to d.hash); Unit }
}

class MemoryDraftStore(vararg initial: PendingDraft) : DraftStore {
    private val list = initial.toMutableList()
    private val originals = Originals()
    override fun all(): List<PendingDraft> = synchronized(list) { list.toList() }
    override fun put(draft: PendingDraft) = synchronized(list) { list.removeAll { it.sameAs(draft) }; list += draft }
    override fun remove(draft: PendingDraft) = synchronized(list) { list.removeAll { it.sameAs(draft) }; originals.remove(draft) }
    override fun rememberOriginal(draft: PendingDraft, text: String) = originals.put(draft, text)
    override fun original(draft: PendingDraft): String? = originals.get(draft)
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

    /** REPLACE / ADD: the user's original text is back in the box (checked by re-reading it). */
    RESTORED,

    /**
     * REPLACE after a restart: Saathi's text is gone from the box, but the user's original text
     * wasn't kept anywhere (never on disk), so it couldn't be put back. The user is told.
     */
    ORIGINAL_LOST,
    ;

    /** True when nothing Saathi typed is left behind (record can be forgotten). */
    val resolved: Boolean get() = this == CLEARED || this == GONE || this == NOT_OURS || this == RESTORED || this == ORIGINAL_LOST
}

/**
 * Removes ONLY the text Saathi typed: opens WhatsApp if needed, gets to the chat with the exact
 * recorded title (backing out of other chats, using the chat list / search), and empties the box
 * only if it holds exactly that text (hash match) and the gate would allow typing there. For a
 * REPLACE / ADD record it puts the user's original text back instead (only text whose hash is the
 * recorded original), then re-reads the box to prove it. Every action goes through the
 * [GatedDevice]. Logs are lengths and booleans only, never text.
 */
class DraftCleaner(
    private val device: GatedDevice,
    private val t: Texts,
    private val log: (String) -> Unit = {},
    private val maxSteps: Int = 12,
) {
    suspend fun clean(draft: PendingDraft, original: String? = null): Cleanup {
        log("cleanup: start mode=${draft.mode} draftLen=${draft.length} attempts=${draft.attempts} originalKnown=${original != null}")
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
                if (title.text == draft.chat) {
                    return if (draft.mode == DraftMode.NEW) clearHere(s, draft, ctx) else restoreHere(s, draft, ctx, original)
                }
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

    /** REPLACE / ADD: put the user's original text back in place of what Saathi typed. */
    private suspend fun restoreHere(s: Screen, draft: PendingDraft, ctx: CommandContext, original: String?): Cleanup {
        val typed = WhatsApp.typedText(s)
        val ours = draft.matches(typed)
        val asBefore = draft.originalMatches(typed)
        log("cleanup: in chat mode=${draft.mode} typedLen=${typed.length} ours=$ours asBefore=$asBefore originalKnown=${original != null}")
        if (asBefore) return done(Cleanup.RESTORED) // Saathi's text never landed, or is already undone
        if (typed.isNotEmpty() && !ours) return done(Cleanup.NOT_OURS) // the user changed it since: theirs
        val back = original ?: draft.recoverOriginal(typed)
        if (back == null) {
            // REPLACE after a restart: the original was only in memory. Remove Saathi's text; say so.
            if (typed.isEmpty()) return done(Cleanup.ORIGINAL_LOST)
            val ok = device.clearDraft(s, draft, ctx)
            log("cleanup: clear (original unknown) ok=$ok")
            if (!ok) return done(Cleanup.FAILED)
            device.waitForChange(s.hash, AgentLoop.TAP_WAIT_MS)
            val after = device.snapshot() ?: return done(Cleanup.UNAVAILABLE)
            val inChat = WhatsApp.chatTitle(after)?.text == draft.chat
            return done(if (inChat && WhatsApp.typedText(after).isEmpty()) Cleanup.ORIGINAL_LOST else Cleanup.FAILED)
        }
        val ok = device.restoreDraft(s, draft, back, ctx)
        log("cleanup: restore ok=$ok")
        if (!ok) return done(Cleanup.FAILED)
        device.waitForChange(s.hash, AgentLoop.TAP_WAIT_MS)
        val after = device.snapshot() ?: return done(Cleanup.UNAVAILABLE)
        val inChat = WhatsApp.chatTitle(after)?.text == draft.chat
        val now = WhatsApp.typedText(after)
        val back2 = draft.originalMatches(now)
        log("cleanup: after typedLen=${now.length} send=${WhatsApp.sendButton(after) != null} inChat=$inChat original=$back2")
        return done(if (inChat && back2) Cleanup.RESTORED else Cleanup.FAILED)
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

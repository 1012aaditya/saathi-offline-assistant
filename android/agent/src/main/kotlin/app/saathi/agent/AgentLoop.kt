package app.saathi.agent

import app.saathi.safety.Action
import app.saathi.safety.Answer
import app.saathi.safety.Apps
import app.saathi.safety.CommandContext
import app.saathi.safety.Home
import app.saathi.safety.Kind
import app.saathi.safety.Verdict
import app.saathi.safety.OpenApp
import app.saathi.safety.Screen
import app.saathi.safety.TypeText
import app.saathi.safety.grounded
import app.saathi.safety.isCaptcha
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/** Shown (verbatim) and spoken before an irreversible step. All strings come from the screen. */
data class ConfirmRequest(
    val readback: String, // the gate's read-back, exactly
    val rule: String,
    val recipient: String, // verbatim on-screen chat title
    val message: String, // verbatim on-screen message box text
    /** What a No / Cancel does to the box: remove Saathi's text, put the user's back, or leave it. */
    val afterCancel: AfterCancel = AfterCancel.REMOVE_TYPED,
)

/** How a task ended. `message` is what Saathi says; quotes inside it are verbatim on screen. */
sealed interface Outcome {
    val message: String

    data class Answered(
        override val message: String,
        val title: String = "",
        val quote: String? = null,
        val quotes: List<String> = emptyList(),
        /** An extra line to show under [quote] (already part of [message]). */
        val note: String? = null,
    ) : Outcome

    /** [restored]: the user's own earlier text was put back in the box (and checked). */
    data class Cancelled(override val message: String, val draftCleared: Boolean = false, val restored: Boolean = false) : Outcome
    data class HandedOver(override val message: String) : Outcome
    data class Asked(override val message: String) : Outcome
    data class Failed(override val message: String) : Outcome
    data class Stopped(override val message: String) : Outcome
}

/** The same outcome with [note] (e.g. what happened to a typed draft) added to what Saathi says. */
fun Outcome.withNote(note: String?): Outcome {
    if (note.isNullOrBlank()) return this
    val m = "$message $note"
    return when (this) {
        is Outcome.Answered -> copy(message = m)
        is Outcome.Cancelled -> copy(message = m)
        is Outcome.HandedOver -> copy(message = m)
        is Outcome.Asked -> copy(message = m)
        is Outcome.Failed -> copy(message = m)
        is Outcome.Stopped -> copy(message = m)
    }
}

/**
 * Snapshot -> plan -> gate -> execute -> wait for change -> repeat (port of saathi/agent.py's loop
 * without the model). Limits as in Python: max 25 steps; stop after 3 consecutive failures
 * (blocked, no change, or the same action 3x within 6 steps). Coroutine cancellation = Stop:
 * the loop ends at once and only removes a draft it typed (NonCancellable cleanup).
 *
 * Typed drafts: a [PendingDraft] record is written to [drafts] BEFORE the text is typed and
 * removed only once it was sent or cleaned up. If cleanup can't happen now (service gone,
 * process killed), the record survives and [cleanPending] removes the text later; the user is
 * told either way ([lastNote]).
 *
 * The user's own unsent text: when a reply finds text already in the box, the user is asked
 * ([DraftChoiceRequest], default KEEP). REPLACE / ADD records what Saathi changed and the hash of
 * the original BEFORE typing; the original text itself stays in memory only. On anything but a
 * yes (No, unclear, timeout, Stop, error) the original is put back and re-read to prove it.
 */
class AgentLoop(
    private val device: GatedDevice,
    private val t: Texts,
    private val ownPackage: String = "app.saathi",
    private val maxSteps: Int = MAX_STEPS,
    private val maxFailures: Int = MAX_FAILURES,
    private val drafts: DraftStore = MemoryDraftStore(),
    private val log: (String) -> Unit = {},
    private val confirmTimeoutMs: Long = CONFIRM_TIMEOUT_MS,
) {
    companion object {
        const val MAX_STEPS = 25
        const val MAX_FAILURES = 3
        const val LOOP_WINDOW = 6
        const val LOOP_REPEATS = 3
        const val OPEN_WAIT_MS = 8_000L // cold-starting an app took ~3.5 s on the tablet
        const val TAP_WAIT_MS = 3_000L

        /** No answer on the Confirm screen for this long (app left, recogniser stuck) = cancel. */
        const val CONFIRM_TIMEOUT_MS = 90_000L
    }

    /** Number of steps taken in the last run (for history / tests). */
    var steps: Int = 0
        private set

    /** How the last cleanup of this run's typed draft went (null: none was needed / tried). */
    var lastCleanup: Cleanup? = null
        private set

    /** What to tell the user about this run's typed draft (null: nothing to say). */
    var lastNote: String? = null
        private set

    /** The write-ahead record for the draft this run typed (null until it types one). */
    private var record: PendingDraft? = null

    suspend fun run(
        flow: Flow,
        ctx: CommandContext,
        say: suspend (String) -> Unit,
        choose: suspend (DraftChoiceRequest) -> DraftChoice = { DraftChoice.KEEP },
        confirm: suspend (ConfirmRequest) -> Answer,
    ): Outcome {
        steps = 0
        lastCleanup = null
        lastNote = null
        record = null
        try {
            // Text typed by an earlier run that couldn't be removed then: remove it first.
            val earlier = if (flow.readOnly) null else cleanPending(say)?.note
            val out = loop(flow, ctx, say, choose, confirm)
            settleAtEnd(flow)
            return out.withNote(earlier).withNote(lastNote.takeIf { out !is Outcome.Cancelled })
        } catch (e: CancellationException) {
            withContext(NonCancellable) { cleanupDraft(flow, "stop") }
            throw e
        } catch (e: Exception) {
            // Unexpected error after typing: still remove the text (or keep the record and say so).
            if (runCatching { cleanupDraft(flow, "error") }.isFailure || (lastNote == null && lastCleanup == null)) {
                record?.let { lastNote = t.draftLeft(it.chat) }
            }
            throw e
        }
    }

    /** What [cleanPending] did: one result per earlier record, and what to tell the user. */
    data class PendingResult(val results: List<Cleanup>, val note: String?)

    /**
     * Remove text left by earlier runs (the [drafts] records), e.g. after the service reconnects
     * or before the next task. Returns null when nothing was pending.
     */
    suspend fun cleanPending(say: suspend (String) -> Unit = {}): PendingResult? {
        val pending = drafts.all()
        if (pending.isEmpty()) return null
        val results = mutableListOf<Cleanup>()
        val notes = mutableListOf<String>()
        for (p in pending) {
            log("cleanup: pending found draftLen=${p.length} attempts=${p.attempts}")
            say(if (p.mode == DraftMode.NEW) t.clearingEarlierDraft(p.chat) else t.restoringEarlierDraft(p.chat))
            val original = drafts.original(p)
            val r = DraftCleaner(device, t, log).clean(p, original)
            results += r
            settle(p, r, later = true, original)?.let { notes += it }
        }
        return PendingResult(results, notes.joinToString(" ").ifEmpty { null })
    }

    /**
     * Forget or keep the record after a cleanup; returns what to tell the user. When the user's
     * own earlier text couldn't be put back, they hear it (it was verbatim on screen) so it isn't lost.
     */
    private fun settle(p: PendingDraft, r: Cleanup, later: Boolean, original: String? = null): String? {
        val theirs = original?.takeIf { p.mode != DraftMode.NEW }
        if (r.resolved) {
            drafts.remove(p) // forgets the in-memory original too
            log("draft record: cleared ($r)")
            return when (r) {
                Cleanup.CLEARED -> if (later) t.earlierDraftRemoved(p.chat) else t.draftRemoved
                Cleanup.RESTORED -> if (later) t.earlierRestored(p.chat) else t.originalRestored
                Cleanup.ORIGINAL_LOST -> t.originalLost(p.chat)
                Cleanup.NOT_OURS -> listOfNotNull(t.draftChanged(p.chat), theirs?.let(t::yourEarlierText)).joinToString(" ")
                else -> null
            }
        }
        val next = if (r == Cleanup.FAILED) p.copy(attempts = p.attempts + 1) else p
        if (later && next.attempts >= PendingDraft.MAX_ATTEMPTS) {
            drafts.remove(p)
            log("draft record: dropped after ${next.attempts} attempts")
            return if (theirs != null) t.restoreGaveUp(p.chat, theirs) else t.draftLeftGiveUp(p.chat)
        }
        drafts.put(next)
        log("draft record: kept for later ($r)")
        return if (theirs != null) t.restoreFailed(p.chat, theirs) else t.draftLeft(p.chat)
    }

    /** End of a run: a sent draft's record goes; a typed draft nobody cleaned up stays, and is said. */
    private fun settleAtEnd(flow: Flow) {
        val r = record ?: return
        if (flow.draft == null) {
            drafts.remove(r)
            record = null
            log("draft record: cleared (sent)")
        } else if (lastCleanup == null) {
            log("draft record: kept, run ended without cleanup")
            lastNote = t.draftLeft(r.chat)
        }
    }

    private suspend fun loop(
        flow: Flow,
        ctx: CommandContext,
        say: suspend (String) -> Unit,
        choose: suspend (DraftChoiceRequest) -> DraftChoice,
        confirm: suspend (ConfirmRequest) -> Answer,
    ): Outcome {
        var failures = 0
        val recent = ArrayDeque<String>()

        suspend fun fail(message: String): Outcome {
            cleanupDraft(flow, "fail")
            return Outcome.Failed(message)
        }

        for (step in 1..maxSteps) {
            coroutineContext.ensureActive()
            steps = step
            val screen = device.snapshot()
            if (screen == null) {
                if (++failures >= maxFailures) return fail(t.cantRead)
                device.waitForChange("", 500)
                continue
            }
            if (!flow.readOnly) {
                if (device.gate.isPinScreen(screen)) return Outcome.HandedOver(t.verdict(pinVerdict()))
                if (isCaptcha(screen)) return Outcome.HandedOver(t.verdict(captchaVerdict()))
            }

            val plan = flow.next(screen)
            writeAhead(flow, screen)
            when (plan) {
                is Plan.Done -> {
                    val src = plan.source ?: screen
                    if (!grounded(plan.quotes, src) && plan.quotes.isNotEmpty()) return fail(t.notExact)
                    return Outcome.Answered(plan.answer, plan.title, plan.quote, plan.quotes, plan.note)
                }
                is Plan.Ask -> {
                    cleanupDraft(flow, "ask")
                    return Outcome.Asked(plan.question)
                }
                is Plan.HandOver -> return Outcome.HandedOver(plan.reason)
                is Plan.Fail -> return fail(plan.reason)
                is Plan.ChooseDraft -> {
                    if (flow.readOnly) return Outcome.Failed(t.readOnly)
                    chooseDraft(flow, plan, screen, ctx, choose)?.let { return it }
                }
                is Plan.Act -> {
                    val action = plan.action
                    if (flow.readOnly) return Outcome.Failed(t.readOnly) // "what's on screen" never acts
                    if (screen.packageName == ownPackage && action !is OpenApp && action !is Home) {
                        return fail(t.ownScreen)
                    }
                    when (val r = device.execute(action, screen, ctx) { say(plan.say) }) {
                        is Exec.Ran -> {
                            val changed = device.waitForChange(screen.hash, if (action is OpenApp) OPEN_WAIT_MS else TAP_WAIT_MS)
                            if (r.ok) flow.executed(action, changed)
                            val sig = action.toString()
                            recent.addLast(sig)
                            while (recent.size > LOOP_WINDOW) recent.removeFirst()
                            val looping = recent.count { it == sig } >= LOOP_REPEATS
                            failures = if (r.ok && changed && !looping) 0 else failures + 1
                            if (failures >= maxFailures) return fail(t.stuck)
                        }
                        is Exec.Held -> {
                            val req = ConfirmRequest(
                                readback = r.verdict.readback,
                                rule = r.verdict.rule,
                                recipient = WhatsApp.chatTitle(screen)?.text.orEmpty(),
                                message = WhatsApp.entry(screen)?.text.orEmpty(),
                                afterCancel = flow.afterCancel,
                            )
                            // Defaults to cancel: only a clear YES proceeds; no answer in time = cancel.
                            val answer = withTimeoutOrNull(confirmTimeoutMs) { confirm(req) }
                                ?: Answer.UNCLEAR.also { log("confirm: no answer in ${confirmTimeoutMs / 1000} s, cancelling") }
                            coroutineContext.ensureActive()
                            if (answer != Answer.YES) {
                                log("confirm: answer=$answer")
                                return cancelled(flow, cleanupDraft(flow, "answer"))
                            }
                            bringBack(r.packageName, ctx)
                            say(t.sending)
                            when (val done = device.executeConfirmed(r, Answer.YES, ctx)) {
                                is Exec.Ran -> {
                                    val changed = device.waitForChange(screen.hash, TAP_WAIT_MS)
                                    if (done.ok) flow.executed(r.action, changed) else return fail(t.notSureSent)
                                }
                                is Exec.HandOff -> return Outcome.HandedOver(t.verdict(done.verdict))
                                else -> return fail(t.notSentChanged)
                            }
                        }
                        is Exec.Blocked -> {
                            say(t.verdict(r.verdict))
                            if (++failures >= maxFailures) return fail(t.unsafe)
                        }
                        is Exec.HandOff -> return Outcome.HandedOver(t.verdict(r.verdict))
                    }
                }
            }
        }
        return fail(t.tooManySteps)
    }

    /** What a No on the send confirmation leaves the user with, from what the cleanup really did. */
    private fun cancelled(flow: Flow, r: Cleanup?): Outcome = when (r) {
        null -> Outcome.Cancelled(if (flow.afterCancel == AfterCancel.LEAVE_AS_IS) t.cancelledLeftAsIs else t.cancelled(false))
        Cleanup.CLEARED -> Outcome.Cancelled(t.cancelled(true), draftCleared = true)
        Cleanup.RESTORED -> Outcome.Cancelled(t.cancelledRestored, restored = true)
        else -> Outcome.Cancelled(t.cancelled(false)).withNote(lastNote)
    }

    /**
     * The box already holds the user's own text: ask what to do (default KEEP). Returns the
     * outcome when the task ends here (keep / private / unclear), or null to carry on.
     */
    private suspend fun chooseDraft(
        flow: Flow,
        plan: Plan.ChooseDraft,
        screen: Screen,
        ctx: CommandContext,
        choose: suspend (DraftChoiceRequest) -> DraftChoice,
    ): Outcome? {
        val chat = WhatsApp.chatTitle(screen)?.text.orEmpty()
        val entry = WhatsApp.entry(screen) ?: return Outcome.Failed(t.cantTypeHere)
        val shown = plan.existing
        val raw = device.entryText(screen)
        // A code / PIN / password is never read out, typed back or remembered: leave it alone.
        val private = PrivateText.looksPrivate(shown) || (raw != null && PrivateText.looksPrivate(raw))
        if (private) {
            log("draft choice: existingLen=${shown.length} private=true, left alone")
            return Outcome.Cancelled(t.draftPrivate)
        }
        if (!grounded(listOf(shown) + listOf(chat).filter { it.isNotEmpty() }, screen)) return Outcome.Failed(t.notExact)
        // It can only be put back exactly if it's one line, the screen shows the same text, and
        // the gate would let Saathi type it there (R2 forbids line breaks, R7 secret fields).
        val multiline = raw != null && (raw.contains('\n') || raw.contains('\r'))
        val same = raw == null || PendingDraft.norm(raw) == PendingDraft.norm(shown)
        val original = if (raw != null && same) raw else shown
        val typable = device.gate.check(TypeText(original, entry.index), screen, ctx).kind == Kind.ALLOW
        val canChange = !multiline && same && typable
        log("draft choice: existingLen=${shown.length} rawKnown=${raw != null} multiline=$multiline canChange=$canChange")
        val req = DraftChoiceRequest(chat, shown, plan.newMessage, canChange)
        val answer = withTimeoutOrNull(confirmTimeoutMs) { choose(req) }
            ?: DraftChoice.KEEP.also { log("draft choice: no answer in ${confirmTimeoutMs / 1000} s, keeping") }
        coroutineContext.ensureActive()
        val choice = if (!canChange && (answer == DraftChoice.REPLACE || answer == DraftChoice.ADD)) DraftChoice.KEEP else answer
        log("draft choice: answer=$choice")
        if (choice == DraftChoice.KEEP) return Outcome.Cancelled(t.draftKept)
        flow.chose(choice, original)
        bringBack(WhatsApp.PKG, ctx)
        return null
    }

    /** The confirm screen may have covered the app: bring it back (a gated OpenApp) before acting. */
    private suspend fun bringBack(packageName: String, ctx: CommandContext) {
        val now = device.snapshot()
        if (now?.packageName == packageName) return
        val alias = Apps.APP_ALIASES.entries.firstOrNull { it.value == packageName }?.key ?: return
        if (device.execute(OpenApp(alias), now ?: return, ctx) is Exec.Ran) {
            device.waitForChange(now.hash, OPEN_WAIT_MS)
        }
    }

    /** Save the record BEFORE the flow's draft is typed (the flow sets [Flow.draft] when it plans the typing). */
    private fun writeAhead(flow: Flow, screen: Screen) {
        val text = flow.draft ?: return
        if (record != null) return
        val chat = WhatsApp.chatTitle(screen)?.text.orEmpty()
        val original = flow.original
        val r = if (original != null) PendingDraft.changing(chat, text, flow.draftMode, original) else PendingDraft.of(chat, text)
        record = r
        drafts.put(r)
        if (original != null) drafts.rememberOriginal(r, original) // memory only, never on disk
        log("draft record: saved mode=${r.mode} draftLen=${r.length} originalLen=${r.originalLength} chatKnown=${chat.isNotEmpty()}")
    }

    /**
     * Undo text Saathi typed and didn't send (cancel / failure / ask / stop): remove it, or for
     * REPLACE / ADD put the user's original text back. Null when there was nothing to undo.
     * Only that exact text is touched; if it can't be done now the record stays for later.
     */
    private suspend fun cleanupDraft(flow: Flow, reason: String): Cleanup? {
        if (flow.draft == null) return null
        val p = record ?: return null
        log("cleanup: reason=$reason mode=${p.mode}")
        val original = drafts.original(p)
        val r = DraftCleaner(device, t, log).clean(p, original)
        lastCleanup = r
        lastNote = settle(p, r, later = false, original)
        if (r.resolved) record = null
        return r
    }

    private fun pinVerdict() = Verdict(Kind.HANDOFF, "R8", "अपना पिन खुद डालिए, मैं रुक रहा हूँ।")

    private fun captchaVerdict() = Verdict(Kind.HANDOFF, "R9", "यह कैप्चा आपको खुद भरना होगा।")
}

/** Builds the flow for a task command. */
object Flows {
    fun forCommand(
        command: Command,
        t: Texts,
        gate: app.saathi.safety.SafetyGate,
        captured: Screen? = null,
        capturedLabel: String = "",
        appLabel: (String) -> String = { it },
    ): Flow? = when (command) {
        is Command.ReadLast -> command.contact?.let { ReadLastFlow(it, Names.contextFor(it, command.utterance), t) }
        is Command.Reply -> if (command.contact != null && command.text != null) {
            ReplyFlow(command.contact, command.text, Names.contextFor(command.contact, command.utterance), t)
        } else null
        is Command.UnreadSummary -> UnreadFlow(t)
        is Command.ScreenSummary -> ScreenSummaryFlow(captured, capturedLabel.ifEmpty { captured?.packageName.orEmpty() }, gate, t)
        is Command.OpenApp -> OpenAppFlow(command.alias, command.packageName, appLabel(command.packageName), t)
        else -> null
    }

    /** The safety context (names for R5) for a task command. */
    fun contextFor(command: Command): CommandContext = when (command) {
        is Command.ReadLast -> Names.contextFor(command.contact, command.utterance)
        is Command.Reply -> Names.contextFor(command.contact, command.utterance)
        else -> CommandContext(command = command.utterance)
    }

    /** The question to ask when a task command is missing a part (whose message, what to say). */
    fun missing(command: Command, t: Texts): String? = when (command) {
        is Command.ReadLast -> if (command.contact == null) t.whoseMessage else null
        is Command.Reply -> when {
            command.contact == null -> t.replyToWhom
            command.text == null -> t.whatToSay(command.contact)
            else -> null
        }
        else -> null
    }
}

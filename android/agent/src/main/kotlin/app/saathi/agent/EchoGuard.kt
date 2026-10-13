package app.saathi.agent

import java.text.Normalizer as TextNormalizer

/**
 * Saathi must never answer its own question. On 13 Oct a test run on the tablet chose "Replace"
 * and then confirmed a WhatsApp send with no human answer, a few seconds after Saathi's own TTS
 * read the question aloud. Whatever opened the microphone, the voice path must not be able to
 * hear Saathi's own words (or TalkBack reading Saathi's screen) as the user's answer.
 *
 * Two pure layers (the app's Voice and sheets call them; unit-tested on the JVM):
 *  - [timing]: the microphone opens only [LISTEN_AFTER_SPEECH_MS] after Saathi stopped talking,
 *    a session Saathi spoke during is discarded, and anything heard within
 *    [MIN_HEARD_AFTER_SPEECH_MS] of the end of Saathi's speech is discarded.
 *  - [overlap]: a transcript that repeats the prompt / the words on the sheet is an echo.
 *
 * An echo is never an answer: the dialogs treat it as UNCLEAR (ask once more, then the safe
 * default: cancel / keep).
 */
object EchoGuard {
    /** The microphone may open this long after Saathi's speech ended (room echo, audio buffers). */
    const val LISTEN_AFTER_SPEECH_MS = 700L

    /** A transcript finished sooner than this after Saathi's speech ended is not trusted. */
    const val MIN_HEARD_AFTER_SPEECH_MS = 1_500L

    enum class Reason {
        NONE,

        /** Saathi was speaking while (or just before) the microphone was open. */
        SPOKE_DURING_LISTENING,

        /** The transcript arrived too soon after Saathi stopped speaking to be a reply. */
        TOO_SOON_AFTER_SPEECH,

        /** The transcript repeats words / a run of words from what Saathi said or showed. */
        OVERLAPS_PROMPT,

        /** The deciding word (e.g. the "yes") is itself in what Saathi said or showed. */
        ANSWER_WORD_IN_PROMPT,
    }

    /**
     * When the microphone was open and what Saathi's speech did around it. All times are from
     * the same monotonic clock. [lastSpeechStartMs] / [lastSpeechEndMs] null = Saathi hasn't spoken;
     * [lastSpeechEndMs] null with a start = still speaking.
     */
    data class Timing(
        val micOpenedAtMs: Long,
        val heardAtMs: Long,
        val lastSpeechStartMs: Long?,
        val lastSpeechEndMs: Long?,
        val speakingNow: Boolean,
    )

    fun timing(t: Timing): Reason {
        if (t.speakingNow) return Reason.SPOKE_DURING_LISTENING
        val start = t.lastSpeechStartMs ?: return Reason.NONE // Saathi hasn't said anything
        val end = t.lastSpeechEndMs ?: return Reason.SPOKE_DURING_LISTENING // never finished
        if (start >= t.micOpenedAtMs) return Reason.SPOKE_DURING_LISTENING // spoke while listening
        if (end + LISTEN_AFTER_SPEECH_MS > t.micOpenedAtMs) return Reason.SPOKE_DURING_LISTENING
        if (t.heardAtMs - end < MIN_HEARD_AFTER_SPEECH_MS) return Reason.TOO_SOON_AFTER_SPEECH
        return Reason.NONE
    }

    private val WORD = Regex("[^\\p{L}\\p{M}\\p{N}']+")

    /** Lowercase, Devanagari nukta / Unicode forms unified, apostrophes kept ("don't"), in order. */
    fun tokens(text: String): List<String> {
        val n = TextNormalizer.normalize(text.lowercase(), TextNormalizer.Form.NFC)
            .replace("़", "") // nukta: ड़ typed either way matches
            .replace('’', '\'')
            .replace('‘', '\'')
        return n.split(WORD).map { it.trim('\'') }.filter { it.isNotEmpty() }
    }

    /**
     * Is [transcript] an echo of [sources] (what Saathi just said, what the sheet shows)?
     *  - [decisive]: words that decide the answer (e.g. the "yes" words). If one of the
     *    transcript's decisive words is anywhere in the sources, it may have come from Saathi:
     *    echo. (So prompts must never contain the yes words.)
     *  - Any run of 3 consecutive words that is also in a source: echo.
     *  - [ignore]: the answer vocabulary and fillers ("replace", "it", "please"…). Of the other
     *    words, if at least half are in the sources: echo. Only checked when the transcript has
     *    at least [minTokensForRatio] words.
     */
    fun overlap(
        transcript: String,
        sources: List<String>,
        ignore: Set<String> = emptySet(),
        decisive: Set<String> = emptySet(),
        minTokensForRatio: Int = 1,
    ): Reason {
        val t = tokens(transcript)
        if (t.isEmpty()) return Reason.NONE
        val src = sources.map(::tokens).filter { it.isNotEmpty() }
        if (src.isEmpty()) return Reason.NONE
        val vocab = src.flatten().toSet()
        if (t.any { it in decisive && it in vocab }) return Reason.ANSWER_WORD_IN_PROMPT
        if (t.size >= 3) {
            val runs = src.flatMap { s -> s.windowed(3) }.toSet()
            if (t.windowed(3).any { it in runs }) return Reason.OVERLAPS_PROMPT
        }
        if (t.size >= minTokensForRatio) {
            val content = t.filter { it !in ignore }
            if (content.isNotEmpty() && content.count { it in vocab } * 2 >= content.size) return Reason.OVERLAPS_PROMPT
        }
        return Reason.NONE
    }

    /**
     * The looser check for screens whose answers don't send or change anything (Welcome, Result,
     * Working …): only a transcript that both contains a 3-word run of a source and is mostly
     * made of its words. Short commands ("continue", "repeat") always pass; timing guards them.
     */
    fun looksLikeEcho(transcript: String, sources: List<String>): Boolean {
        val t = tokens(transcript)
        if (t.size < 3) return false
        val src = sources.map(::tokens).filter { it.isNotEmpty() }
        val vocab = src.flatten().toSet()
        val runs = src.flatMap { s -> s.windowed(3) }.toSet()
        return t.windowed(3).any { it in runs } && t.count { it in vocab } * 2 >= t.size
    }
}

/**
 * The speak / listen interlock, as plain state so it is unit-tested (the app's Voice drives it
 * from TextToSpeech and SpeechRecognizer callbacks):
 *  - [speechStarted] when Saathi starts (or queues) speech: any listening session, open or
 *    waiting to open, is invalidated (its result is discarded) and true is returned so the
 *    caller cancels the recognizer.
 *  - [msUntilMicMayOpen]: null while Saathi is speaking (stop speaking first); otherwise how long
 *    to wait so the mic opens at least [EchoGuard.LISTEN_AFTER_SPEECH_MS] after the speech ended.
 *  - [open] refuses a stale / too early session; [judge] applies [EchoGuard.timing] to a result.
 *  - [recentPrompts]: what Saathi said lately, for [EchoGuard.overlap].
 */
class VoiceInterlock(private val now: () -> Long) {
    private class Spoken(val text: String, val startMs: Long, var endMs: Long?)

    private val history = ArrayDeque<Spoken>()
    private var waitingId: Int? = null
    private var openId: Int? = null
    private var openedAtMs: Long = 0L
    private var nextId = 0

    @get:Synchronized
    val speaking: Boolean get() = history.any { it.endMs == null }

    @get:Synchronized
    val lastSpeechStartMs: Long? get() = history.lastOrNull()?.startMs

    @get:Synchronized
    val lastSpeechEndMs: Long? get() = history.lastOrNull()?.endMs

    /** True while a session is open or waiting to open. */
    @get:Synchronized
    val listening: Boolean get() = waitingId != null || openId != null

    /** Saathi starts saying [text]. Returns true when a listening session was cancelled by it. */
    @Synchronized
    fun speechStarted(text: String): Boolean {
        val t = now()
        history.forEach { if (it.endMs == null) it.endMs = t } // QUEUE_FLUSH ends the previous one
        history.addLast(Spoken(text, t, null))
        while (history.size > MAX_HISTORY) history.removeFirst()
        val cancelled = listening
        waitingId = null
        openId = null
        return cancelled
    }

    /** Saathi's speech finished, was stopped, or failed. */
    @Synchronized
    fun speechEnded() {
        val t = now()
        history.forEach { if (it.endMs == null) it.endMs = t }
    }

    /**
     * The speech engine is still making sound although Saathi's speech was stopped (a TTS
     * engine finishing its buffered audio after stop(), 14 Oct): count the speech as ending now,
     * so the mic waits the full gap after the sound really stops, and timings measure from there.
     */
    @Synchronized
    fun stillAudible() {
        val last = history.lastOrNull() ?: return
        if (last.endMs != null) last.endMs = now()
    }

    /** Null while speaking; else ms to wait before the mic may open (0 = now). */
    @Synchronized
    fun msUntilMicMayOpen(): Long? {
        if (speaking) return null
        val end = lastSpeechEndMs ?: return 0L
        return (end + EchoGuard.LISTEN_AFTER_SPEECH_MS - now()).coerceAtLeast(0L)
    }

    /** The user asked to talk: a new session id, waiting to open. Any older session is dropped. */
    @Synchronized
    fun requestListen(): Int {
        val id = ++nextId
        waitingId = id
        openId = null
        return id
    }

    /** Open session [id] now. False if it was cancelled / replaced, or the mic may not open yet. */
    @Synchronized
    fun open(id: Int): Boolean {
        if (waitingId != id) return false
        if (msUntilMicMayOpen() != 0L) return false
        waitingId = null
        openId = id
        openedAtMs = now()
        return true
    }

    @Synchronized
    fun isCurrent(id: Int): Boolean = waitingId == id || openId == id

    /** End session [id] (result, error or cancel). */
    @Synchronized
    fun close(id: Int) {
        if (waitingId == id) waitingId = null
        if (openId == id) openId = null
    }

    /** Drop whatever session there is. */
    @Synchronized
    fun cancelAll() {
        waitingId = null
        openId = null
    }

    /** Whether a result of session [id], heard now, may be used (NONE) or must be discarded. */
    @Synchronized
    fun judge(id: Int): EchoGuard.Reason {
        if (openId != id) return EchoGuard.Reason.SPOKE_DURING_LISTENING // cancelled by speech, or stale
        return EchoGuard.timing(
            EchoGuard.Timing(
                micOpenedAtMs = openedAtMs,
                heardAtMs = now(),
                lastSpeechStartMs = lastSpeechStartMs,
                lastSpeechEndMs = lastSpeechEndMs,
                speakingNow = speaking,
            ),
        )
    }

    /**
     * How long ago Saathi's speech ended (ms): 0 while speaking, null if Saathi hasn't spoken.
     * Goes with every transcript to the answer sheets ([Heard.msAfterSpeech]).
     */
    @Synchronized
    fun msSinceSpeechEnded(): Long? {
        if (speaking) return 0L
        val end = lastSpeechEndMs ?: return null
        return (now() - end).coerceAtLeast(0L)
    }

    /** What Saathi said in the last [windowMs] (newest last). */
    @Synchronized
    fun recentPrompts(windowMs: Long = RECENT_MS): List<String> {
        val t = now()
        return history.filter { t - (it.endMs ?: t) <= windowMs }.map { it.text }
    }

    companion object {
        const val MAX_HISTORY = 12
        const val RECENT_MS = 90_000L
    }
}

/**
 * Guide mode listens ONLY after the user's own press: Speak, the talk button, or Volume-down held.
 * 14 Oct 00:40: right after Saathi said 'Say "read more"', the guide got "read more" from the
 * voice path. Whatever opened the mic, this makes "Saathi's turn opened the mic" impossible:
 *  - [press] arms ONE listening session; [mayListen] uses it up.
 *  - It must be used within [PRESS_VALID_MS] of the press (a stale, buffered press never counts).
 *  - Every new Saathi turn ([turnSpoken]) disarms a press not used yet, and a press made before
 *    the latest turn never counts. Nothing but a press arms it: a turn, a timer or the end of
 *    Saathi's speech can't.
 */
class PushToTalk(private val now: () -> Long) {
    private var pressedAt: Long? = null
    private var lastTurnAt: Long? = null

    /** The user pressed Speak / the talk button / held Volume-down at [at] (same clock as [now]). */
    @Synchronized
    fun press(at: Long = now()) {
        pressedAt = at
    }

    /** Saathi said a new turn: a press not used yet no longer counts. */
    @Synchronized
    fun turnSpoken() {
        lastTurnAt = now()
        pressedAt = null
    }

    /** True once per fresh press, made after the latest turn: the mic may open now. */
    @Synchronized
    fun mayListen(): Boolean {
        val p = pressedAt ?: return false
        pressedAt = null
        val age = now() - p
        if (age < 0 || age > PRESS_VALID_MS) return false
        val turn = lastTurnAt
        return turn == null || p >= turn
    }

    companion object {
        const val PRESS_VALID_MS = 2_000L
    }
}

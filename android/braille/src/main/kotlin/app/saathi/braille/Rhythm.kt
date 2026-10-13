package app.saathi.braille

/**
 * Rhythm braille: a phone can't raise a bump under one fingertip, so each cell is played in time.
 * The six dots play in order 1→6: a raised dot is a strong long pulse, a flat dot a faint short
 * tick (so the slots can be counted). A pause between the columns (dots 1–3 | 4–6) halves the
 * counting; longer silences mark letters and words; a long low pulse ends a sentence.
 */
enum class PulseKind { Raised, Flat, Silence, SentenceEnd }

data class Pulse(val kind: PulseKind, val ms: Int)

/** Base timings at pace 1.0 (ms). One letter takes about 1.6 s; pace 2.0 halves everything. */
object RhythmTiming {
    const val RAISED = 150
    const val FLAT = 30
    const val SLOT_GAP = 110
    const val COLUMN_GAP = 260
    const val LETTER_GAP = 520
    const val WORD_GAP = 1_000
    const val SENTENCE_END = 450
}

private fun scaled(ms: Int, pace: Float): Int = (ms / pace).toInt().coerceAtLeast(10)

/** One cell: 6 slots with gaps, column pause after dot 3. No gap after the last slot. */
fun cellRhythm(cell: Cell, pace: Float = 1f): List<Pulse> {
    val out = mutableListOf<Pulse>()
    for (dot in 1..6) {
        out += if (cell.raised(dot)) Pulse(PulseKind.Raised, scaled(RhythmTiming.RAISED, pace))
        else Pulse(PulseKind.Flat, scaled(RhythmTiming.FLAT, pace))
        when (dot) {
            3 -> out += Pulse(PulseKind.Silence, scaled(RhythmTiming.COLUMN_GAP, pace))
            6 -> Unit
            else -> out += Pulse(PulseKind.Silence, scaled(RhythmTiming.SLOT_GAP, pace))
        }
    }
    return out
}

/** Silence between letters of a word. */
fun letterGap(pace: Float = 1f) = Pulse(PulseKind.Silence, scaled(RhythmTiming.LETTER_GAP, pace))

/** What follows a word: silence, or the sentence-end pulse and then silence. */
fun wordEnd(endsSentence: Boolean, pace: Float = 1f): List<Pulse> = buildList {
    if (endsSentence) {
        add(Pulse(PulseKind.Silence, scaled(RhythmTiming.LETTER_GAP, pace)))
        add(Pulse(PulseKind.SentenceEnd, scaled(RhythmTiming.SENTENCE_END, pace)))
    }
    add(Pulse(PulseKind.Silence, scaled(RhythmTiming.WORD_GAP, pace)))
}

fun durationMs(pulses: List<Pulse>): Int = pulses.sumOf { it.ms }

/**
 * Reading speed that adapts to the reader. Pace multiplies speed (1.0 = beginner, 3.0 = fast).
 * - Quiz: 3 right in a row → 10% faster; a mistake → 15% slower.
 * - Reading: repeating a word → 5% slower; 5 words in a row without repeating → 5% faster.
 * Always within [MIN]..[MAX]; the user can also set it with Slower / Faster.
 */
class Pace(initial: Float = 1f) {
    var value: Float = initial.coerceIn(MIN, MAX)
        private set
    private var streak = 0
    private var smooth = 0

    fun correct() {
        if (++streak >= 3) { streak = 0; scale(1.10f) }
    }

    fun wrong() { streak = 0; scale(0.85f) }

    fun repeated() { smooth = 0; scale(0.95f) }

    fun advanced() {
        if (++smooth >= 5) { smooth = 0; scale(1.05f) }
    }

    fun faster() = scale(1.15f)
    fun slower() = scale(1f / 1.15f)

    private fun scale(f: Float) { value = (value * f).coerceIn(MIN, MAX) }

    companion object {
        const val MIN = 0.5f
        const val MAX = 3.0f
    }
}

package app.saathi.braille

import kotlinx.coroutines.delay

/** Plays rhythm braille cell by cell, so the screen can follow along. Cancel the coroutine to stop. */
class RhythmPlayer(private val out: BrailleHaptics) {

    suspend fun playCell(cell: Cell, pace: Float) {
        try {
            delay(out.pulses(cellRhythm(cell, pace)).toLong())
        } finally {
            out.stopPulses()
        }
    }

    /** One word: its letters with letter gaps, then the word end (and sentence end) signal. */
    suspend fun playWord(word: Word, endsSentence: Boolean, pace: Float, onCell: (Int) -> Unit = {}) {
        try {
            word.cells.forEachIndexed { i, cell ->
                onCell(i)
                delay(out.pulses(cellRhythm(cell, pace)).toLong())
                if (i < word.cells.lastIndex) delay(letterGap(pace).ms.toLong())
            }
            onCell(-1)
            delay(out.pulses(wordEnd(endsSentence, pace)).toLong())
        } finally {
            out.stopPulses()
        }
    }
}

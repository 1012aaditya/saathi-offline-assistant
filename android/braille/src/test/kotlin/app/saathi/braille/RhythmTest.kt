package app.saathi.braille

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentTest {
    private val doc = Document("The little boy ran. The sun was shining!  Ok")

    @Test fun `splits sentences and words`() {
        assertEquals(listOf(4, 4, 1), doc.sentences.map { it.words.size })
        assertEquals(9, doc.wordCount)
        assertEquals("ran.", doc.word(Position(0, 3)).text)
    }

    @Test fun `next and previous cross sentence borders`() {
        assertEquals(Position(1, 0), doc.next(Position(0, 3)))
        assertEquals(Position(0, 3), doc.previous(Position(1, 0)))
        assertNull(doc.next(Position(2, 0)))
        assertNull(doc.previous(Position(0, 0)))
    }

    @Test fun `sentence jumps`() {
        assertEquals(Position(1, 0), doc.nextSentence(Position(0, 2)))
        assertEquals(Position(1, 0), doc.previousSentence(Position(1, 2)))
        assertEquals(Position(0, 0), doc.previousSentence(Position(1, 0)))
        assertNull(doc.nextSentence(Position(2, 0)))
    }

    @Test fun `ordinal and sentence end`() {
        assertEquals(5, doc.ordinal(Position(1, 0)))
        assertTrue(doc.endsSentence(Position(0, 3)))
    }

    @Test fun `empty text`() {
        assertTrue(Document("   ").isEmpty)
    }

    @Test fun `Hindi danda ends a sentence`() {
        assertEquals(2, Document("नमस्ते। ok").sentences.size)
    }
}

class RhythmTest {
    @Test fun `letter b is two raised then four flat with a column pause`() {
        val p = cellRhythm(encode("b").single())
        val slots = p.filter { it.kind != PulseKind.Silence }.map { it.kind }
        assertEquals(listOf(PulseKind.Raised, PulseKind.Raised, PulseKind.Flat, PulseKind.Flat, PulseKind.Flat, PulseKind.Flat), slots)
        // gaps: 1-2, 2-3, column, 4-5, 5-6
        val gaps = p.filter { it.kind == PulseKind.Silence }.map { it.ms }
        assertEquals(listOf(110, 110, 260, 110, 110), gaps)
    }

    @Test fun `raised pulses are clearly longer than flat ticks`() {
        assertTrue(RhythmTiming.RAISED >= 4 * RhythmTiming.FLAT)
    }

    @Test fun `pace scales every duration`() {
        val slow = durationMs(cellRhythm(encode("a").single(), 1f))
        val fast = durationMs(cellRhythm(encode("a").single(), 2f))
        assertTrue(fast in slow / 2 - 6..slow / 2 + 6)
    }

    @Test fun `word end and sentence end differ`() {
        assertEquals(listOf(PulseKind.Silence), wordEnd(false).map { it.kind })
        assertEquals(listOf(PulseKind.Silence, PulseKind.SentenceEnd, PulseKind.Silence), wordEnd(true).map { it.kind })
    }
}

class PaceTest {
    @Test fun `three right answers speed up, a mistake slows down`() {
        val p = Pace()
        p.correct(); p.correct()
        assertEquals(1f, p.value)
        p.correct()
        assertEquals(1.1f, p.value, 0.001f)
        p.wrong()
        assertEquals(1.1f * 0.85f, p.value, 0.001f)
    }

    @Test fun `a mistake resets the streak`() {
        val p = Pace()
        p.correct(); p.correct(); p.wrong(); p.correct()
        assertEquals(0.85f, p.value, 0.001f)
    }

    @Test fun `smooth reading speeds up slowly, repeats slow down`() {
        val p = Pace()
        repeat(5) { p.advanced() }
        assertEquals(1.05f, p.value, 0.001f)
        p.repeated()
        assertEquals(1.05f * 0.95f, p.value, 0.001f)
    }

    @Test fun `stays within bounds`() {
        val p = Pace()
        repeat(50) { p.faster() }
        assertEquals(Pace.MAX, p.value)
        repeat(50) { p.slower() }
        assertEquals(Pace.MIN, p.value)
        assertEquals(Pace.MAX, Pace(99f).value)
    }
}

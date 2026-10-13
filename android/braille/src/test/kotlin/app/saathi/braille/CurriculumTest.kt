package app.saathi.braille

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CurriculumTest {
    @Test fun `lesson ids are 1 to n in order`() {
        assertEquals((1..LESSONS.size).toList(), LESSONS.map { it.id })
    }

    @Test fun `every lesson has steps and every quiz is passable`() {
        LESSONS.forEach { l ->
            assertTrue(l.steps.isNotEmpty(), l.title)
            l.steps.filterIsInstance<Step.Quiz>().forEach { q ->
                assertTrue(q.pool.size >= 4, "${l.title}: need 4 options")
                assertTrue(q.pass in 1..q.questions, l.title)
            }
        }
    }

    @Test fun `every character taught encodes to known braille`() {
        val texts = LESSONS.flatMap { l ->
            l.steps.flatMap { s ->
                when (s) {
                    is Step.Meet -> listOf(s.char.toString())
                    is Step.Compare -> listOf(s.from.toString(), s.to.toString())
                    is Step.Build -> listOf(s.char.toString())
                    is Step.Listen -> listOf(s.text)
                    is Step.Quiz -> s.pool
                    is Step.Read -> listOf(s.text)
                    else -> emptyList()
                }
            }
        }
        texts.forEach { t -> assertTrue(encode(t).none { it.kind == Cell.Kind.Unknown }, t) }
    }

    @Test fun `the compare rule really holds - k to t add dot 3, u v x y z add 3 and 6`() {
        LESSONS.flatMap { it.steps }.filterIsInstance<Step.Compare>().forEach { c ->
            val from = encode(c.from.toString()).single().dots
            val to = encode(c.to.toString()).single().dots
            val added = to and from.inv()
            assertEquals(from, to and from, "${c.from}->${c.to} keeps the base dots")
            assertTrue(added == dots(3) || added == dots(3, 6), "${c.from}->${c.to}")
        }
    }

    @Test fun `a to j only use the top four dots`() {
        ('a'..'j').forEach { assertEquals(0, encode(it.toString()).single().dots and dots(3, 6), "$it") }
    }

    @Test fun `memory hints`() {
        assertEquals("a plus dot 3", memoryHint('k'))
        assertEquals("c plus dots 3 and 6", memoryHint('x'))
        assertEquals("j plus dot 6 (added later)", memoryHint('w'))
        assertEquals("number sign, then c", memoryHint('3'))
        assertEquals("number sign, then j", memoryHint('0'))
        assertNull(memoryHint('?'))
        ('a'..'z').forEach { assertTrue(memoryHint(it) != null, "$it") }
    }

    @Test fun `w hint is true`() {
        assertEquals(encode("j").single().dots or dots(6), encode("w").single().dots)
    }

    @Test fun `describe and names`() {
        assertEquals("dots 1, 4 and 5", describeDots(encode("d").single()))
        assertEquals("dot 1", describeDots(encode("a").single()))
        assertEquals("letter b", itemName("b"))
        assertEquals("number 7", itemName("7"))
        assertEquals("full stop", itemName("."))
        assertEquals("the word cat", itemName("cat"))
    }
}

class MasteryTest {
    @Test fun `records and keeps the last six`() {
        val m = Mastery()
        repeat(10) { m.record("a", it >= 4) } // 4 wrong, then 6 right
        assertEquals(6, m.attempts("a"))
        assertEquals(1f, m.accuracy("a"))
        assertTrue(m.known("a"))
    }

    @Test fun `known needs three tries and 80 percent`() {
        val m = Mastery()
        m.record("b", true); m.record("b", true)
        assertFalse(m.known("b"))
        m.record("b", false)
        assertFalse(m.known("b"))
        assertNull(m.accuracy("c"))
    }

    @Test fun `weakest first, known ones left out`() {
        val m = Mastery()
        repeat(3) { m.record("a", true) }
        m.record("b", false); m.record("b", true)
        m.record("c", false)
        assertEquals(listOf("c", "b"), m.weakest(5))
    }

    @Test fun `round trip and bad input`() {
        val m = Mastery()
        m.record("a", true); m.record("cat", false)
        val back = Mastery(m.serialize())
        assertEquals(m.serialize(), back.serialize())
        assertEquals(0, Mastery("garbage;;x:2;:1").attempts("x"))
    }
}

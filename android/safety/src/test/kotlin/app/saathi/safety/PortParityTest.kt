package app.saathi.safety

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Not in tests/test_safety.py: pins the Python-semantics helpers of the port to reference values
 * produced by the real libraries (rapidfuzz 3.14.6, Pillow, CPython 3.12) on 13 Oct.
 */
class PortParityTest {
    @Test
    fun tokenSetRatioMatchesRapidfuzz() {
        val cases = listOf(
            Triple("rahul", "rahul sharma", 100.0),
            Triple("rohit", "rahul sharma", 23.529411764705884),
            Triple("राहुल", "rahul sharma", 0.0),
            Triple("rahul", "rahul.s@okaxis", 52.63157894736842),
            Triple("mummy", "mummy", 100.0),
            Triple("papa", "mummy", 0.0),
            Triple("rahul kumar", "rahul sharma", 69.56521739130434),
            Triple("fuzzy was a bear but not a dog", "fuzzy was a bear but not a cat", 92.3076923076923),
            Triple("rahool", "rahul sharma", 44.44444444444444),
            Triple("", "x", 0.0),
            Triple("ramesh", "ram", 66.66666666666666),
        )
        for ((a, b, want) in cases) assertEquals(want, Fuzz.tokenSetRatio(a, b), 1e-9, "$a | $b")
    }

    @Test
    fun thumbnailSizeMatchesPillow() {
        assertEquals(100 to 200, thumbnailSize(300, 600, 200, 200))
        assertEquals(90 to 200, thumbnailSize(1080, 2400, 200, 200))
        assertEquals(200 to 90, thumbnailSize(2400, 1080, 200, 200))
        assertNull(thumbnailSize(150, 150, 200, 200))
        assertEquals(112 to 200, thumbnailSize(1080, 1920, 200, 200))
        assertEquals(200 to 199, thumbnailSize(201, 200, 200, 200))
    }

    @Test
    fun unicodeDigitsAndCaseLikePython() {
        val dev = CommandContext.fromUtterance("Rahul ko ५ rupaye bhejo")
        assertEquals(listOf("Rahul") to 5L, dev.names to dev.amount)
        val upper = CommandContext.fromUtterance("RAHUL KO 2 RUPEES")
        assertEquals(listOf("RAHUL") to 2L, upper.names to upper.amount)
        // Python raises ValueError here (isdigit() but not int()-able); the port throws too.
        assertThrows(NumberFormatException::class.java) { CommandContext.fromUtterance("Amma ko ²rupaye") }
    }

    @Test
    fun wordBoundaryIsPythonsNotJavas() {
        // Python's \w excludes Devanagari vowel signs; Java's Unicode \w includes them.
        assertTrue(Py.search(SECRET_REGEX, "Enter PIN") != null)
        assertFalse(Py.search(SECRET_REGEX, "spinner") != null)
        assertTrue(Py.search(PAY_REGEX, "ीpay") != null) // vowel sign is not a word char in Python
    }

    @Test
    fun secureScreenshotFromArgb() {
        val black = Screenshot.fromArgb(4, 4, IntArray(16) { 0xFF000000.toInt() })
        val white = Screenshot.fromArgb(4, 4, IntArray(16) { 0xFFFFFFFF.toInt() })
        assertTrue(isSecureScreenshot(black))
        assertFalse(isSecureScreenshot(white))
        assertFalse(isSecureScreenshot(Screenshot.solid(0, 0, 0)))
    }
}

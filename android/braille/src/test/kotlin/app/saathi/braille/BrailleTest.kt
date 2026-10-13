package app.saathi.braille

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrailleTest {
    private fun masks(text: String) = encode(text).map { it.dots }

    @Test fun `letters a to j and their dots`() {
        assertEquals(listOf(dots(1), dots(1, 2), dots(1, 4)), masks("abc"))
        assertEquals(listOf(dots(2, 4, 5)), masks("j"))
        assertEquals(listOf(dots(1, 3, 5, 6)), masks("z"))
    }

    @Test fun `digits get one number sign per run`() {
        assertEquals(
            listOf(NUMBER_SIGN, dots(1, 4, 5), dots(1, 2, 5), dots(1, 2), dots(2, 4), dots(1), dots(1, 4)),
            masks("482913"),
        )
        assertEquals(listOf(NUMBER_SIGN, dots(1), 0, NUMBER_SIGN, dots(1, 2)), masks("1 2"))
    }

    @Test fun `zero is shaped like j`() {
        assertEquals(listOf(NUMBER_SIGN, dots(2, 4, 5)), masks("0"))
    }

    @Test fun `letter a to j right after digits needs a letter sign`() {
        assertEquals(listOf(NUMBER_SIGN, dots(1), LETTER_SIGN, dots(1, 2)), masks("1b"))
        // k is not a digit shape, so no letter sign.
        assertEquals(listOf(NUMBER_SIGN, dots(1), dots(1, 3)), masks("1k"))
    }

    @Test fun `capitals get a capital sign so passwords keep their case`() {
        assertEquals(listOf(CAPITAL_SIGN, dots(1), dots(1)), masks("Aa"))
    }

    @Test fun `password symbols use UEB two-cell signs`() {
        assertEquals(listOf(dots(4), dots(1)), masks("@"))
        assertEquals(listOf(dots(4, 5, 6), dots(1, 4, 5, 6)), masks("#"))
    }

    @Test fun `spaces collapse and are trimmed`() {
        val cells = encode("  hi   you ")
        assertEquals(6, cells.size)
        assertEquals(Cell.Kind.Space, cells[2].kind)
    }

    @Test fun `unknown characters become a full cell, never dropped`() {
        val cells = encode("नमस्ते")
        assertTrue(cells.isNotEmpty())
        assertTrue(cells.all { it.kind == Cell.Kind.Unknown && it.dots == UNKNOWN })
    }

    @Test fun `raised and dotAt agree with the standard layout`() {
        val b = encode("b").single()
        assertTrue(b.raised(dotAt(0, 0)))  // dot 1
        assertTrue(b.raised(dotAt(0, 1)))  // dot 2
        assertFalse(b.raised(dotAt(1, 0))) // dot 4
        assertEquals(6, dotAt(1, 2))
    }

    @Test fun `every symbol cell is in range`() {
        encode("a,b;c:d.e!f?g'h-i@j#k\$l&m*n_o+p=q%r(s)t/u\"v₹").forEach {
            assertTrue(it.dots in 0..0b111111)
        }
    }
}

class OtpTest {
    @Test fun `typical bank OTP`() {
        assertEquals("482913", extractOtp("482913 is your OTP for login. Do not share it with anyone. -HDFC Bank"))
    }

    @Test fun `OTP is picked over the amount`() {
        assertEquals("7351", extractOtp("Your OTP for payment of Rs 1500 at BLINKIT is 7351. Valid for 10 min."))
        assertEquals("7351", extractOtp("₹2500 debit: use code 7351"))
    }

    @Test fun `phone and account numbers are not codes`() {
        assertEquals("552190", extractOtp("Call 9876543210 if not you. Verification code: 552190"))
        assertNull(extractOtp("Call 9876543210 for help"))
    }

    @Test fun `Hindi messages`() {
        assertEquals("80421", extractOtp("आपका ओटीपी 80421 है। किसी से साझा न करें।"))
    }

    @Test fun `no clear code returns null`() {
        assertNull(extractOtp("Your order is on the way"))
        // Two numbers and no code word: ambiguous, so don't guess.
        assertNull(extractOtp("Ref 1234 and 5678"))
    }

    @Test fun `decimals are not codes`() {
        assertNull(extractOtp("Balance 4520.50"))
    }
}

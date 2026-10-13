package app.saathi.safety

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The protected-app lists can't be swapped out, so no caller can switch off R1/R3/R6/R8. */
class SafetyConfigTest {
    @Test
    fun `protected app lists are always the built-in ones`() {
        val c = SafetyConfig(maxAmountInr = 5)
        assertEquals(Apps.PAYMENT_APPS, c.paymentApps)
        assertEquals(Apps.ORDER_APPS, c.orderApps)
        assertEquals(Apps.WHATSAPP, c.whatsapp)
        assertEquals(Apps.APP_ALIASES, c.appAliases)
    }

    @Test
    fun `money cap must be positive`() {
        assertFailsWith<IllegalArgumentException> { SafetyConfig(maxAmountInr = 0) }
        assertFailsWith<IllegalArgumentException> { SafetyConfig(maxAmountInr = -1) }
    }
}

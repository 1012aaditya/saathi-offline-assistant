package app.saathi.braille

/** Words that usually sit next to a one-time code in Indian bank / app SMS. */
private val CODE_WORDS = Regex(
    """(?i)\b(otp|one[\s-]?time|code|verification|passcode|password)\b|ओटीपी|कोड""",
)

/** Money markers: a number right after these is an amount, not a code. */
private val MONEY_BEFORE = Regex("""(?i)(rs\.?|inr|₹)\s*$""")

private val CANDIDATE = Regex("""(?<![\d.,])\d{4,8}(?![\d.,]*\d)""")

/**
 * Find the one-time code in an SMS. Returns null when there's no clear 4–8 digit code.
 *
 * Picks the candidate closest to a code word ("OTP", "code", …); skips amounts ("Rs 1000") and
 * numbers glued to other digits (phone numbers, account numbers). The caller must never log,
 * speak or store the message or the code.
 */
fun extractOtp(message: String): String? {
    val candidates = CANDIDATE.findAll(message)
        .filterNot { MONEY_BEFORE.containsMatchIn(message.substring(0, it.range.first)) }
        .toList()
    if (candidates.isEmpty()) return null
    val words = CODE_WORDS.findAll(message).map { it.range.first }.toList()
    if (words.isEmpty()) return candidates.singleOrNull()?.value
    return candidates.minBy { c -> words.minOf { w -> kotlin.math.abs(c.range.first - w) } }.value
}

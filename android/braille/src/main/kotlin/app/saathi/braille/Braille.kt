package app.saathi.braille

/**
 * Uncontracted (grade 1) Unified English Braille, enough for OTPs, passwords and short sentences.
 *
 * A cell is 6 dots in two columns:
 * ```
 *   1 ● ● 4
 *   2 ● ● 5
 *   3 ● ● 6
 * ```
 * [Cell.dots] is a bitmask: bit 0 = dot 1 … bit 5 = dot 6.
 */
data class Cell(val dots: Int, val kind: Kind, val source: String) {
    enum class Kind { Letter, Digit, Capital, Number, LetterSign, Symbol, Space, Unknown }

    fun raised(dot: Int): Boolean = dots and (1 shl (dot - 1)) != 0

    /** Plain-language name of the cell, e.g. "letter b" or "number sign". Only for non-private text. */
    val spoken: String
        get() = when (kind) {
            Kind.Letter -> "letter $source"
            Kind.Digit -> "digit $source"
            Kind.Capital -> "capital sign"
            Kind.Number -> "number sign"
            Kind.LetterSign -> "letter sign"
            Kind.Space -> "space"
            Kind.Symbol -> SYMBOL_NAMES[source] ?: "symbol"
            Kind.Unknown -> "a character braille can't show here"
        }
}

/** Dot numbers -> bitmask, e.g. dots(1, 2) == 0b11. */
fun dots(vararg n: Int): Int = n.fold(0) { acc, d -> acc or (1 shl (d - 1)) }

private val LETTERS: Map<Char, Int> = mapOf(
    'a' to dots(1), 'b' to dots(1, 2), 'c' to dots(1, 4), 'd' to dots(1, 4, 5), 'e' to dots(1, 5),
    'f' to dots(1, 2, 4), 'g' to dots(1, 2, 4, 5), 'h' to dots(1, 2, 5), 'i' to dots(2, 4), 'j' to dots(2, 4, 5),
    'k' to dots(1, 3), 'l' to dots(1, 2, 3), 'm' to dots(1, 3, 4), 'n' to dots(1, 3, 4, 5), 'o' to dots(1, 3, 5),
    'p' to dots(1, 2, 3, 4), 'q' to dots(1, 2, 3, 4, 5), 'r' to dots(1, 2, 3, 5), 's' to dots(2, 3, 4), 't' to dots(2, 3, 4, 5),
    'u' to dots(1, 3, 6), 'v' to dots(1, 2, 3, 6), 'w' to dots(2, 4, 5, 6), 'x' to dots(1, 3, 4, 6), 'y' to dots(1, 3, 4, 5, 6),
    'z' to dots(1, 3, 5, 6),
)

/** Digits 1–9, 0 reuse the shapes of a–j after a number sign. */
private val DIGITS: Map<Char, Int> = "1234567890".zip("abcdefghij").associate { (d, l) -> d to LETTERS.getValue(l) }

val CAPITAL_SIGN = dots(6)
val NUMBER_SIGN = dots(3, 4, 5, 6)
val LETTER_SIGN = dots(5, 6)

/** UEB symbols; some take two cells. */
private val SYMBOLS: Map<Char, List<Int>> = mapOf(
    ',' to listOf(dots(2)), ';' to listOf(dots(2, 3)), ':' to listOf(dots(2, 5)), '.' to listOf(dots(2, 5, 6)),
    '!' to listOf(dots(2, 3, 5)), '?' to listOf(dots(2, 3, 6)), '\'' to listOf(dots(3)), '-' to listOf(dots(3, 6)),
    '@' to listOf(dots(4), dots(1)), '#' to listOf(dots(4, 5, 6), dots(1, 4, 5, 6)), '$' to listOf(dots(4), dots(2, 3, 4)),
    '&' to listOf(dots(4), dots(1, 2, 3, 4, 6)), '*' to listOf(dots(5), dots(3, 5)), '_' to listOf(dots(4, 6), dots(3, 6)),
    '+' to listOf(dots(5), dots(2, 3, 5)), '=' to listOf(dots(5), dots(2, 3, 5, 6)), '%' to listOf(dots(4, 6), dots(3, 5, 6)),
    '(' to listOf(dots(5), dots(1, 2, 6)), ')' to listOf(dots(5), dots(3, 4, 5)), '/' to listOf(dots(4, 5, 6), dots(3, 4)),
    '"' to listOf(dots(6), dots(2, 3, 5, 6)), '₹' to listOf(dots(4), dots(1, 2, 3, 5)),
)

private val SYMBOL_NAMES = mapOf(
    "," to "comma", ";" to "semicolon", ":" to "colon", "." to "full stop", "!" to "exclamation mark",
    "?" to "question mark", "'" to "apostrophe", "-" to "hyphen", "@" to "at sign", "#" to "hash", "$" to "dollar",
    "&" to "and sign", "*" to "star", "_" to "underscore", "+" to "plus", "=" to "equals", "%" to "percent",
    "(" to "open bracket", ")" to "close bracket", "/" to "slash", "\"" to "quote", "₹" to "rupee",
)

/** Characters braille can't show here (e.g. Devanagari for now) become one all-dots cell. */
val UNKNOWN = dots(1, 2, 3, 4, 5, 6)

/**
 * Encode [text] as braille cells. Rules (UEB grade 1):
 * - a number sign starts each run of digits; a letter sign follows if the next letter is a–j;
 * - each capital letter gets a capital sign (passwords are case-sensitive);
 * - spaces are empty cells; runs of whitespace collapse to one.
 */
fun encode(text: String): List<Cell> {
    val out = mutableListOf<Cell>()
    var inNumber = false
    var lastWasSpace = true
    for (ch in text.trim()) {
        when {
            ch.isWhitespace() -> {
                if (!lastWasSpace) out += Cell(0, Cell.Kind.Space, " ")
                inNumber = false
            }
            ch in DIGITS -> {
                if (!inNumber) out += Cell(NUMBER_SIGN, Cell.Kind.Number, "")
                out += Cell(DIGITS.getValue(ch), Cell.Kind.Digit, ch.toString())
                inNumber = true
            }
            ch.lowercaseChar() in LETTERS -> {
                val lower = ch.lowercaseChar()
                if (inNumber && lower in 'a'..'j') out += Cell(LETTER_SIGN, Cell.Kind.LetterSign, "")
                if (ch.isUpperCase()) out += Cell(CAPITAL_SIGN, Cell.Kind.Capital, "")
                out += Cell(LETTERS.getValue(lower), Cell.Kind.Letter, lower.toString())
                inNumber = false
            }
            ch in SYMBOLS -> {
                SYMBOLS.getValue(ch).forEach { out += Cell(it, Cell.Kind.Symbol, ch.toString()) }
                inNumber = false
            }
            else -> {
                out += Cell(UNKNOWN, Cell.Kind.Unknown, "")
                inNumber = false
            }
        }
        lastWasSpace = ch.isWhitespace()
    }
    return out
}

/**
 * Where each of the 6 dots sits on the reading surface, as (column, row): column 0..1, row 0..2.
 * Dot n is in column (n-1)/3, row (n-1)%3.
 */
fun dotAt(column: Int, row: Int): Int = column * 3 + row + 1

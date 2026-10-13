package app.saathi.safety

import java.math.BigInteger
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Python semantics the safety gate depends on, reproduced exactly on the JVM.
 *
 * saathi/safety.py uses Python `re` on str patterns, where \w, \d, \s and \b are Unicode-aware
 * and differ from Java's defaults (and from Java's UNICODE_CHARACTER_CLASS, whose \w also
 * matches Devanagari vowel signs; Python's does not). So the patterns in this module never use
 * Java's \w, \d, \s or \b: they use the explicit classes below, verified against CPython 3.12
 * over every code point (\w == [\p{L}\p{N}_], \d == \p{Nd}, \s == str.isspace()).
 */
internal object Py {
    /** Python's str.isspace() set: also what re's \s, str.split() and str.strip() use. */
    private const val SPACE_BODY =
        "\\t\\n\\x{0B}\\f\\r\\x{1C}-\\x{1F} \\x{85}\\x{A0}\\x{1680}\\x{2000}-\\x{200A}" +
            "\\x{2028}\\x{2029}\\x{202F}\\x{205F}\\x{3000}"

    /** Python \s */
    const val S = "[$SPACE_BODY]"

    /** Python [^\s<extra>] : pass the extra characters (already regex-escaped). */
    fun notSpaceOr(extra: String): String = "[^$SPACE_BODY$extra]"

    /** Python \d (Unicode decimal digits). */
    const val D = "\\p{Nd}"

    private const val W = "[\\p{L}\\p{N}_]"

    /** Python \b placed before a word character (every \b in safety.py is next to one). */
    const val B_START = "(?<!$W)"

    /** Python \b placed after a word character. */
    const val B_END = "(?!$W)"

    /**
     * UNIX_LINES makes `$` and `.` treat only '\n' as a line end, like Python. (?i) in Python
     * is Unicode case folding: CASE_INSENSITIVE + UNICODE_CASE.
     */
    fun re(pattern: String, ignoreCase: Boolean = false): Pattern {
        var flags = Pattern.UNIX_LINES
        if (ignoreCase) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        return Pattern.compile(pattern, flags)
    }

    /** re.search */
    fun search(p: Pattern, s: String): Matcher? = p.matcher(s).let { if (it.find()) it else null }

    /** re.match (anchored at the start only) */
    fun match(p: Pattern, s: String): Matcher? = p.matcher(s).let { if (it.lookingAt()) it else null }

    /** re.fullmatch */
    fun fullmatch(p: Pattern, s: String): Boolean = p.matcher(s).matches()

    /** re.findall: group 1 if the pattern has groups, else the whole match. */
    fun findall(p: Pattern, s: String): List<String> {
        val m = p.matcher(s)
        val out = mutableListOf<String>()
        while (m.find()) out += if (m.groupCount() >= 1) (m.group(1) ?: "") else m.group()
        return out
    }

    fun isSpace(cp: Int): Boolean =
        cp in 0x09..0x0D || cp in 0x1C..0x20 || cp == 0x85 || cp == 0xA0 || cp == 0x1680 ||
            cp in 0x2000..0x200A || cp == 0x2028 || cp == 0x2029 || cp == 0x202F ||
            cp == 0x205F || cp == 0x3000

    /** str.split() with no separator. */
    fun split(s: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        s.codePoints().forEach { cp ->
            if (isSpace(cp)) {
                if (cur.isNotEmpty()) out += cur.toString().also { cur.setLength(0) }
            } else {
                cur.appendCodePoint(cp)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    /** str.strip() (all Python whitespace is in the BMP, so a char-level trim is exact). */
    fun strip(s: String): String = s.trim { isSpace(it.code) }

    /** str.lower() */
    fun lower(s: String): String = s.lowercase()

    /** len() in code points. */
    fun len(s: String): Int = s.codePointCount(0, s.length)

    /** Python's default str ordering (by code point, not UTF-16 unit). */
    val CODE_POINT_ORDER: Comparator<String> = Comparator { a, b ->
        val ia = a.codePoints().iterator()
        val ib = b.codePoints().iterator()
        while (ia.hasNext() && ib.hasNext()) {
            val c = ia.nextInt().compareTo(ib.nextInt())
            if (c != 0) return@Comparator c
        }
        ia.hasNext().compareTo(ib.hasNext())
    }

    /** str.isdigit(): non-empty, every char a decimal digit or a digit-valued number (e.g. ²). */
    fun isDigit(s: String): Boolean =
        s.isNotEmpty() && s.codePoints().allMatch { cp ->
            Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER.toInt() ||
                (Character.getType(cp) == Character.OTHER_NUMBER.toInt() &&
                    Character.getNumericValue(cp) in 0..9)
        }

    /**
     * int(s) for a digit string: accepts any Unicode decimal digits (e.g. Devanagari ५).
     * Throws NumberFormatException where Python raises ValueError (e.g. "²").
     */
    fun parseInt(s: String): BigInteger {
        if (s.isEmpty()) throw NumberFormatException("invalid literal for int(): ''")
        val ascii = StringBuilder()
        s.codePoints().forEach { cp ->
            if (Character.getType(cp) != Character.DECIMAL_DIGIT_NUMBER.toInt()) {
                throw NumberFormatException("invalid literal for int(): '$s'")
            }
            ascii.append(Character.digit(cp, 10))
        }
        return BigInteger(ascii.toString())
    }

    /**
     * float(s) for strings matched by [\d,]+(?:\.\d{1,2})? after removing commas. Unicode
     * digits are mapped to ASCII. Throws NumberFormatException where Python raises ValueError
     * (e.g. the empty string left by "₹,").
     */
    fun parseFloat(s: String): Double {
        val ascii = StringBuilder()
        s.codePoints().forEach { cp ->
            if (Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER.toInt()) {
                ascii.append(Character.digit(cp, 10))
            } else {
                ascii.appendCodePoint(cp)
            }
        }
        val t = ascii.toString()
        if (!Regex("[0-9]+(\\.[0-9]+)?").matches(t)) {
            throw NumberFormatException("could not convert string to float: '$s'")
        }
        return t.toDouble()
    }

    fun saturatedLong(v: BigInteger): Long =
        if (v.bitLength() < 64) v.toLong() else Long.MAX_VALUE

    fun saturatedInt(v: BigInteger): Int =
        if (v.bitLength() < 32) v.toInt() else Int.MAX_VALUE
}

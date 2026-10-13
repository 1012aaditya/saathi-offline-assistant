package app.saathi.braille

/**
 * Which letters (or quiz items) the learner knows, from their last [KEEP] answers each.
 * Stored on the phone only, as a short string ("a:1101;b:10").
 */
class Mastery(saved: String = "") {
    private val results: MutableMap<String, String> = saved.split(';')
        .mapNotNull { e -> e.split(':').takeIf { it.size == 2 && it[0].isNotEmpty() && it[1].all { c -> c == '0' || c == '1' } } }
        .associate { (k, v) -> k to v.takeLast(KEEP) }
        .toMutableMap()

    fun record(item: String, correct: Boolean) {
        results[item] = ((results[item] ?: "") + if (correct) "1" else "0").takeLast(KEEP)
    }

    fun attempts(item: String): Int = results[item]?.length ?: 0

    /** 0..1, or null if never tried. */
    fun accuracy(item: String): Float? = results[item]?.let { r -> r.count { it == '1' }.toFloat() / r.length }

    /** Known = at least 3 tries and 80% right recently. */
    fun known(item: String): Boolean = attempts(item) >= 3 && (accuracy(item) ?: 0f) >= 0.8f

    /** Tried items with the lowest accuracy first (fewer tries break ties). */
    fun weakest(n: Int): List<String> = results.keys
        .filter { !known(it) }
        .sortedWith(compareBy({ accuracy(it) }, { attempts(it) }))
        .take(n)

    fun knownCount(items: Iterable<String>): Int = items.count { known(it) }

    fun serialize(): String = results.entries.joinToString(";") { "${it.key}:${it.value}" }

    companion object {
        const val KEEP = 6
    }
}

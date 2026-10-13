package app.saathi.safety

/**
 * rapidfuzz.fuzz.token_set_ratio (rapidfuzz 3.x, processor=None, score_cutoff=0), ported from
 * rapidfuzz/fuzz_py.py. Lengths and the Indel distance are over code points, like Python str.
 */
object Fuzz {
    fun tokenSetRatio(s1: String, s2: String): Double {
        val tokensA = Py.split(s1).toSet()
        val tokensB = Py.split(s2).toSet()
        // FuzzyWuzzy compatibility: an empty side scores 0.
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0

        val intersect = tokensA intersect tokensB
        val diffAb = tokensA - tokensB
        val diffBa = tokensB - tokensA

        // one sentence is part of the other one
        if (intersect.isNotEmpty() && (diffAb.isEmpty() || diffBa.isEmpty())) return 100.0

        val diffAbJoined = diffAb.sortedWith(Py.CODE_POINT_ORDER).joinToString(" ")
        val diffBaJoined = diffBa.sortedWith(Py.CODE_POINT_ORDER).joinToString(" ")

        val abLen = Py.len(diffAbJoined)
        val baLen = Py.len(diffBaJoined)
        val sectLen = Py.len(intersect.joinToString(" "))
        val sep = if (sectLen != 0) 1 else 0

        // string length sect+ab <-> sect and sect+ba <-> sect
        val sectAbLen = sectLen + sep + abLen
        val sectBaLen = sectLen + sep + baLen

        // score_cutoff = 0: the cutoff distance is the full length sum, which Indel never exceeds
        val dist = indelDistance(diffAbJoined, diffBaJoined)
        val result = normDistance(dist, sectAbLen + sectBaLen)

        // exit early since the other ratios are 0
        if (sectLen == 0) return result

        val sectAbRatio = normDistance(sep + abLen, sectLen + sectAbLen)
        val sectBaRatio = normDistance(sep + baLen, sectLen + sectBaLen)
        return maxOf(result, sectAbRatio, sectBaRatio)
    }

    private fun normDistance(dist: Int, lensum: Int): Double =
        if (lensum != 0) 100 - 100.0 * dist / lensum else 100.0

    /** Indel distance = len(a) + len(b) - 2 * LCS(a, b), over code points. */
    internal fun indelDistance(a: String, b: String): Int {
        val x = a.codePoints().toArray()
        val y = b.codePoints().toArray()
        var prev = IntArray(y.size + 1)
        var cur = IntArray(y.size + 1)
        for (i in 1..x.size) {
            for (j in 1..y.size) {
                cur[j] = if (x[i - 1] == y[j - 1]) prev[j - 1] + 1 else maxOf(prev[j], cur[j - 1])
            }
            val t = prev
            prev = cur
            cur = t
        }
        return x.size + y.size - 2 * prev[y.size]
    }
}

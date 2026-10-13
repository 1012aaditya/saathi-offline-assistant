package app.saathi.photo

import app.saathi.agent.Lang
import app.saathi.walk.Box
import app.saathi.walk.Det
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Photo description: detections in, one short honest sentence out (English or Hindi).
 * Pure Kotlin, no Android types, unit-tested on the JVM (app/src/test/.../photo/DescribeTest.kt).
 *
 * Honesty rules:
 *  - Only names things the two YOLO models found with enough confidence; unsure ones get "maybe".
 *  - Always ends with "I can only recognise common objects." when something was found.
 *  - Never says a place is safe, clear or empty: a missed object would turn that into a lie.
 */

// ------------------------------------------------------------------------------------------------
// Crops and merging
// ------------------------------------------------------------------------------------------------

/** A part of the photo, as 0..1 fractions of the upright image. The first crop is always the whole photo. */
data class Crop(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
    val w get() = x1 - x0
    val h get() = y1 - y0
    val isFull get() = x0 <= 0.0 && y0 <= 0.0 && x1 >= 1.0 && y1 >= 1.0

    companion object {
        val FULL = Crop(0.0, 0.0, 1.0, 1.0)
    }
}

/** One thing found in the photo (box as 0..1 fractions of the whole photo). */
data class Found(val thing: Thing, val score: Double, val box: Box)

object PhotoMerge {
    /** Each tile covers this share of the width and height; neighbours overlap by 2 × it − 1 (20 %). */
    const val TILE = 0.6

    /** Smaller photos gain nothing from tiles (the model input is 256 px). */
    const val MIN_SIDE_FOR_TILES = 480

    /**
     * The whole photo, then (if it is big enough) four overlapping tiles. YOLO sees each at 256 px, so a
     * tile shows small things (a cup, a phone, a key-sized object) at ~1.7× the detail of the whole photo.
     */
    fun crops(width: Int, height: Int): List<Crop> {
        if (min(width, height) < MIN_SIDE_FOR_TILES) return listOf(Crop.FULL)
        val o = 1.0 - TILE
        return listOf(
            Crop.FULL,
            Crop(0.0, 0.0, TILE, TILE),
            Crop(o, 0.0, 1.0, TILE),
            Crop(0.0, o, TILE, 1.0),
            Crop(o, o, 1.0, 1.0),
        )
    }

    /** A detection made inside [c] (box as fractions of the crop) → fractions of the whole photo. */
    fun toPhoto(d: Det, c: Crop): Det =
        d.copy(x = c.x0 + d.x * c.w, y = c.y0 + d.y * c.h, w = d.w * c.w, h = d.h * c.h)

    /**
     * True when a tile's box touches a tile edge that is inside the photo: the object was cut by the tile,
     * so the box is only part of it (the whole-photo pass or the neighbouring tile sees it whole).
     */
    fun cutByTile(d: Det, c: Crop, margin: Double = 0.01): Boolean =
        (c.x0 > 0 && d.x <= margin) || (c.y0 > 0 && d.y <= margin) ||
            (c.x1 < 1 && d.x + d.w >= 1 - margin) || (c.y1 < 1 && d.y + d.h >= 1 - margin)

    private fun area(b: Box) = b.w * b.h

    private fun inter(a: Box, b: Box): Double {
        val ix = max(0.0, min(a.x + a.w, b.x + b.w) - max(a.x, b.x))
        val iy = max(0.0, min(a.y + a.h, b.y + b.h) - max(a.y, b.y))
        return ix * iy
    }

    /** Same object twice: boxes overlap a lot (IoU), or the smaller one sits mostly inside the bigger one. */
    fun sameObject(a: Box, b: Box): Boolean {
        val i = inter(a, b)
        if (i <= 0) return false
        val iou = i / (area(a) + area(b) - i)
        val inside = i / max(1e-9, min(area(a), area(b)))
        return iou > 0.45 || inside > 0.7
    }

    /**
     * All passes (crop + its raw detections, crop-relative boxes) → one list of things, each said once.
     * Unknown classes and low scores are dropped; tile boxes cut by the tile edge are dropped; look-alike
     * classes from the two models ([Thing.key]) and repeats from overlapping crops are merged. Whole-photo
     * boxes win over tile boxes (their size and position are complete); the higher score is kept.
     */
    fun merge(passes: List<Pair<Crop, List<Det>>>): List<Found> {
        data class Cand(val found: Found, val full: Boolean)

        val cands = ArrayList<Cand>()
        for ((crop, dets) in passes) {
            for (d in dets) {
                val e = Vocab.entry(d.label) ?: continue
                if (d.score < e.minScore) continue
                if (!crop.isFull && cutByTile(d, crop)) continue
                val p = toPhoto(d, crop)
                cands.add(Cand(Found(e.thing, d.score, p.box), crop.isFull))
            }
        }
        cands.sortWith(compareByDescending<Cand> { it.full }.thenByDescending { it.found.score })
        val kept = ArrayList<Found>()
        for (c in cands) {
            val i = kept.indexOfFirst { it.thing.key == c.found.thing.key && sameObject(it.box, c.found.box) }
            if (i < 0) {
                kept.add(c.found)
            } else if (c.found.score > kept[i].score) {
                kept[i] = kept[i].copy(score = c.found.score) // seen again, more surely: keep the better score
            }
        }
        return kept
    }
}

// ------------------------------------------------------------------------------------------------
// Picture quality
// ------------------------------------------------------------------------------------------------

data class Quality(val dark: Boolean = false, val bright: Boolean = false, val plain: Boolean = false, val blurry: Boolean = false) {
    companion object {
        val OK = Quality()
    }
}

object PhotoQuality {
    /** Mean brightness below this (0..255) = "very dark". */
    const val DARK = 30.0

    /** Mean above this = washed out / facing a light. */
    const val BRIGHT = 237.0

    /** Brightness spread below this = almost one colour (covered lens, a plain wall up close). */
    const val PLAIN_STD = 10.0

    /**
     * Variance of the Laplacian below this (on a ~320 px wide grey picture) = blurry. A starting guess:
     * sharp phone photos measure in the hundreds; tune on real blurry shots.
     */
    const val BLUR_VAR = 35.0

    data class Stats(val mean: Double, val std: Double, val lapVar: Double)

    /** [luma]: w × h grey values 0..255, row by row. */
    fun stats(luma: FloatArray, w: Int, h: Int): Stats {
        require(luma.size >= w * h && w > 0 && h > 0)
        var s = 0.0
        var s2 = 0.0
        for (i in 0 until w * h) {
            val v = luma[i].toDouble()
            s += v
            s2 += v * v
        }
        val n = (w * h).toDouble()
        val mean = s / n
        val std = sqrt(max(0.0, s2 / n - mean * mean))
        var ls = 0.0
        var ls2 = 0.0
        var m = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val l = (4 * luma[i] - luma[i - 1] - luma[i + 1] - luma[i - w] - luma[i + w]).toDouble()
            ls += l
            ls2 += l * l
            m++
        }
        val lapVar = if (m == 0) 0.0 else max(0.0, ls2 / m - (ls / m) * (ls / m))
        return Stats(mean, std, lapVar)
    }

    fun assess(luma: FloatArray, w: Int, h: Int): Quality = assess(stats(luma, w, h))

    fun assess(s: Stats): Quality {
        val dark = s.mean < DARK
        val bright = !dark && s.mean > BRIGHT
        val plain = !dark && s.std < PLAIN_STD
        val blurry = !dark && !plain && s.lapVar < BLUR_VAR
        return Quality(dark = dark, bright = bright, plain = plain, blurry = blurry)
    }
}

// ------------------------------------------------------------------------------------------------
// Wording
// ------------------------------------------------------------------------------------------------

enum class Where { Left, Middle, Right }

enum class Range { Near, Mid, Far }

/** All the found things of one kind. */
data class Group(val thing: Thing, val members: List<Found>) {
    val count get() = members.size
    val best get() = members.maxOf { it.score }
    val biggest get() = members.maxOf { it.box.w * it.box.h }
}

object Describe {
    /** Below this, a kind of thing is said with "maybe". */
    const val SURE = 0.5

    /** At most this many kinds are named; the rest become "and a few other things". */
    const val MAX_GROUPS = 5

    /** Left / middle / right thirds by the box centre. Left and right are the photo's (= yours, back camera). */
    fun where(b: Box): Where {
        val cx = b.x + b.w / 2
        return when {
            cx < 1.0 / 3 -> Where.Left
            cx > 2.0 / 3 -> Where.Right
            else -> Where.Middle
        }
    }

    /** Big in the picture = close; tiny = far. Rough: a cup up close and a bus far away can be the same size. */
    fun range(b: Box): Range = when {
        b.h >= 0.6 || b.w * b.h >= 0.3 -> Range.Near
        b.w * b.h <= 0.01 && b.h < 0.15 -> Range.Far
        else -> Range.Mid
    }

    /** Group by kind: people first, then the kinds that take up the most of the picture. */
    fun groups(found: List<Found>): List<Group> =
        found.groupBy { it.thing.key }.values
            .map { Group(it.first().thing, it) }
            .sortedWith(compareByDescending<Group> { it.thing.key == "person" }.thenByDescending { it.biggest })

    private val EN_NUM = listOf("no", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")
    private val HI_NUM = listOf("कोई", "एक", "दो", "तीन", "चार", "पाँच", "छह", "सात", "आठ", "नौ", "दस")

    fun enNumber(n: Int): String = EN_NUM.getOrNull(n) ?: n.toString()

    fun hiNumber(n: Int): String = HI_NUM.getOrNull(n) ?: n.toString()

    private fun article(word: String) = if (word.first().lowercaseChar() in "aeiou") "an" else "a"

    /** live: the back camera right now ("on your left", "ahead"); otherwise a saved photo ("on the left"). */
    private fun enWhere(w: Where, live: Boolean) = when (w) {
        Where.Left -> if (live) "on your left" else "on the left"
        Where.Middle -> if (live) "ahead" else "in the middle"
        Where.Right -> if (live) "on your right" else "on the right"
    }

    private fun hiWhere(w: Where, live: Boolean) = when (w) {
        Where.Left -> if (live) "बाईं ओर" else "बाईं तरफ़"
        Where.Middle -> if (live) "सामने" else "बीच में"
        Where.Right -> if (live) "दाईं ओर" else "दाईं तरफ़"
    }

    private fun enRange(r: Range, live: Boolean) = when (r) {
        Range.Near -> if (live) "close" else "close up"
        Range.Far -> if (live) "far away" else "in the background"
        Range.Mid -> null
    }

    private fun hiRange(r: Range) = when (r) {
        Range.Near -> "पास में"
        Range.Far -> "दूर"
        Range.Mid -> null
    }

    /** One kind of thing, e.g. "two people ahead", "maybe a door on your right", "three chairs, two on your left and one ahead". */
    fun phrase(g: Group, lang: Lang, live: Boolean): String {
        val n = g.count
        val zones = g.members.map { where(it.box) }
        val distinct = Where.entries.filter { it in zones }
        val ranges = g.members.map { range(it.box) }.distinct()
        val range = if (distinct.size == 1 && ranges.size == 1) ranges.first() else Range.Mid
        val maybe = g.best < SURE
        return if (lang == Lang.HI) {
            val head = "${if (n == 1) "एक" else hiNumber(n)} ${if (n == 1) g.thing.hi else g.thing.hiPlural}"
            val body = if (distinct.size == 1) {
                listOfNotNull(hiWhere(distinct.first(), live), hiRange(range), head).joinToString(" ")
            } else {
                head + " — " + distinct.joinToString(", ") { z -> "${hiNumber(zones.count { it == z })} ${hiWhere(z, live)}" }
            }
            if (maybe) "शायद $body" else body
        } else {
            val head = if (n == 1) "${article(g.thing.en)} ${g.thing.en}" else "${enNumber(n)} ${g.thing.enPlural}"
            val body = if (distinct.size == 1) {
                listOfNotNull(head, enRange(range, live), enWhere(distinct.first(), live)).joinToString(" ")
            } else {
                val parts = distinct.map { z -> "${enNumber(zones.count { it == z })} ${enWhere(z, live)}" }
                "$head, " + if (parts.size == 2) parts.joinToString(" and ") else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
            }
            if (maybe) "maybe $body" else body
        }
    }

    private fun capitalise(s: String) = s.replaceFirstChar { it.uppercaseChar() }

    /** Spoken notes about the picture itself (dark, blurry…), at most two. */
    fun qualityNotes(q: Quality, lang: Lang): List<String> {
        val t = PhotoTexts(lang)
        return buildList {
            if (q.dark) add(t.dark)
            if (q.bright) add(t.bright)
            if (q.plain) add(t.plain)
            if (q.blurry) add(t.blurry)
        }.take(2)
    }

    /**
     * The whole description: picture-quality notes, then what was found (or that nothing was), then the
     * honest limit. [live]: taken just now with the back camera (directions are "yours").
     */
    fun describe(found: List<Found>, quality: Quality, lang: Lang, live: Boolean): String {
        val t = PhotoTexts(lang)
        val notes = qualityNotes(quality, lang)
        val gs = groups(found)
        if (gs.isEmpty()) return (notes + (if (live) t.nothingLive else t.nothingPhoto)).joinToString(" ")
        val phrases = gs.take(MAX_GROUPS).map { phrase(it, lang, live) }.toMutableList()
        if (gs.size > MAX_GROUPS) phrases.add(t.others)
        // Kinds spread over several places read better as separate sentences.
        val spread = gs.take(MAX_GROUPS).any { g -> g.members.map { where(it.box) }.distinct().size > 1 }
        val end = if (lang == Lang.HI) "।" else "."
        val body = if (spread) {
            phrases.joinToString(" ") { capitalise(it) + end }
        } else {
            capitalise(phrases.joinToString(", ")) + end
        }
        return (notes + body + t.onlyCommon).joinToString(" ")
    }

    // ---- when a saved photo was taken -----------------------------------------------------------

    private val EN_MONTHS = listOf(
        "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December",
    )
    private val HI_MONTHS = listOf(
        "जनवरी", "फ़रवरी", "मार्च", "अप्रैल", "मई", "जून", "जुलाई", "अगस्त", "सितंबर", "अक्टूबर", "नवंबर", "दिसंबर",
    )

    /** "Taken today at 6:40 PM." / "Taken on 3 October at 9:05 AM." · "आज शाम 6:40 बजे ली गई।" */
    fun takenAt(millis: Long, now: Long, zone: ZoneId, lang: Lang): String {
        val t = Instant.ofEpochMilli(millis).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val d = t.toLocalDate()
        val h12 = (t.hour % 12).let { if (it == 0) 12 else it }
        val clock = "%d:%02d".format(h12, t.minute)
        return if (lang == Lang.HI) {
            val day = when {
                d == today -> "आज"
                d == today.minusDays(1) -> "कल"
                d.year == today.year -> "${d.dayOfMonth} ${HI_MONTHS[d.monthValue - 1]} को"
                else -> "${d.dayOfMonth} ${HI_MONTHS[d.monthValue - 1]} ${d.year} को"
            }
            val part = when (t.hour) {
                in 4..11 -> "सुबह"
                in 12..15 -> "दोपहर"
                in 16..19 -> "शाम"
                else -> "रात"
            }
            "$day $part $clock बजे ली गई।"
        } else {
            val day = when {
                d == today -> "today"
                d == today.minusDays(1) -> "yesterday"
                d.year == today.year -> "on ${d.dayOfMonth} ${EN_MONTHS[d.monthValue - 1]}"
                else -> "on ${d.dayOfMonth} ${EN_MONTHS[d.monthValue - 1]} ${d.year}"
            }
            "Taken $day at $clock ${if (t.hour < 12) "AM" else "PM"}."
        }
    }

    /**
     * What is said before a saved photo's description: which photo, when it was taken, and honest notes
     * (a screenshot; only some photos shared with Saathi).
     */
    fun photoHeader(index: Int, takenMillis: Long?, now: Long, zone: ZoneId, lang: Lang, screenshot: Boolean, limited: Boolean): String {
        val t = PhotoTexts(lang)
        return listOfNotNull(
            t.whichPhoto(index),
            takenMillis?.let { takenAt(it, now, zone, lang) } ?: t.unknownTime,
            if (screenshot) t.screenshot else null,
            if (limited && index == 0) t.limited else null,
        ).joinToString(" ")
    }
}

/** Everything the photo screen says, English or Hindi (Devanagari). */
class PhotoTexts(private val lang: Lang) {
    private fun s(en: String, hi: String) = if (lang == Lang.HI) hi else en

    val onlyCommon get() = s("I can only recognise common objects.", "मैं सिर्फ़ आम चीज़ें पहचान सकता हूँ।")
    val nothingLive get() = s(
        "I couldn't recognise anything clearly. Try more light or step back.",
        "मुझे कुछ साफ़ पहचान में नहीं आया। ज़्यादा रोशनी में या थोड़ा पीछे हटकर फिर कोशिश करें।",
    )
    val nothingPhoto get() = s(
        "I couldn't recognise anything clearly in this photo.",
        "इस फ़ोटो में मुझे कुछ साफ़ पहचान में नहीं आया।",
    )
    val others get() = s("and a few other things", "और कुछ दूसरी चीज़ें")
    val dark get() = s("It's very dark.", "बहुत अँधेरा है।")
    val bright get() = s("It's very bright. The camera may be facing a light.", "बहुत तेज़ रोशनी है। कैमरा शायद रोशनी की तरफ़ है।")
    val plain get() = s("The picture is almost all one colour. The camera may be covered.", "तस्वीर लगभग एक ही रंग की है। कैमरा शायद ढका है।")
    val blurry get() = s("The photo looks blurry. Hold the phone still.", "फ़ोटो धुंधली है। फ़ोन स्थिर रखें।")
    val unknownTime get() = s("I don't know when it was taken.", "पता नहीं यह कब ली गई।")
    val screenshot get() = s("It looks like a screenshot. I can't read text in pictures.", "यह स्क्रीनशॉट लगता है। मैं तस्वीरों में लिखा हुआ नहीं पढ़ सकता।")
    val limited get() = s(
        "You let me see only some of your photos, so this may not be your newest one.",
        "आपने मुझे सिर्फ़ कुछ फ़ोटो देखने दी हैं, इसलिए यह शायद आपकी सबसे नई फ़ोटो न हो।",
    )

    fun whichPhoto(index: Int) = if (index == 0) {
        s("Your latest photo.", "आपकी सबसे नई फ़ोटो।")
    } else {
        s("Photo ${index + 1}, counting from the newest.", "सबसे नई से गिनकर फ़ोटो ${index + 1}।")
    }

    // ---- screen lines ----
    val pointCamera get() = s(
        "Hold the phone up with the back camera facing out. Taking a photo.",
        "फ़ोन उठाइए, पीछे वाला कैमरा सामने की ओर। फ़ोटो ले रहा हूँ।",
    )
    val cameraStarting get() = s("The camera is still starting. Try again in a moment.", "कैमरा अभी शुरू हो रहा है। थोड़ी देर में फिर कोशिश करें।")
    val cameraWhy get() = s(
        "To describe what's in front of you, I need the camera. Photos are checked on this phone and never sent.",
        "सामने क्या है बताने के लिए मुझे कैमरा चाहिए। फ़ोटो इसी फ़ोन पर जाँची जाती है, कहीं भेजी नहीं जाती।",
    )
    val cameraRefused get() = s(
        "I can't take photos without the camera. You can allow it in the phone's settings.",
        "कैमरे के बिना मैं फ़ोटो नहीं ले सकता। आप फ़ोन की सेटिंग में इसकी अनुमति दे सकते हैं।",
    )
    val photosWhy get() = s(
        "To describe your last photo, I need to see your photos. They are read only on this phone and never uploaded.",
        "आपकी आखिरी फ़ोटो बताने के लिए मुझे आपकी फ़ोटो देखनी होंगी। वे सिर्फ़ इसी फ़ोन पर पढ़ी जाती हैं, कहीं अपलोड नहीं होतीं।",
    )
    val photosRefused get() = s(
        "I can't see your photos without permission. You can allow it in the phone's settings.",
        "अनुमति के बिना मैं आपकी फ़ोटो नहीं देख सकता। आप फ़ोन की सेटिंग में अनुमति दे सकते हैं।",
    )
    val noPhotos get() = s("I couldn't find any photos on this phone.", "इस फ़ोन पर मुझे कोई फ़ोटो नहीं मिली।")
    val oldest get() = s("That was your oldest photo.", "वह आपकी सबसे पुरानी फ़ोटो थी।")
    val newest get() = s("This is already your newest photo.", "यह पहले से आपकी सबसे नई फ़ोटो है।")
    val photoUnreadable get() = s("I couldn't open that photo.", "वह फ़ोटो खुल नहीं पाई।")
    val captureFailed get() = s("I couldn't take the photo. Try again.", "फ़ोटो नहीं ले पाया। फिर कोशिश करें।")
    val modelsFailed get() = s("Photo description couldn't start on this phone.", "इस फ़ोन पर फ़ोटो बताना शुरू नहीं हो पाया।")
    val takeFirst get() = s("Take a photo first, then say “Save it”.", "पहले फ़ोटो लीजिए, फिर “सेव करो” बोलिए।")
    val alreadySaved get() = s("It's already saved.", "यह पहले से सेव है।")
    val saveNeedsAndroid10 get() = s("Saving photos needs Android 10 or newer.", "फ़ोटो सेव करने के लिए Android 10 या नया चाहिए।")
    val saved get() = s("Saved to your photos, in the Saathi album.", "आपकी फ़ोटो में, Saathi एल्बम में सेव कर दी।")
    val saveFailed get() = s("I couldn't save it.", "सेव नहीं हो पाई।")
    val nothingYet get() = s("Nothing described yet.", "अभी कुछ नहीं बताया।")
}

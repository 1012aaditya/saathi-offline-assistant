package app.saathi.photo

import app.saathi.agent.Lang
import app.saathi.walk.Box
import app.saathi.walk.Det
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

/** Photo description: crops, merging, wording (English + Hindi), picture quality, "taken at". */
class DescribeTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    private fun found(key: String, score: Double, x: Double, y: Double, w: Double, h: Double) =
        Found(Vocab.thing(key)!!, score, Box(x, y, w, h))

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    // ---- vocabulary -----------------------------------------------------------------------------

    /** Every class name we decode must exist in the real model class lists (walk/models), spelled exactly. */
    @Test fun `vocabulary labels exist in the bundled models`() {
        val models = File("../../walk/models").takeIf { it.isDirectory } ?: File("walk/models")
        val names = listOf("coco.json", "oiv7.json").flatMap { f ->
            Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(File(models, f).readText()).map { it.groupValues[1] }.toList()
        }.toSet()
        val missing = Vocab.LABELS.filter { it !in names }
        assertTrue("not in the models: $missing", missing.isEmpty())
    }

    @Test fun `no body parts or gendered people classes, people come from COCO person only`() {
        for (l in listOf("Man", "Woman", "Boy", "Girl", "Person", "Human face", "Human hand", "Clothing")) {
            assertEquals(null, Vocab.entry(l))
        }
        assertEquals("person", Vocab.entry("person")!!.thing.key)
    }

    // ---- crops + merge --------------------------------------------------------------------------

    @Test fun `small photos are checked whole, big ones whole plus four overlapping tiles`() {
        assertEquals(listOf(Crop.FULL), PhotoMerge.crops(320, 240))
        val cs = PhotoMerge.crops(1440, 1920)
        assertEquals(5, cs.size)
        assertEquals(Crop.FULL, cs[0])
        assertTrue(cs.drop(1).all { !it.isFull && it.w == PhotoMerge.TILE && it.h == PhotoMerge.TILE })
        // Tiles overlap in the middle, so a small thing on a seam is whole in at least one tile.
        assertTrue(cs[1].x1 > cs[2].x0 && cs[1].y1 > cs[3].y0)
    }

    @Test fun `tile boxes map back to the whole photo`() {
        val tile = Crop(0.4, 0.4, 1.0, 1.0)
        val d = PhotoMerge.toPhoto(Det("cup", 0.9, 0.5, 0.5, 0.1, 0.2), tile)
        assertEquals(0.7, d.x, 1e-9)
        assertEquals(0.7, d.y, 1e-9)
        assertEquals(0.06, d.w, 1e-9)
        assertEquals(0.12, d.h, 1e-9)
    }

    @Test fun `a box cut by an inner tile edge is dropped, one on the photo edge is kept`() {
        val tl = Crop(0.0, 0.0, 0.6, 0.6)
        assertTrue(PhotoMerge.cutByTile(Det("cup", 0.9, 0.8, 0.2, 0.2, 0.2), tl)) // touches the tile's right edge (inside the photo)
        assertFalse(PhotoMerge.cutByTile(Det("cup", 0.9, 0.0, 0.2, 0.2, 0.2), tl)) // touches the photo's left edge
    }

    @Test fun `look-alike classes from both models are one thing, low scores and unknown classes are dropped`() {
        val full = Crop.FULL to listOf(
            Det("dining table", 0.8, 0.3, 0.5, 0.4, 0.3),
            Det("Table", 0.6, 0.31, 0.52, 0.38, 0.28), // Open Images sees the same table
            Det("chair", 0.35, 0.0, 0.5, 0.2, 0.3), // below COCO's floor
            Det("tie", 0.95, 0.4, 0.1, 0.1, 0.2), // not in the vocabulary
        )
        val out = PhotoMerge.merge(listOf(full))
        assertEquals(1, out.size)
        assertEquals("table", out[0].thing.key)
        assertEquals(0.8, out[0].score, 1e-9)
    }

    @Test fun `a small thing seen only in a tile is added, a repeat of a whole-photo box is merged`() {
        val tile = Crop(0.4, 0.4, 1.0, 1.0)
        val passes = listOf(
            Crop.FULL to listOf(Det("person", 0.7, 0.45, 0.2, 0.2, 0.6)),
            tile to listOf(
                Det("person", 0.9, 0.083, 0.0, 0.333, 0.667), // same person, cut at the tile top → dropped
                Det("cell phone", 0.6, 0.6, 0.6, 0.05, 0.08), // only visible at tile detail
            ),
        )
        val out = PhotoMerge.merge(passes)
        assertEquals(listOf("person", "phone"), out.map { it.thing.key })
        assertEquals(0.45, out[0].box.x, 1e-9) // whole-photo box kept
        assertEquals(0.4 + 0.6 * 0.6, out[1].box.x, 1e-9)
    }

    @Test fun `a repeat from a tile raises the score but keeps the whole-photo box`() {
        val tile = Crop(0.0, 0.0, 0.6, 0.6)
        val out = PhotoMerge.merge(
            listOf(
                Crop.FULL to listOf(Det("cup", 0.45, 0.2, 0.2, 0.1, 0.1)),
                tile to listOf(Det("cup", 0.85, 0.2 / 0.6, 0.2 / 0.6, 0.1 / 0.6, 0.1 / 0.6)),
            ),
        )
        assertEquals(1, out.size)
        assertEquals(0.85, out[0].score, 1e-9)
        assertEquals(0.1, out[0].box.w, 1e-9)
    }

    // ---- wording --------------------------------------------------------------------------------

    @Test fun `left, middle, right by box centre, near and far by size`() {
        assertEquals(Where.Left, Describe.where(Box(0.0, 0.0, 0.2, 0.2)))
        assertEquals(Where.Middle, Describe.where(Box(0.4, 0.0, 0.2, 0.2)))
        assertEquals(Where.Right, Describe.where(Box(0.8, 0.0, 0.2, 0.2)))
        assertEquals(Range.Near, Describe.range(Box(0.2, 0.1, 0.4, 0.8)))
        assertEquals(Range.Far, Describe.range(Box(0.2, 0.1, 0.05, 0.1)))
        assertEquals(Range.Mid, Describe.range(Box(0.2, 0.1, 0.2, 0.3)))
    }

    @Test fun `the example sentence, live camera`() {
        val f = listOf(
            found("table", 0.8, 0.4, 0.55, 0.25, 0.2),
            found("person", 0.9, 0.35, 0.2, 0.1, 0.4),
            found("person", 0.85, 0.52, 0.2, 0.1, 0.4),
            found("chair", 0.7, 0.05, 0.5, 0.15, 0.25),
        )
        assertEquals(
            "Two people ahead, a table ahead, a chair on your left. I can only recognise common objects.",
            Describe.describe(f, Quality.OK, Lang.EN, live = true),
        )
    }

    @Test fun `a saved photo says the picture's left and middle, not yours`() {
        val f = listOf(found("dog", 0.9, 0.1, 0.3, 0.2, 0.3), found("car", 0.8, 0.45, 0.4, 0.06, 0.05))
        assertEquals(
            "A dog on the left, a car in the background in the middle. I can only recognise common objects.",
            Describe.describe(f, Quality.OK, Lang.EN, live = false),
        )
    }

    @Test fun `near things say close, articles follow the word`() {
        val f = listOf(found("umbrella", 0.9, 0.3, 0.1, 0.4, 0.8))
        assertEquals("An umbrella close ahead. I can only recognise common objects.", Describe.describe(f, Quality.OK, Lang.EN, live = true))
    }

    @Test fun `a kind spread over places reads as separate sentences, bigger kinds first`() {
        val f = listOf(
            found("chair", 0.8, 0.05, 0.5, 0.15, 0.25),
            found("chair", 0.8, 0.1, 0.4, 0.1, 0.25),
            found("chair", 0.8, 0.45, 0.5, 0.1, 0.25),
            found("door", 0.9, 0.75, 0.1, 0.2, 0.5),
        )
        assertEquals(
            "A door on your right. Three chairs, two on your left and one ahead. I can only recognise common objects.",
            Describe.describe(f, Quality.OK, Lang.EN, live = true),
        )
    }

    @Test fun `unsure things get maybe`() {
        val f = listOf(found("door", 0.46, 0.75, 0.1, 0.2, 0.5))
        assertEquals("Maybe a door on your right. I can only recognise common objects.", Describe.describe(f, Quality.OK, Lang.EN, live = true))
    }

    @Test fun `more than five kinds end with a few other things`() {
        val keys = listOf("person", "chair", "table", "cup", "bottle", "book", "phone")
        val f = keys.mapIndexed { i, k -> found(k, 0.9, 0.4, 0.1 * i, 0.2, 0.1) }
        val s = Describe.describe(f, Quality.OK, Lang.EN, live = true)
        assertTrue(s, s.contains("and a few other things."))
        assertFalse(s, s.contains("phone"))
    }

    @Test fun `Hindi wording`() {
        val f = listOf(
            found("person", 0.9, 0.35, 0.2, 0.1, 0.4),
            found("person", 0.85, 0.52, 0.2, 0.1, 0.4),
            found("chair", 0.7, 0.05, 0.5, 0.15, 0.25),
        )
        assertEquals(
            "सामने दो लोग, बाईं ओर एक कुर्सी। मैं सिर्फ़ आम चीज़ें पहचान सकता हूँ।",
            Describe.describe(f, Quality.OK, Lang.HI, live = true),
        )
        val spread = listOf(found("chair", 0.48, 0.05, 0.5, 0.15, 0.25), found("chair", 0.45, 0.75, 0.5, 0.15, 0.25))
        assertEquals(
            "शायद दो कुर्सियाँ — एक बाईं तरफ़, एक दाईं तरफ़। मैं सिर्फ़ आम चीज़ें पहचान सकता हूँ।",
            Describe.describe(spread, Quality.OK, Lang.HI, live = false),
        )
    }

    @Test fun `nothing found`() {
        assertEquals(
            "I couldn't recognise anything clearly. Try more light or step back.",
            Describe.describe(emptyList(), Quality.OK, Lang.EN, live = true),
        )
        assertEquals(
            "It's very dark. I couldn't recognise anything clearly. Try more light or step back.",
            Describe.describe(emptyList(), Quality(dark = true), Lang.EN, live = true),
        )
        assertEquals("I couldn't recognise anything clearly in this photo.", Describe.describe(emptyList(), Quality.OK, Lang.EN, live = false))
    }

    @Test fun `quality notes come first`() {
        val f = listOf(found("cup", 0.9, 0.4, 0.4, 0.2, 0.2))
        assertEquals(
            "The photo looks blurry. Hold the phone still. A cup ahead. I can only recognise common objects.",
            Describe.describe(f, Quality(blurry = true), Lang.EN, live = true),
        )
    }

    /** It only ever lists what it recognised: never "safe", "clear", "nothing in front", "no obstacles". */
    @Test fun `never claims a place is safe or clear`() {
        val banned = listOf("safe", "clear path", "path is clear", "all clear", "is clear", "no obstacle", "nothing in front", "nothing ahead", "सुरक्षित", "रास्ता साफ़")
        val keys = listOf("person", "car", "chair", "door", "stairs", "dog", "table", "cup", "bus", "pole")
        val rnd = Random(7)
        repeat(300) {
            val f = List(rnd.nextInt(0, 6)) {
                val w = rnd.nextDouble(0.02, 0.6)
                val h = rnd.nextDouble(0.02, 0.9)
                found(keys[rnd.nextInt(keys.size)], rnd.nextDouble(0.4, 1.0), rnd.nextDouble(0.0, 1 - w), rnd.nextDouble(0.0, 1 - h), w, h)
            }
            val q = Quality(dark = rnd.nextBoolean(), blurry = rnd.nextBoolean())
            for (lang in Lang.entries) for (live in listOf(true, false)) {
                val s = Describe.describe(f, q, lang, live).lowercase()
                for (b in banned) assertFalse("'$b' in: $s", s.contains(b))
            }
        }
    }

    // ---- picture quality ------------------------------------------------------------------------

    private fun img(w: Int, h: Int, f: (Int, Int) -> Float) = FloatArray(w * h) { f(it % w, it / w) }

    @Test fun `dark, plain, sharp and blurry pictures`() {
        assertEquals(Quality(dark = true), PhotoQuality.assess(img(64, 48) { _, _ -> 10f }, 64, 48))
        assertEquals(Quality(plain = true), PhotoQuality.assess(img(64, 48) { _, _ -> 128f }, 64, 48))
        assertEquals(Quality(bright = true, plain = true), PhotoQuality.assess(img(64, 48) { _, _ -> 250f }, 64, 48))
        // Checkerboard: lots of edges → sharp.
        assertEquals(Quality.OK, PhotoQuality.assess(img(64, 48) { x, y -> if ((x / 4 + y / 4) % 2 == 0) 40f else 200f }, 64, 48))
        // Smooth left-to-right ramp: plenty of contrast, no edges → blurry.
        assertEquals(Quality(blurry = true), PhotoQuality.assess(img(64, 48) { x, _ -> 40f + x * 2.5f }, 64, 48))
    }

    // ---- taken at -------------------------------------------------------------------------------

    @Test fun `when a photo was taken, English`() {
        val now = millis(2026, 10, 13, 20, 0)
        assertEquals("Taken today at 6:40 PM.", Describe.takenAt(millis(2026, 10, 13, 18, 40), now, zone, Lang.EN))
        assertEquals("Taken yesterday at 9:05 AM.", Describe.takenAt(millis(2026, 10, 12, 9, 5), now, zone, Lang.EN))
        assertEquals("Taken on 3 October at 12:15 AM.", Describe.takenAt(millis(2026, 10, 3, 0, 15), now, zone, Lang.EN))
        assertEquals("Taken on 25 December 2025 at 12:30 PM.", Describe.takenAt(millis(2025, 12, 25, 12, 30), now, zone, Lang.EN))
    }

    @Test fun `when a photo was taken, Hindi`() {
        val now = millis(2026, 10, 13, 20, 0)
        assertEquals("आज शाम 6:40 बजे ली गई।", Describe.takenAt(millis(2026, 10, 13, 18, 40), now, zone, Lang.HI))
        assertEquals("कल सुबह 9:05 बजे ली गई।", Describe.takenAt(millis(2026, 10, 12, 9, 5), now, zone, Lang.HI))
        assertEquals("3 अक्टूबर को रात 12:15 बजे ली गई।", Describe.takenAt(millis(2026, 10, 3, 0, 15), now, zone, Lang.HI))
    }

    @Test fun `photo header names the photo, the time and honest notes`() {
        val now = millis(2026, 10, 13, 20, 0)
        assertEquals(
            "Your latest photo. Taken today at 6:40 PM. It looks like a screenshot. I can't read text in pictures. " +
                "You let me see only some of your photos, so this may not be your newest one.",
            Describe.photoHeader(0, millis(2026, 10, 13, 18, 40), now, zone, Lang.EN, screenshot = true, limited = true),
        )
        assertEquals(
            "Photo 3, counting from the newest. I don't know when it was taken.",
            Describe.photoHeader(2, null, now, zone, Lang.EN, screenshot = false, limited = true),
        )
    }
}

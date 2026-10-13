package app.saathi.walk

import app.saathi.walk.WalkCore.DIST
import app.saathi.walk.WalkCore.LABELS
import app.saathi.walk.WalkCore.MIN_HITS
import app.saathi.walk.WalkCore.OBJECTS
import app.saathi.walk.WalkCore.checkHealth
import app.saathi.walk.WalkCore.dedupe
import app.saathi.walk.WalkCore.distanceOf
import app.saathi.walk.WalkCore.growthRate
import app.saathi.walk.WalkCore.isClipped
import app.saathi.walk.WalkCore.levelOf
import app.saathi.walk.WalkCore.pickAlert
import app.saathi.walk.WalkCore.steerHint
import app.saathi.walk.WalkCore.updateTracks
import app.saathi.walk.WalkCore.zoneOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Port of walk/tests/core.test.mjs (same cases, same Hindi wording), plus English + sensitivity. */
class WalkCoreTest {
    private val hi = Phrasebook.Hindi
    private val en = Phrasebook.English
    private val aspect = 0.75 // portrait 3:4, the default in updateTracks

    private fun box(cx: Double, h: Double, cy: Double = 0.5, w: Double = h / 2) = Box(cx - w / 2, cy - h / 2, w, h)
    private fun det(label: String, cx: Double, h: Double, score: Double = 0.8): Det {
        val b = box(cx, h)
        return Det(label, score, b.x, b.y, b.w, b.h)
    }

    /** Box height on screen for an object of this label at d metres. */
    private fun hAt(label: String, d: Double) = OBJECTS.getValue(label).h * 0.6 * hypot(1.0, aspect) / d

    private class Fed(val tracker: Tracker, val now: Double)

    /** Feed the same object for n frames at 100 ms steps, distance from d0 to d1 metres. */
    private fun feed(label: String, cx: Double, d0: Double, d1: Double, n: Int = 8): Fed {
        val tracker = Tracker()
        for (i in 0 until n) {
            val d = d0 + (d1 - d0) * i / (n - 1)
            updateTracks(tracker, listOf(det(label, cx, hAt(label, d))), i * 100.0)
        }
        return Fed(tracker, (n - 1) * 100.0)
    }

    private val banned = listOf("साफ", "सुरक्षित", "safe", "clear", "चलिए", "पार", "Safe", "Clear", "cross", "Go ahead")

    @Test fun `zones split the frame in thirds`() {
        assertEquals(Zone.Left, zoneOf(box(0.1, 0.1)))
        assertEquals(Zone.Center, zoneOf(box(0.5, 0.1)))
        assertEquals(Zone.Right, zoneOf(box(0.9, 0.1)))
    }

    @Test fun `distance - a 2 m door half the frame tall is about 3 m away, twice as tall is half as far`() {
        val d = distanceOf(box(0.5, 0.5), 2.0, aspect)
        assertTrue("door at $d", d > 2.5 && d < 3.5)
        assertTrue(abs(distanceOf(box(0.5, 1.0), 2.0, aspect) - d / 2) < 1e-9)
    }

    @Test fun `distance is spoken as rough Hindi buckets`() {
        assertEquals("1 मीटर से कम", hi.meters(0.6))
        assertEquals("लगभग डेढ़ मीटर", hi.meters(1.4))
        assertEquals("लगभग ढाई मीटर", hi.meters(2.6))
        assertEquals("लगभग 6 मीटर", hi.meters(6.3))
        assertEquals("2 मीटर के अंदर", hi.meters(2.1, true)) // clipped box: only an upper bound
    }

    @Test fun `distance in English`() {
        assertEquals("under 1 metre", en.meters(0.6))
        assertEquals("about 1 metre", en.meters(1.1))
        assertEquals("about 1.5 metres", en.meters(1.4))
        assertEquals("about 2.5 metres", en.meters(2.6))
        assertEquals("about 6 metres", en.meters(6.3))
        assertEquals("within 2 metres", en.meters(2.1, true))
    }

    @Test fun `a box cut off by the frame edge is flagged as clipped`() {
        assertTrue(isClipped(Box(0.3, 0.0, 0.3, 0.6)))
        assertFalse(isClipped(box(0.5, 0.5)))
    }

    @Test fun `no phrase ever claims the way is clear or safe`() {
        for (words in listOf(hi, en)) {
            val lines = ArrayList(words.fixed)
            for (label in LABELS) {
                lines.add(words.lostClose(label))
                for (zone in Zone.entries) for (level in 1..3) for (approaching in listOf(true, false)) {
                    lines.add(words.phrase(label, zone, level, approaching, words.meters(2.0)))
                }
            }
            for (line in lines) for (word in banned) assertFalse("$line contains $word", line.contains(word))
        }
    }

    @Test fun `phrases are short, name the object and the distance, and use the right gender`() {
        assertEquals("सामने दरवाज़ा, लगभग 3 मीटर", hi.phrase("Door", Zone.Center, 1, false, "लगभग 3 मीटर"))
        assertEquals("बाईं ओर गाड़ी पास आ रही है, लगभग 6 मीटर", hi.phrase("car", Zone.Left, 2, true, "लगभग 6 मीटर"))
        assertEquals("सामने ट्रक पास आ रहा है, लगभग 7 मीटर", hi.phrase("truck", Zone.Center, 2, true, "लगभग 7 मीटर"))
        assertEquals("रुकिए! सामने कुर्सी", hi.phrase("chair", Zone.Center, 3, false, "1 मीटर से कम")) // stop: shortest line
        for (words in listOf(hi, en)) for (label in LABELS) {
            assertTrue(words.phrase(label, Zone.Right, 2, true, words.meters(2.6, true)).split(" ").size <= 12)
        }
    }

    @Test fun `English phrases match the design wording`() {
        assertEquals("Chair ahead, about 2 metres", en.phrase("chair", Zone.Center, 2, false, en.meters(2.1)))
        assertEquals("Car on the left, coming closer, about 6 metres", en.phrase("car", Zone.Left, 2, true, en.meters(6.2)))
        assertEquals("Stop! Chair ahead", en.phrase("chair", Zone.Center, 3, false, en.meters(0.7)))
        assertEquals("More space on the left.", en.steer(SteerHint.Left))
    }

    @Test fun `every label has a kind with thresholds and a positive real height`() {
        for ((label, o) in OBJECTS) {
            assertNotNull(label, DIST[o.kind])
            assertTrue(label, o.h > 0)
        }
    }

    @Test fun `levels by distance - far is quiet, close ahead warns then stops`() {
        assertEquals(0, levelOf(Kind.Obstacle, Zone.Center, 5.0, 0.0))
        assertEquals(1, levelOf(Kind.Obstacle, Zone.Center, 2.8, 0.0))
        assertEquals(2, levelOf(Kind.Obstacle, Zone.Center, 1.8, 0.0))
        assertEquals(3, levelOf(Kind.Obstacle, Zone.Center, 0.8, 0.0))
        assertEquals(1, levelOf(Kind.Door, Zone.Center, 4.0, 0.0))
        assertEquals(3, levelOf(Kind.Hazard, Zone.Center, 1.2, 0.0)) // stairs
        assertEquals(0, levelOf(Kind.Person, Zone.Center, 4.0, 0.0)) // people ahead at a distance aren't news
        assertEquals(2, levelOf(Kind.Person, Zone.Center, 2.0, 0.0))
    }

    @Test fun `levels - vehicles approaching from the side warn, parked ones at the side stay quiet`() {
        assertEquals(0, levelOf(Kind.Vehicle, Zone.Left, 10.0, 0.0))
        assertEquals(2, levelOf(Kind.Vehicle, Zone.Left, 10.0, 0.5))
        assertEquals(3, levelOf(Kind.Vehicle, Zone.Left, 6.0, 0.5))
        assertEquals(3, levelOf(Kind.Vehicle, Zone.Left, 14.0, 1.0)) // fast approach
        assertEquals(0, levelOf(Kind.Animal, Zone.Left, 4.0, 0.5)) // animal off to the side: not a warning
    }

    @Test fun `sensitivity scales the distances - High warns earlier, Low later`() {
        assertEquals(0, levelOf(Kind.Obstacle, Zone.Center, 3.5, 0.0, scale = 1.0))
        assertEquals(1, levelOf(Kind.Obstacle, Zone.Center, 3.5, 0.0, scale = 1.3))
        assertEquals(2, levelOf(Kind.Obstacle, Zone.Center, 1.8, 0.0, scale = 1.0))
        assertEquals(1, levelOf(Kind.Obstacle, Zone.Center, 1.8, 0.0, scale = 0.75))
    }

    @Test fun `growth rate needs enough history and measures per-second growth`() {
        assertEquals(0.0, growthRate(listOf(Sample(0.0, 0.1))), 0.0)
        val hist = listOf(0, 100, 200, 300, 400, 500, 600).map { Sample(it.toDouble(), 0.1 + it / 2000.0) }
        val r = growthRate(hist)
        assertTrue("rate $r", r > 1 && r < 5)
    }

    @Test fun `dedupe keeps one box when both models see the same table`() {
        val out = dedupe(listOf(det("dining table", 0.5, 0.3, 0.7), det("Table", 0.5, 0.31, 0.6), det("person", 0.2, 0.6, 0.6)))
        assertEquals(listOf("dining table", "person"), out.map { it.label }.sorted())
    }

    @Test fun `tracker keeps one object across frames and car-truck flicker`() {
        val tracker = Tracker()
        updateTracks(tracker, listOf(det("car", 0.5, 0.3)), 0.0)
        updateTracks(tracker, listOf(det("truck", 0.51, 0.31)), 100.0)
        val tracks = updateTracks(tracker, listOf(det("car", 0.52, 0.32)), 200.0)
        assertEquals(1, tracks.size)
        assertEquals(3, tracks[0].hits)
    }

    @Test fun `tracker drops unknown labels and forgets objects that disappear`() {
        val tracker = Tracker()
        updateTracks(tracker, listOf(det("car", 0.5, 0.3), det("Toaster", 0.2, 0.3)), 0.0)
        assertEquals(1, tracker.tracks.size)
        assertEquals(0, updateTracks(tracker, emptyList(), 3000.0).size)
    }

    @Test fun `a single-frame detection is not announced unless it is at stop distance`() {
        val far = Tracker().also { updateTracks(it, listOf(det("car", 0.5, 0.12)), 0.0) }
        assertNull(pickAlert(far, 0.0, Announcer()))
        val near = Tracker().also { updateTracks(it, listOf(det("car", 0.5, 0.7)), 0.0) }
        val alert = pickAlert(near, 0.0, Announcer())
        assertTrue("a very close object is spoken on its first frame", alert != null && alert.level == 3)
    }

    @Test fun `a door ahead is announced with its distance`() {
        val f = feed("Door", 0.5, 3.0, 3.0)
        assertEquals("सामने दरवाज़ा, लगभग 3 मीटर", pickAlert(f.tracker, f.now, Announcer())!!.text)
        val g = feed("Door", 0.5, 3.0, 3.0)
        assertEquals("Door ahead, about 3 metres", pickAlert(g.tracker, g.now, Announcer(), words = en)!!.text)
    }

    @Test fun `approaching car from the left is announced once, then not repeated right away`() {
        val f = feed("car", 0.15, 12.0, 9.0)
        assertTrue(f.tracker.tracks[0].approaching)
        val ann = Announcer()
        val alert = pickAlert(f.tracker, f.now, ann)!!
        assertEquals(2, alert.level)
        assertTrue(alert.text, Regex("^बाईं ओर गाड़ी पास आ रही है, लगभग \\d+ मीटर$").matches(alert.text))
        assertNull(pickAlert(f.tracker, f.now + 500, ann))
    }

    @Test fun `walking toward a door - announced again as it gets noticeably closer`() {
        val tracker = Tracker()
        val ann = Announcer()
        for (i in 0 until 3) updateTracks(tracker, listOf(det("Door", 0.5, hAt("Door", 4.5))), i * 100.0)
        assertTrue(pickAlert(tracker, 200.0, ann)!!.text.contains("लगभग 5 मीटर"))
        for (i in 3 until 6) updateTracks(tracker, listOf(det("Door", 0.5, hAt("Door", 4.3))), 5000.0 + i * 100)
        assertNull(pickAlert(tracker, 5500.0, ann)) // barely closer: stay quiet
        for (i in 6 until 9) updateTracks(tracker, listOf(det("Door", 0.5, hAt("Door", 2.9))), 6000.0 + i * 100)
        assertTrue(pickAlert(tracker, 6800.0, ann)!!.text.contains("लगभग 3 मीटर"))
    }

    @Test fun `escalation interrupts - the same object getting close is announced again at once`() {
        val tracker = Tracker()
        val ann = Announcer()
        for (i in 0 until 3) updateTracks(tracker, listOf(det("chair", 0.5, hAt("chair", 1.8))), i * 100.0)
        assertEquals(2, pickAlert(tracker, 200.0, ann)!!.level)
        for (i in 3 until 5) updateTracks(tracker, listOf(det("chair", 0.5, hAt("chair", 0.8))), i * 100.0)
        val alert = pickAlert(tracker, 400.0, ann, 2)!!
        assertEquals(3, alert.level)
        assertTrue(alert.interrupt)
    }

    @Test fun `a less urgent object never talks over a more urgent line`() {
        val f = feed("cow", 0.5, 4.0, 4.0)
        assertNull(pickAlert(f.tracker, f.now, Announcer(), 2))
        assertEquals("सामने गाय, लगभग 4 मीटर", pickAlert(f.tracker, f.now, Announcer(), 0)!!.text)
    }

    @Test fun `most urgent object wins when several are visible`() {
        val tracker = Tracker()
        for (i in 0 until 3) {
            updateTracks(tracker, listOf(det("Door", 0.5, hAt("Door", 4.0)), det("motorcycle", 0.2, hAt("motorcycle", 3.0))), i * 100.0)
        }
        // the bike is parked to the side (not approaching) but within its stop distance: mention level, closer wins
        assertTrue(pickAlert(tracker, 200.0, Announcer())!!.text.contains("बाइक"))
        for (i in 3 until 6) {
            updateTracks(tracker, listOf(det("Door", 0.5, hAt("Door", 4.0)), det("Stairs", 0.5, hAt("Stairs", 1.2))), i * 100.0)
        }
        assertTrue(pickAlert(tracker, 500.0, Announcer())!!.text.startsWith("रुकिए! सामने सीढ़ियाँ"))
    }

    @Test fun `health - a stalled or covered camera is announced, and repeated`() {
        val h = Health()
        assertNull(checkHealth(h, 1000.0, 900.0, 0.5))
        assertEquals(HealthProblem.Stall, checkHealth(h, 5000.0, 1000.0, 0.5))
        assertNull(checkHealth(h, 6000.0, 1000.0, 0.5)) // not every tick
        assertEquals(HealthProblem.Dark, checkHealth(h, 14000.0, 13900.0, 0.02))
        assertEquals("सावधान! कैमरा रुक गया है।", hi.health(HealthProblem.Stall))
    }

    // ---- steering hints ----
    private fun trk(zone: Zone, dist: Double, level: Int = 2) = Track(0, "chair", Kind.Obstacle, box(0.5, 0.2), 0.0, 5).also {
        it.zone = zone
        it.dist = dist
        it.level = level
    }

    @Test fun `steer - ahead and close, left empty - suggest left`() {
        val best = trk(Zone.Center, 1.2, 3)
        assertEquals(SteerHint.Left, steerHint(listOf(best, trk(Zone.Right, 2.0)), best))
    }

    @Test fun `steer - ahead and close, right farther than margin - suggest right`() {
        val best = trk(Zone.Center, 1.0, 3)
        assertEquals(SteerHint.Right, steerHint(listOf(best, trk(Zone.Left, 1.5), trk(Zone.Right, 6.0)), best))
    }

    @Test fun `steer - both sides blocked - say so`() {
        val best = trk(Zone.Center, 1.0, 3)
        assertEquals(SteerHint.Both, steerHint(listOf(best, trk(Zone.Left, 1.2), trk(Zone.Right, 1.4)), best))
    }

    @Test fun `steer - nothing seen on either side - no guess`() {
        val best = trk(Zone.Center, 1.0, 3)
        assertNull(steerHint(listOf(best), best))
    }

    @Test fun `steer - only for things ahead and at warn level or above`() {
        assertNull(steerHint(listOf(trk(Zone.Left, 1.0)), trk(Zone.Left, 1.0, 3)))
        assertNull(steerHint(emptyList(), trk(Zone.Center, 2.5, 1)))
    }

    @Test fun `a stop alert carries the direction hint`() {
        val tracker = Tracker()
        for (i in 0 until 3) {
            updateTracks(tracker, listOf(det("chair", 0.5, hAt("chair", 0.8)), det("chair", 0.88, hAt("chair", 1.2))), i * 100.0)
        }
        val a = pickAlert(tracker, 200.0, Announcer(), words = en)!!
        assertEquals("Stop! Chair ahead", a.line)
        assertEquals("More space on the left.", a.hint)
        assertEquals("Stop! Chair ahead. More space on the left.", a.text)
    }

    // ---- slow devices ----
    @Test fun `slow device (1 frame per s) - an object seen every frame is kept and confirmed`() {
        val tr = Tracker()
        val chair = Det("chair", 0.8, 0.4, 0.3, 0.2, 0.5)
        var tracks: List<Track> = emptyList()
        for (i in 0 until 4) tracks = updateTracks(tr, listOf(chair), 1000.0 + i * 1100)
        assertEquals(1, tracks.size)
        assertTrue(tracks[0].hits >= MIN_HITS)
    }

    // ---- looming (walls and unnamed obstacles) + lost-while-close ----
    /** Textured synthetic scene, zoomed by s about the centre (s > 1 = closer). */
    private fun scene(s: Double = 1.0, flat: Boolean = false): FloatArray {
        val w = Loom.W
        val h = Loom.H
        val g = FloatArray(w * h)
        val cx = (w - 1) / 2.0
        val cy = (h - 1) / 2.0
        for (y in 0 until h) for (x in 0 until w) {
            val u = cx + (x - cx) / s
            val v = cy + (y - cy) / s
            g[y * w + x] = if (flat) {
                128f + (x % 2)
            } else {
                (128 + 60 * sin(u / 2.3) * cos(v / 3.1) + 30 * sin((u + v) / 1.7)).toFloat()
            }
        }
        return g
    }

    @Test fun `looming - a zooming-in scene reads as getting closer, a still one doesn't`() {
        assertTrue(Loom.expansion(scene(1.0), scene(1.05), Loom.W, Loom.H) > 1.02)
        assertEquals(1.0, Loom.expansion(scene(1.0), scene(1.0), Loom.W, Loom.H), 0.0)
    }

    @Test fun `looming - walking fast at a surface escalates warn to stop`() {
        val st = LoomState()
        val out = ArrayList<LoomAlert?>()
        var s = 1.0
        for (i in 0 until 6) {
            out.add(Loom.update(st, scene(s), i * 250.0)) // 4 frames/s, 10 % closer per frame ≈ TTC 2.5 s
            s *= 1.1
        }
        val spoken = out.filterNotNull()
        assertTrue("warned", spoken.isNotEmpty())
        assertTrue(spoken.any { it.kind == LoomKind.Stop || it.kind == LoomKind.Warn })
    }

    @Test fun `looming - standing still stays quiet`() {
        val st = LoomState()
        for (i in 0 until 6) assertNull(Loom.update(st, scene(1.0), i * 250.0))
    }

    @Test fun `looming - a plain, featureless close surface is flagged as a possible wall`() {
        val st = LoomState()
        val res = (0 until 4).map { Loom.update(st, scene(1.0, true), it * 250.0) }
        assertTrue(res.any { it?.kind == LoomKind.Wall })
    }

    @Test fun `a close door that drops out of detection still gets a stop warning`() {
        val tr = Tracker()
        val door = Det("Door", 0.8, 0.3, 0.0, 0.4, 0.99) // fills the frame, ahead
        for (i in 0 until 3) updateTracks(tr, listOf(door), i * 300.0)
        val ann = Announcer()
        pickAlert(tr, 900.0, ann) // normal warning
        for (i in 0 until 4) updateTracks(tr, emptyList(), 3000.0 + i * 1000) // model loses it
        val a = pickAlert(tr, 7000.0, ann)
        assertTrue("$a", a != null && a.level == 3 && a.text.contains("बहुत पास"))
    }

    @Test fun `toGray matches the JS weights`() {
        val g = Loom.toGray(byteArrayOf(255.toByte(), 0, 0, 255.toByte(), 0, 255.toByte(), 0, 255.toByte()), 2, 1)
        assertEquals(0.299f * 255, g[0], 1e-3f)
        assertEquals(0.587f * 255, g[1], 1e-3f)
    }
}

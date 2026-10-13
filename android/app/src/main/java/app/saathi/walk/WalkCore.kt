package app.saathi.walk

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Walk mode decision logic: detections in, spoken warnings out.
 * A 1:1 port of walk/core.js (the web walk mode) — same object list, distances, levels, tracking,
 * escalation, direction hints, looming and camera health. Pure Kotlin, no Android types, so it is
 * unit-tested on the JVM (app/src/test/.../WalkCoreTest.kt mirrors walk/tests/core.test.mjs).
 *
 * Safety stance: this only ever WARNS. It never says a path or road is clear, because a missed
 * detection would then turn into an instruction to walk into danger.
 *
 * Times are milliseconds as Double (so "never announced" can be -Infinity, like the JS).
 */

enum class Kind { Vehicle, Person, Animal, Hazard, Door, Obstacle }

enum class Zone { Left, Center, Right }

/** hi: Hindi word · fem: gender for "आ रही / आ रहा" · en: English word · h: typical real height (m). */
data class ObjectInfo(val hi: String, val fem: Boolean, val en: String, val kind: Kind, val h: Double)

data class Thresholds(val mention: Double, val warn: Double, val stop: Double) {
    operator fun times(k: Double) = Thresholds(mention * k, warn * k, stop * k)
}

/** Box as 0..1 fractions of the (upright) camera frame. */
data class Box(val x: Double, val y: Double, val w: Double, val h: Double)

/** One detection from a model. label is the model's class name (COCO lowercase, Open Images Capitalised). */
data class Det(val label: String, val score: Double, val x: Double, val y: Double, val w: Double, val h: Double) {
    val box get() = Box(x, y, w, h)
}

data class Sample(val t: Double, val s: Double)

class Track(val id: Int, var label: String, val kind: Kind, var box: Box, var lastSeen: Double, var hits: Int) {
    val hist = ArrayList<Sample>()
    var announcedLevel = 0
    var announcedAt = Double.NEGATIVE_INFINITY
    var announcedDist = Double.POSITIVE_INFINITY
    var missed = 0
    var zone = Zone.Center
    var clipped = false
    var dist = Double.POSITIVE_INFINITY
    var rate = 0.0
    var approaching = false
    var level = 0
}

class Tracker(
    /** Walk sensitivity: multiplies every distance threshold (Low 0.75 · Medium 1 · High 1.3). */
    var distScale: Double = 1.0,
) {
    val tracks = ArrayList<Track>()
    var nextId = 1
    val lostClose = ArrayList<Track>()
    var lastUpdate = 0.0
}

class Announcer {
    var lastAt = Double.NEGATIVE_INFINITY
    var lastLevel = 0
}

/** line: the warning itself · hint: direction hint ("" if none) · text: what is spoken. */
data class Alert(val id: Int, val level: Int, val line: String, val hint: String, val text: String, val interrupt: Boolean)

class Health {
    var lastWarnAt = Double.NEGATIVE_INFINITY
}

enum class HealthProblem { Stall, Dark }

enum class SteerHint { Left, Right, Both }

enum class LoomKind { Warn, Stop, Wall }

data class LoomAlert(val level: Int, val kind: LoomKind)

class LoomState {
    var prev: FloatArray? = null
    var prevAt = 0.0
    var streak = 0
    var flat = 0
    var lastAt = Double.NEGATIVE_INFINITY
    var lastLevel = 0
}

object WalkCore {
    // Class names from two models: lowercase = COCO (every frame: traffic, people, furniture);
    // Capitalised = Open Images V7 (every 3rd frame, only for things COCO doesn't know: doors,
    // stairs, poles…). Names are unique across both.
    val OBJECTS: Map<String, ObjectInfo> = linkedMapOf(
        "car" to ObjectInfo("गाड़ी", true, "Car", Kind.Vehicle, 1.5),
        "truck" to ObjectInfo("ट्रक", false, "Truck", Kind.Vehicle, 3.0),
        "bus" to ObjectInfo("बस", true, "Bus", Kind.Vehicle, 3.0),
        "motorcycle" to ObjectInfo("बाइक", true, "Motorbike", Kind.Vehicle, 1.1),
        "bicycle" to ObjectInfo("साइकिल", true, "Bicycle", Kind.Vehicle, 1.0),
        "person" to ObjectInfo("कोई", false, "Person", Kind.Person, 1.65),
        "dog" to ObjectInfo("कुत्ता", false, "Dog", Kind.Animal, 0.6),
        "cow" to ObjectInfo("गाय", true, "Cow", Kind.Animal, 1.4),
        "horse" to ObjectInfo("घोड़ा", false, "Horse", Kind.Animal, 1.6),
        "cat" to ObjectInfo("बिल्ली", true, "Cat", Kind.Animal, 0.3),
        "chair" to ObjectInfo("कुर्सी", true, "Chair", Kind.Obstacle, 0.9),
        "couch" to ObjectInfo("सोफ़ा", false, "Sofa", Kind.Obstacle, 0.85),
        "bed" to ObjectInfo("बिस्तर", false, "Bed", Kind.Obstacle, 0.6),
        "dining table" to ObjectInfo("टेबल", true, "Table", Kind.Obstacle, 0.75),
        "bench" to ObjectInfo("बेंच", true, "Bench", Kind.Obstacle, 0.8),
        "potted plant" to ObjectInfo("गमला", false, "Plant pot", Kind.Obstacle, 0.6),
        "fire hydrant" to ObjectInfo("पाइप", false, "Hydrant", Kind.Obstacle, 0.8),
        "toilet" to ObjectInfo("टॉयलेट", false, "Toilet", Kind.Obstacle, 0.8),
        "refrigerator" to ObjectInfo("फ़्रिज", false, "Fridge", Kind.Obstacle, 1.7),
        "suitcase" to ObjectInfo("सूटकेस", false, "Suitcase", Kind.Obstacle, 0.65),
        "Door" to ObjectInfo("दरवाज़ा", false, "Door", Kind.Door, 2.0),
        "Stairs" to ObjectInfo("सीढ़ियाँ", true, "Stairs", Kind.Hazard, 1.0),
        "Ladder" to ObjectInfo("सीढ़ी", true, "Ladder", Kind.Obstacle, 2.0),
        "Table" to ObjectInfo("टेबल", true, "Table", Kind.Obstacle, 0.75),
        "Coffee table" to ObjectInfo("टेबल", true, "Low table", Kind.Obstacle, 0.45),
        "Desk" to ObjectInfo("डेस्क", true, "Desk", Kind.Obstacle, 0.75),
        "Stool" to ObjectInfo("स्टूल", false, "Stool", Kind.Obstacle, 0.6),
        "Cabinetry" to ObjectInfo("अलमारी", true, "Cupboard", Kind.Obstacle, 1.8),
        "Bookcase" to ObjectInfo("अलमारी", true, "Bookshelf", Kind.Obstacle, 1.8),
        "Waste container" to ObjectInfo("कूड़ेदान", false, "Bin", Kind.Obstacle, 0.9),
        "Box" to ObjectInfo("डिब्बा", false, "Box", Kind.Obstacle, 0.4),
        "Cart" to ObjectInfo("ठेला", false, "Cart", Kind.Obstacle, 1.0),
        "Wheelchair" to ObjectInfo("व्हीलचेयर", true, "Wheelchair", Kind.Obstacle, 0.9),
        "Street light" to ObjectInfo("खंभा", false, "Pole", Kind.Obstacle, 6.0),
        "Tree" to ObjectInfo("पेड़", false, "Tree", Kind.Obstacle, 5.0),
    )
    val LABELS: Set<String> = OBJECTS.keys

    /** Distance thresholds in metres. mention: say once · warn · stop. Starting guesses — tune on a real walk. */
    val DIST: Map<Kind, Thresholds> = mapOf(
        Kind.Vehicle to Thresholds(15.0, 8.0, 4.0),
        Kind.Person to Thresholds(0.0, 2.5, 1.2), // people ahead at a distance aren't news
        Kind.Animal to Thresholds(5.0, 3.0, 1.5),
        Kind.Hazard to Thresholds(6.0, 3.0, 1.5),
        Kind.Door to Thresholds(5.0, 2.0, 0.8),
        Kind.Obstacle to Thresholds(3.0, 2.0, 1.0),
    )

    /** Focal length in units of the frame diagonal, for a typical phone main camera (~26 mm equivalent). */
    const val FOCAL_PER_DIAGONAL = 0.6
    const val APPROACH_RATE = 0.3 // box-height growth per second that counts as "getting closer"
    const val FAST_APPROACH_RATE = 0.8 // a vehicle growing this fast is a stop warning
    const val MIN_HITS = 2 // frames an object must be seen before we speak (filters flicker)
    const val TRACK_TTL_MS = 1200.0 // forget an object not seen for this long (and missed in 2 frames)
    const val MAX_UNSEEN_MS = 2500.0 // always forget after this, however slow the frames are
    const val HISTORY_MS_SLOW = 3000.0 // keep >= 2 samples at ~1 frame/s so "getting closer" still works
    val REPEAT_MS = mapOf(1 to 20000.0, 2 to 5000.0, 3 to 2500.0) // re-announce the same object at the same level
    const val UPDATE_MS = 1500.0 // re-announce this soon when it got noticeably closer
    const val GAP_MS = 1500.0 // min gap between two announcements of equal or lower level
    const val STEER_MARGIN_M = 1.5 // a side must be this much emptier to be suggested

    const val STALL_MS = 3000.0
    const val DARK_LUMA = 0.08
    const val HEALTH_REPEAT_MS = 8000.0

    fun thresholds(kind: Kind, scale: Double = 1.0): Thresholds = DIST.getValue(kind) * scale

    fun zoneOf(box: Box): Zone {
        val cx = box.x + box.w / 2
        return when {
            cx < 1.0 / 3 -> Zone.Left
            cx > 2.0 / 3 -> Zone.Right
            else -> Zone.Center
        }
    }

    /** Box touches the top or bottom edge, so the object is taller than it looks: it is at most this far. */
    fun isClipped(box: Box) = box.y < 0.02 || box.y + box.h > 0.98

    /**
     * Pinhole estimate: distance = real height × focal length / height on screen.
     * aspect = frame width / height. Rough (±30%) — always spoken as "about".
     */
    fun distanceOf(box: Box, realH: Double, aspect: Double): Double {
        val focalPerHeight = FOCAL_PER_DIAGONAL * hypot(1.0, aspect)
        return realH * focalPerHeight / max(box.h, 0.01)
    }

    /** JS Math.round (half up), not Kotlin's round (half even). */
    fun jsRound(v: Double) = floor(v + 0.5)

    /** The spoken distance bucket in metres: halves under 3 m, whole metres above. null = under 1 m. */
    fun bucket(d: Double): Double? = if (d < 1) null else if (d < 3) jsRound(d * 2) / 2 else jsRound(d)

    fun iou(a: Box, b: Box): Double {
        val x1 = max(a.x, b.x)
        val y1 = max(a.y, b.y)
        val x2 = min(a.x + a.w, b.x + b.w)
        val y2 = min(a.y + a.h, b.y + b.h)
        val inter = max(0.0, x2 - x1) * max(0.0, y2 - y1)
        val union = a.w * a.h + b.w * b.h - inter
        return if (union > 0) inter / union else 0.0
    }

    /** Drop duplicate boxes of the same kind (COCO "dining table" + Open Images "Table"). Highest score wins. */
    fun dedupe(dets: List<Det>, overlap: Double = 0.5): List<Det> {
        val kept = ArrayList<Det>()
        for (d in dets.sortedByDescending { it.score }) {
            val kind = OBJECTS[d.label]?.kind ?: continue
            if (kept.any { OBJECTS.getValue(it.label).kind == kind && iou(it.box, d.box) > overlap }) continue
            kept.add(d)
        }
        return kept
    }

    private fun centerDist(a: Box, b: Box) = hypot(a.x + a.w / 2 - (b.x + b.w / 2), a.y + a.h / 2 - (b.y + b.h / 2))

    /** Size growth per second over the history window (0 when there isn't enough history). */
    fun growthRate(hist: List<Sample>): Double {
        if (hist.size < 4) return 0.0
        val a0 = hist[0]
        val a1 = hist[1]
        val b0 = hist[hist.size - 2]
        val b1 = hist[hist.size - 1]
        val dt = ((b0.t + b1.t) / 2 - (a0.t + a1.t) / 2) / 1000
        if (dt < 0.4) return 0.0
        val s0 = (a0.s + a1.s) / 2
        val s1 = (b0.s + b1.s) / 2
        return if (s0 > 0) (s1 / s0 - 1) / dt else 0.0
    }

    /** 0 = say nothing, 1 = mention, 2 = warn, 3 = stop. */
    fun levelOf(kind: Kind, zone: Zone, dist: Double, rate: Double, scale: Double = 1.0): Int {
        val t = thresholds(kind, scale)
        val ahead = zone == Zone.Center
        val approaching = rate >= APPROACH_RATE
        if (dist <= t.stop && (ahead || approaching)) return 3
        if (kind == Kind.Vehicle && approaching && dist <= t.warn) return 3
        if (kind == Kind.Vehicle && rate >= FAST_APPROACH_RATE && dist <= t.mention) return 3
        if (dist <= t.warn && ahead) return 2
        if (approaching && dist <= t.mention && (kind == Kind.Vehicle || ahead)) return 2
        if (dist <= t.stop) return 1 // very close but off to the side and not moving closer
        if (ahead && dist <= t.mention) return 1
        return 0
    }

    /**
     * Which way has less in it? Only when something is ahead and close (level >= 2): compare the
     * nearest tracked object on the left and the right. Relative wording only — the camera can't
     * see kerbs, potholes or wires, so this never says a side is clear or safe.
     */
    fun steerHint(tracks: List<Track>, best: Track?): SteerHint? {
        if (best == null || best.zone != Zone.Center || best.level < 2) return null
        fun nearest(zone: Zone) = tracks.filter { it.zone == zone && it.hits >= MIN_HITS }.minOfOrNull { it.dist } ?: Double.POSITIVE_INFINITY
        val left = nearest(Zone.Left)
        val right = nearest(Zone.Right)
        val near = max(best.dist, 1.0) + STEER_MARGIN_M
        if (left < near && right < near) return SteerHint.Both
        if (left == right) return null // nothing seen on either side: don't guess
        val side = if (left > right) SteerHint.Left else SteerHint.Right
        return if (max(left, right) >= near) side else null
    }

    /**
     * Match this frame's detections to tracked objects and update their size history.
     * dets have box values as 0..1 fractions of the frame; aspect = frame width / height.
     * Returns the live tracks with zone/dist/rate/level filled in.
     */
    fun updateTracks(tracker: Tracker, dets: List<Det>, now: Double, aspect: Double = 0.75): List<Track> {
        val known = dedupe(dets)
        val free = LinkedHashSet<Track>(tracker.tracks)
        for (d in known) {
            val kind = OBJECTS.getValue(d.label).kind
            var best: Track? = null
            var bestScore = 0.0
            for (t in free) {
                if (t.kind != kind) continue // car ↔ truck flicker stays one object
                val overlap = iou(t.box, d.box)
                val score = if (overlap >= 0.2) overlap else if (centerDist(t.box, d.box) < 0.12) 0.1 else 0.0
                if (score > bestScore) {
                    best = t
                    bestScore = score
                }
            }
            val box = d.box
            val tr: Track
            if (best != null) {
                free.remove(best)
                best.label = d.label
                best.box = box
                best.lastSeen = now
                best.hits += 1
                tr = best
            } else {
                tr = Track(tracker.nextId++, d.label, kind, box, now, 1)
                tracker.tracks.add(tr)
            }
            tr.hist.add(Sample(now, box.h))
            tr.hist.removeAll { now - it.t > HISTORY_MS_SLOW }
        }
        // Slow phones run ~1 frame/s: a fixed 1.2 s memory forgot objects between frames, so they
        // never reached MIN_HITS. Forget after TTL only once the object has also been missed in 2
        // frames, and always after MAX_UNSEEN_MS.
        tracker.lastUpdate = now
        for (t in tracker.tracks) t.missed = if (t.lastSeen == now) 0 else t.missed + 1
        val kept = ArrayList<Track>()
        for (t in tracker.tracks) {
            val unseen = now - t.lastSeen
            if (unseen <= TRACK_TTL_MS || (t.missed < 2 && unseen <= MAX_UNSEEN_MS)) {
                kept.add(t)
            } else if (t.zone == Zone.Center && t.dist <= thresholds(t.kind, tracker.distScale).warn && t.hits >= MIN_HITS) {
                // Close and ahead, then gone: usually it now fills the frame and the model can't see
                // it. That's the most dangerous moment to go quiet.
                tracker.lostClose.add(t)
            }
        }
        tracker.tracks.clear()
        tracker.tracks.addAll(kept)
        for (t in tracker.tracks) {
            t.zone = zoneOf(t.box)
            t.clipped = isClipped(t.box)
            t.dist = distanceOf(t.box, OBJECTS.getValue(t.label).h, aspect)
            t.rate = growthRate(t.hist)
            t.approaching = t.rate >= APPROACH_RATE
            t.level = if (t.lastSeen == now) levelOf(t.kind, t.zone, t.dist, t.rate, tracker.distScale) else 0
        }
        return tracker.tracks
    }

    /**
     * Pick at most one thing to say this frame: the most urgent object that is new, got more
     * urgent, got noticeably closer, or is due a repeat. busyLevel = level of the line still being
     * spoken (0 if silent); only something more urgent may interrupt it.
     */
    fun pickAlert(tracker: Tracker, now: Double, ann: Announcer, busyLevel: Int = 0, words: Phrasebook = Phrasebook.Hindi): Alert? {
        val tracks = tracker.tracks
        var best: Track? = null
        for (t in tracks) {
            if (t.level < t.announcedLevel && now - t.announcedAt > 3000) t.announcedLevel = t.level // allow re-escalation
            // "Stop"-level objects are spoken on their first frame: a late warning is worse than a
            // rare false one when someone is walking. Everything else waits for MIN_HITS.
            if (t.level < 1 || t.hits < (if (t.level >= 3) 1 else MIN_HITS) || t.level <= busyLevel) continue
            val closer = t.dist < t.announcedDist * 0.8 && now - t.announcedAt > UPDATE_MS
            val due = t.level > t.announcedLevel || closer || now - t.announcedAt > REPEAT_MS.getValue(t.level)
            if (!due) continue
            val b = best
            if (b == null || t.level > b.level || (t.level == b.level && t.dist < b.dist)) best = t
        }
        if (best == null && tracker.lostClose.isNotEmpty()) {
            val t = tracker.lostClose.first()
            tracker.lostClose.clear()
            ann.lastAt = now
            ann.lastLevel = 3
            val line = words.lostClose(t.label)
            return Alert(t.id, 3, line, "", line, interrupt = true)
        }
        val b = best ?: return null
        if (now - ann.lastAt < GAP_MS && b.level <= ann.lastLevel) return null
        b.announcedLevel = b.level
        b.announcedAt = now
        b.announcedDist = b.dist
        ann.lastAt = now
        ann.lastLevel = b.level
        val hint = steerHint(tracks, b)?.let(words::steer) ?: ""
        val line = words.phrase(b.label, b.zone, b.level, b.approaching, words.meters(b.dist, b.clipped))
        return Alert(b.id, b.level, line, hint, if (hint.isNotEmpty()) words.join(line, hint) else line, interrupt = b.level >= 3)
    }

    /** Fail loud: if the camera stops or is covered, the user must know they're unprotected. */
    fun checkHealth(h: Health, now: Double, lastFrameAt: Double, brightness: Double?): HealthProblem? {
        val problem = when {
            now - lastFrameAt > STALL_MS -> HealthProblem.Stall
            brightness != null && brightness < DARK_LUMA -> HealthProblem.Dark
            else -> null
        }
        if (problem == null || now - h.lastWarnAt < HEALTH_REPEAT_MS) return null
        h.lastWarnAt = now
        return problem
    }
}

// ---------- looming: "something is getting closer", for things the models have no name for ----------
// Walls, pillars, big boxes. Compare each small grey frame with the previous one: if the picture is
// zooming in, the camera is moving towards a surface. Time to contact ≈ dt / (scale − 1).
object Loom {
    const val W = 80
    const val H = 60
    val SCALES = doubleArrayOf(1.0, 1.01, 1.02, 1.035, 1.05, 1.07, 1.1, 1.14)
    const val MIN_GAIN = 0.08 // best zoom must beat "no zoom" by 8 % (noise filter)
    const val TTC_WARN = 2.5 // seconds to contact
    const val TTC_STOP = 1.2
    const val STREAK = 2 // frames in a row before speaking
    const val FLAT_STD = 7.0 // a close plain wall: almost no texture, light is fine
    const val FLAT_FRAMES = 3
    const val REPEAT_MS = 2500.0

    /** RGBA pixels → grey FloatArray (0..255). */
    fun toGray(rgba: ByteArray, w: Int, h: Int): FloatArray {
        val g = FloatArray(w * h)
        var p = 0
        for (i in g.indices) {
            g[i] = 0.299f * (rgba[p].toInt() and 0xff) + 0.587f * (rgba[p + 1].toInt() and 0xff) + 0.114f * (rgba[p + 2].toInt() and 0xff)
            p += 4
        }
        return g
    }

    fun stdOf(g: FloatArray): Double {
        var m = 0.0
        for (v in g) m += v
        m /= g.size
        var v2 = 0.0
        for (v in g) v2 += (v - m) * (v - m)
        return sqrt(v2 / g.size)
    }

    /** Mean abs difference between cur and prev zoomed by s about the centre (central 70 % only). */
    fun zoomError(prev: FloatArray, cur: FloatArray, w: Int, h: Int, s: Double): Double {
        val cx = (w - 1) / 2.0
        val cy = (h - 1) / 2.0
        var sum = 0.0
        var n = 0
        var y = floor(h * 0.15).toInt()
        while (y < h * 0.85) {
            var x = floor(w * 0.15).toInt()
            while (x < w * 0.85) {
                val px = cx + (x - cx) / s
                val py = cy + (y - cy) / s
                val x0 = floor(px).toInt()
                val y0 = floor(py).toInt()
                val fx = px - x0
                val fy = py - y0
                val i = y0 * w + x0
                val v = (prev[i] * (1 - fx) + prev[i + 1] * fx) * (1 - fy) + (prev[i + w] * (1 - fx) + prev[i + w + 1] * fx) * fy
                sum += kotlin.math.abs(cur[y * w + x] - v)
                n++
                x++
            }
            y++
        }
        return sum / n
    }

    /** Best zoom factor (>1 = getting closer), or 1 when the evidence is weak. */
    fun expansion(prev: FloatArray, cur: FloatArray, w: Int, h: Int): Double {
        val base = zoomError(prev, cur, w, h, 1.0)
        var best = 1.0
        var bestErr = base
        for (k in 1 until SCALES.size) {
            val e = zoomError(prev, cur, w, h, SCALES[k])
            if (e < bestErr) {
                best = SCALES[k]
                bestErr = e
            }
        }
        return if (bestErr < base * (1 - MIN_GAIN)) best else 1.0
    }

    /**
     * Feed one small grey frame (W × H). Returns what to say, or null.
     * brightOk: false when the camera is dark/covered (the health check speaks for that).
     */
    fun update(st: LoomState, gray: FloatArray, now: Double, brightOk: Boolean = true): LoomAlert? {
        var level = 0
        var kind = LoomKind.Warn
        val flat = brightOk && stdOf(gray) < FLAT_STD
        st.flat = if (flat) st.flat + 1 else 0
        val prev = st.prev
        if (prev != null && !flat) {
            val dt = (now - st.prevAt) / 1000
            val s = expansion(prev, gray, W, H)
            val ttc = if (s > 1 && dt > 0) dt / (s - 1) else Double.POSITIVE_INFINITY
            st.streak = if (ttc <= TTC_WARN) st.streak + 1 else 0
            if (st.streak >= STREAK) {
                if (ttc <= TTC_STOP) {
                    level = 3
                    kind = LoomKind.Stop
                } else {
                    level = 2
                    kind = LoomKind.Warn
                }
            }
        }
        if (st.flat >= FLAT_FRAMES) {
            level = 3
            kind = LoomKind.Wall
        }
        st.prev = gray
        st.prevAt = now
        if (level == 0) return null
        if (now - st.lastAt < REPEAT_MS && level <= st.lastLevel) return null
        st.lastAt = now
        st.lastLevel = level
        return LoomAlert(level, kind)
    }
}

/**
 * Every spoken/displayed walk-mode string, per language. Hindi is the web walk mode's wording
 * (walk/core.js), English follows DESIGN.md ("Chair ahead, about 2 metres", "More space on the left").
 * None of them may ever claim a way is clear or safe (tested).
 */
sealed class Phrasebook {
    abstract fun word(label: String): String
    abstract fun meters(d: Double, clipped: Boolean = false): String
    abstract fun phrase(label: String, zone: Zone, level: Int, approaching: Boolean, distText: String): String
    abstract fun lostClose(label: String): String
    abstract fun steer(h: SteerHint): String
    abstract fun join(line: String, hint: String): String
    abstract fun health(p: HealthProblem): String
    abstract fun loom(k: LoomKind): String
    abstract val start: String
    abstract val stop: String

    /** Every fixed sentence (for the "never says clear/safe" test). */
    val fixed: List<String>
        get() = listOf(start, stop) + HealthProblem.entries.map(::health) + LoomKind.entries.map(::loom) + SteerHint.entries.map(::steer)

    protected fun num(n: Double) = if (n == floor(n)) n.toLong().toString() else n.toString()

    data object Hindi : Phrasebook() {
        private val where = mapOf(Zone.Left to "बाईं ओर", Zone.Right to "दाईं ओर", Zone.Center to "सामने")
        override fun word(label: String) = WalkCore.OBJECTS.getValue(label).hi
        override fun meters(d: Double, clipped: Boolean): String {
            val n = WalkCore.bucket(d) ?: return "1 मीटर से कम"
            val spoken = when (n) {
                1.5 -> "डेढ़"
                2.5 -> "ढाई"
                else -> num(n)
            }
            return if (clipped) "$spoken मीटर के अंदर" else "लगभग $spoken मीटर"
        }
        override fun phrase(label: String, zone: Zone, level: Int, approaching: Boolean, distText: String): String {
            val o = WalkCore.OBJECTS.getValue(label)
            val w = where.getValue(zone)
            if (level >= 3) return "रुकिए! $w ${o.hi}" // short: it's already very close
            if (level == 2 && approaching) return "$w ${o.hi} पास आ ${if (o.fem) "रही" else "रहा"} है, $distText"
            return "$w ${o.hi}, $distText"
        }
        override fun lostClose(label: String) = "रुकिए! ${word(label)} बहुत पास है"
        override fun steer(h: SteerHint) = when (h) {
            SteerHint.Left -> "बाईं ओर कम रुकावट।"
            SteerHint.Right -> "दाईं ओर कम रुकावट।"
            SteerHint.Both -> "दोनों तरफ़ भी रुकावट।"
        }
        override fun join(line: String, hint: String) = "$line। $hint"
        override fun health(p: HealthProblem) = when (p) {
            HealthProblem.Stall -> "सावधान! कैमरा रुक गया है।"
            HealthProblem.Dark -> "सावधान! कैमरा ढका है या अंधेरा है।"
        }
        override fun loom(k: LoomKind) = when (k) {
            LoomKind.Warn -> "सावधान! सामने कोई चीज़ पास है।"
            LoomKind.Stop -> "रुकिए! सामने कुछ बहुत पास है।"
            LoomKind.Wall -> "रुकिए! सामने दीवार या बड़ी सतह हो सकती है।"
        }
        override val start = "चलना मोड शुरू। यह छड़ी की जगह नहीं, सिर्फ़ चेतावनी देगा।"
        override val stop = "चलना मोड बंद।"
    }

    data object English : Phrasebook() {
        override fun word(label: String) = WalkCore.OBJECTS.getValue(label).en
        override fun meters(d: Double, clipped: Boolean): String {
            val n = WalkCore.bucket(d) ?: return "under 1 metre"
            val unit = if (n == 1.0) "metre" else "metres"
            return if (clipped) "within ${num(n)} $unit" else "about ${num(n)} $unit"
        }
        private fun where(zone: Zone, obj: String) = when (zone) {
            Zone.Center -> "$obj ahead"
            Zone.Left -> "$obj on the left"
            Zone.Right -> "$obj on the right"
        }
        override fun phrase(label: String, zone: Zone, level: Int, approaching: Boolean, distText: String): String {
            val o = word(label)
            if (level >= 3) return "Stop! ${where(zone, o)}" // short: it's already very close
            if (level == 2 && approaching) return "${where(zone, o)}, coming closer, $distText"
            return "${where(zone, o)}, $distText"
        }
        override fun lostClose(label: String) = "Stop! ${word(label)} very close"
        override fun steer(h: SteerHint) = when (h) {
            SteerHint.Left -> "More space on the left."
            SteerHint.Right -> "More space on the right."
            SteerHint.Both -> "Things on both sides too."
        }
        override fun join(line: String, hint: String) = "$line. $hint"
        override fun health(p: HealthProblem) = when (p) {
            HealthProblem.Stall -> "Careful! The camera has stopped."
            HealthProblem.Dark -> "Careful! The camera is covered or it's too dark."
        }
        override fun loom(k: LoomKind) = when (k) {
            LoomKind.Warn -> "Careful! Something close ahead."
            LoomKind.Stop -> "Stop! Something very close ahead."
            LoomKind.Wall -> "Stop! Maybe a wall or large surface ahead."
        }
        override val start = "Walk mode on. It only warns. Keep using your cane."
        override val stop = "Walk mode off."
    }

    companion object {
        /** Follows the phone / app language: Hindi for "hi", English otherwise. */
        fun forLanguage(lang: String?) = if (lang == "hi") Hindi else English
    }
}

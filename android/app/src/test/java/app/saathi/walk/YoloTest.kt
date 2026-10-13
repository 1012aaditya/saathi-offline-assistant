package app.saathi.walk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** Letterbox, YOLOv8 decode and NMS on synthetic tensors (no onnxruntime needed). */
class YoloTest {
    private val names = listOf("person", "bicycle", "car", "airplane", "chair") // toy class list
    private val keep = Yolo.keepIndices(names, WalkCore.LABELS) // airplane isn't a walk-mode class

    /** Synthetic raw output [4 + classes, anchors], channel-major like Ultralytics' export. */
    private class Out(val classes: Int, val anchors: Int) {
        val data = FloatArray((4 + classes) * anchors)
        fun put(a: Int, cx: Float, cy: Float, w: Float, h: Float, cls: Int, score: Float) {
            data[a] = cx
            data[anchors + a] = cy
            data[2 * anchors + a] = w
            data[3 * anchors + a] = h
            data[(4 + cls) * anchors + a] = score
        }
    }

    @Test fun `letterbox a portrait 480x640 frame into 256`() {
        val lb = Letterbox.of(480, 640, 256)
        assertEquals(0.4, lb.scale, 1e-9)
        assertEquals(192.0, lb.dw, 1e-9)
        assertEquals(256.0, lb.dh, 1e-9)
        assertEquals(32.0, lb.dx, 1e-9)
        assertEquals(0.0, lb.dy, 1e-9)
    }

    @Test fun `keep only walk-mode classes`() {
        assertEquals(listOf(0, 1, 2, 4), keep.toList())
    }

    @Test fun `decode maps model pixels back to 0-1 frame fractions`() {
        val lb = Letterbox.of(480, 640, 256)
        val o = Out(names.size, 10)
        // A chair in the middle of the picture: 96 x 128 model px centred at (128, 128).
        o.put(3, 128f, 128f, 96f, 128f, cls = 4, score = 0.9f)
        val dets = Yolo.decode(o.data, 4 + names.size, 10, keep, names, lb)
        assertEquals(1, dets.size)
        val d = dets[0]
        assertEquals("chair", d.label)
        assertEquals(0.9, d.score, 1e-6)
        assertEquals((128 - 48 - 32) / 192.0, d.x, 1e-6)
        assertEquals(0.25, d.y, 1e-6)
        assertEquals(96 / 192.0, d.w, 1e-6)
        assertEquals(0.5, d.h, 1e-6)
    }

    @Test fun `decode ignores low scores and unwanted classes, and clips to the frame`() {
        val lb = Letterbox.of(480, 640, 256)
        val o = Out(names.size, 6)
        o.put(0, 128f, 128f, 50f, 50f, cls = 0, score = 0.2f) // below 0.3
        o.put(1, 128f, 128f, 50f, 50f, cls = 3, score = 0.95f) // airplane: not wanted
        o.put(2, 40f, 10f, 40f, 60f, cls = 2, score = 0.6f) // car sticking out top-left
        val dets = Yolo.decode(o.data, 4 + names.size, 6, keep, names, lb)
        assertEquals(listOf("car"), dets.map { it.label })
        val d = dets[0]
        assertEquals(0.0, d.x, 1e-9)
        assertEquals(0.0, d.y, 1e-9)
        assertTrue(d.w > 0 && d.h > 0 && d.x + d.w <= 1 && d.y + d.h <= 1)
    }

    @Test fun `nms keeps the best of overlapping boxes per class, not across classes`() {
        val lb = Letterbox.of(256, 256, 256)
        val o = Out(names.size, 5)
        o.put(0, 100f, 100f, 80f, 80f, cls = 0, score = 0.7f)
        o.put(1, 102f, 101f, 80f, 80f, cls = 0, score = 0.9f) // same person, higher score
        o.put(2, 100f, 100f, 80f, 80f, cls = 4, score = 0.5f) // a chair in the same place: kept
        o.put(3, 200f, 200f, 40f, 40f, cls = 0, score = 0.6f) // another person far away: kept
        val dets = Yolo.decode(o.data, 4 + names.size, 5, keep, names, lb)
        assertEquals(3, dets.size)
        assertEquals(listOf("person" to 0.9f, "person" to 0.6f, "chair" to 0.5f), dets.map { it.label to it.score.toFloat() })
    }

    @Test fun `nms caps at 20 boxes`() {
        val dets = (0 until 30).map { Det("chair", 0.5 + it / 100.0, it / 31.0, 0.0, 0.02, 0.02) }
        assertEquals(20, Yolo.nms(dets, 0.5).size)
    }

    /** 4 × 2 sensor image, each pixel's R = its index, so we can see where every sample came from. */
    private fun indexFrame(rotation: Int): RgbaFrame {
        val w = 4
        val h = 2
        val buf = ByteBuffer.allocate(w * h * 4)
        for (i in 0 until w * h) buf.put(i.toByte()).put(0).put(0).put(255.toByte())
        buf.rewind()
        return RgbaFrame(buf.array(), w, h, w * 4, rotation)
    }

    @Test fun `rotation 90 turns the sensor image upright (clockwise)`() {
        val f = indexFrame(90)
        assertEquals(2, f.uprightW)
        assertEquals(4, f.uprightH)
        // Upright top-left comes from the sensor's bottom-left (index 4); top-right from top-left (0).
        assertEquals(4.0, f.sample(0.0, 0.0, 0), 1e-4)
        assertEquals(0.0, f.sample(1.0, 0.0, 0), 1e-4)
        assertEquals(7.0, f.sample(0.0, 3.0, 0), 1e-4)
    }

    @Test fun `table preprocessing matches direct bilinear sampling, rotated`() {
        val w = 40
        val h = 30
        val bytes = ByteArray(w * h * 4) { ((it * 37) % 251).toByte() }
        val frame = RgbaFrame(bytes, w, h, w * 4, 90)
        val size = 32
        val out = FloatArray(3 * size * size)
        val lb = Preprocessor(size).run(frame, out)
        val plane = size * size
        val inv = 1 / lb.scale
        for (oy in listOf(3, 16, 28)) for (ox in listOf(4, 16, 26)) {
            val u = (ox + 0.5 - lb.dx) * inv - 0.5
            val v = (oy + 0.5 - lb.dy) * inv - 0.5
            for (c in 0..2) assertEquals(frame.sample(u, v, c) / 255, out[c * plane + oy * size + ox].toDouble(), 1e-4)
        }
    }

    @Test fun `preprocess letterboxes with grey padding and fills the picture`() {
        val buf = ByteBuffer.allocate(64 * 32 * 4)
        repeat(64 * 32) { buf.put(255.toByte()).put(0).put(0).put(255.toByte()) } // pure red, landscape
        buf.rewind()
        val frame = RgbaFrame(buf.array(), 64, 32, 64 * 4, 0)
        val size = 16
        val out = FloatArray(3 * size * size)
        val lb = Yolo.preprocess(frame, size, out)
        assertEquals(4.0, lb.dy, 1e-9) // 16 x 8 picture centred vertically
        val plane = size * size
        assertEquals(Yolo.PAD, out[0], 1e-6f) // top row: padding
        assertEquals(1f, out[8 * size + 8], 1e-6f) // centre: red
        assertEquals(0f, out[plane + 8 * size + 8], 1e-6f) // green channel
    }
}

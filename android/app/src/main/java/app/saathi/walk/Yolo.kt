package app.saathi.walk

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * YOLOv8 pre/post-processing, ported from walk/detector.js. Pure Kotlin so the
 * letterbox, decode and NMS are unit-tested on the JVM with synthetic tensors.
 */

/**
 * Where the (upright) camera frame sits inside the model's square input: scaled by [scale],
 * offset by ([dx], [dy]), the rest filled with grey 114 (Ultralytics' letterbox colour).
 */
data class Letterbox(val size: Int, val srcW: Int, val srcH: Int, val scale: Double, val dx: Double, val dy: Double) {
    val dw get() = srcW * scale
    val dh get() = srcH * scale

    companion object {
        fun of(srcW: Int, srcH: Int, size: Int): Letterbox {
            val scale = min(size.toDouble() / srcW, size.toDouble() / srcH)
            return Letterbox(size, srcW, srcH, scale, (size - srcW * scale) / 2, (size - srcH * scale) / 2)
        }
    }
}

/**
 * One camera frame as RGBA_8888 (CameraX ImageAnalysis output, copied into a heap array — direct
 * ByteBuffer reads one byte at a time are slow on ART), plus how to turn it upright.
 */
class RgbaFrame(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val rowStride: Int,
    /** Clockwise rotation that makes the image upright (CameraX imageInfo.rotationDegrees). */
    val rotation: Int,
) {
    /** Size of the upright image. */
    val uprightW get() = if (rotation % 180 == 0) width else height
    val uprightH get() = if (rotation % 180 == 0) height else width

    /** Upright (u, v) → sensor (x, y). */
    private fun sx(u: Double, v: Double) = when (rotation) {
        90 -> v
        180 -> width - 1 - u
        270 -> width - 1 - v
        else -> u
    }

    private fun sy(u: Double, v: Double) = when (rotation) {
        90 -> height - 1 - u
        180 -> height - 1 - v
        270 -> u
        else -> v
    }

    private companion object {
        const val INV255 = 1f / 255f
    }

    private fun at(x: Int, y: Int, c: Int): Int = bytes[y * rowStride + x * 4 + c].toInt() and 0xff

    /** Byte offset of the sensor pixel nearest to upright (u, v). */
    fun offsetOf(u: Double, v: Double): Int {
        val x = sx(u, v).toInt().coerceIn(0, width - 1)
        val y = sy(u, v).toInt().coerceIn(0, height - 1)
        return y * rowStride + x * 4
    }

    /** Bilinear taps for upright (u, v): 4 byte offsets into [o] and weights into [w] at index k. */
    fun taps(u: Double, v: Double, o: IntArray, w: FloatArray, k: Int) {
        val x = sx(u, v).coerceIn(0.0, width - 1.0)
        val y = sy(u, v).coerceIn(0.0, height - 1.0)
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val fx = (x - x0).toFloat()
        val fy = (y - y0).toFloat()
        o[4 * k] = y0 * rowStride + x0 * 4
        o[4 * k + 1] = y0 * rowStride + x1 * 4
        o[4 * k + 2] = y1 * rowStride + x0 * 4
        o[4 * k + 3] = y1 * rowStride + x1 * 4
        w[4 * k] = (1 - fx) * (1 - fy)
        w[4 * k + 1] = fx * (1 - fy)
        w[4 * k + 2] = (1 - fx) * fy
        w[4 * k + 3] = fx * fy
    }

    /** Same geometry (so cached sampling tables still apply). */
    fun sameShape(o: RgbaFrame?) = o != null && o.width == width && o.height == height && o.rowStride == rowStride && o.rotation == rotation

    /** Bilinear sample of channel c (0=R,1=G,2=B) at upright coordinates (u, v), in 0..255. */
    fun sample(u: Double, v: Double, c: Int): Double {
        val tmp = FloatArray(3)
        sampleRgb(u, v, tmp, 0, 1)
        return tmp[c] * 255.0
    }

    /** Bilinear RGB sample at upright (u, v), written as 0..1 floats to out[i], out[i + plane], out[i + 2·plane]. */
    fun sampleRgb(u: Double, v: Double, out: FloatArray, i: Int, plane: Int) {
        val x = sx(u, v).coerceIn(0.0, width - 1.0)
        val y = sy(u, v).coerceIn(0.0, height - 1.0)
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val fx = (x - x0).toFloat()
        val fy = (y - y0).toFloat()
        val w00 = (1 - fx) * (1 - fy)
        val w10 = fx * (1 - fy)
        val w01 = (1 - fx) * fy
        val w11 = fx * fy
        val p00 = y0 * rowStride + x0 * 4
        val p10 = y0 * rowStride + x1 * 4
        val p01 = y1 * rowStride + x0 * 4
        val p11 = y1 * rowStride + x1 * 4
        val b = bytes
        for (c in 0 until 3) {
            val s = (b[p00 + c].toInt() and 0xff) * w00 + (b[p10 + c].toInt() and 0xff) * w10 +
                (b[p01 + c].toInt() and 0xff) * w01 + (b[p11 + c].toInt() and 0xff) * w11
            out[i + c * plane] = s * INV255
        }
    }

    fun luma(u: Double, v: Double): Double {
        val x = sx(u, v).toInt().coerceIn(0, width - 1)
        val y = sy(u, v).toInt().coerceIn(0, height - 1)
        return 0.299 * at(x, y, 0) + 0.587 * at(x, y, 1) + 0.114 * at(x, y, 2)
    }
}

object Yolo {
    const val PAD = 114f / 255f

    /**
     * Letterbox the upright frame into a planar CHW float tensor (RGB, 0..1) of size × size.
     * out must hold 3 × size × size floats. One-off version (tests); the camera loop reuses a [Preprocessor].
     */
    fun preprocess(frame: RgbaFrame, size: Int, out: FloatArray): Letterbox = Preprocessor(size).run(frame, out)

    /** Small grey picture of the centre 60 % of the frame, for looming (Loom.W × Loom.H, 0..255). */
    fun loomGray(frame: RgbaFrame): FloatArray = Preprocessor(1).loomGray(frame)

    /** Mean brightness 0..1 from a 16 × 16 grid (dark / covered camera check). */
    fun brightness(frame: RgbaFrame): Double {
        var s = 0.0
        for (y in 0 until 16) for (x in 0 until 16) {
            s += frame.luma((x + 0.5) * frame.uprightW / 16, (y + 0.5) * frame.uprightH / 16)
        }
        return s / 256 / 255
    }

    /** Class indices to keep: those whose name is in [wanted]. */
    fun keepIndices(names: List<String>, wanted: Set<String>): IntArray =
        names.indices.filter { names[it] in wanted }.toIntArray()

    /**
     * Decode a raw YOLOv8 output [1, 4 + classes, anchors] (channel-major, as exported by
     * Ultralytics) into boxes as 0..1 fractions of the upright frame, then per-class NMS.
     */
    fun decode(
        data: FloatArray,
        channels: Int,
        anchors: Int,
        keep: IntArray,
        names: List<String>,
        lb: Letterbox,
        threshold: Float = 0.3f,
    ): List<Det> {
        require(data.size >= channels * anchors) { "output too small: ${data.size} < $channels × $anchors" }
        val n = anchors
        val dets = ArrayList<Det>()
        for (a in 0 until n) {
            var best = -1
            var score = threshold
            for (c in keep) {
                val s = data[(4 + c) * n + a]
                if (s > score) {
                    best = c
                    score = s
                }
            }
            if (best < 0) continue
            val cx = data[a].toDouble()
            val cy = data[n + a].toDouble()
            val bw = data[2 * n + a].toDouble()
            val bh = data[3 * n + a].toDouble()
            val x = (cx - bw / 2 - lb.dx) / lb.dw
            val y = (cy - bh / 2 - lb.dy) / lb.dh
            val x2 = min(1.0, x + bw / lb.dw)
            val y2 = min(1.0, y + bh / lb.dh)
            val x1 = max(0.0, x)
            val y1 = max(0.0, y)
            if (x2 <= x1 || y2 <= y1) continue
            dets.add(Det(names[best], score.toDouble(), x1, y1, x2 - x1, y2 - y1))
        }
        return nms(dets, 0.5)
    }

    private fun iou(a: Det, b: Det): Double {
        val ix = max(0.0, min(a.x + a.w, b.x + b.w) - max(a.x, b.x))
        val iy = max(0.0, min(a.y + a.h, b.y + b.h) - max(a.y, b.y))
        val inter = ix * iy
        val union = a.w * a.h + b.w * b.h - inter
        return inter / (if (union == 0.0) 1.0 else union)
    }

    /** Per-class non-max suppression, at most [maxBoxes] (WalkCore.dedupe then merges look-alike classes). */
    fun nms(dets: List<Det>, overlap: Double, maxBoxes: Int = 20): List<Det> {
        val kept = ArrayList<Det>()
        for (d in dets.sortedByDescending { it.score }) {
            if (kept.none { it.label == d.label && iou(it, d) > overlap }) kept.add(d)
            if (kept.size >= maxBoxes) break
        }
        return kept
    }
}

/**
 * Per-pixel sampling tables, built once per camera geometry and reused every frame, so the per-frame
 * work is plain array reads (the camera is 640 × 480 and the model wants 256 × 256).
 */
class Preprocessor(val size: Int) {
    private var shape: RgbaFrame? = null
    private var lb: Letterbox? = null
    private var dst = IntArray(0) // output pixel index per tap group
    private var off = IntArray(0)
    private var wt = FloatArray(0)
    private var loomShape: RgbaFrame? = null
    private var loomOff = IntArray(0)

    private fun build(frame: RgbaFrame): Letterbox {
        val lb = Letterbox.of(frame.uprightW, frame.uprightH, size)
        val x0 = floor(lb.dx).toInt()
        val x1 = min(size, x0 + Math.round(lb.dw).toInt())
        val y0 = floor(lb.dy).toInt()
        val y1 = min(size, y0 + Math.round(lb.dh).toInt())
        val n = (x1 - x0) * (y1 - y0)
        dst = IntArray(n)
        off = IntArray(4 * n)
        wt = FloatArray(4 * n)
        val inv = 1 / lb.scale
        var k = 0
        for (oy in y0 until y1) {
            val v = (oy + 0.5 - lb.dy) * inv - 0.5
            for (ox in x0 until x1) {
                dst[k] = oy * size + ox
                frame.taps((ox + 0.5 - lb.dx) * inv - 0.5, v, off, wt, k)
                k++
            }
        }
        shape = frame
        this.lb = lb
        return lb
    }

    fun run(frame: RgbaFrame, out: FloatArray): Letterbox {
        val lb = if (frame.sameShape(shape)) lb!! else build(frame)
        val plane = size * size
        java.util.Arrays.fill(out, 0, 3 * plane, Yolo.PAD)
        val b = frame.bytes
        val off = off
        val wt = wt
        val dst = dst
        for (k in dst.indices) {
            val j = 4 * k
            val o0 = off[j]
            val o1 = off[j + 1]
            val o2 = off[j + 2]
            val o3 = off[j + 3]
            val w0 = wt[j] * INV255
            val w1 = wt[j + 1] * INV255
            val w2 = wt[j + 2] * INV255
            val w3 = wt[j + 3] * INV255
            val i = dst[k]
            out[i] = (b[o0].toInt() and 0xff) * w0 + (b[o1].toInt() and 0xff) * w1 + (b[o2].toInt() and 0xff) * w2 + (b[o3].toInt() and 0xff) * w3
            out[plane + i] = (b[o0 + 1].toInt() and 0xff) * w0 + (b[o1 + 1].toInt() and 0xff) * w1 +
                (b[o2 + 1].toInt() and 0xff) * w2 + (b[o3 + 1].toInt() and 0xff) * w3
            out[2 * plane + i] = (b[o0 + 2].toInt() and 0xff) * w0 + (b[o1 + 2].toInt() and 0xff) * w1 +
                (b[o2 + 2].toInt() and 0xff) * w2 + (b[o3 + 2].toInt() and 0xff) * w3
        }
        return lb
    }

    /** Small grey picture of the centre 60 % of the frame, for looming (Loom.W × Loom.H, 0..255), 2 × 2 samples per cell. */
    fun loomGray(frame: RgbaFrame): FloatArray {
        if (!frame.sameShape(loomShape)) {
            val w = frame.uprightW.toDouble()
            val h = frame.uprightH.toDouble()
            val cw = w * 0.6
            val ch = h * 0.6
            val left = (w - cw) / 2
            val top = (h - ch) / 2
            val sx = cw / Loom.W
            val sy = ch / Loom.H
            loomOff = IntArray(4 * Loom.W * Loom.H)
            var k = 0
            for (y in 0 until Loom.H) for (x in 0 until Loom.W) for (j in 0..1) for (i in 0..1) {
                loomOff[k++] = frame.offsetOf(left + (x + 0.25 + 0.5 * i) * sx, top + (y + 0.25 + 0.5 * j) * sy)
            }
            loomShape = frame
        }
        val b = frame.bytes
        val g = FloatArray(Loom.W * Loom.H)
        val o = loomOff
        for (p in g.indices) {
            var s = 0f
            for (t in 0 until 4) {
                val q = o[4 * p + t]
                s += 0.299f * (b[q].toInt() and 0xff) + 0.587f * (b[q + 1].toInt() and 0xff) + 0.114f * (b[q + 2].toInt() and 0xff)
            }
            g[p] = s / 4
        }
        return g
    }

    private companion object {
        const val INV255 = 1f / 255f
    }
}

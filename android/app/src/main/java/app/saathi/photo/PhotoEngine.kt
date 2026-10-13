package app.saathi.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import app.saathi.walk.Backend
import app.saathi.walk.Det
import app.saathi.walk.Detector
import app.saathi.walk.Letterbox
import app.saathi.walk.WalkModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Runs the two walk-mode YOLOv8n models (COCO + Open Images V7, bundled in the APK) on one photo,
 * on the phone. Nothing is stored or sent: the photo stays in memory and is dropped with the screen.
 *
 * Each model's input is a fixed 256 px, so a 12 MP photo squeezed into 256 px loses small things.
 * The photo is therefore checked whole, then as four overlapping tiles (each ~1.7× the detail), and
 * the boxes are merged ([PhotoMerge]). About 10 model runs ≈ 1 s on the Galaxy Tab S6 Lite; on a phone
 * where the whole-photo pass alone is slow, the tiles are skipped.
 */
class PhotoEngine(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "photo-detect") }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val loaded = CompletableDeferred<Boolean>()

    // Owned by the executor thread.
    private var coco: Detector? = null
    private var oiv7: Detector? = null
    private var canvasBitmap: Bitmap? = null
    private var ints = IntArray(0)

    @Volatile private var closed = false

    /** What one analysis found. */
    data class Analysis(val found: List<Found>, val quality: Quality, val ms: Long, val crops: Int, val doorsModel: Boolean)

    init {
        executor.execute { load() }
    }

    private fun load() {
        try {
            coco = loadOne(WalkModel.Coco)
            oiv7 = runCatching { loadOne(WalkModel.Oiv7) }
                .onFailure { Log.w(TAG, "Open Images model unavailable", it) }
                .getOrNull()
                ?.takeIf { it.size == coco!!.size }
            loaded.complete(!closed)
        } catch (t: Throwable) {
            Log.e(TAG, "photo models failed to load", t)
            loaded.complete(false)
        }
    }

    private fun loadOne(m: WalkModel): Detector =
        runCatching { Detector.load(app, m, Backend.Xnnpack, THREADS, threshold = DECODE_MIN, labels = Vocab.LABELS) }
            .getOrElse { Detector.load(app, m, Backend.Cpu, THREADS, threshold = DECODE_MIN, labels = Vocab.LABELS) }

    /** True once both models are ready; false if they can't run on this phone. */
    suspend fun ready(): Boolean = loaded.await()

    /** Describe-ready analysis of an upright photo. Throws if the models couldn't load. */
    suspend fun analyse(photo: Bitmap): Analysis {
        check(ready()) { "photo models unavailable" }
        return withContext(dispatcher) { run(photo) }
    }

    private fun run(photo: Bitmap): Analysis {
        val det = coco ?: error("closed")
        val extra = oiv7
        val t0 = SystemClock.uptimeMillis()
        val quality = PhotoQuality.assess(luma(photo))
        val size = det.size
        val px = FloatArray(3 * size * size)
        val passes = ArrayList<Pair<Crop, List<Det>>>()
        for ((i, crop) in PhotoMerge.crops(photo.width, photo.height).withIndex()) {
            if (i == 1 && SystemClock.uptimeMillis() - t0 > SLOW_FULL_MS) break // slow phone: whole photo only
            if (closed) break
            val lb = render(photo, crop, size, px)
            val dets = det.detect(px, lb) + (extra?.detect(px, lb) ?: emptyList())
            passes.add(crop to dets)
        }
        val found = PhotoMerge.merge(passes)
        val ms = SystemClock.uptimeMillis() - t0
        Log.i(TAG, "${passes.size} crops, ${found.size} things, $ms ms")
        return Analysis(found, quality, ms, passes.size, extra != null)
    }

    /** Grey picture ~320 px wide for the dark / plain / blur checks. */
    private fun luma(photo: Bitmap): PhotoQuality.Stats {
        val small = shrink(photo, Rect(0, 0, photo.width, photo.height), QUALITY_WIDTH.toDouble())
        val w = small.width
        val h = small.height
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== photo) small.recycle()
        val g = FloatArray(w * h) { i ->
            val c = px[i]
            0.299f * ((c shr 16) and 0xff) + 0.587f * ((c shr 8) and 0xff) + 0.114f * (c and 0xff)
        }
        return PhotoQuality.stats(g, w, h)
    }

    /**
     * Halve [r] of [src] until it is at most 2 × [targetW] wide, so the last (bilinear) scaling step
     * doesn't alias. Returns [src] itself when no shrinking is needed and [r] is the whole bitmap.
     */
    private fun shrink(src: Bitmap, r: Rect, targetW: Double): Bitmap {
        var cur = if (r.left == 0 && r.top == 0 && r.width() == src.width && r.height() == src.height) {
            src
        } else {
            Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())
        }
        while (cur.width > 2 * targetW && cur.width >= 4 && cur.height >= 4) {
            val next = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
            if (cur !== src) cur.recycle()
            cur = next
        }
        if (cur.width > targetW && cur !== src) {
            val k = targetW / cur.width
            val next = Bitmap.createScaledBitmap(cur, max(1, (cur.width * k).roundToInt()), max(1, (cur.height * k).roundToInt()), true)
            cur.recycle()
            cur = next
        }
        return cur
    }

    /** Letterbox [crop] of [photo] into the model's square input (grey 114 padding, like Ultralytics). */
    private fun render(photo: Bitmap, crop: Crop, size: Int, out: FloatArray): Letterbox {
        val r = Rect(
            (crop.x0 * photo.width).roundToInt(),
            (crop.y0 * photo.height).roundToInt(),
            (crop.x1 * photo.width).roundToInt().coerceAtMost(photo.width),
            (crop.y1 * photo.height).roundToInt().coerceAtMost(photo.height),
        )
        val lb = Letterbox.of(r.width(), r.height(), size)
        val region = shrink(photo, r, lb.dw)
        val dst = canvasBitmap?.takeIf { it.width == size } ?: Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { canvasBitmap = it }
        Canvas(dst).apply {
            drawColor(android.graphics.Color.rgb(114, 114, 114))
            val src = if (region === photo) r else Rect(0, 0, region.width, region.height)
            drawBitmap(region, src, RectF(lb.dx.toFloat(), lb.dy.toFloat(), (lb.dx + lb.dw).toFloat(), (lb.dy + lb.dh).toFloat()), PAINT)
        }
        if (region !== photo) region.recycle()
        val plane = size * size
        if (ints.size != plane) ints = IntArray(plane)
        dst.getPixels(ints, 0, size, 0, 0, size, size)
        for (i in 0 until plane) {
            val c = ints[i]
            out[i] = ((c shr 16) and 0xff) * INV255
            out[plane + i] = ((c shr 8) and 0xff) * INV255
            out[2 * plane + i] = (c and 0xff) * INV255
        }
        return lb
    }

    override fun close() {
        closed = true
        executor.execute {
            coco?.close()
            oiv7?.close()
            coco = null
            oiv7 = null
            canvasBitmap?.recycle()
            canvasBitmap = null
        }
        executor.shutdown()
    }

    companion object {
        private const val TAG = "PhotoEngine"
        private const val THREADS = 4
        /** Decode floor; [Vocab] then applies each model's own (higher) floor. */
        private const val DECODE_MIN = 0.3f
        /** Whole-photo pass slower than this (both models): skip the tiles. */
        private const val SLOW_FULL_MS = 600L
        private const val QUALITY_WIDTH = 320
        private const val INV255 = 1f / 255f
        private val PAINT = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    }
}

/** A CameraX capture as an upright bitmap (in memory only). */
fun ImageProxy.toUprightBitmap(): Bitmap {
    val b = toBitmap()
    val rot = imageInfo.rotationDegrees
    if (rot == 0) return b
    val m = Matrix().apply { postRotate(rot.toFloat()) }
    val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    if (r !== b) b.recycle()
    return r
}

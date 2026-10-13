package app.saathi.walk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.sin

/**
 * DEBUG BUILDS ONLY (app/src/debug): on-device detector benchmark without the camera or the UI.
 * Runs N inferences on a synthetic 640×480 frame and logs ms/frame to logcat (tag WalkBench).
 * Only the shell can trigger it (the receiver requires android.permission.DUMP):
 *
 *   adb shell am broadcast -n app.saathi/.walk.WalkBenchReceiver --include-stopped-packages \
 *       --es model coco|oiv7|both|parallel --es ep cpu|xnnpack|nnapi --ei threads 4 --ei n 20
 *   adb logcat -s WalkBench WalkDetector
 */
class WalkBenchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                run(app, intent)
            } catch (t: Throwable) {
                Log.e(TAG, "bench failed: $t", t)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun run(context: Context, intent: Intent) {
        val which = intent.getStringExtra("model") ?: "coco"
        val backend = when (intent.getStringExtra("ep")) {
            "xnnpack" -> Backend.Xnnpack
            "nnapi" -> Backend.Nnapi
            else -> Backend.Cpu
        }
        val threads = intent.getIntExtra("threads", 4)
        val threads2 = intent.getIntExtra("threads2", threads)
        val n = intent.getIntExtra("n", 20)
        // Optional: a still image already on the device (e.g. adb push to the app's external files dir),
        // to check that real objects are found. Never a camera frame.
        val frame = intent.getStringExtra("image")?.let(::imageFrame) ?: syntheticFrame()

        val t0 = SystemClock.elapsedRealtime()
        val models = when (which) {
            "oiv7" -> listOf(WalkModel.Oiv7)
            "both", "parallel" -> listOf(WalkModel.Coco, WalkModel.Oiv7)
            else -> listOf(WalkModel.Coco)
        }
        val dets = models.mapIndexed { i, m -> Detector.load(context, m, backend, if (i == 0) threads else threads2) }
        Log.i(TAG, "loaded $which on $backend×$threads/$threads2 in ${SystemClock.elapsedRealtime() - t0} ms")

        val size = dets.first().size
        val pixels = FloatArray(3 * size * size)
        val prep = Preprocessor(size)
        val pool = Executors.newFixedThreadPool(2)
        val pre = ArrayList<Double>()
        val total = ArrayList<Double>()
        val per = models.associateWith { ArrayList<Double>() }
        var boxes = 0
        var lastFound: List<Det> = emptyList()
        try {
            repeat(n + 2) { iter ->
                val f0 = SystemClock.elapsedRealtimeNanos()
                val lb = prep.run(frame, pixels)
                val f1 = SystemClock.elapsedRealtimeNanos()
                val found = if (which == "parallel") {
                    val futures = dets.map { d -> pool.submit<List<Det>> { d.detect(pixels, lb) } }
                    futures.flatMap { it.get() }
                } else {
                    dets.flatMap { it.detect(pixels, lb) }
                }
                val f2 = SystemClock.elapsedRealtimeNanos()
                if (iter >= 2) { // first 2 = warm-up
                    pre.add((f1 - f0) / 1e6)
                    total.add((f2 - f0) / 1e6)
                    dets.forEach { per.getValue(it.model).add(it.lastMs) }
                    boxes = found.size
                    lastFound = found
                }
            }
        } finally {
            pool.shutdown()
            dets.forEach { it.close() }
        }
        fun stats(xs: List<Double>): String {
            val s = xs.sorted()
            return "avg %.1f · median %.1f · min %.1f · max %.1f".format(xs.average(), s[s.size / 2], s.first(), s.last())
        }
        Log.i(TAG, "RESULT model=$which ep=$backend threads=$threads/$threads2 n=$n size=$size")
        Log.i(TAG, "  preprocess ms: ${stats(pre)}")
        per.forEach { (m, xs) -> Log.i(TAG, "  ${m.name} inference ms: ${stats(xs)}") }
        lastFound.forEach { Log.i(TAG, "  found ${it.label} %.2f at x=%.2f y=%.2f w=%.2f h=%.2f".format(it.score, it.x, it.y, it.w, it.h)) }
        Log.i(TAG, "  TOTAL per frame ms (pre + models + decode): ${stats(total)} · boxes on synthetic frame: $boxes")
    }

    private fun imageFrame(path: String): RgbaFrame? {
        val bmp = android.graphics.BitmapFactory.decodeFile(path) ?: return null.also { Log.w(TAG, "can't read $path") }
        val argb = bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
        val buf = ByteBuffer.allocate(argb.width * argb.height * 4)
        argb.copyPixelsToBuffer(buf) // RGBA byte order
        Log.i(TAG, "image ${argb.width}x${argb.height} from $path")
        return RgbaFrame(buf.array(), argb.width, argb.height, argb.width * 4, 0)
    }

    /** 640×480 RGBA, sensor-landscape with a 90° rotation like a phone held upright. Smooth shapes + gradient. */
    private fun syntheticFrame(): RgbaFrame {
        val w = 640
        val h = 480
        val buf = ByteBuffer.allocate(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val r = (128 + 100 * sin(x / 37.0)).toInt()
            val g = (128 + 100 * sin(y / 23.0 + x / 91.0)).toInt()
            val b = if ((x / 80 + y / 60) % 2 == 0) 200 else 40
            buf.put(r.toByte()).put(g.toByte()).put(b.toByte()).put(255.toByte())
        }
        buf.rewind()
        return RgbaFrame(buf.array(), w, h, w * 4, 90)
    }

    private companion object {
        const val TAG = "WalkBench"
    }
}

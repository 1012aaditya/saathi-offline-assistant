package app.saathi.walk

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/** Where warnings go: the screen wires these to deps.voice / deps.feedback. Called on the main thread. */
interface WalkOutput {
    /** Speak now, replacing anything being said. Implementations skip it when sounds are off. */
    fun speak(text: String)

    /** 1 notice · 2 careful · 3 stop (ANDROID_APP.md §6 patterns). */
    fun vibrate(level: Int)

    fun isSpeaking(): Boolean
}

/** A tracked object for the overlay. box: 0..1 fractions of the upright camera frame. */
data class OverlayBox(
    val id: Int,
    val word: String,
    val level: Int,
    val dist: Double,
    val approaching: Boolean,
    val box: Box,
)

data class WalkSnapshot(
    val phase: Phase = Phase.Loading,
    val running: Boolean = false,
    val boxes: List<OverlayBox> = emptyList(),
    /** Upright camera frame width / height (for fitting the overlay to the preview). */
    val frameAspect: Double = 0.75,
    /** Last warning line (no hint), its level and when (uptime ms). */
    val line: String = "",
    val lineLevel: Int = 0,
    val lineAt: Long = 0,
    val hint: SteerHint? = null,
    val hintAt: Long = 0,
    val fps: Double = 0.0,
    val msPerFrame: Double = 0.0,
    val backend: String = "",
    /** false when the doors/stairs model failed to load or is skipped as too slow. */
    val extraOn: Boolean = true,
    val error: String? = null,
) {
    enum class Phase { Loading, Ready, Failed }
}

/**
 * Walk mode runtime: CameraX ImageAnalysis → [Detector] (both YOLOv8n models) → [WalkCore] →
 * speech + vibration, plus looming and camera health. Port of walk/app.js.
 *
 * Threads: camera frames arrive on [analysisThread] (looming + brightness on every frame, cheap);
 * when the detector is idle the frame is letterboxed there and handed to [detectThread]; frames
 * that arrive while it's busy are dropped, so it always runs at the fastest steady rate the
 * phone manages and never lags behind. Nothing is stored or sent anywhere.
 */
class WalkEngine(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val analysisThread: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "walk-camera") }
    private val detectThread: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "walk-detect") }
    private val extraThread: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "walk-detect-extra") }

    private val _state = MutableStateFlow(WalkSnapshot())
    val state: StateFlow<WalkSnapshot> = _state.asStateFlow()

    @Volatile private var mainDet: Detector? = null
    @Volatile private var extraDet: Detector? = null
    @Volatile private var parallel = false
    @Volatile private var released = false

    // Settings, applied live.
    @Volatile var words: Phrasebook = Phrasebook.English
    @Volatile var distScale: Double = 1.0

    // Run state.
    @Volatile private var running = false
    private var output: WalkOutput? = null
    private var provider: ProcessCameraProvider? = null
    private val busy = AtomicBoolean(false)
    private var pixels = FloatArray(0)
    private var prep: Preprocessor? = null
    private var frameBytes = ByteArray(0) // owned by analysisThread

    // Owned by detectThread.
    private var tracker = Tracker()
    private var announcer = Announcer()
    private var detFrames = 0
    private var fpsAt = 0L
    private var fpsFrames = 0

    // Owned by analysisThread.
    private var loom = LoomState()
    private var lastLoomAt = 0L
    private var camFrames = 0
    private val loomPrep = Preprocessor(1)

    // Shared (volatile): camera health.
    @Volatile private var lastFrameAt = 0L
    @Volatile private var brightness: Double? = null
    private var health = Health()

    // Speech arbitration (web app.js speak()): a line only interrupts a less urgent one.
    private val speechLock = Any()
    private var currentLevel = 0
    private var lastSpokeAt = 0L

    init {
        // Load both models off the main thread as soon as the screen opens (no camera yet).
        detectThread.execute { load() }
    }

    private fun load() {
        try {
            val prefs = app.getSharedPreferences("walk", Context.MODE_PRIVATE)
            val cached = prefs.getString(PREF_BACKEND, null)?.let { runCatching { Backend.valueOf(it) }.getOrNull() }
            val m = fastest(WalkModel.Coco, cached)
            if (released) return m.close()
            mainDet = m
            prefs.edit().putString(PREF_BACKEND, m.backend.name).apply()
            pixels = FloatArray(3 * m.size * m.size)
            prep = Preprocessor(m.size)
            var extra: Detector? = runCatching { Detector.load(app, WalkModel.Oiv7, m.backend, EXTRA_THREADS) }
                .onFailure { Log.w(TAG, "doors/stairs model unavailable", it) }
                .getOrNull()
            if (extra != null && extra.size != m.size) { extra.close(); extra = null }
            if (released) { extra?.close(); return }
            extraDet = extra
            parallel = PARALLEL_EXTRA
            _state.update {
                it.copy(phase = WalkSnapshot.Phase.Ready, backend = "${m.size}px ${m.backend}", extraOn = extra != null)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "walk models failed to load", t)
            _state.update { it.copy(phase = WalkSnapshot.Phase.Failed, error = t.message ?: t.toString()) }
        }
    }

    /**
     * Like detector.js fastestSession(): time a couple of runs on each backend and keep the faster
     * one. The choice is remembered, so later opens load one session only.
     */
    private fun fastest(model: WalkModel, cached: Backend?): Detector {
        val order = if (cached != null) listOf(cached) else CANDIDATES
        var best: Pair<Detector, Double>? = null
        for (b in order) {
            val d = runCatching { Detector.load(app, model, b, MAIN_THREADS) }
                .onFailure { Log.w(TAG, "$b unavailable", it) }
                .getOrNull() ?: continue
            if (order.size == 1) return d
            val px = FloatArray(3 * d.size * d.size) { 0.5f }
            val lb = Letterbox.of(d.size, d.size, d.size)
            d.detect(px, lb) // warm-up
            val t0 = SystemClock.elapsedRealtimeNanos()
            repeat(2) { d.detect(px, lb) }
            val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 2e6
            Log.i(TAG, "$model on $b: %.1f ms".format(ms))
            val cur = best
            if (cur == null || ms < cur.second) {
                cur?.first?.close()
                best = d to ms
            } else {
                d.close()
            }
        }
        return best?.first ?: Detector.load(app, model, Backend.Cpu, MAIN_THREADS)
    }

    /**
     * Bind the camera and start warning. Call on the main thread. [previewView] null: no preview
     * (walk mode from the floating bubble, over another app: camera analysis only).
     */
    fun start(owner: LifecycleOwner, previewView: PreviewView?, out: WalkOutput) {
        if (running || released) return
        output = out
        running = true
        val now = SystemClock.uptimeMillis()
        lastFrameAt = now
        brightness = null
        health = Health()
        synchronized(speechLock) { currentLevel = 0 }
        detectThread.execute {
            tracker = Tracker(distScale)
            announcer = Announcer()
            detFrames = 0
            fpsAt = SystemClock.uptimeMillis()
            fpsFrames = 0
        }
        analysisThread.execute {
            loom = LoomState()
            lastLoomAt = 0
            camFrames = 0
        }
        _state.update { it.copy(running = true, boxes = emptyList(), line = "", lineLevel = 0, hint = null, fps = 0.0) }
        say(words.start, SPEAK_ALWAYS)

        val future = ProcessCameraProvider.getInstance(app)
        future.addListener({
            if (!running) return@addListener
            try {
                val p = future.get()
                provider = p
                val selector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()
                val preview = previewView?.let { v ->
                    Preview.Builder()
                        .setResolutionSelector(
                            ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build(),
                        )
                        .build()
                        .also { it.surfaceProvider = v.surfaceProvider }
                }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(selector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { it.setAnalyzer(analysisThread, ::analyze) }
                p.unbindAll()
                if (preview != null) {
                    p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } else {
                    p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                }
                lastFrameAt = SystemClock.uptimeMillis()
            } catch (t: Throwable) {
                Log.e(TAG, "camera failed", t)
                _state.update { it.copy(error = "Camera unavailable: ${t.message ?: t}") }
                say(words.health(HealthProblem.Stall), SPEAK_ALWAYS)
            }
        }, ContextCompat.getMainExecutor(app))
        main.postDelayed(watchdog, WATCHDOG_MS)
    }

    /** Unbind the camera and go quiet. Safe to call twice. Call on the main thread. */
    fun stop(sayStopped: Boolean = true) {
        if (!running) return
        running = false
        main.removeCallbacks(watchdog)
        runCatching { provider?.unbindAll() }
        if (sayStopped) say(words.stop, SPEAK_ALWAYS)
        output = if (sayStopped) output else null
        _state.update { it.copy(running = false, boxes = emptyList(), line = "", lineLevel = 0, hint = null) }
    }

    private val wantFrame = AtomicBoolean(false)
    @Volatile private var grabbed: android.graphics.Bitmap? = null

    /**
     * The next camera frame, upright ("what's in front?" while walking, from the bubble), or null
     * if none came within [timeoutMs]. Blocking: call off the main thread.
     */
    fun nextFrame(timeoutMs: Long = 1_500): android.graphics.Bitmap? {
        if (!running) return null
        grabbed = null
        wantFrame.set(true)
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            grabbed?.let { grabbed = null; return it }
            Thread.sleep(30)
        }
        wantFrame.set(false)
        return null
    }

    /** Free the models and threads (screen left). */
    fun release() {
        stop(sayStopped = false)
        output = null
        released = true
        detectThread.execute {
            mainDet?.close()
            extraDet?.close()
            mainDet = null
            extraDet = null
        }
        detectThread.shutdown()
        extraThread.shutdown()
        analysisThread.shutdown()
    }

    // ---------- per-frame work ----------

    private fun analyze(image: ImageProxy) {
        try {
            if (!running) return
            val plane = image.planes[0]
            val src = plane.buffer
            src.rewind()
            if (frameBytes.size != src.remaining()) frameBytes = ByteArray(src.remaining())
            src.get(frameBytes) // one bulk copy; per-byte reads from the direct buffer are slow
            val frame = RgbaFrame(frameBytes, image.width, image.height, plane.rowStride, image.imageInfo.rotationDegrees)
            if (wantFrame.compareAndSet(true, false)) grabbed = runCatching { frame.toUprightBitmap() }.getOrNull()
            val now = SystemClock.uptimeMillis()
            camFrames++
            if (brightness == null || camFrames % 5 == 0) brightness = Yolo.brightness(frame)
            // Looming runs on (nearly) every camera frame, independent of the slower object models,
            // so a wall or anything else getting close is warned about almost at once.
            if (now - lastLoomAt >= LOOM_EVERY_MS) {
                lastLoomAt = now
                val b = brightness
                val a = Loom.update(loom, loomPrep.loomGray(frame), now.toDouble(), b == null || b > WalkCore.DARK_LUMA)
                if (a != null && a.level > busyLevel()) {
                    val text = words.loom(a.kind)
                    say(text, a.level)
                    vibrate(a.level)
                    _state.update { it.copy(line = text, lineLevel = a.level, lineAt = now, hint = null) }
                }
            }
            val det = mainDet
            if (det == null) {
                lastFrameAt = now // still loading: not a stall
                return
            }
            if (!busy.compareAndSet(false, true)) return // detector busy: drop this frame
            val lb = (prep ?: Preprocessor(det.size).also { prep = it }).run(frame, pixels)
            val aspect = frame.uprightW.toDouble() / frame.uprightH
            detectThread.execute { detect(det, lb, aspect) }
        } catch (t: Throwable) {
            Log.e(TAG, "frame failed", t)
            busy.set(false)
        } finally {
            image.close()
        }
    }

    private fun detect(det: Detector, lb: Letterbox, aspect: Double) {
        try {
            if (!running) return
            val t0 = SystemClock.uptimeMillis()
            val extra = extraDet
            // Measured fps < 3: run the doors/stairs model half as often so it never adds much lag.
            val every = if (_state.value.fps in 0.1..3.0) EXTRA_EVERY * 2 else EXTRA_EVERY
            val runExtra = extra != null && detFrames % every == 0
            val dets: List<Det> = if (!runExtra) {
                det.detect(pixels, lb)
            } else if (parallel) {
                val f: Future<List<Det>> = extraThread.submit<List<Det>> { extra!!.detect(pixels, lb) }
                det.detect(pixels, lb) + f.get()
            } else {
                det.detect(pixels, lb) + extra!!.detect(pixels, lb)
            }
            val now = SystemClock.uptimeMillis()
            onDetections(dets, now, aspect, (now - t0).toDouble())
        } catch (t: Throwable) {
            Log.e(TAG, "detect failed", t)
        } finally {
            busy.set(false)
        }
    }

    private fun onDetections(dets: List<Det>, now: Long, aspect: Double, ms: Double) {
        if (!running) return
        lastFrameAt = now
        detFrames++
        tracker.distScale = distScale
        val tracks = WalkCore.updateTracks(tracker, dets, now.toDouble(), aspect)
        // Something at "stop" distance on its first frame: buzz now, speak once it's confirmed.
        if (tracks.any { it.level >= 3 && it.hits == 1 }) vibrate(3)
        val alert = WalkCore.pickAlert(tracker, now.toDouble(), announcer, busyLevel(), words)
        if (alert != null) {
            say(alert.text, alert.level)
            vibrate(alert.level)
        }
        val hint = if (alert != null && alert.hint.isNotEmpty()) {
            tracks.firstOrNull { it.id == alert.id }?.let { WalkCore.steerHint(tracks, it) }
        } else {
            null
        }
        fpsFrames++
        val fpsNow = if (now - fpsAt >= 1000) fpsFrames * 1000.0 / (now - fpsAt) else null
        if (fpsNow != null) {
            fpsAt = now
            fpsFrames = 0
        }
        val boxes = tracks.map { t ->
            OverlayBox(t.id, words.word(t.label), if (t.hits >= WalkCore.MIN_HITS || t.level >= 3) t.level else 0, t.dist, t.approaching, t.box)
        }
        _state.update { s ->
            s.copy(
                boxes = boxes,
                frameAspect = aspect,
                line = alert?.line ?: s.line,
                lineLevel = alert?.level ?: s.lineLevel,
                lineAt = if (alert != null) now else s.lineAt,
                hint = if (alert != null) hint else s.hint,
                hintAt = if (alert != null) now else s.hintAt,
                fps = fpsNow ?: s.fps,
                msPerFrame = if (s.msPerFrame == 0.0) ms else s.msPerFrame * 0.8 + ms * 0.2,
            )
        }
    }

    private val watchdog: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            val p = WalkCore.checkHealth(health, now.toDouble(), lastFrameAt.toDouble(), brightness)
            if (p != null) {
                val text = words.health(p)
                say(text, 3)
                vibrate(3)
                _state.update { it.copy(line = text, lineLevel = 3, lineAt = now, hint = null) }
            }
            main.postDelayed(this, WATCHDOG_MS)
        }
    }

    // ---------- speech + vibration ----------

    private fun busyLevel(): Int = synchronized(speechLock) {
        val speaking = runCatching { output?.isSpeaking() == true }.getOrDefault(false)
        if (speaking || SystemClock.uptimeMillis() - lastSpokeAt < SPEECH_START_MS) currentLevel else 0
    }

    private fun say(text: String, level: Int) {
        synchronized(speechLock) {
            if (level < SPEAK_ALWAYS && busyLevel() >= level) return
            currentLevel = level
            lastSpokeAt = SystemClock.uptimeMillis()
        }
        main.post { output?.speak(text) }
    }

    private fun vibrate(level: Int) {
        main.post { if (running) output?.vibrate(level) }
    }

    companion object {
        private const val TAG = "WalkEngine"
        private const val PREF_BACKEND = "backend.v1"
        // Measured on the Galaxy Tab S6 Lite (Exynos 9611, 13 Oct, 256 px, debug build): XNNPACK×3
        // COCO ≈ 67 ms, Open Images ≈ 85 ms, both in a row ≈ 165 ms; CPU×3-4 ≈ 72-80 ms; NNAPI
        // ≈ 550 ms (falls back to slow paths) so it isn't tried. Running the two models at the same
        // time was slower than one after the other (they fight for the 4 big cores), and 3 threads
        // leave a core for the camera and the screen.
        private val CANDIDATES = listOf(Backend.Xnnpack, Backend.Cpu)
        private const val MAIN_THREADS = 3
        private const val EXTRA_THREADS = 3
        private const val PARALLEL_EXTRA = false
        private const val EXTRA_EVERY = 3
        private const val SPEAK_ALWAYS = 9 // start/stop/errors outrank every warning
        private const val LOOM_EVERY_MS = 100L
        private const val WATCHDOG_MS = 500L
        private const val SPEECH_START_MS = 400L // TTS reports "speaking" a moment after speak()
    }
}

/** This frame as an upright bitmap (a copy: the frame's bytes are reused for the next one). */
internal fun RgbaFrame.toUprightBitmap(): android.graphics.Bitmap {
    val tight = width * 4
    val pixels = if (rowStride == tight) {
        bytes.copyOf(tight * height)
    } else {
        ByteArray(tight * height).also { out -> for (r in 0 until height) System.arraycopy(bytes, r * rowStride, out, r * tight, tight) }
    }
    val b = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
    b.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(pixels))
    if (rotation % 360 == 0) return b
    val m = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
    return android.graphics.Bitmap.createBitmap(b, 0, 0, width, height, m, true).also { if (it !== b) b.recycle() }
}

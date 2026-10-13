package app.saathi.overlay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import androidx.core.content.ContextCompat
import app.saathi.data.WalkSensitivity
import app.saathi.feedback.Cue
import app.saathi.feedback.WalkLevel
import app.saathi.ui.Deps
import app.saathi.walk.Phrasebook
import app.saathi.walk.WalkEngine
import app.saathi.walk.WalkOutput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Walk mode from the bubble (owner, 14 Oct: "handle walk mode from the bubble"): the same
 * [WalkEngine] as the Walk screen, without a preview, over whatever app is in front. Warnings are
 * spoken and vibrated exactly as on the Walk screen; the panel shows "Walking · Stop walking".
 *
 * Camera: never asked from here (an overlay can't show Android's dialog). If Saathi doesn't have it
 * yet, the user is told to allow it once on Saathi's Walk screen. Main thread only.
 */
internal class FloatingWalk(private val context: Context, private val deps: Deps) {
    private companion object {
        const val TAG = "SaathiFloatingWalk"
    }

    private var engine: WalkEngine? = null
    private var owner: OverlayOwner? = null

    private val _walking = MutableStateFlow(false)
    val walking: StateFlow<Boolean> = _walking.asStateFlow()

    private val output = object : WalkOutput {
        // Obstacle warnings are safety output: always spoken while walking (as on the Walk screen).
        override fun speak(text: String) = deps.voice.speak(text)

        override fun vibrate(level: Int) {
            when (level) {
                1 -> WalkLevel.Notice
                2 -> WalkLevel.Careful
                3 -> WalkLevel.Stop
                else -> null
            }?.let(deps.feedback::walk)
        }

        override fun isSpeaking() = deps.voice.speaking.value
    }

    fun hasCamera(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** Start walking. False (and said why) when the camera isn't allowed or walk mode can't run. */
    fun start(): Boolean {
        if (_walking.value) return true
        if (!hasCamera()) {
            deps.feedback.cue(Cue.Cancelled)
            deps.voice.speak("Walk mode needs the camera. Open Saathi's Walk screen once and allow it.")
            return false
        }
        val e = engine ?: WalkEngine(context).also { engine = it }
        val state = deps.state
        e.words = Phrasebook.forLanguage(state.languageTag?.substringBefore('-') ?: deps.voice.locale.language)
        e.distScale = when (state.walkSensitivity) {
            WalkSensitivity.Low -> 0.75
            WalkSensitivity.Medium -> 1.0
            WalkSensitivity.High -> 1.3
        }
        val o = OverlayOwner().also { it.create() }
        owner = o
        deps.voice.stopSpeaking()
        deps.feedback.cue(Cue.StartListening)
        runCatching { e.start(o, null, output) }
            .onFailure {
                Log.w(TAG, "walk from the bubble failed: ${it::class.simpleName}")
                stopCamera()
                deps.voice.speak("Walk mode couldn't start here. Try Saathi's Walk screen.")
                return false
            }
        _walking.value = true
        Log.i(TAG, "walking from the bubble")
        return true
    }

    fun stop() {
        if (!_walking.value) return
        engine?.stop()
        deps.feedback.stopVibration()
        deps.feedback.cue(Cue.StopListening)
        stopCamera()
        _walking.value = false
        Log.i(TAG, "walk from the bubble stopped")
    }

    private fun stopCamera() {
        owner?.destroy()
        owner = null
    }

    /** The next camera frame while walking (blocking: off the main thread), else null. */
    fun frame(): Bitmap? = if (_walking.value) engine?.nextFrame() else null

    /** The service is going: camera off, models freed. */
    fun release() {
        if (_walking.value) {
            engine?.stop(sayStopped = false)
            deps.feedback.stopVibration()
            stopCamera()
            _walking.value = false
        }
        engine?.release()
        engine = null
    }
}

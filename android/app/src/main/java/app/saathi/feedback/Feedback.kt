package app.saathi.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/** Earcon + haptic events from ANDROID_APP.md §6 / DESIGN.md. Same everywhere in the app. */
enum class Cue { StartListening, StopListening, Tick, NeedsAnswer, Done, Cancelled, HandOver }

/** Walk-mode warning levels; haptics only (sound is optional and quiet). */
enum class WalkLevel { Notice, Careful, Stop }

/**
 * One pulse train: [timings] alternate off/on in ms (Android waveform format, starting with a
 * delay); [amps] are the on-segment strengths 1..255 used when the motor supports amplitude.
 */
private class Haptic(val timings: LongArray, val amps: IntArray)

class Feedback(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Read by the UI each time so the Settings toggle applies immediately. */
    var soundOn: Boolean = true

    /**
     * Opt-in soft tick on [Cue.Tick] (DESIGN.md has no vibration for "Working", so off by default).
     * Also used by [tick].
     */
    var tickHaptic: Boolean = false

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /** False on phones/tablets with no vibration motor (e.g. Galaxy Tab S6 Lite). Settings can say so. */
    val canVibrate: Boolean get() = vibrator?.hasVibrator() == true

    /**
     * False when the user has turned vibration off system-wide ("Vibration & haptics" master switch,
     * Android 13+ setting `vibrate_on`). Informational only: Saathi never overrides it, and Android
     * would drop the vibration anyway.
     */
    val systemVibrationOn: Boolean
        get() = runCatching { Settings.System.getInt(context.contentResolver, "vibrate_on", 1) != 0 }.getOrDefault(true)

    // DESIGN.md table: start/stop 1 tap 40 ms · needs answer 2 taps · done 1 long 120 ms ·
    // cancelled 2 short · hand-over 3 taps · walk 80 / 200-100-200 / 400-100-400-100-400.
    private fun hapticFor(cue: Cue): Haptic? = when (cue) {
        Cue.StartListening -> Haptic(longArrayOf(0, 40), intArrayOf(200))
        Cue.StopListening -> Haptic(longArrayOf(0, 40), intArrayOf(150))
        Cue.Tick -> null
        Cue.NeedsAnswer -> Haptic(longArrayOf(0, 40, 60, 40), intArrayOf(220, 220))
        Cue.Done -> Haptic(longArrayOf(0, 120), intArrayOf(255))
        Cue.Cancelled -> Haptic(longArrayOf(0, 30, 60, 30), intArrayOf(140, 140))
        Cue.HandOver -> Haptic(longArrayOf(0, 40, 60, 40, 60, 40), intArrayOf(230, 230, 230))
    }

    private fun hapticFor(level: WalkLevel): Haptic = when (level) {
        WalkLevel.Notice -> Haptic(longArrayOf(0, 80), intArrayOf(160))
        WalkLevel.Careful -> Haptic(longArrayOf(0, 200, 100, 200), intArrayOf(220, 220))
        WalkLevel.Stop -> Haptic(longArrayOf(0, 400, 100, 400, 100, 400), intArrayOf(255, 255, 255))
    }

    fun cue(cue: Cue) {
        // Haptics always play (blind users rely on them); sound follows the setting.
        hapticFor(cue)?.let(::vibrate)
        if (cue == Cue.Tick && tickHaptic) tick()
        if (!soundOn) return
        val notes = when (cue) {
            Cue.StartListening -> listOf(440.0, 660.0)
            Cue.StopListening -> listOf(660.0, 440.0)
            Cue.Tick -> listOf(1200.0)
            Cue.NeedsAnswer -> listOf(587.0, 784.0)
            Cue.Done -> listOf(523.0, 659.0, 784.0)
            Cue.Cancelled -> listOf(220.0)
            Cue.HandOver -> listOf(880.0, 1320.0, 880.0)
        }
        play(notes, gain = if (cue == Cue.Tick) 0.12 else 0.35)
    }

    fun walk(level: WalkLevel) = vibrate(hapticFor(level))

    /** Soft tick (e.g. a step finished, a row focused). Very light; never repeats. */
    fun tick() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        } else {
            VibrationEffect.createOneShot(12, if (v.hasAmplitudeControl()) 70 else VibrationEffect.DEFAULT_AMPLITUDE)
        }
        deliver(v, effect)
    }

    fun stopVibration() = vibrator?.cancel()

    private fun vibrate(h: Haptic) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val effect = if (v.hasAmplitudeControl()) {
            // Waveform amplitudes: 0 for each gap, the cue's strength for each pulse.
            var pulse = 0
            val amps = IntArray(h.timings.size) { i -> if (i % 2 == 1) h.amps.getOrElse(pulse++) { 255 } else 0 }
            VibrationEffect.createWaveform(h.timings, amps, -1)
        } else {
            VibrationEffect.createWaveform(h.timings, -1)
        }
        deliver(v, effect)
    }

    /**
     * Vibrate tagged as accessibility feedback. Android only silences ringtone/notification
     * vibrations in silent mode, so these still play when the phone is on silent; the user's
     * "Vibration & haptics" switch and intensity settings still apply (we never set bypass flags).
     */
    private fun deliver(v: Vibrator, effect: VibrationEffect) {
        runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                    v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ACCESSIBILITY))
                else -> {
                    @Suppress("DEPRECATION")
                    v.vibrate(
                        effect,
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                }
            }
        }
    }

    /** Short sine notes, 90 ms apart, each with a soft attack/decay. Generated, so no audio assets. */
    private fun play(freqs: List<Double>, gain: Double) = scope.launch {
        val rate = 44_100
        val noteLen = (0.35 * rate).toInt()
        val step = (0.09 * rate).toInt()
        val total = step * (freqs.size - 1) + noteLen
        val pcm = ShortArray(total)
        freqs.forEachIndexed { i, f ->
            val start = i * step
            for (n in 0 until noteLen) {
                val t = n.toDouble() / rate
                val env = min(1.0, t / 0.02) * exp(-t * 9.0)
                val s = sin(2 * PI * f * t) * env * gain
                val idx = start + n
                pcm[idx] = (pcm[idx] + (s * Short.MAX_VALUE)).toInt().coerceIn(-32767, 32767).toShort()
            }
        }
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            kotlinx.coroutines.delay((total * 1000L / rate) + 100)
            track.release()
        }
    }
}

/** Android "Remove animations" (Settings > Accessibility). Motion off, sounds/haptics unchanged. */
fun reducedMotion(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f ||
        Settings.Global.getFloat(context.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) == 0f

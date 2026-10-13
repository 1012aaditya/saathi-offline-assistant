package app.saathi.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
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

class Feedback(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Read by the UI each time so the Settings toggle applies immediately. */
    var soundOn: Boolean = true

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    fun cue(cue: Cue) {
        // Haptics always play (blind users rely on them); sound follows the setting.
        when (cue) {
            Cue.StartListening, Cue.StopListening -> vibrate(longArrayOf(0, 40))
            Cue.Tick -> Unit
            Cue.NeedsAnswer -> vibrate(longArrayOf(0, 40, 60, 40))
            Cue.Done -> vibrate(longArrayOf(0, 120))
            Cue.Cancelled -> vibrate(longArrayOf(0, 30, 60, 30))
            Cue.HandOver -> vibrate(longArrayOf(0, 40, 60, 40, 60, 40))
        }
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

    fun walk(level: WalkLevel) {
        when (level) {
            WalkLevel.Notice -> vibrate(longArrayOf(0, 80))
            WalkLevel.Careful -> vibrate(longArrayOf(0, 200, 100, 200))
            WalkLevel.Stop -> vibrate(longArrayOf(0, 400, 100, 400, 100, 400))
        }
    }

    fun stopVibration() = vibrator?.cancel()

    private fun vibrate(pattern: LongArray) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        v.vibrate(VibrationEffect.createWaveform(pattern, -1))
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

package app.saathi.braille

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

private const val RATE = 44_100

/** 100 ms of a 440 Hz sine = exactly 44 cycles, so it loops without a click. */
private const val LOOP_FRAMES = RATE / 10
private const val DOT_HZ = 440.0

/**
 * Output for the braille surface: while the finger is on a raised dot, buzz and/or play a steady
 * tone; stop when it leaves. Vibration whenever the phone has a motor; sound when [sound] is on.
 * Separate from Feedback's cues so the two never fight over the motor's patterns.
 */
class BrailleHaptics(context: Context) {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** False on devices with no motor (e.g. Galaxy Tab S6 Lite): sound is the only way to feel dots. */
    val canVibrate: Boolean = vibrator?.hasVibrator() == true

    /** Play tones. Set from the screen's switch; defaults to on when the device can't vibrate. */
    var sound: Boolean = !canVibrate
        set(value) {
            field = value
            if (!value) dotTone?.let { if (it.playState == AudioTrack.PLAYSTATE_PLAYING) stopTone(it) }
        }

    private var buzzing = false

    /** Steady tone while on a dot. Accessibility usage: plays at TalkBack's volume. */
    private val dotTone: AudioTrack? = runCatching {
        val pcm = ShortArray(LOOP_FRAMES) { n -> (sin(2 * PI * DOT_HZ * n / RATE) * 0.3 * Short.MAX_VALUE).toInt().toShort() }
        track(pcm).apply { setLoopPoints(0, LOOP_FRAMES, -1) }
    }.getOrNull()

    /** Steady buzz/tone until [off]; the buzz is capped at 4 s so a stuck finger can't vibrate forever. */
    fun on() {
        if (buzzing) return
        buzzing = true
        if (canVibrate) vibrator?.vibrate(VibrationEffect.createOneShot(4_000, VibrationEffect.DEFAULT_AMPLITUDE))
        if (sound) dotTone?.let { runCatching { it.play() } }
    }

    fun off() {
        if (!buzzing) return
        buzzing = false
        if (canVibrate) vibrator?.cancel()
        dotTone?.let(::stopTone)
    }

    /** Short double tick: moved to another cell. */
    fun nextCell() {
        off()
        if (canVibrate) vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 25, 70, 25), -1))
        if (sound) blips(listOf(1200.0 to 30, 0.0 to 60, 1200.0 to 30))
    }

    /** Long single pulse / low tone: end of the text. */
    fun end() {
        off()
        if (canVibrate) vibrator?.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
        if (sound) blips(listOf(330.0 to 300))
    }

    /**
     * Headphones (wired, USB or Bluetooth) connected? With private text and sound on, people nearby
     * could hear which dots are raised — the screen warns when this is false.
     */
    @SuppressLint("InlinedApi") // TYPE_BLE_HEADSET is a plain int; on older Android it just never matches.
    fun headphonesOn(): Boolean = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
        it.type in setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
    }

    private var pulseTrack: AudioTrack? = null

    /**
     * Rhythm braille: start [pulses] now on the motor and/or speaker; returns their length in ms
     * (the caller waits). Raised = strong long pulse, flat = faint short tick, sentence end = low pulse.
     */
    fun pulses(pulses: List<Pulse>): Int {
        stopPulses()
        val total = durationMs(pulses)
        if (canVibrate) {
            val v = vibrator!!
            val amp = v.hasAmplitudeControl()
            val timings = pulses.map { it.ms.toLong() }.toLongArray()
            val amps = pulses.map {
                when (it.kind) {
                    PulseKind.Raised -> 255
                    PulseKind.Flat -> if (amp) 60 else 255 // no amplitude control: the short length tells it apart
                    PulseKind.SentenceEnd -> if (amp) 160 else 255
                    PulseKind.Silence -> 0
                }
            }.toIntArray()
            v.vibrate(VibrationEffect.createWaveform(timings, amps, -1))
        }
        if (sound) {
            val pcm = pulses.flatMap { p ->
                when (p.kind) {
                    PulseKind.Raised -> tone(520.0, p.ms, 0.30)
                    PulseKind.Flat -> tone(260.0, p.ms, 0.10)
                    PulseKind.SentenceEnd -> tone(220.0, p.ms, 0.30)
                    PulseKind.Silence -> List(RATE * p.ms / 1000) { 0.toShort() }
                }
            }.toShortArray()
            runCatching { pulseTrack = track(pcm).also { it.play() } }
        }
        return total
    }

    fun stopPulses() {
        if (canVibrate) vibrator?.cancel()
        pulseTrack?.let { runCatching { it.stop(); it.release() } }
        pulseTrack = null
    }

    private fun tone(hz: Double, ms: Int, gain: Double): List<Short> {
        val n = RATE * ms / 1000
        val fade = min(n / 2, RATE / 200) // 5 ms, no clicks
        return List(n) { i ->
            val env = min(1.0, min(i, n - 1 - i).toDouble() / fade.coerceAtLeast(1))
            (sin(2 * PI * hz * i / RATE) * env * gain * Short.MAX_VALUE).toInt().toShort()
        }
    }

    /** Call when the screen goes away. */
    fun release() {
        off()
        stopPulses()
        dotTone?.release()
        scope.cancel()
    }

    private fun stopTone(t: AudioTrack) = runCatching {
        t.pause()
        t.playbackHeadPosition = 0
    }

    private fun track(pcm: ShortArray): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setTransferMode(AudioTrack.MODE_STATIC)
        .setBufferSizeInBytes(pcm.size * 2)
        .build()
        .also { it.write(pcm, 0, pcm.size) }

    /** (frequency Hz, duration ms) pieces; 0 Hz = silence. Short fade in/out so there's no click. */
    private fun blips(parts: List<Pair<Double, Int>>) = scope.launch {
        val pcm = parts.flatMap { (hz, ms) ->
            val n = RATE * ms / 1000
            val fade = min(n / 2, RATE / 200) // 5 ms
            List(n) { i ->
                if (hz == 0.0) 0.toShort()
                else {
                    val env = min(1.0, min(i, n - 1 - i).toDouble() / fade)
                    (sin(2 * PI * hz * i / RATE) * env * 0.3 * Short.MAX_VALUE).toInt().toShort()
                }
            }
        }.toShortArray()
        runCatching {
            val t = track(pcm)
            t.play()
            delay(parts.sumOf { it.second }.toLong() + 100)
            t.release()
        }
    }
}

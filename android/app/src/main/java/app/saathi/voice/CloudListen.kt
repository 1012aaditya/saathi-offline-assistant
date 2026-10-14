package app.saathi.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Speech to text by a cloud model (demo builds: OpenAI through the laptop bridge, 14 Oct, owner:
 * "it catches wrong words, use ChatGPT"). [Voice] records one utterance itself ([Recorder]) and
 * hands the WAV here. Null = no text (the caller says "I didn't catch that").
 */
interface Transcriber {
    /** True when it can be used right now (e.g. the laptop answered its last check). */
    val available: Boolean

    /** Blocking: call off the main thread. */
    fun transcribe(wav: ByteArray): String?
}

/**
 * One utterance from the microphone: starts when speech starts, ends after [END_SILENCE_MS] of
 * quiet (or [MAX_MS]); nothing at all within [NO_SPEECH_MS] = no utterance. Also tells when the
 * microphone gives only silence (Android blocked it for this process): the caller then falls back
 * to the system recognizer.
 */
internal class Recorder(
    private val stillWanted: () -> Boolean,
    private val stopAsked: () -> Boolean,
    private val onOpen: () -> Unit,
    private val onLevel: (Float) -> Unit,
) {
    sealed interface Result {
        data class Speech(val wav: ByteArray) : Result
        data object NoSpeech : Result
        data object Blocked : Result
        data object Cancelled : Result
    }

    companion object {
        const val RATE = 16_000
        private const val FRAME = 320 // 20 ms
        private const val END_SILENCE_MS = 900
        private const val NO_SPEECH_MS = 7_000
        private const val MAX_MS = 12_000
        private const val BLOCKED_CHECK_MS = 800
        private const val MIN_SPEECH_RMS = 220.0
        private const val ANY_SOUND_RMS = 120.0
    }

    @SuppressLint("MissingPermission") // the caller checked RECORD_AUDIO
    fun record(): Result {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, FRAME * 8))
        }.getOrNull() ?: return Result.Blocked
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return Result.Blocked }
        val pcm = ByteArrayOutputStream()
        val frame = ShortArray(FRAME)
        var ms = 0
        var anySignal = false
        var noise = 0.0
        var speechAt = -1
        var lastLoud = 0
        var peak = 0.0
        var anyPeak = 0.0
        try {
            rec.startRecording()
            onOpen()
            while (true) {
                if (!stillWanted()) return Result.Cancelled
                val n = rec.read(frame, 0, FRAME)
                if (n <= 0) continue
                ms += n * 1000 / RATE
                var sum = 0.0
                for (i in 0 until n) { val v = frame[i].toInt(); sum += v * v; if (v != 0) anySignal = true }
                val rms = sqrt(sum / n)
                if (ms <= 300) noise = minOf(max(noise, rms), 600.0) // the room before the person speaks (capped: they may start at once)
                anyPeak = max(anyPeak, rms)
                onLevel(((rms / 3000.0).coerceIn(0.0, 1.0)).toFloat())
                val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until n) bb.putShort(frame[i])
                pcm.write(bb.array())
                if (ms >= BLOCKED_CHECK_MS && !anySignal) return Result.Blocked
                val loud = rms > max(MIN_SPEECH_RMS, noise * 2.5)
                if (loud) { if (speechAt < 0) speechAt = ms; peak = max(peak, rms) }
                // Still talking: well above the room AND a fair share of how loud the voice was
                // (14 Oct: a fan / far-off talk kept a fixed threshold "loud" for the full 12 s).
                if (speechAt >= 0 && rms > max(MIN_SPEECH_RMS, max(noise * 2.0, peak * 0.18))) lastLoud = ms
                if (speechAt < 0 && ms >= NO_SPEECH_MS) {
                    android.util.Log.i("SaathiVoice", "speech in: no clear start in ${ms} ms, loudest ${anyPeak.toInt()}, room ${noise.toInt()}")
                    // Something was said, just quietly: let the model decide (it returns nothing for silence).
                    return if (anyPeak >= ANY_SOUND_RMS) Result.Speech(wav(pcm.toByteArray())) else Result.NoSpeech
                }
                if (speechAt >= 0 && (ms - lastLoud >= END_SILENCE_MS || stopAsked())) break
                if (speechAt < 0 && stopAsked()) return Result.NoSpeech
                if (ms >= MAX_MS) break
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        android.util.Log.i("SaathiVoice", "speech in: recorded ${ms} ms, loudest ${anyPeak.toInt()}, room ${noise.toInt()}")
        return Result.Speech(wav(pcm.toByteArray()))
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(pcm.size)
        return h.array() + pcm
    }
}

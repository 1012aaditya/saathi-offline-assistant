package app.saathi.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

/**
 * Step-1 voice: Android's built-in SpeechRecognizer + TextToSpeech, in the phone's language.
 * No API keys and no network calls from this app. A later step may route speech through the
 * Saathi proxy (Sarvam) for better Hindi; the UI only talks to this class.
 */
class Voice(private val context: Context) {

    sealed interface Listen {
        data object Idle : Listen
        data class Hearing(val partial: String, val level: Float) : Listen
        data class Heard(val text: String) : Listen
        data class Failed(val reason: String) : Listen
    }

    private val _listen = MutableStateFlow<Listen>(Listen.Idle)
    val listen: StateFlow<Listen> = _listen.asStateFlow()

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    var locale: Locale = Locale.getDefault()
    /** 0.7 .. 1.5, Settings > Voice > Speaking rate. */
    var rate: Float = 1.0f

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pending = ArrayDeque<String>()

    val recognitionAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun init() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = locale
                while (pending.isNotEmpty()) speak(pending.removeFirst())
            }
        }.also {
            it.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { _speaking.value = true }
                override fun onDone(id: String?) { _speaking.value = false }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { _speaking.value = false }
            })
        }
    }

    /** Speak now, replacing anything already being said. */
    fun speak(text: String) {
        val t = tts
        if (t == null || !ttsReady) { pending.addLast(text); init(); return }
        t.language = locale
        t.setSpeechRate(rate)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    fun stopSpeaking() { tts?.stop(); _speaking.value = false }

    /** Must be called on the main thread. Needs RECORD_AUDIO granted. */
    fun startListening() {
        stopSpeaking()
        if (!recognitionAvailable) { _listen.value = Listen.Failed("Speech recognition isn't available on this phone."); return }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { _listen.value = Listen.Hearing("", 0f) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                val cur = _listen.value
                val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                _listen.value = Listen.Hearing((cur as? Listen.Hearing)?.partial.orEmpty(), level)
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                _listen.value = Listen.Failed(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Saathi needs the microphone permission."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech needs a connection right now."
                        else -> "Listening stopped."
                    },
                )
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                _listen.value = if (text.isBlank()) Listen.Failed("I didn't catch that.") else Listen.Heard(text)
            }
            override fun onPartialResults(partial: Bundle?) {
                val text = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                val cur = _listen.value
                _listen.value = Listen.Hearing(text, (cur as? Listen.Hearing)?.level ?: 0f)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        _listen.value = Listen.Hearing("", 0f)
        r.startListening(intent)
    }

    fun stopListening() { recognizer?.stopListening() }

    fun cancelListening() { recognizer?.cancel(); _listen.value = Listen.Idle }

    fun reset() { _listen.value = Listen.Idle }

    fun shutdown() {
        recognizer?.destroy(); recognizer = null
        tts?.shutdown(); tts = null; ttsReady = false
    }
}

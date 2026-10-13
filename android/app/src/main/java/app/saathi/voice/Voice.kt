package app.saathi.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID
import android.speech.tts.Voice as TtsVoice

/**
 * Voice in and out, offline first. No API keys and no network calls from this app.
 *
 * Speech in: on Android 12+ the phone's on-device recognizer is used when it exists
 * ([SpeechRecognizer.createOnDeviceSpeechRecognizer]); otherwise the default recognizer is asked
 * to prefer offline ([RecognizerIntent.EXTRA_PREFER_OFFLINE]). If the on-device recognizer has
 * no language pack for the current language, the session falls back to the default recognizer
 * once, without the user noticing.
 *
 * Speech out: an installed voice that doesn't need the network is picked for the language
 * (highest quality first). If the phone's default engine has none, other installed engines
 * (e.g. Google, Samsung) are tried.
 *
 * The UI only talks to this class. Status lines are logged under the tag "SaathiVoice".
 */
class Voice(private val context: Context) {

    /** Why a listening session ended without text. Added after [Listen.Failed.reason]; defaults to [Other]. */
    enum class FailCode {
        NoMatch, SpeechTimeout, NoPermission, Network, LanguageNotSupported, LanguageUnavailable,
        Busy, Audio, Server, Client, Unavailable, Other,
    }

    sealed interface Listen {
        data object Idle : Listen
        data class Hearing(val partial: String, val level: Float) : Listen
        data class Heard(val text: String) : Listen

        /** [reason] is the spoken/shown sentence (unchanged strings); [code] says why, for logic. */
        data class Failed(val reason: String, val code: FailCode = FailCode.Other) : Listen
    }

    /** Which recognizer [startListening] uses. */
    enum class SpeechMode {
        /** Android 12+ on-device recognizer: audio never leaves the phone. */
        OnDevice,

        /** The phone's default recognizer, asked to prefer offline. May use the network if it has no offline model. */
        PreferOffline,

        /** No speech recognizer on this phone. */
        Unavailable,
    }

    /** On-device language pack for the current language (Android 13+ can check; older phones report Unknown). */
    enum class OfflinePack { Unknown, Installed, Downloading, Downloadable, NotSupported }

    /** Result of [downloadOfflineSpeech]. */
    enum class DownloadRequest { Requested, AlreadyInstalled, NotSupported, NeedsAndroid13 }

    private val _listen = MutableStateFlow<Listen>(Listen.Idle)
    val listen: StateFlow<Listen> = _listen.asStateFlow()

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val _speechMode = MutableStateFlow(SpeechMode.Unavailable)
    /** Current speech-in mode (updates when the language changes or a pack turns out to be missing). */
    val speechMode: StateFlow<SpeechMode> = _speechMode.asStateFlow()

    private val _offlinePack = MutableStateFlow(OfflinePack.Unknown)
    /** On-device language pack state for [locale]. */
    val offlinePack: StateFlow<OfflinePack> = _offlinePack.asStateFlow()

    private val _offlineSpeechAvailable = MutableStateFlow(false)
    /** True when speech in is known to work with no connection for the current language. */
    val offlineSpeechAvailable: StateFlow<Boolean> = _offlineSpeechAvailable.asStateFlow()

    private val _offlineVoiceAvailable = MutableStateFlow(false)
    /** True when speech out has an installed, no-network voice for the current language. */
    val offlineVoiceAvailable: StateFlow<Boolean> = _offlineVoiceAvailable.asStateFlow()

    private val _voiceDescription = MutableStateFlow("")
    /** Engine package + voice name in use, for Settings ("com.samsung.SMT · hi-IN-SMTf00"). */
    val voiceDescription: StateFlow<String> = _voiceDescription.asStateFlow()

    var locale: Locale = Locale.getDefault()
        set(value) {
            if (field == value) return
            field = value
            appliedLocale = null
            onDeviceRefused = false
            main.post {
                ensureOfflineVoice()
                refreshOfflineStatus()
            }
        }

    /** 0.7 .. 1.5, Settings > Voice > Speaking rate. */
    var rate: Float = 1.0f

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var recognizerIsOnDevice = false
    private var supportRecognizer: SpeechRecognizer? = null

    /** The on-device recognizer said it can't do this language; use the default one until the language changes. */
    private var onDeviceRefused = false

    /** BCP-47 tag the on-device recognizer actually has (e.g. "en-US" when the app is set to en-IN). */
    private var onDeviceTag: String? = null

    private var tts: TextToSpeech? = null
    private var ttsEngine: String? = null
    private var ttsReady = false
    private var appliedLocale: Locale? = null
    private var probing = false
    private val pending = ArrayDeque<String>()

    val recognitionAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /** Android 12+ and the phone has an on-device recognition service. */
    val onDeviceRecognitionAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    fun init() {
        refreshOfflineStatus()
        if (tts != null) return
        startTts(null)
    }

    // ------------------------------------------------------------------ speech out

    private fun startTts(engine: String?) {
        ttsReady = false
        appliedLocale = null
        ttsEngine = engine
        val created = arrayOfNulls<TextToSpeech>(1)
        val listener = TextToSpeech.OnInitListener { status ->
            val mine = created[0]
            if (mine == null) {
                // Failed synchronously inside the constructor: fall back to the default engine.
                if (engine != null) main.post { startTts(null) }
                return@OnInitListener
            }
            if (tts !== mine) return@OnInitListener
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                if (ttsEngine == null) ttsEngine = runCatching { tts?.defaultEngine }.getOrNull()
                ensureOfflineVoice()
                while (pending.isNotEmpty()) speak(pending.removeFirst())
            } else if (engine != null) {
                main.post { startTts(null) } // chosen engine failed to start: go back to the phone's default
            }
        }
        val t = if (engine == null) TextToSpeech(context, listener) else TextToSpeech(context, listener, engine)
        created[0] = t
        tts = t
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { _speaking.value = true }
            override fun onDone(id: String?) { _speaking.value = false }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { _speaking.value = false }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { _speaking.value = false }
        })
    }

    /** Best installed voice for [loc] that works with no connection, or null. */
    private fun offlineVoiceFor(t: TextToSpeech, loc: Locale): TtsVoice? {
        val voices = runCatching { t.voices }.getOrNull().orEmpty()
        return voices
            .filter { it.locale.language == loc.language }
            .filter { !it.isNetworkConnectionRequired }
            .filter { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in (it.features ?: emptySet()) }
            .sortedWith(
                compareByDescending<TtsVoice> { it.locale.country.equals(loc.country, ignoreCase = true) }
                    .thenByDescending { it.quality }
                    .thenBy { it.latency },
            )
            .firstOrNull()
    }

    /** Pick an offline voice on the current engine; if there is none, look at the other installed engines. */
    private fun ensureOfflineVoice() {
        val t = tts ?: return
        if (!ttsReady) return
        val loc = locale
        val v = offlineVoiceFor(t, loc)
        if (v != null) {
            runCatching { t.voice = v }
            appliedLocale = loc
            _offlineVoiceAvailable.value = true
            _voiceDescription.value = "${ttsEngine.orEmpty()} · ${v.name}"
            Log.i(TAG, "speech out: offline voice ${v.name} (quality ${v.quality}) on ${ttsEngine} for ${loc.toLanguageTag()}")
            return
        }
        // No offline voice here: plain language (online voice if connected) while other engines are checked.
        runCatching { t.language = loc }
        appliedLocale = loc
        _offlineVoiceAvailable.value = false
        _voiceDescription.value = "${ttsEngine.orEmpty()} · ${loc.toLanguageTag()} (may need a connection)"
        Log.i(TAG, "speech out: no offline ${loc.toLanguageTag()} voice on ${ttsEngine}; checking other engines")
        probeOtherEngines(loc)
    }

    /** Try each other installed TTS engine; switch to the first with an offline voice for [loc]. */
    private fun probeOtherEngines(loc: Locale) {
        if (probing) return
        val current = ttsEngine
        val others = runCatching { tts?.engines }.getOrNull().orEmpty().map { it.name }.filter { it != current }
        if (others.isEmpty()) return
        probing = true
        fun next(i: Int) {
            if (i >= others.size || locale != loc) { probing = false; return }
            val name = others[i]
            val holder = arrayOfNulls<TextToSpeech>(1)
            holder[0] = TextToSpeech(context, { status ->
                val probe = holder[0]
                val ok = status == TextToSpeech.SUCCESS && probe != null && offlineVoiceFor(probe, loc) != null
                runCatching { probe?.shutdown() }
                if (ok && locale == loc) {
                    probing = false
                    Log.i(TAG, "speech out: switching to $name, it has an offline ${loc.toLanguageTag()} voice")
                    runCatching { tts?.shutdown() }
                    startTts(name)
                } else {
                    main.post { next(i + 1) }
                }
            }, name)
        }
        next(0)
    }

    /** Speak now, replacing anything already being said. */
    fun speak(text: String) {
        val t = tts
        if (t == null || !ttsReady) { pending.addLast(text); if (t == null) init(); return }
        if (appliedLocale != locale) ensureOfflineVoice()
        t.setSpeechRate(rate)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    fun stopSpeaking() { tts?.stop(); _speaking.value = false }

    /** Opens the phone's text-to-speech settings (choose engine, download voices). For the Settings screen. */
    fun ttsSettingsIntent(): Intent = Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Asks the current TTS engine to install voice data (opens the engine's own download screen). */
    fun installVoiceDataIntent(): Intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
        .apply { ttsEngine?.let { setPackage(it) } }
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ------------------------------------------------------------------ speech in

    private fun recognizerIntent(onDevice: Boolean): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, (if (onDevice) onDeviceTag else null) ?: locale.toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    private fun useOnDevice(): Boolean =
        onDeviceRecognitionAvailable && !onDeviceRefused &&
            _offlinePack.value != OfflinePack.NotSupported && _offlinePack.value != OfflinePack.Downloadable &&
            _offlinePack.value != OfflinePack.Downloading

    private fun publishMode() {
        _speechMode.value = when {
            useOnDevice() -> SpeechMode.OnDevice
            recognitionAvailable -> SpeechMode.PreferOffline
            else -> SpeechMode.Unavailable
        }
        _offlineSpeechAvailable.value = _speechMode.value == SpeechMode.OnDevice && _offlinePack.value == OfflinePack.Installed
    }

    /** Must be called on the main thread. Needs RECORD_AUDIO granted. */
    fun startListening() {
        stopSpeaking()
        val onDevice = useOnDevice()
        if (!onDevice && !recognitionAvailable) {
            _listen.value = Listen.Failed("Speech recognition isn't available on this phone.", FailCode.Unavailable)
            return
        }
        startSession(onDevice)
    }

    private fun startSession(onDevice: Boolean) {
        if (recognizer != null && recognizerIsOnDevice != onDevice) { recognizer?.destroy(); recognizer = null }
        val r = recognizer ?: (
            if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
            ).also { recognizer = it; recognizerIsOnDevice = onDevice }
        publishMode()
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
                val code = codeFor(error)
                // On-device recognizer has no model for this language: quietly retry with the default one.
                if (onDevice && (code == FailCode.LanguageNotSupported || code == FailCode.LanguageUnavailable) && recognitionAvailable) {
                    Log.i(TAG, "speech in: on-device recognizer refused ${locale.toLanguageTag()} ($code); using default recognizer, prefer offline")
                    onDeviceRefused = true
                    if (_offlinePack.value == OfflinePack.Unknown || _offlinePack.value == OfflinePack.Installed) {
                        _offlinePack.value = if (code == FailCode.LanguageNotSupported) OfflinePack.NotSupported else OfflinePack.Downloadable
                    }
                    main.post { startSession(onDevice = false) }
                    return
                }
                _listen.value = Listen.Failed(messageFor(code), code)
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (onDevice && _offlinePack.value == OfflinePack.Unknown) {
                    _offlinePack.value = OfflinePack.Installed // it just worked on-device
                    publishMode()
                }
                _listen.value = if (text.isBlank()) Listen.Failed("I didn't catch that.", FailCode.NoMatch) else Listen.Heard(text)
            }
            override fun onPartialResults(partial: Bundle?) {
                val text = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                val cur = _listen.value
                _listen.value = Listen.Hearing(text, (cur as? Listen.Hearing)?.level ?: 0f)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        _listen.value = Listen.Hearing("", 0f)
        r.startListening(recognizerIntent(onDevice))
    }

    private fun codeFor(error: Int): FailCode = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> FailCode.NoMatch
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> FailCode.SpeechTimeout
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> FailCode.NoPermission
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> FailCode.Network
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> FailCode.Busy
        SpeechRecognizer.ERROR_AUDIO -> FailCode.Audio
        SpeechRecognizer.ERROR_SERVER -> FailCode.Server
        SpeechRecognizer.ERROR_CLIENT -> FailCode.Client
        ERROR_SERVER_DISCONNECTED -> FailCode.Server
        ERROR_LANGUAGE_NOT_SUPPORTED -> FailCode.LanguageNotSupported
        ERROR_LANGUAGE_UNAVAILABLE -> FailCode.LanguageUnavailable
        else -> FailCode.Other
    }

    /** The original sentences, unchanged (ConfirmScreen matches "I didn't catch that."). */
    private fun messageFor(code: FailCode): String = when (code) {
        FailCode.NoMatch, FailCode.SpeechTimeout -> "I didn't catch that."
        FailCode.NoPermission -> "Saathi needs the microphone permission."
        FailCode.Network -> "Speech needs a connection right now."
        else -> "Listening stopped."
    }

    fun stopListening() { recognizer?.stopListening() }

    fun cancelListening() { recognizer?.cancel(); _listen.value = Listen.Idle }

    fun reset() { _listen.value = Listen.Idle }

    // ------------------------------------------------------------------ offline language pack

    /**
     * Re-check whether the on-device recognizer has the current language. Safe to call any time
     * (e.g. when Settings resumes). Android 13+ asks the recognizer; older versions stay Unknown
     * until a session succeeds on-device.
     */
    fun refreshOfflineStatus() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { refreshOfflineStatus() }; return }
        onDeviceTag = null
        if (!onDeviceRecognitionAvailable) {
            _offlinePack.value = OfflinePack.NotSupported
            publishMode()
            Log.i(TAG, "speech in: no on-device recognizer; mode ${_speechMode.value}")
            return
        }
        _offlinePack.value = OfflinePack.Unknown
        publishMode()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) checkSupport33()
        else Log.i(TAG, "speech in: on-device recognizer present (Android 12); pack status unknown until first use")
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun supportRecognizer(): SpeechRecognizer =
        supportRecognizer ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { supportRecognizer = it }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun checkSupport33() {
        val loc = locale
        runCatching {
            supportRecognizer().checkRecognitionSupport(
                recognizerIntent(onDevice = false),
                context.mainExecutor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(s: RecognitionSupport) {
                        if (loc != locale) return
                        val installed = match(s.installedOnDeviceLanguages, loc)
                        val pending = match(s.pendingOnDeviceLanguages, loc)
                        val supported = match(s.supportedOnDeviceLanguages, loc)
                        val nothingReported = s.installedOnDeviceLanguages.isEmpty() && s.pendingOnDeviceLanguages.isEmpty() &&
                            s.supportedOnDeviceLanguages.isEmpty()
                        onDeviceTag = installed ?: pending ?: supported
                        _offlinePack.value = when {
                            installed != null -> OfflinePack.Installed
                            pending != null -> OfflinePack.Downloading
                            supported != null -> OfflinePack.Downloadable
                            nothingReported -> OfflinePack.Unknown
                            else -> OfflinePack.NotSupported
                        }
                        publishMode()
                        Log.i(
                            TAG,
                            "speech in: ${loc.toLanguageTag()} pack=${_offlinePack.value} mode=${_speechMode.value} " +
                                "installed=${s.installedOnDeviceLanguages} pending=${s.pendingOnDeviceLanguages} " +
                                "supported=${s.supportedOnDeviceLanguages.size} langs",
                        )
                    }

                    override fun onError(error: Int) {
                        Log.i(TAG, "speech in: checkRecognitionSupport error $error; pack unknown")
                        if (loc == locale) { _offlinePack.value = OfflinePack.Unknown; publishMode() }
                    }
                },
            )
        }.onFailure { Log.w(TAG, "speech in: checkRecognitionSupport failed", it) }
    }

    /**
     * Ask the phone to download the on-device speech pack for the current language. Only call this
     * when the user asked for it (Settings > Voice). Android 13+ only; the system may show its own
     * prompt or notification. Watch [offlinePack] / call [refreshOfflineStatus] afterwards.
     */
    fun downloadOfflineSpeech(): DownloadRequest {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return DownloadRequest.NeedsAndroid13
        if (!onDeviceRecognitionAvailable) return DownloadRequest.NotSupported
        when (_offlinePack.value) {
            OfflinePack.Installed -> return DownloadRequest.AlreadyInstalled
            OfflinePack.NotSupported -> return DownloadRequest.NotSupported
            else -> Unit
        }
        if (Looper.myLooper() != Looper.getMainLooper()) main.post { triggerDownload33() } else triggerDownload33()
        return DownloadRequest.Requested
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun triggerDownload33() {
        val intent = recognizerIntent(onDevice = true)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                supportRecognizer().triggerModelDownload(
                    intent,
                    context.mainExecutor,
                    object : ModelDownloadListener {
                        override fun onProgress(completedPercent: Int) { _offlinePack.value = OfflinePack.Downloading; publishMode() }
                        override fun onSuccess() { onDeviceRefused = false; refreshOfflineStatus() }
                        override fun onScheduled() { _offlinePack.value = OfflinePack.Downloading; publishMode() }
                        override fun onError(error: Int) { Log.i(TAG, "speech in: model download error $error"); refreshOfflineStatus() }
                    },
                )
            } else {
                supportRecognizer().triggerModelDownload(intent)
                _offlinePack.value = OfflinePack.Downloading
                publishMode()
            }
            Log.i(TAG, "speech in: model download requested for ${locale.toLanguageTag()}")
        }.onFailure { Log.w(TAG, "speech in: triggerModelDownload failed", it) }
    }

    fun shutdown() {
        recognizer?.destroy(); recognizer = null
        supportRecognizer?.destroy(); supportRecognizer = null
        tts?.shutdown(); tts = null; ttsReady = false
    }

    private companion object {
        const val TAG = "SaathiVoice"

        // SpeechRecognizer constants added in API 31; inlined so this compiles the same on all levels.
        const val ERROR_SERVER_DISCONNECTED = 11
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13

        /** Best tag in [tags] for [loc]: exact match, else same language ("en-US" for "en-IN"). */
        fun match(tags: List<String>, loc: Locale): String? {
            val want = loc.toLanguageTag()
            tags.firstOrNull { it.equals(want, ignoreCase = true) }?.let { return it }
            return tags.firstOrNull { Locale.forLanguageTag(it).language == loc.language }
        }
    }
}

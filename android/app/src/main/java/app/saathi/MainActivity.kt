package app.saathi

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.saathi.data.AppState
import app.saathi.feedback.Feedback
import app.saathi.ui.Deps
import app.saathi.ui.SaathiNavHost
import app.saathi.ui.theme.SaathiTheme
import app.saathi.voice.Voice
import java.util.Locale

class MainActivity : ComponentActivity() {
    companion object {
        /** Set by the accessibility service: Volume-down was held in another app -> listen. */
        const val EXTRA_TALK = "app.saathi.TALK"

        @Volatile private var current: java.lang.ref.WeakReference<MainActivity>? = null

        /**
         * Guide mode: Saathi steps back so the app the user was in shows again, exactly as they left
         * it (Saathi's task goes behind it). Main thread. False when Saathi has no activity.
         */
        fun sendToBack(): Boolean {
            val a = current?.get() ?: return false
            if (a.isFinishing) return false
            return a.moveTaskToBack(true)
        }
    }

    private lateinit var deps: Deps

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = java.lang.ref.WeakReference(this)
        enableEdgeToEdge()
        val state = AppState(applicationContext)
        val feedback = Feedback(applicationContext).also { it.soundOn = state.soundOn }
        val voice = Voice(applicationContext).also {
            it.locale = state.languageTag?.let(Locale::forLanguageTag) ?: Locale.getDefault()
            it.rate = state.speechRate
            it.init()
        }
        deps = Deps(state, feedback, voice)
        if (intent?.getBooleanExtra(EXTRA_TALK, false) == true) state.pendingTalk = true
        setContent {
            // Keep services in sync with Settings.
            feedback.soundOn = state.soundOn
            voice.rate = state.speechRate
            voice.locale = state.languageTag?.let(Locale::forLanguageTag) ?: Locale.getDefault()
            SaathiTheme(state.theme) { SaathiNavHost(deps) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_TALK, false)) deps.state.requestTalk()
    }

    // Volume-down (when Saathi's accessibility service is off; when on, the service handles it everywhere): a short press still changes the volume; a long press is push-to-talk.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            event.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            deps.state.talkRequests.tryEmit(Unit)
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event.isTracking && !event.isCanceled) {
            // Short press: behave like a normal volume key.
            val audio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            audio.adjustSuggestedStreamVolume(android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.USE_DEFAULT_STREAM_TYPE, android.media.AudioManager.FLAG_SHOW_UI)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        deps.voice.shutdown()
        deps.feedback.stopVibration()
        super.onDestroy()
    }
}

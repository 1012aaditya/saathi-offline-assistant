package app.saathi

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.saathi.ui.Deps
import app.saathi.ui.SharedDeps
import app.saathi.ui.SaathiNavHost
import app.saathi.ui.theme.SaathiTheme
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
        // [brain hook] Find the on-device AI model file (if any) at startup; it loads only when guide mode starts.
        app.saathi.brain.LlmEngine.attach(applicationContext)
        // Shared with Floating Saathi: one Voice, so one speak / listen interlock for the whole app.
        deps = SharedDeps.acquire(this)
        val state = deps.state
        val feedback = deps.feedback
        val voice = deps.voice
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
        SharedDeps.release() // speech engines shut down when the overlay doesn't use them either
        super.onDestroy()
    }
}

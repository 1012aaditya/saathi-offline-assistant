package app.saathi.ui

import android.content.Context
import android.os.Looper
import app.saathi.data.AppState
import app.saathi.feedback.Feedback
import app.saathi.voice.Voice
import java.util.Locale

/**
 * ONE set of services for the whole process: Saathi's own screens and Floating Saathi (the
 * overlay the accessibility service draws) share the same [Voice], so there is exactly one
 * speak / listen interlock ([app.saathi.agent.VoiceInterlock]). Two Voices would each think the
 * other was silent, and the microphone could open while Saathi is talking (13 Oct incidents).
 * The same [AppState] means a Settings switch is seen by the overlay at once.
 *
 * Reference-counted: the activity and the overlay each [acquire] it and [release] it; the speech
 * engines are shut down when nobody uses them. Main thread only.
 */
object SharedDeps {
    private var deps: Deps? = null
    private var users = 0

    fun acquire(context: Context): Deps {
        check(Looper.myLooper() == Looper.getMainLooper()) { "SharedDeps on the main thread only" }
        users++
        deps?.let { return it }
        val app = context.applicationContext
        val state = AppState(app)
        val feedback = Feedback(app).also { it.soundOn = state.soundOn }
        val voice = Voice(app).also {
            it.locale = state.languageTag?.let(Locale::forLanguageTag) ?: Locale.getDefault()
            it.rate = state.speechRate
            it.init()
        }
        return Deps(state, feedback, voice).also { deps = it }
    }

    fun release() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "SharedDeps on the main thread only" }
        if (users > 0) users--
        if (users > 0) return
        deps?.let {
            it.voice.shutdown()
            it.feedback.stopVibration()
        }
        deps = null
    }

    /** The services if someone holds them (never creates them). */
    fun peek(): Deps? = deps
}

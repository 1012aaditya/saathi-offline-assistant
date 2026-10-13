package app.saathi.ui

import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Typed commands for on-device testing. Only the debug-build DebugCommandReceiver (protected by
 * android.permission.DUMP, so only `adb shell` can send) ever posts here.
 */
object DebugCommands {
    @Volatile private var pending: String? = null
    val flow = MutableSharedFlow<String>(extraBufferCapacity = 4)

    fun post(text: String) {
        pending = text
        flow.tryEmit(text)
    }

    /** The command waiting for a cold-started activity, cleared once read. */
    fun take(): String? = pending.also { pending = null }
}

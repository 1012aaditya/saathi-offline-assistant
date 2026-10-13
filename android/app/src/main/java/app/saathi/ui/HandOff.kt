package app.saathi.ui

import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Something said on the floating panel that needs Saathi's own screens (opening a new app needs
 * its approval sheet; Settings, Braille …): the panel posts the words here and brings Saathi
 * forward, and Saathi handles them exactly like speech (Requests.handle).
 */
object HandOff {
    @Volatile private var pending: String? = null
    val flow = MutableSharedFlow<String>(extraBufferCapacity = 4)

    fun post(text: String) {
        pending = text
        flow.tryEmit(text)
    }

    /** The words waiting for a cold-started activity, cleared once read. */
    fun take(): String? = pending.also { pending = null }
}

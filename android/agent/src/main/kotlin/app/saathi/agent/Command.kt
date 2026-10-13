package app.saathi.agent

/** Language for Saathi's own words (narration, answers). On-screen strings are always quoted as they are. */
enum class Lang { EN, HI }

/** Saathi's own screens a spoken phrase can open ("Go home", "Walk mode", ...). */
enum class Destination { HOME, WALK, ACTIVITY, SETTINGS, SAFETY }

/**
 * What the user asked for, decided by [CommandParser] with keywords only (no model, no network).
 * `utterance` is the recognised text as heard.
 */
sealed interface Command {
    val utterance: String

    /** Commands that operate the phone through the accessibility service. */
    val isTask: Boolean get() = false

    /** "Read Mom's last message", "Mummy ka last message padho". `contact` null = ask whose. */
    data class ReadLast(val contact: String?, override val utterance: String) : Command {
        override val isTask: Boolean get() = true
    }

    /** "Who messaged me?", "Kiske message aaye hain?", "Read my messages". */
    data class UnreadSummary(override val utterance: String) : Command {
        override val isTask: Boolean get() = true
    }

    /** "What's on screen?", "Screen pe kya hai?" — read-only, never acts. */
    data class ScreenSummary(override val utterance: String) : Command {
        override val isTask: Boolean get() = true
    }

    /** "Open WhatsApp", "WhatsApp kholo". `alias` is a key of safety Apps.APP_ALIASES. */
    data class OpenApp(val alias: String, val packageName: String, override val utterance: String) : Command {
        override val isTask: Boolean get() = true
    }

    /** "Reply to Mom I'll be home by 7", "Mom ko bolo main aa raha hoon". Missing parts are asked for. */
    data class Reply(val contact: String?, val text: String?, override val utterance: String) : Command {
        override val isTask: Boolean get() = true
    }

    data class Stop(override val utterance: String) : Command

    data class Help(override val utterance: String) : Command

    data class Navigate(val target: Destination, override val utterance: String) : Command

    /** Anything else: answered honestly ("I can't do that offline yet"), never guessed at. */
    data class Unsupported(override val utterance: String) : Command
}

/**
 * Follow-ups across commands (port of saathi/session.py): the last contact talked about, and a
 * reply waiting for its text ("Reply" on the Result screen, then the user says the message).
 */
class Session {
    var lastContact: String? = null
    var pendingReplyTo: String? = null

    fun remember(command: Command) {
        when (command) {
            is Command.ReadLast -> command.contact?.let { lastContact = it }
            is Command.Reply -> command.contact?.let { lastContact = it }
            else -> Unit
        }
    }

    fun clear() {
        lastContact = null
        pendingReplyTo = null
    }
}

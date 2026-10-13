package app.saathi.agent

import app.saathi.data.AppLevel
import app.saathi.safety.Apps
import app.saathi.ui.AppNav
import app.saathi.ui.Deps
import app.saathi.ui.Route
import app.saathi.ui.routeForPhrase

/**
 * What happens to something the user said: parse it offline ([CommandParser]) and go to the
 * right screen. Tasks go to Working; help / unsupported / missing parts go to Result.
 */
object Requests {
    fun lang(deps: Deps): Lang = if (deps.voice.locale.language == "hi") Lang.HI else Lang.EN

    fun handle(text: String, deps: Deps, nav: AppNav) {
        // Guide mode: everything said (or a debug `--es text` command) goes to the conversation.
        // It still can't answer a Confirm sheet: the guide grammar has no yes.
        // A floating session (driven from the overlay) is not this: a new request here ends it.
        if (TaskRunner.isAppGuiding) {
            TaskRunner.guideCommand(Heard(text), "request")
            if (nav.current() != Route.Guide) nav.replace(Route.Guide)
            return
        }
        deps.voice.stopSpeaking()
        val t = Texts(lang(deps))
        // Saved names (trusted contacts, and the phone's contacts if the user allowed them) keep
        // multi-word names whole: "message Rahul Sharma I'm on my way".
        val people = SaathiContext.app?.let { PhoneBook.names(it) }.orEmpty()
        val command = CommandParser.parse(text, TaskRunner.session, deps.state.trustedContacts.toList() + people)
        deps.state.lastRequest = text
        when (command) {
            is Command.Stop -> nav.home()
            is Command.Navigate -> when (command.target) {
                Destination.HOME -> nav.home()
                Destination.WALK -> nav.replace(Route.Walk)
                Destination.ACTIVITY -> nav.replace(Route.Activity)
                Destination.SETTINGS -> nav.replace(Route.Settings)
                Destination.SAFETY -> nav.replace(Route.Safety)
            }
            is Command.Help -> {
                TaskRunner.showResult(command, Outcome.Answered(t.help, t.helpTitle))
                nav.replace(Route.Result)
            }
            is Command.Photo -> {
                // Camera / last photo, described on the phone. The Photo screen picks up the mode.
                app.saathi.photo.PhotoRequest.open(command.source)
                // Already on the Photo screen: it switches mode in place. Re-creating it here would
                // race the old screen for the request and lose it (seen on the tablet, 13 Oct).
                if (nav.current() != Route.Photo) nav.replace(Route.Photo)
            }
            // Any other installed app: resolved on the phone, opened only with the user's approval.
            is Command.LaunchApp -> LaunchRequests.handle(command, deps, nav)
            is Command.Unsupported -> {
                // Saathi's own screens not known to the parser (e.g. Braille) still open by name.
                routeForPhrase(text)?.let { route ->
                    if (route == Route.Home) nav.home() else nav.replace(route)
                    return
                }
                TaskRunner.showResult(command, Outcome.Failed(t.unsupported), deps.state)
                nav.replace(Route.Result)
            }
            else -> startTask(command, deps, nav, t)
        }
    }

    internal fun startTask(command: Command, deps: Deps, nav: AppNav, t: Texts) {
        // Missing a part: ask, don't guess. A reply missing its text waits for the next utterance.
        Flows.missing(command, t)?.let { question ->
            if (command is Command.Reply && command.contact != null) TaskRunner.session.pendingReplyTo = command.contact
            TaskRunner.showResult(command, Outcome.Asked(question))
            nav.replace(Route.Result)
            return
        }
        // Settings > Apps levels: Off = never operate it; Read only = never send there.
        levelRefusal(command, deps, t)?.let { why ->
            TaskRunner.showResult(command, Outcome.Failed(why), deps.state)
            nav.replace(Route.Result)
            return
        }
        TaskRunner.pending = command
        nav.replace(Route.Working)
    }

    private fun levelRefusal(command: Command, deps: Deps, t: Texts): String? {
        fun level(app: String) = deps.state.appLevels.firstOrNull { it.first == app }?.second
        val whatsapp = command is Command.ReadLast || command is Command.Reply || command is Command.UnreadSummary ||
            (command is Command.OpenApp && command.packageName == Apps.WHATSAPP)
        if (whatsapp && level("WhatsApp") == AppLevel.Off) return t.appOff("WhatsApp")
        if (command is Command.Reply && level("WhatsApp") == AppLevel.ReadOnly) return t.readOnlyApp("WhatsApp")
        if (command is Command.OpenApp) {
            if (command.packageName in Apps.PAYMENT_APPS && level("Payments") == AppLevel.Off) return t.appOff(TaskRunner.appName(command.packageName))
            if (command.packageName in Apps.ORDER_APPS && level("Grocery app") == AppLevel.Off) return t.appOff(TaskRunner.appName(command.packageName))
        }
        return null
    }
}

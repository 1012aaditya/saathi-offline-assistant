package app.saathi.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.saathi.data.AppLevel
import app.saathi.data.AppState
import app.saathi.ui.AppNav
import app.saathi.ui.Deps
import app.saathi.ui.Route

/** The application context, for request handling that has no screen of its own (set by the nav host). */
object SaathiContext {
    @Volatile
    var app: Context? = null
        private set

    fun attach(context: Context) {
        app = context.applicationContext
    }
}

/**
 * The ONE path that opens apps outside the safety gate's list (WhatsApp, Google Pay, Blinkit,
 * Settings keep [Command.OpenApp] through the gate). It only ever starts the app's own launcher
 * activity: no taps, no typing, nothing read. [LaunchPolicy] is checked when asked and again right
 * before launching: Saathi itself, Not allowed apps, payment apps while Payments is Off, and
 * Blinkit while Grocery is Off are never opened. Logs never name the app.
 */
object AppLauncher {
    private const val TAG = "SaathiLaunch"

    /** The phone's launcher apps (MAIN/LAUNCHER query, visible through the manifest's <queries>; no QUERY_ALL_PACKAGES). */
    fun installed(context: Context): List<LaunchableApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found = runCatching { query(pm, intent) }.getOrDefault(emptyList())
        return found.mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            LaunchableApp(ri.loadLabel(pm).toString().trim(), pkg)
        }.distinctBy { it.packageName }
    }

    @Suppress("DEPRECATION")
    private fun query(pm: PackageManager, intent: Intent) =
        if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0)) else pm.queryIntentActivities(intent, 0)

    private fun level(state: AppState, app: String): AppLevel? = state.appLevels.firstOrNull { it.first == app }?.second

    fun decide(context: Context, app: LaunchableApp, state: AppState): LaunchDecision = LaunchPolicy.decide(
        app = app,
        approval = AppApprovals.get(context, app.packageName),
        paymentsOff = level(state, "Payments") == AppLevel.Off,
        groceryOff = level(state, "Grocery app") == AppLevel.Off,
        ownPackage = context.packageName,
    )

    /** The launcher intent of exactly [packageName], or null. */
    private fun launcherIntent(context: Context, packageName: String): Intent? {
        val i = context.packageManager.getLaunchIntentForPackage(packageName) ?: return null
        if (i.action != Intent.ACTION_MAIN || i.component?.packageName != packageName) return null
        return i
    }

    fun canLaunch(context: Context, app: LaunchableApp): Boolean = launcherIntent(context, app.packageName) != null

    /** Start [app]'s launcher activity, only if the policy says Launch right now. */
    fun launch(context: Context, app: LaunchableApp, state: AppState): Boolean {
        val d = decide(context, app, state)
        if (d != LaunchDecision.Launch) {
            Log.i(TAG, "launch refused: ${d::class.simpleName}")
            return false
        }
        if (installed(context).none { it.packageName == app.packageName }) return false
        val intent = launcherIntent(context, app.packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { context.startActivity(intent) }.isSuccess
        Log.i(TAG, "launch: ${if (ok) "ok" else "failed"}")
        return ok
    }
}

/**
 * "Open camera" / "Instagram kholo": resolve the app, then open it, ask first (Approval screen),
 * hand a safety-list app to the normal task path, or say honestly why not.
 */
object LaunchRequests {
    private const val TAG = "SaathiLaunch"

    /** Lets the Result screen start speaking before the other app covers Saathi. */
    private const val LAUNCH_DELAY_MS = 700L

    /** The app waiting for the user's first-time answer on the Approval screen. */
    data class Pending(val command: Command.LaunchApp, val app: LaunchableApp)

    @Volatile
    var pending: Pending? = null
        private set

    private val main = Handler(Looper.getMainLooper())

    fun handle(command: Command.LaunchApp, deps: Deps, nav: AppNav) {
        val t = Texts(Requests.lang(deps))
        val ctx = SaathiContext.app ?: return show(command, Outcome.Failed(t.appCouldNotOpen(command.name)), deps, nav)
        when (val r = AppResolver.resolve(command.name, AppLauncher.installed(ctx), ctx.packageName)) {
            AppResolver.Result.None -> show(command, Outcome.Failed(t.appNotFound(command.name)), deps, nav)
            is AppResolver.Result.Many -> show(command, Outcome.Asked(t.appWhich(r.apps.map { it.label })), deps, nav)
            is AppResolver.Result.One -> decide(command, r.app, deps, nav, t, ctx)
        }
    }

    private fun decide(command: Command.LaunchApp, app: LaunchableApp, deps: Deps, nav: AppNav, t: Texts, ctx: Context) {
        when (val d = AppLauncher.decide(ctx, app, deps.state)) {
            LaunchDecision.Launch -> openNow(command, app, deps, nav, t, ctx)
            LaunchDecision.AskFirst -> {
                Log.i(TAG, "first time: asking")
                pending = Pending(command, app)
                nav.replace(Route.AppApproval)
            }
            // WhatsApp / Google Pay / Blinkit / Settings: the gated OpenApp path and its Settings level.
            is LaunchDecision.Managed -> Requests.startTask(Command.OpenApp(d.alias, app.packageName, command.utterance), deps, nav, t)
            is LaunchDecision.Refuse -> show(command, Outcome.Failed(t.refused(d.reason, app.label)), deps, nav)
        }
    }

    /** The user's clear yes (tap Open, or a voice yes that passed the echo checks). */
    fun approve(deps: Deps, nav: AppNav, source: String) {
        val p = pending ?: return nav.home()
        pending = null
        val t = Texts(Requests.lang(deps))
        val ctx = SaathiContext.app ?: return show(p.command, Outcome.Failed(t.appCouldNotOpen(p.app.label)), deps, nav)
        Log.i(TAG, "approved from $source")
        AppApprovals.set(ctx, p.app.packageName, p.app.label, Approval.ALLOWED)
        openNow(p.command, p.app, deps, nav, t, ctx)
    }

    /** No, unclear twice, Back, or the screen was left: nothing opened, nothing remembered. */
    fun decline(deps: Deps, nav: AppNav?, unsure: Boolean, source: String) {
        val p = pending ?: return
        pending = null
        Log.i(TAG, "declined from $source unsure=$unsure")
        val t = Texts(Requests.lang(deps))
        val outcome = Outcome.Cancelled(if (unsure) t.appNotSure(p.app.label) else t.appNotOpened(p.app.label))
        if (nav != null) show(p.command, outcome, deps, nav) else TaskRunner.showResult(p.command, outcome, deps.state)
    }

    private fun openNow(command: Command.LaunchApp, app: LaunchableApp, deps: Deps, nav: AppNav, t: Texts, ctx: Context) {
        val d = AppLauncher.decide(ctx, app, deps.state)
        if (d is LaunchDecision.Refuse) return show(command, Outcome.Failed(t.refused(d.reason, app.label)), deps, nav)
        if (d != LaunchDecision.Launch || !AppLauncher.canLaunch(ctx, app)) {
            return show(command, Outcome.Failed(t.appCouldNotOpen(app.label)), deps, nav)
        }
        // Result first (it says what Saathi can and can't do there), then the app comes to the front.
        show(command, Outcome.Answered(t.appOpened(app.label), t.appOpenTitle(app.label)), deps, nav)
        main.postDelayed({
            if (!AppLauncher.launch(ctx, app, deps.state)) deps.voice.speak(t.appCouldNotOpen(app.label))
        }, LAUNCH_DELAY_MS)
    }

    private fun show(command: Command, outcome: Outcome, deps: Deps, nav: AppNav) {
        TaskRunner.showResult(command, outcome, deps.state)
        nav.replace(Route.Result)
    }
}

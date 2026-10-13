package app.saathi.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.saathi.data.AppState
import app.saathi.feedback.Feedback
import app.saathi.feedback.reducedMotion
import app.saathi.ui.screens.ActivityScreen
import app.saathi.ui.screens.ConfirmScreen
import app.saathi.ui.screens.HandoverScreen
import app.saathi.ui.screens.HomeScreen
import app.saathi.ui.screens.ListeningScreen
import app.saathi.ui.screens.ResultScreen
import app.saathi.ui.screens.SafetyScreen
import app.saathi.ui.screens.SettingsScreen
import app.saathi.ui.screens.SetupScreen
import app.saathi.ui.screens.WalkScreen
import app.saathi.ui.screens.WelcomeScreen
import app.saathi.ui.screens.WorkingScreen
import app.saathi.voice.Voice

/** The 12 Clarity screens (DESIGN.md). */
enum class Route { Welcome, Setup, Home, Listening, Working, Confirm, Result, Handover, Walk, Activity, Settings, Safety }

/** Shared services, available to every screen via [LocalDeps]. */
class Deps(val state: AppState, val feedback: Feedback, val voice: Voice)

val LocalDeps = staticCompositionLocalOf<Deps> { error("Deps not provided") }

class AppNav(private val controller: NavHostController) {
    fun go(route: Route) = controller.navigate(route.name) { launchSingleTop = true }
    fun back() { if (!controller.popBackStack()) go(Route.Home) }
    /** Clears the stack back to Home (every screen has a labelled way home). */
    fun home() {
        // Home already on the stack: go back to it. Not there (first-run setup): clear everything.
        if (!controller.popBackStack(Route.Home.name, inclusive = false)) restart(Route.Home)
    }
    /** Clear the whole back stack and start at [route] (e.g. Welcome after "Delete all data"). */
    fun restart(route: Route) = controller.navigate(route.name) {
        popUpTo(controller.graph.id) { inclusive = true }
        launchSingleTop = true
    }
    /** Replace the current screen (e.g. Working -> Result) so Back doesn't re-run a task. */
    fun replace(route: Route) {
        val current = controller.currentDestination?.route
        controller.navigate(route.name) {
            if (current != null) popUpTo(current) { inclusive = true }
            launchSingleTop = true
        }
    }
}

/** Global voice phrases that work on every screen ("Go home", "Walk mode", …). English for now. */
fun routeForPhrase(text: String): Route? {
    val t = text.lowercase()
    return when {
        "go home" in t || t.trim() == "home" -> Route.Home
        "walk" in t -> Route.Walk
        "activity" in t || "history" in t -> Route.Activity
        "safety" in t -> Route.Safety
        "setting" in t -> Route.Settings
        else -> null
    }
}

@Composable
fun SaathiNavHost(deps: Deps) {
    val controller = rememberNavController()
    val nav = remember(controller) { AppNav(controller) }
    val context = LocalContext.current
    val still = remember { reducedMotion(context) }
    val ease = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

    // Volume-down long-press anywhere opens Listening.
    LaunchedEffect(Unit) { deps.state.talk.collect { nav.go(Route.Listening) } }

    CompositionLocalProvider(LocalDeps provides deps) {
        NavHost(
            navController = controller,
            startDestination = if (deps.state.setupDone) Route.Home.name else Route.Welcome.name,
            enterTransition = {
                if (still) EnterTransition.None
                else fadeIn(tween(350, easing = ease)) + slideInVertically(tween(350, easing = ease)) { it / 40 }
            },
            exitTransition = { if (still) ExitTransition.None else fadeOut(tween(200)) },
            popEnterTransition = { if (still) EnterTransition.None else fadeIn(tween(300, easing = ease)) },
            popExitTransition = { if (still) ExitTransition.None else fadeOut(tween(200)) },
        ) {
            composable(Route.Welcome.name) { WelcomeScreen(nav) }
            composable(Route.Setup.name) { SetupScreen(nav) }
            composable(Route.Home.name) { HomeScreen(nav) }
            composable(Route.Listening.name) { ListeningScreen(nav) }
            composable(Route.Working.name) { WorkingScreen(nav) }
            composable(Route.Confirm.name) { ConfirmScreen(nav) }
            composable(Route.Result.name) { ResultScreen(nav) }
            composable(Route.Handover.name) { HandoverScreen(nav) }
            composable(Route.Walk.name) { WalkScreen(nav) }
            composable(Route.Activity.name) { ActivityScreen(nav) }
            composable(Route.Settings.name) { SettingsScreen(nav) }
            composable(Route.Safety.name) { SafetyScreen(nav) }
        }
    }
}

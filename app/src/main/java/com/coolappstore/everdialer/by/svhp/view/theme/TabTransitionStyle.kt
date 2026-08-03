package com.coolappstore.everdialer.by.svhp.view.theme

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry
import com.coolappstore.everdialer.by.svhp.controller.util.PreferenceManager
import com.ramcosta.composedestinations.animations.NavHostAnimatedDestinationStyle
import com.ramcosta.composedestinations.generated.destinations.ContactScreenDestination
import com.ramcosta.composedestinations.generated.destinations.FavoritesScreenDestination
import com.ramcosta.composedestinations.generated.destinations.NotesScreenDestination
import com.ramcosta.composedestinations.generated.destinations.RecentScreenDestination
import com.ramcosta.composedestinations.generated.destinations.RecordingsScreenDestination

/** Maps a tab-order key (as stored in [PreferenceManager.KEY_TAB_ORDER]) to its nav route. */
private fun routeForTabKey(key: String): String? = when (key) {
    "favorites"  -> FavoritesScreenDestination.route
    "calls"      -> RecentScreenDestination.route
    "contacts"   -> ContactScreenDestination.route
    "recordings" -> RecordingsScreenDestination.route
    "notes"      -> NotesScreenDestination.route
    else         -> null
}

/** Tab route order, kept in sync with the user's Settings > Appearance > Tab Sections order —
 *  never hardcoded. Falls back to [PreferenceManager.DEFAULT_TAB_ORDER] until the first sync. */
private var tabRouteOrder: List<String> =
    PreferenceManager.parseTabOrder(null).mapNotNull { routeForTabKey(it) }

/** Call whenever settings may have changed (e.g. once per recomposition from the screen that
 *  hosts the main NavHost) so the page-switching slide direction always matches the tab order
 *  the user actually configured, however they've arranged it. */
internal fun syncTabTransitionOrder(prefs: PreferenceManager) {
    tabRouteOrder = prefs.getTabOrder().mapNotNull { routeForTabKey(it) }
}

private val EaseOutQuart = CubicBezierEasing(0.25f, 1f, 0.5f, 1f)
private val EaseOutExpo  = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

internal var isLandscapeMode: Boolean = false

object TabTransitionStyle : NavHostAnimatedDestinationStyle() {

    private fun routeOrder(route: String?): Int {
        if (route == null) return -1
        val base = route.substringBefore("?").substringBefore("/")
        return tabRouteOrder.indexOfFirst { base.contains(it, ignoreCase = true) }
    }

    private fun isTabRoute(route: String?): Boolean = routeOrder(route) >= 0

    override val enterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        val fromTab = isTabRoute(initialState.destination.route)
        val toTab   = isTabRoute(targetState.destination.route)
        val fromIdx = routeOrder(initialState.destination.route)
        val toIdx   = routeOrder(targetState.destination.route)

        when {
            fromTab && toTab && !isLandscapeMode -> {
                val goRight = toIdx > fromIdx
                slideInHorizontally(
                    animationSpec = tween(550, easing = EaseOutQuart),
                    initialOffsetX = { if (goRight) (it * 0.25f).toInt() else -(it * 0.25f).toInt() }
                ) + fadeIn(tween(400, easing = EaseOutQuart))
            }
            !toTab && !isLandscapeMode -> {
                slideInHorizontally(
                    animationSpec = tween(600, easing = EaseOutExpo),
                    initialOffsetX = { (it * 0.35f).toInt() }
                ) + fadeIn(tween(500, easing = EaseOutExpo))
            }
            else -> fadeIn(tween(450, easing = EaseOutQuart))
        }
    }

    override val exitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        val fromTab = isTabRoute(initialState.destination.route)
        val toTab   = isTabRoute(targetState.destination.route)
        val fromIdx = routeOrder(initialState.destination.route)
        val toIdx   = routeOrder(targetState.destination.route)

        when {
            fromTab && toTab && !isLandscapeMode -> {
                val goRight = toIdx > fromIdx
                slideOutHorizontally(
                    animationSpec = tween(550, easing = EaseOutQuart),
                    targetOffsetX = { if (goRight) -(it * 0.25f).toInt() else (it * 0.25f).toInt() }
                ) + fadeOut(tween(350, easing = EaseOutQuart))
            }
            !toTab && !isLandscapeMode -> {
                slideOutHorizontally(
                    animationSpec = tween(600, easing = EaseOutExpo),
                    targetOffsetX = { -(it * 0.12f).toInt() }
                ) + fadeOut(tween(400, easing = EaseOutExpo))
            }
            else -> fadeOut(tween(380, easing = EaseOutQuart))
        }
    }

    override val popEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        val toTab = isTabRoute(targetState.destination.route)
        if (!isLandscapeMode) {
            if (toTab) {
                slideInHorizontally(
                    animationSpec = tween(550, easing = EaseOutQuart),
                    initialOffsetX = { -(it * 0.25f).toInt() }
                ) + fadeIn(tween(400, easing = EaseOutQuart))
            } else {
                slideInHorizontally(
                    animationSpec = tween(600, easing = EaseOutExpo),
                    initialOffsetX = { -(it * 0.12f).toInt() }
                ) + fadeIn(tween(450, easing = EaseOutExpo))
            }
        } else {
            fadeIn(tween(450, easing = EaseOutQuart))
        }
    }

    override val popExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (!isLandscapeMode) {
            slideOutHorizontally(
                animationSpec = tween(600, easing = EaseOutExpo),
                targetOffsetX = { (it * 0.35f).toInt() }
            ) + fadeOut(tween(450, easing = EaseOutExpo))
        } else {
            fadeOut(tween(380, easing = EaseOutQuart))
        }
    }
}

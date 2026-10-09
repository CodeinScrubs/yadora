package com.example.ui

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.data.repository.MedReviewRepository
import com.example.ui.add.AddUnitScreen
import com.example.ui.library.LibraryScreen
import com.example.ui.navigation.Screen
import com.example.ui.progress.ProgressScreen
import com.example.ui.review.ReviewSessionScreen
import com.example.ui.settings.SettingsScreen
import com.example.ui.settings.ThemeSettingsScreen
import com.example.ui.today.TodayScreen

@Composable
fun MedReviewApp(repository: MedReviewRepository, onLanguageChange: (String) -> Unit = {}, onThemeChange: (String, String) -> Unit = { _, _ -> }, openReviewSignal: Int = 0) {
    val navController = rememberNavController()

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Determine if we should show the bottom bar
    val showBottomBar = currentDestination?.route?.let { route ->
        route.contains("Today") || route.contains("Library") || route.contains("Progress")
    } ?: true

    // Deep-link from a "Review now" notification/alarm straight into the review session.
    androidx.compose.runtime.LaunchedEffect(openReviewSignal) {
        if (openReviewSignal > 0) {
            navController.navigate(Screen.ReviewSession(-1L)) {
                launchSingleTop = true
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
// Keep the same imports but ensure we have everything
                NavigationBar(
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface,
                    contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                    tonalElevation = 0.dp
                ) {
                    val strings = com.example.ui.i18n.LocalStrings.current
                    val items = listOf(
                        Triple(Screen.Today, strings.navToday.uppercase(), Icons.Default.Today),
                        Triple(Screen.Library, strings.navLibrary.uppercase(), Icons.AutoMirrored.Filled.List),
                        Triple(Screen.Progress, strings.navProgress.uppercase(), Icons.Default.Timeline)
                    )
                    items.forEach { (screen, label, icon) ->
                        val selected = currentDestination?.hierarchy?.any { it.route?.contains(screen::class.simpleName ?: "") == true } == true
                        NavigationBarItem(
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, maxLines = 1) },
                            selected = selected,
                            colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                                selectedIconColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                                selectedTextColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                                indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                unselectedIconColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            onClick = {
                                navController.navigate(screen) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Today,
            // Consumed as well as applied: every screen has its own Scaffold, and its top bar and content insets counted
            // the status and navigation bars a second time. Every screen opened with a status bar's height of empty
            // space above its title, and the + button and the keyboard sat a navigation bar too high (2026-10-03).
            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
            // Calm cross-fade between screens — direction-agnostic, so it reads the same in RTL and
            // never fights the bottom-tab siblings. Momentum, not fireworks.
            enterTransition = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(com.example.ui.theme.AppMotion.StandardMs, easing = com.example.ui.theme.AppMotion.Standard)) },
            exitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(com.example.ui.theme.AppMotion.FastMs)) },
            popEnterTransition = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(com.example.ui.theme.AppMotion.StandardMs, easing = com.example.ui.theme.AppMotion.Standard)) },
            popExitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(com.example.ui.theme.AppMotion.FastMs)) },
        ) {
            composable<Screen.Today> {
                TodayScreen(
                    repository = repository,
                    onNavigateToAdd = { navController.navigate(Screen.AddUnit) { launchSingleTop = true } },
                    onNavigateToReview = { unitId -> navController.navigate(Screen.ReviewSession(unitId)) { launchSingleTop = true } },
                    onReviewMoreAnyway = { navController.navigate(Screen.ReviewSession(-1L, ignoreLimit = true)) { launchSingleTop = true } },
                    onReviewAhead = { navController.navigate(Screen.ReviewSession(-1L, ahead = true)) { launchSingleTop = true } },
                    onNavigateToEdit = { unitId -> navController.navigate(Screen.EditUnit(unitId)) { launchSingleTop = true } },
                    onNavigateToSettings = { navController.navigate(Screen.Settings) { launchSingleTop = true } }
                )
            }
            composable<Screen.Library> {
                LibraryScreen(
                    repository = repository,
                    onNavigateToEdit = { unitId -> navController.navigate(Screen.EditUnit(unitId)) { launchSingleTop = true } },
                    onNavigateToAdd = { navController.navigate(Screen.AddUnit) { launchSingleTop = true } },
                    onNavigateToSettings = { navController.navigate(Screen.Settings) { launchSingleTop = true } },
                    // On-demand review of one topic, due or not (a self-test before an exam).
                    onNavigateToReview = { unitId -> navController.navigate(Screen.ReviewSession(unitId)) { launchSingleTop = true } }
                )
            }
            composable<Screen.Progress> {
                ProgressScreen(
                    repository = repository,
                    onNavigateToSettings = { navController.navigate(Screen.Settings) { launchSingleTop = true } }
                )
            }
            composable<Screen.AddUnit> {
                AddUnitScreen(
                    repository = repository,
                    unitId = null,
                    onBack = { navController.popBackStack() },
                    // "Save and rate now": the study just happened, so its first rating is logged at once
                    // instead of waiting in Today. The Add screen is replaced, so Back returns to where
                    // the learner came from.
                    onRateNow = { newId ->
                        navController.popBackStack()
                        navController.navigate(Screen.ReviewSession(newId)) { launchSingleTop = true }
                    },
                    onOpenRelatedTopic = { id, archived ->
                        // Keep the Add draft in the back stack: viewing a match never creates a duplicate,
                        // overwrites the existing topic or silently restores an archived topic.
                        if (archived) navController.navigate(Screen.EditUnit(id)) { launchSingleTop = true }
                        else navController.navigate(Screen.ReviewSession(id)) { launchSingleTop = true }
                    },
                )
            }
            composable<Screen.EditUnit> { backStackEntry ->
                val editUnit = backStackEntry.toRoute<Screen.EditUnit>()
                AddUnitScreen(
                    repository = repository,
                    unitId = editUnit.unitId,
                    onBack = { navController.popBackStack() },
                    // "Save and review now": the review replaces this page, so Back returns to where the topic was
                    // opened (the Library, Today), exactly like "Save and rate now" for a new topic.
                    onReviewNow = { id ->
                        navController.popBackStack()
                        navController.navigate(Screen.ReviewSession(id)) { launchSingleTop = true }
                    },
                    showReviewNow = !editUnit.fromReview,
                )
            }
            composable<Screen.ReviewSession> { backStackEntry ->
                val reviewSession = backStackEntry.toRoute<Screen.ReviewSession>()
                ReviewSessionScreen(
                    repository = repository,
                    unitId = reviewSession.unitId,
                    ignoreLimit = reviewSession.ignoreLimit,
                    ahead = reviewSession.ahead,
                    onNavigateToEdit = { id -> navController.navigate(Screen.EditUnit(id, fromReview = true)) { launchSingleTop = true } },
                    onFinish = { navController.popBackStack() }
                )
            }
            composable<Screen.Settings> {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onLanguageChange = onLanguageChange,
                    onOpenThemeSettings = { navController.navigate(Screen.ThemeSettings) { launchSingleTop = true } }
                )
            }
            composable<Screen.ThemeSettings> {
                ThemeSettingsScreen(
                    onBack = { navController.popBackStack() },
                    onThemeChange = onThemeChange
                )
            }
        }
    }
}

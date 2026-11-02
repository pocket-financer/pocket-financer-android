package com.pocketfinancer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.pocketfinancer.ui.navigation.Screen
import com.pocketfinancer.ui.settings.SettingsScreen
import com.pocketfinancer.ui.transactions.TransactionsScreen
import com.pocketfinancer.ui.onboarding.OnboardingScreen
import com.pocketfinancer.ui.review.ReviewDetailScreen
import com.pocketfinancer.ui.review.ReviewInboxScreen
import com.pocketfinancer.ui.home.HomeScreen
import com.pocketfinancer.ui.insights.InsightsScreen
import com.pocketfinancer.ui.theme.M3_OnSecondaryContainer
import com.pocketfinancer.ui.theme.M3_OnSurface
import com.pocketfinancer.ui.theme.M3_OnSurfaceVariant
import com.pocketfinancer.ui.theme.M3_SecondaryContainer
import com.pocketfinancer.ui.theme.M3_SurfaceContainer
import com.pocketfinancer.ui.theme.PocketFinancerTheme

@Composable
fun PocketFinancerRoot() {
    PocketFinancerTheme {
        val context = androidx.compose.ui.platform.LocalContext.current
        val preferences = remember(context) {
            context.getSharedPreferences(
                ".app_settings",
                android.content.Context.MODE_PRIVATE
            )
        }
        var onboardingCompleted by remember(preferences) {
            mutableStateOf(preferences.getBoolean("onboarding_completed", false))
        }
        DisposableEffect(preferences) {
            val listener =
                android.content.SharedPreferences.OnSharedPreferenceChangeListener {
                        changedPreferences,
                        key ->
                    if (key == "onboarding_completed") {
                        onboardingCompleted = changedPreferences.getBoolean(
                            "onboarding_completed",
                            false
                        )
                    }
                }
            preferences.registerOnSharedPreferenceChangeListener(listener)
            // Re-read after registering so a commit racing with composition
            // cannot leave the shell gate stale.
            onboardingCompleted = preferences.getBoolean(
                "onboarding_completed",
                false
            )
            onDispose {
                preferences.unregisterOnSharedPreferenceChangeListener(listener)
            }
        }

        if (!onboardingCompleted) {
            OnboardingScreen(
                onComplete = { onboardingCompleted = true }
            )
        } else {
            val navController = rememberNavController()

            Scaffold(
                bottomBar = {
                    NavigationBar(
                        containerColor = M3_SurfaceContainer
                    ) {
                        val navBackStackEntry by navController.currentBackStackEntryAsState()
                        val currentDestination = navBackStackEntry?.destination

                        Screen.tabs.forEach { screen ->
                            val selected = currentDestination?.hierarchy?.any {
                                it.route == screen.route
                            } == true

                            NavigationBarItem(
                                icon = {
                                    Icon(
                                        imageVector = if (selected) screen.selectedIcon else screen.unselectedIcon,
                                        contentDescription = screen.label
                                    )
                                },
                                label = {
                                    Text(
                                        text = screen.label,
                                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall
                                    )
                                },
                                selected = selected,
                                onClick = {
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = M3_OnSecondaryContainer,
                                    selectedTextColor = M3_OnSurface,
                                    unselectedIconColor = M3_OnSurfaceVariant,
                                    unselectedTextColor = M3_OnSurfaceVariant,
                                    indicatorColor = M3_SecondaryContainer
                                )
                            )
                        }
                    }
                }
            ) { innerPadding ->
                NavHost(
                    navController = navController,
                    startDestination = Screen.Home.route,
                    modifier = Modifier.padding(innerPadding)
                ) {
                    composable(Screen.Home.route) {
                        HomeScreen(
                            onNavigateToTab = { tab ->
                                when (tab.lowercase()) {
                                    "reviews" -> navController.navigate(Screen.Reviews.route) {
                                        launchSingleTop = true
                                    }
                                    "transactions" -> navController.navigate(Screen.Transactions.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                    "settings" -> navController.navigate(Screen.Settings.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            }
                        )
                    }
                    composable(Screen.Transactions.route) {
                        TransactionsScreen(
                            onNavigateToTab = { tab ->
                                when (tab.lowercase()) {
                                    "settings" -> navController.navigate(Screen.Settings.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                    "reviews" -> navController.navigate(Screen.Reviews.route) {
                                        launchSingleTop = true
                                    }
                                }
                            },
                            onOpenReview = { reviewCaseId ->
                                navController.navigate(Screen.ReviewDetail.route(reviewCaseId))
                            }
                        )
                    }
                    composable(Screen.Insights.route) { InsightsScreen() }
                    composable(Screen.Settings.route) { SettingsScreen() }
                    composable(Screen.Reviews.route) {
                        ReviewInboxScreen(onOpenReview = { reviewCaseId ->
                            navController.navigate(Screen.ReviewDetail.route(reviewCaseId))
                        })
                    }
                    composable(Screen.ReviewDetail.route) { entry ->
                        val reviewCaseId = entry.arguments?.getString("reviewCaseId")
                        if (reviewCaseId != null) {
                            ReviewDetailScreen(
                                reviewCaseId = reviewCaseId,
                                onFinished = {
                                    navController.navigate(Screen.Transactions.route) {
                                        popUpTo(navController.graph.findStartDestination().id)
                                        launchSingleTop = true
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceholderScreen(title: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = M3_OnSurfaceVariant,
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium
        )
    }
}

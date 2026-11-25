package com.pocketfinancer.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    data object Home : Screen("home", "Home", Icons.Filled.Home, Icons.Outlined.Home)
    data object Transactions : Screen("transactions", "Transactions", Icons.Filled.List, Icons.Outlined.List)
    data object Insights : Screen("insights", "Insights", Icons.Filled.PieChart, Icons.Outlined.PieChart)
    data object Settings : Screen("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
    data object Reviews : Screen("reviews", "Reviews", Icons.Filled.List, Icons.Outlined.List)

    data object ReviewDetail : Screen(
        "reviews/{reviewCaseId}",
        "Review",
        Icons.Filled.List,
        Icons.Outlined.List
    ) {
        fun route(reviewCaseId: String) = "reviews/$reviewCaseId"
    }

    companion object {
        val tabs = listOf(Home, Transactions, Insights, Settings)
    }
}

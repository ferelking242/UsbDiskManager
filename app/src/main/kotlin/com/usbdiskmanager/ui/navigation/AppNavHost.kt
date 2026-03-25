package com.usbdiskmanager.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.usbdiskmanager.ps2.ui.Ps2StudioScreen
import com.usbdiskmanager.ps2.ui.components.AppTab
import com.usbdiskmanager.ps2.ui.components.FloatingNavDock
import com.usbdiskmanager.ui.screens.DashboardScreen
import com.usbdiskmanager.ui.screens.DiskDetailScreen
import com.usbdiskmanager.ui.screens.FileExplorerScreen
import com.usbdiskmanager.ui.screens.LogsScreen
import com.usbdiskmanager.ui.screens.SettingsScreen

sealed class Screen(val route: String) {
    data object Dashboard : Screen("dashboard")
    data object Ps2Studio : Screen("ps2_studio")
    data object DiskDetail : Screen("disk/{deviceId}") {
        fun createRoute(deviceId: String) = "disk/$deviceId"
    }
    data object FileExplorer : Screen("files/{deviceId}/{mountPoint}") {
        fun createRoute(deviceId: String, mountPoint: String) =
            "files/$deviceId/${mountPoint.replace("/", "|")}"
    }
    data object Logs : Screen("logs")
    data object Settings : Screen("settings")
}

private val DOCK_ROUTES = setOf(Screen.Dashboard.route, Screen.Ps2Studio.route)

@Composable
fun AppNavHost(
    onRequestSafPermission: () -> Unit
) {
    val navController = rememberNavController()
    val backstackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backstackEntry?.destination?.route

    val currentTab = when (currentRoute) {
        Screen.Ps2Studio.route -> AppTab.PS2_STUDIO
        else                    -> AppTab.USB
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route
        ) {
            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    onDeviceClick = { deviceId ->
                        navController.navigate(Screen.DiskDetail.createRoute(deviceId))
                    },
                    onLogsClick = {
                        navController.navigate(Screen.Logs.route)
                    },
                    onSettingsClick = {
                        navController.navigate(Screen.Settings.route)
                    },
                    onRequestSafPermission = onRequestSafPermission
                )
            }

            composable(Screen.Ps2Studio.route) {
                Ps2StudioScreen()
            }

            composable(
                route = Screen.DiskDetail.route,
                arguments = listOf(navArgument("deviceId") { type = NavType.StringType })
            ) { backStackEntry ->
                val deviceId = backStackEntry.arguments?.getString("deviceId") ?: return@composable
                DiskDetailScreen(
                    deviceId = deviceId,
                    onNavigateUp = { navController.popBackStack() },
                    onOpenFileExplorer = { mountPoint ->
                        navController.navigate(
                            Screen.FileExplorer.createRoute(deviceId, mountPoint)
                        )
                    }
                )
            }

            composable(
                route = Screen.FileExplorer.route,
                arguments = listOf(
                    navArgument("deviceId") { type = NavType.StringType },
                    navArgument("mountPoint") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val mountPoint = backStackEntry.arguments?.getString("mountPoint")
                    ?.replace("|", "/") ?: return@composable
                FileExplorerScreen(
                    mountPoint = mountPoint,
                    onNavigateUp = { navController.popBackStack() }
                )
            }

            composable(Screen.Logs.route) {
                LogsScreen(onNavigateUp = { navController.popBackStack() })
            }

            composable(Screen.Settings.route) {
                SettingsScreen(onNavigateUp = { navController.popBackStack() })
            }
        }

        if (currentRoute in DOCK_ROUTES) {
            FloatingNavDock(
                currentTab = currentTab,
                onTabSelected = { tab ->
                    val route = when (tab) {
                        AppTab.USB        -> Screen.Dashboard.route
                        AppTab.PS2_STUDIO -> Screen.Ps2Studio.route
                    }
                    if (currentRoute != route) {
                        navController.navigate(route) {
                            popUpTo(Screen.Dashboard.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

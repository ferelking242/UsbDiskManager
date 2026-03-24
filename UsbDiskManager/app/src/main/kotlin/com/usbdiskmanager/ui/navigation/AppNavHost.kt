package com.usbdiskmanager.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.usbdiskmanager.ui.screens.DashboardScreen
import com.usbdiskmanager.ui.screens.DiskDetailScreen
import com.usbdiskmanager.ui.screens.FileExplorerScreen
import com.usbdiskmanager.ui.screens.LogsScreen

sealed class Screen(val route: String) {
    data object Dashboard : Screen("dashboard")
    data object DiskDetail : Screen("disk/{deviceId}") {
        fun createRoute(deviceId: String) = "disk/$deviceId"
    }
    data object FileExplorer : Screen("files/{deviceId}/{mountPoint}") {
        fun createRoute(deviceId: String, mountPoint: String) =
            "files/$deviceId/${mountPoint.replace("/", "|")}"
    }
    data object Logs : Screen("logs")
}

@Composable
fun AppNavHost(
    onRequestSafPermission: () -> Unit
) {
    val navController = rememberNavController()

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
                onRequestSafPermission = onRequestSafPermission
            )
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
    }
}

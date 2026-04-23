package com.ispindle.plotter.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ispindle.plotter.IspindleApp
import com.ispindle.plotter.ui.screens.CalibrationScreen
import com.ispindle.plotter.ui.screens.DevicesScreen
import com.ispindle.plotter.ui.screens.GraphScreen
import com.ispindle.plotter.ui.screens.HomeScreen
import com.ispindle.plotter.ui.screens.SetupScreen

private data class TopTab(val dest: Dest, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val topTabs = listOf(
    TopTab(Dest.Home, Icons.Default.Home),
    TopTab(Dest.Devices, Icons.Default.Devices),
    TopTab(Dest.Setup, Icons.AutoMirrored.Filled.Help)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(app: IspindleApp, vm: MainViewModel) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    val label = topTabs.firstOrNull { it.dest.route == currentRoute }?.dest?.label
                        ?: when {
                            currentRoute?.startsWith("graph") == true -> "Graph"
                            currentRoute?.startsWith("calibrate") == true -> "Calibrate"
                            else -> "iSpindle Plotter"
                        }
                    Text(label)
                }
            )
        },
        bottomBar = {
            NavigationBar {
                topTabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.dest.route,
                        onClick = {
                            navController.navigate(tab.dest.route) {
                                popUpTo(Dest.Home.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.dest.label) },
                        label = { Text(tab.dest.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Dest.Home.route
        ) {
            composable(Dest.Home.route) {
                HomeScreen(vm = vm, serverState = app.httpServer.state, padding = padding)
            }
            composable(Dest.Devices.route) {
                DevicesScreen(
                    vm = vm,
                    padding = padding,
                    onOpenGraph = { navController.navigate(Dest.Graph.of(it)) },
                    onOpenCalibrate = { navController.navigate(Dest.Calibrate.of(it)) }
                )
            }
            composable(Dest.Setup.route) { SetupScreen(padding = padding) }
            composable(
                Dest.Graph.route,
                arguments = listOf(navArgument("deviceId") { type = NavType.LongType })
            ) { entry ->
                val id = entry.arguments?.getLong("deviceId") ?: return@composable
                GraphScreen(vm = vm, deviceId = id, padding = padding)
            }
            composable(
                Dest.Calibrate.route,
                arguments = listOf(navArgument("deviceId") { type = NavType.LongType })
            ) { entry ->
                val id = entry.arguments?.getLong("deviceId") ?: return@composable
                CalibrationScreen(vm = vm, deviceId = id, padding = padding)
            }
        }
    }
}

package com.rknepp.parity.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.rknepp.parity.app.LocalServiceLocator
import com.rknepp.parity.app.StartupDestination
import com.rknepp.parity.auth.events.AuthEvent
import com.rknepp.parity.auth.ui.connect.ConnectToServerScreen
import com.rknepp.parity.auth.ui.login.LoginScreen
import com.rknepp.parity.auth.ui.register.RegisterScreen
import com.rknepp.parity.home.ui.HomeScreen

@Composable
fun ParityNavHost(navController: NavHostController) {
    val locator = LocalServiceLocator.current

    val initialDestination by locator.startupGate.initialDestination
        .collectAsState(initial = null)

    // Forced-logout banner state is a one-shot flag consumed on first
    // display of the login screen.
    var sessionExpiredPending by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        locator.authEventBus.events.collect { event ->
            when (event) {
                AuthEvent.SessionExpired -> {
                    sessionExpiredPending = true
                    navController.navigate(Route.Login()) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
        }
    }

    val start = initialDestination
    if (start == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val startRoute: Route = when (start) {
        StartupDestination.Connect -> Route.Connect
        StartupDestination.Login -> Route.Login()
        StartupDestination.Home -> Route.Home
    }

    NavHost(navController = navController, startDestination = startRoute) {
        composable<Route.Connect> {
            ConnectToServerScreen(
                onConnected = {
                    navController.navigate(Route.Login()) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable<Route.Login> { backStackEntry ->
            val args: Route.Login = backStackEntry.toRoute()
            val showBanner = sessionExpiredPending
            LoginScreen(
                showSessionExpiredBanner = showBanner,
                onSessionExpiredConsumed = { sessionExpiredPending = false },
                prefillUsername = args.prefillUsername.orEmpty(),
                onLoggedIn = {
                    navController.navigate(Route.Home) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onNavigateToRegister = { navController.navigate(Route.Register) },
            )
        }
        composable<Route.Register> {
            RegisterScreen(
                onRegistered = { username ->
                    navController.navigate(Route.Login(prefillUsername = username)) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onBackToLogin = { navController.popBackStack() },
            )
        }
        composable<Route.Home> {
            HomeScreen(
                onLoggedOut = {
                    navController.navigate(Route.Login()) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
    }
}

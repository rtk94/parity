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
import com.rknepp.parity.auth.ui.login.LoginScreen
import com.rknepp.parity.auth.ui.register.RegisterScreen
import com.rknepp.parity.ledger.ui.CreateExpenseScreen
import com.rknepp.parity.ledger.ui.CreatePaymentScreen
import com.rknepp.parity.main.ui.MainScreen
import com.rknepp.parity.relationships.ui.CreateRelationshipScreen
import com.rknepp.parity.relationships.ui.RelationshipDetailScreen

@Composable
fun ParityNavHost(
    navController: NavHostController,
    deepLinkRelationshipId: Long? = null,
    onDeepLinkConsumed: () -> Unit = {},
) {
    val locator = LocalServiceLocator.current

    val initialDestination by locator.startupGate.initialDestination
        .collectAsState(initial = null)

    // Forced-logout banner state is a one-shot flag consumed by the
    // login screen when it first composes.
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
                AuthEvent.LoggedOut -> {
                    sessionExpiredPending = false
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
        StartupDestination.Login -> Route.Login()
        StartupDestination.Home -> Route.Home
    }

    NavHost(navController = navController, startDestination = startRoute) {
        composable<Route.Login> { backStackEntry ->
            val args: Route.Login = backStackEntry.toRoute()
            LoginScreen(
                showSessionExpiredBanner = sessionExpiredPending,
                onSessionExpiredConsumed = { sessionExpiredPending = false },
                prefillUsername = args.prefillUsername.orEmpty(),
                onLoggedIn = {
                    // Register this device for push now that a session exists.
                    locator.registerDeviceIfLoggedIn()
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
            MainScreen(
                onNavigateToRelationshipDetail = { id ->
                    navController.navigate(Route.RelationshipDetail(id))
                },
                onNavigateToCreateRelationship = {
                    navController.navigate(Route.CreateRelationship)
                },
            )
        }
        composable<Route.CreateRelationship> {
            CreateRelationshipScreen(
                onBack = { navController.popBackStack() },
                onCreated = { navController.popBackStack() },
            )
        }
        composable<Route.RelationshipDetail> { backStackEntry ->
            val args: Route.RelationshipDetail = backStackEntry.toRoute()
            RelationshipDetailScreen(
                relationshipId = args.relationshipId,
                onBack = { navController.popBackStack() },
                onNavigateToCreateExpense = {
                    navController.navigate(Route.CreateExpense(args.relationshipId))
                },
                onNavigateToCreatePayment = {
                    navController.navigate(Route.CreatePayment(args.relationshipId))
                },
            )
        }
        composable<Route.CreateExpense> { backStackEntry ->
            val args: Route.CreateExpense = backStackEntry.toRoute()
            CreateExpenseScreen(
                relationshipId = args.relationshipId,
                onBack = { navController.popBackStack() },
                onCreated = { navController.popBackStack() },
            )
        }
        composable<Route.CreatePayment> { backStackEntry ->
            val args: Route.CreatePayment = backStackEntry.toRoute()
            CreatePaymentScreen(
                relationshipId = args.relationshipId,
                onBack = { navController.popBackStack() },
                onCreated = { navController.popBackStack() },
            )
        }
    }

    // Deep-link from a tapped notification: once on the signed-in graph,
    // open the relationship. Consumed exactly once (dropped if logged out).
    LaunchedEffect(deepLinkRelationshipId, start) {
        val relationshipId = deepLinkRelationshipId ?: return@LaunchedEffect
        if (start == StartupDestination.Home) {
            navController.navigate(Route.RelationshipDetail(relationshipId))
        }
        onDeepLinkConsumed()
    }
}

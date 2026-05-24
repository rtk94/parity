package com.rknepp.parity.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.rknepp.parity.ServiceLocator
import com.rknepp.parity.navigation.ParityNavHost
import com.rknepp.parity.ui.theme.ParityTheme

@Composable
fun ParityApp(locator: ServiceLocator) {
    ParityTheme {
        CompositionLocalProvider(LocalServiceLocator provides locator) {
            Surface(modifier = Modifier.fillMaxSize()) {
                val navController = rememberNavController()
                ParityNavHost(navController = navController)
            }
        }
    }
}

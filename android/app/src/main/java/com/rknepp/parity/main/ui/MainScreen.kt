package com.rknepp.parity.main.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.rknepp.parity.home.ui.HomeScreen
import com.rknepp.parity.relationships.ui.RelationshipListScreen
import com.rknepp.parity.settings.ui.SettingsScreen

enum class MainTab {
    Home,
    Relationships,
    Settings,
}

@Composable
fun MainScreen(
    onNavigateToCreateRelationship: () -> Unit,
    onNavigateToRelationshipDetail: (Long) -> Unit,
) {
    var currentTab by rememberSaveable { mutableStateOf(MainTab.Home) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                    label = { Text("Home") },
                    selected = currentTab == MainTab.Home,
                    onClick = { currentTab = MainTab.Home },
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Person, contentDescription = "Relationships") },
                    label = { Text("Relationships") },
                    selected = currentTab == MainTab.Relationships,
                    onClick = { currentTab = MainTab.Relationships },
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                    selected = currentTab == MainTab.Settings,
                    onClick = { currentTab = MainTab.Settings },
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (currentTab) {
                MainTab.Home -> HomeScreen(
                    onNavigateToRelationships = { currentTab = MainTab.Relationships },
                    onNavigateToRelationshipDetail = onNavigateToRelationshipDetail,
                    onNavigateToCreateRelationship = onNavigateToCreateRelationship,
                )
                MainTab.Relationships -> RelationshipListScreen(
                    onNavigateToDetail = onNavigateToRelationshipDetail,
                    onNavigateToCreate = onNavigateToCreateRelationship,
                )
                MainTab.Settings -> SettingsScreen()
            }
        }
    }
}

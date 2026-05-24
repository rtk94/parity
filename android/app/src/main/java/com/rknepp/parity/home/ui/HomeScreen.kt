package com.rknepp.parity.home.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rknepp.parity.R
import com.rknepp.parity.app.LocalServiceLocator

@Composable
fun HomeScreen(
    onLoggedOut: () -> Unit,
) {
    val locator = LocalServiceLocator.current
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(locator))
    val ui by vm.ui.collectAsState()

    Scaffold { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
        ) {
            when (val state = ui.state) {
                HomeState.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                HomeState.Error -> Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.home_title),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        text = stringResource(R.string.home_loading_error),
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(
                        onClick = { vm.logout(onLoggedOut) },
                        enabled = !ui.loggingOut,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (ui.loggingOut) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(stringResource(R.string.home_logout))
                        }
                    }
                }
                is HomeState.Loaded -> Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.home_title),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        text = stringResource(
                            R.string.home_signed_in_as,
                            state.user.displayName,
                            state.user.username,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.home_placeholder),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (ui.logoutWarning) {
                        Text(
                            text = stringResource(R.string.home_logout_partial),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(
                        onClick = { vm.logout(onLoggedOut) },
                        enabled = !ui.loggingOut,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (ui.loggingOut) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(stringResource(R.string.home_logout))
                        }
                    }
                }
            }
        }
    }
}

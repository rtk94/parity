package com.rknepp.parity.home.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rknepp.parity.ServiceLocator
import com.rknepp.parity.auth.data.AuthRepository
import com.rknepp.parity.home.data.MeRepository
import com.rknepp.parity.home.model.UserSummary
import com.rknepp.parity.network.ApiResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface HomeState {
    data object Loading : HomeState
    data class Loaded(val user: UserSummary) : HomeState
    data object Error : HomeState
}

data class HomeUi(
    val state: HomeState = HomeState.Loading,
    val loggingOut: Boolean = false,
    val logoutWarning: Boolean = false,
)

class HomeViewModel(
    private val meRepository: MeRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(HomeUi())
    val ui: StateFlow<HomeUi> = _ui.asStateFlow()

    init { reload() }

    fun reload() {
        _ui.update { it.copy(state = HomeState.Loading) }
        viewModelScope.launch {
            when (val result = meRepository.fetchMe()) {
                is ApiResult.Success -> _ui.update { it.copy(state = HomeState.Loaded(result.data)) }
                // 401 is handled inside the authenticator -> session
                // expired event -> nav back to login. From the
                // ViewModel's perspective any non-success here is just
                // "couldn't load" since by the time we render, the nav
                // host has likely already moved us off this screen.
                else -> _ui.update { it.copy(state = HomeState.Error) }
            }
        }
    }

    fun logout(onLoggedOut: () -> Unit) {
        if (_ui.value.loggingOut) return
        _ui.update { it.copy(loggingOut = true, logoutWarning = false) }
        viewModelScope.launch {
            val result = authRepository.logout()
            val warning = result !is ApiResult.Success
            _ui.update { it.copy(loggingOut = false, logoutWarning = warning) }
            onLoggedOut()
        }
    }

    companion object {
        fun factory(locator: ServiceLocator): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(locator.meRepository, locator.authRepository) }
        }
    }
}

package com.relay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.relay.auth.AuthState
import com.relay.ui.HomeScreen
import com.relay.ui.LoginScreen
import com.relay.ui.SessionViewModel
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun App() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val viewModel = koinViewModel<SessionViewModel>()
            val authState by viewModel.authState.collectAsStateWithLifecycle()
            val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
            val busy by viewModel.busy.collectAsStateWithLifecycle()
            val error by viewModel.error.collectAsStateWithLifecycle()

            Box(modifier = Modifier.fillMaxSize().safeContentPadding()) {
                when (authState) {
                    is AuthState.Unknown -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                    is AuthState.LoggedOut -> LoginScreen(
                        busy = busy,
                        error = error,
                        onLogin = viewModel::login,
                        onRegister = viewModel::register
                    )
                    is AuthState.LoggedIn -> HomeScreen(
                        connectionState = connectionState,
                        onLogout = viewModel::logout
                    )
                }
            }
        }
    }
}

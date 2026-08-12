package com.relay.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.relay.auth.AuthResult
import com.relay.auth.AuthState
import com.relay.auth.SessionManager
import com.relay.db.ContactStore
import com.relay.db.MessageStore
import com.relay.network.ConnectionManager
import com.relay.network.ConnectionState
import com.relay.repository.PeerNameResolver
import com.relay.sync.Outbox
import com.relay.sync.SyncEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SessionViewModel(
    private val session: SessionManager,
    private val connection: ConnectionManager,
    private val syncEngine: SyncEngine,
    private val outbox: Outbox,
    private val store: MessageStore,
    private val contacts: ContactStore,
    private val peerNames: PeerNameResolver
) : ViewModel() {

    val authState: StateFlow<AuthState> = session.state
    val connectionState: StateFlow<ConnectionState> = connection.state

    private val mutableBusy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()

    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()

    init {
        viewModelScope.launch {
            session.restoreSession()
            session.state.collect { state ->
                when (state) {
                    is AuthState.LoggedIn -> {
                        syncEngine.start()
                        outbox.start()
                        peerNames.start()
                        connection.start()
                    }
                    is AuthState.LoggedOut -> {
                        connection.stop()
                        outbox.stop()
                        peerNames.stop()
                        syncEngine.stop()
                        store.clearAll()
                        contacts.clearAll()
                    }
                    is AuthState.Unknown -> Unit
                }
            }
        }
    }

    fun login(email: String, password: String) = authAction {
        session.login(email.trim(), password)
    }

    fun register(email: String, password: String, firstName: String, lastName: String) = authAction {
        session.register(email.trim(), password, firstName.trim(), lastName.trim())
    }

    fun logout() {
        viewModelScope.launch { session.logout() }
    }

    private fun authAction(action: suspend () -> AuthResult) {
        if (mutableBusy.value) return
        viewModelScope.launch {
            mutableBusy.value = true
            mutableError.value = null
            when (val result = action()) {
                is AuthResult.Success -> Unit
                is AuthResult.Failure -> mutableError.value = result.reason
            }
            mutableBusy.value = false
        }
    }
}

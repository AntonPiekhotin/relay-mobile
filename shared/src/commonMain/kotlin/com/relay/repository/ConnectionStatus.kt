package com.relay.repository

import com.relay.network.ConnectionState
import com.relay.network.SocketClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest

val RECONNECT_GRACE: Duration = 3.seconds

enum class ConnectionPhase { UNKNOWN, LIVE, RECONNECTING }

interface ConnectionStatus {
    val phase: Flow<ConnectionPhase>
}

class SocketConnectionStatus(
    private val socket: SocketClient,
    private val grace: Duration = RECONNECT_GRACE
) : ConnectionStatus {

    @OptIn(ExperimentalCoroutinesApi::class)
    override val phase: Flow<ConnectionPhase> = socket.state
        .map { it is ConnectionState.Connected }
        .distinctUntilChanged()
        .transformLatest { connected ->
            if (connected) {
                emit(ConnectionPhase.LIVE)
            } else {
                emit(ConnectionPhase.UNKNOWN)
                delay(grace)
                emit(ConnectionPhase.RECONNECTING)
            }
        }
}

package com.relay.network

import com.relay.protocol.Envelope
import com.relay.protocol.InboundFrame
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface SocketClient {
    val state: StateFlow<ConnectionState>
    val frames: SharedFlow<InboundFrame>
    suspend fun sendFrame(envelope: Envelope): Boolean
}

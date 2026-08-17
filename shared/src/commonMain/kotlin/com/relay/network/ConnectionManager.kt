package com.relay.network

import com.relay.auth.AuthState
import com.relay.auth.SessionManager
import com.relay.config.AppConfig
import com.relay.protocol.Envelope
import com.relay.protocol.InboundFrame
import com.relay.protocol.encodeFrame
import com.relay.protocol.parseInboundFrame
import com.relay.protocol.pingFrame
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val userId: String, val sessionId: String) : ConnectionState
}

private val HEARTBEAT_INTERVAL = 30.seconds
private const val MAX_MISSED_PONGS = 2
private const val FRAME_BUFFER = 256

class ConnectionManager(
    private val http: HttpClient,
    private val config: AppConfig,
    private val session: SessionManager,
    private val scope: CoroutineScope
) : SocketClient, SocketLifecycle {
    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = mutableState.asStateFlow()

    private val mutableFrames = MutableSharedFlow<InboundFrame>(extraBufferCapacity = FRAME_BUFFER)
    override val frames: SharedFlow<InboundFrame> = mutableFrames.asSharedFlow()

    private var loopJob: Job? = null
    private var activeSocket: DefaultClientWebSocketSession? = null

    override fun start() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch { connectionLoop() }
    }

    override fun stop() {
        loopJob?.cancel()
        loopJob = null
        val socket = activeSocket
        activeSocket = null
        if (socket != null) {
            scope.launch {
                runCatching {
                    socket.close(CloseReason(CloseReason.Codes.NORMAL, "client going away"))
                }
            }
        }
        mutableState.value = ConnectionState.Disconnected
    }

    override suspend fun sendFrame(envelope: Envelope): Boolean {
        val socket = activeSocket ?: return false
        return try {
            socket.send(encodeFrame(envelope))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) { // allow: broad-catch a failed socket write means "not delivered over this transport" regardless of the engine-specific exception; callers fall back to REST or retry
            false
        }
    }

    private suspend fun connectionLoop() {
        var consecutiveFailures = 0
        var refreshedSinceLastSuccess = false
        while (currentCoroutineContext().isActive) {
            val token = session.validAccessToken()
            if (token == null) {
                mutableState.value = ConnectionState.Disconnected
                session.state.first { it is AuthState.LoggedIn }
                continue
            }
            mutableState.value = ConnectionState.Connecting
            val reachedConnected = runSocket(token)
            mutableState.value = ConnectionState.Disconnected
            if (reachedConnected) {
                consecutiveFailures = 0
                refreshedSinceLastSuccess = false
            } else {
                consecutiveFailures++
                if (!refreshedSinceLastSuccess) {
                    session.forceRefresh()
                    refreshedSinceLastSuccess = true
                }
            }
            delay(backoffDelayMillis(consecutiveFailures))
        }
    }

    private suspend fun runSocket(token: String): Boolean {
        var reachedConnected = false
        val missedPongs = MutableStateFlow(0)
        var heartbeat: Job? = null
        try {
            val socket = http.webSocketSession(config.wsUrl) {
                header(HttpHeaders.SecWebSocketProtocol, "access_token, $token")
            }
            activeSocket = socket
            try {
                for (frame in socket.incoming) {
                    if (frame !is Frame.Text) continue
                    val inbound = parseInboundFrame(frame.readText())
                    when (inbound) {
                        is InboundFrame.SessionConnected -> {
                            reachedConnected = true
                            mutableState.value = ConnectionState.Connected(
                                userId = inbound.payload.userId,
                                sessionId = inbound.payload.sessionId
                            )
                            heartbeat = startHeartbeat(socket, missedPongs)
                        }
                        is InboundFrame.Pong -> missedPongs.value = 0
                        else -> Unit
                    }
                    mutableFrames.emit(inbound)
                }
            } finally {
                heartbeat?.cancel()
                activeSocket = null
            }
        } catch (e: CancellationException) {
            activeSocket = null
            throw e
        } catch (e: Exception) { // allow: broad-catch handshake and transport failures surface as engine-specific exceptions (401 upgrade rejection, DNS, reset); all of them mean "this attempt failed, back off and retry"
            activeSocket = null
        }
        return reachedConnected
    }

    private fun startHeartbeat(
        socket: DefaultClientWebSocketSession,
        missedPongs: MutableStateFlow<Int>
    ): Job =
        socket.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL)
                if (missedPongs.value >= MAX_MISSED_PONGS) {
                    socket.cancel()
                    break
                }
                missedPongs.value += 1
                runCatching { socket.send(encodeFrame(pingFrame())) }
            }
        }
}

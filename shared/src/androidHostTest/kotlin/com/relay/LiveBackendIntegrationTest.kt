package com.relay

import com.relay.auth.AuthResult
import com.relay.auth.JwtClaims
import com.relay.auth.SessionManager
import com.relay.auth.StoredTokens
import com.relay.auth.TokenStore
import com.relay.config.AppConfig
import com.relay.network.ConnectionManager
import com.relay.network.ConnectionState
import com.relay.network.HttpClientFactory
import com.relay.network.KtorAuthApi
import com.relay.protocol.InboundFrame
import com.relay.protocol.newFrameId
import com.relay.protocol.pingFrame
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private data class LocalConfig(
    override val apiBaseUrl: String = "http://localhost:8080",
    override val wsUrl: String = "ws://localhost:8083/ws"
) : AppConfig

private class InMemoryTokenStore : TokenStore {
    private var tokens: StoredTokens? = null
    override suspend fun save(tokens: StoredTokens) {
        this.tokens = tokens
    }

    override suspend fun load(): StoredTokens? = tokens
    override suspend fun clear() {
        tokens = null
    }
}

class LiveBackendIntegrationTest {

    @Test
    fun registersLogsInConnectsAndHeartbeats() {
        if (!portOpen(8080) || !portOpen(8083)) {
            println("SKIPPED: local backend not running (need api-gateway :8080 and websocket-gateway :8083)") // allow: println-prod test-skip notice in test source; no logger is wired in host tests
            return
        }
        runBlocking {
            val config = LocalConfig()
            val http = HttpClientFactory.create()
            val tokenStore = InMemoryTokenStore()
            val session = SessionManager(KtorAuthApi(http, config), tokenStore)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val connection = ConnectionManager(http, config, session, scope)
            try {
                val email = "it.${newFrameId().take(12)}@relay.dev"
                val registered = session.register(email, "Str0ngPass!234", "Integration", "Test")
                assertIs<AuthResult.Success>(registered)

                val expectedUserId = JwtClaims.subjectOf(tokenStore.load()!!.accessToken)

                connection.start()
                val connected = withTimeout(15_000) {
                    connection.state.first { it is ConnectionState.Connected }
                } as ConnectionState.Connected
                assertEquals(expectedUserId, connected.userId)
                assertTrue(connected.sessionId.isNotBlank())

                val ping = pingFrame()
                val pong = withTimeout(10_000) {
                    val awaitPong = async {
                        connection.frames.first {
                            it is InboundFrame.Pong && it.payload.refId == ping.id
                        }
                    }
                    delay(300)
                    assertTrue(connection.sendFrame(ping))
                    awaitPong.await()
                }
                assertIs<InboundFrame.Pong>(pong)

                connection.stop()
                assertIs<ConnectionState.Disconnected>(connection.state.value)
            } finally {
                connection.stop()
                scope.cancel()
                http.close()
            }
        }
    }

    private fun portOpen(port: Int): Boolean =
        runCatching {
            Socket().use { it.connect(InetSocketAddress("localhost", port), 1_000) }
        }.isSuccess
}

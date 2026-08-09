package com.relay.repository

import app.cash.turbine.test
import com.relay.testutil.FakeSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest

private val GRACE = 3.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionStatusTest {

    @Test
    fun aConnectedSocketIsLiveImmediately() = runTest {
        val socket = FakeSocket()
        val status = SocketConnectionStatus(socket, GRACE)

        status.phase.test {
            assertEquals(ConnectionPhase.UNKNOWN, awaitItem())
            socket.connect()
            assertEquals(ConnectionPhase.LIVE, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aShortDropNeverReachesReconnecting() = runTest {
        val socket = FakeSocket()
        socket.connect()
        val status = SocketConnectionStatus(socket, GRACE)

        status.phase.test {
            assertEquals(ConnectionPhase.LIVE, awaitItem())
            socket.disconnect()
            assertEquals(ConnectionPhase.UNKNOWN, awaitItem())
            socket.connect()
            assertEquals(ConnectionPhase.LIVE, awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aSustainedDropReachesReconnectingAfterTheGrace() = runTest {
        val socket = FakeSocket()
        socket.connect()
        val status = SocketConnectionStatus(socket, GRACE)

        status.phase.test {
            assertEquals(ConnectionPhase.LIVE, awaitItem())
            socket.disconnect()
            assertEquals(ConnectionPhase.UNKNOWN, awaitItem())
            assertEquals(ConnectionPhase.RECONNECTING, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun reconnectAttemptsThatNeverConnectDoNotRestartTheGrace() = runTest {
        val socket = FakeSocket()
        socket.connect()
        val status = SocketConnectionStatus(socket, GRACE)

        status.phase.test {
            assertEquals(ConnectionPhase.LIVE, awaitItem())
            socket.disconnect()
            assertEquals(ConnectionPhase.UNKNOWN, awaitItem())
            repeat(5) {
                socket.connecting()
                socket.disconnect()
            }
            assertEquals(ConnectionPhase.RECONNECTING, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}

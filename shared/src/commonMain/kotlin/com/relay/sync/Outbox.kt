package com.relay.sync

import com.relay.db.MessageStore
import com.relay.model.Message
import com.relay.network.ConnectionState
import com.relay.network.MessageApi
import com.relay.network.MessageApiResult
import com.relay.network.SocketClient
import com.relay.network.sendBackoffMillis
import com.relay.protocol.isoToEpochMillisOrNull
import com.relay.protocol.messageSendFrame
import com.relay.protocol.nowEpochMillis
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val MAX_ATTEMPTS = 10L
private const val MAX_PENDING_AGE_MILLIS = 24 * 60 * 60 * 1000L
private const val UNREACHABLE_RETRY_MILLIS = 10_000L
private const val NOT_FOUND = 404
private val FLUSH_INTERVAL = 5.seconds

class Outbox(
    private val store: MessageStore,
    private val socket: SocketClient,
    private val api: MessageApi,
    private val scope: CoroutineScope,
    private val now: () -> Long = ::nowEpochMillis,
    private val random: Random = Random.Default
) {
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun wake() {
        wakeSignal.trySend(Unit)
    }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            flushOnce()
            withTimeoutOrNull(FLUSH_INTERVAL) { wakeSignal.receive() }
        }
    }

    private suspend fun flushOnce() {
        for (message in store.pendingDue(now())) {
            val clientMsgId = message.clientMsgId ?: continue
            if (exhausted(message)) {
                store.markFailed(message.localId, "retries exhausted")
                continue
            }
            val sentOverSocket = socket.state.value is ConnectionState.Connected &&
                socket.sendFrame(messageSendFrame(clientMsgId, message.dialogId, message.text))
            if (sentOverSocket) {
                store.markAttempt(message.localId, nextRetryAt(message), now())
            } else {
                sendOverRest(message, clientMsgId)
            }
        }
    }

    private suspend fun sendOverRest(message: Message, clientMsgId: String) {
        when (val result = api.sendFallback(clientMsgId, message.dialogId, message.text)) {
            is MessageApiResult.Success -> store.applyAck(
                clientMsgId = clientMsgId,
                serverId = result.value.messageId,
                createdAt = isoToEpochMillisOrNull(result.value.createdAt)
            )
            is MessageApiResult.Rejected ->
                if (result.status == NOT_FOUND) {
                    store.scheduleRetry(message.localId, now() + UNREACHABLE_RETRY_MILLIS, now())
                } else {
                    store.markFailed(message.localId, "rejected with HTTP ${result.status}")
                }
            is MessageApiResult.Unavailable ->
                store.scheduleRetry(message.localId, now() + UNREACHABLE_RETRY_MILLIS, now())
        }
    }

    private fun exhausted(message: Message): Boolean {
        val firstAttemptAt = message.firstAttemptAt
        return message.attemptCount >= MAX_ATTEMPTS ||
            (firstAttemptAt != null && now() - firstAttemptAt > MAX_PENDING_AGE_MILLIS)
    }

    private fun nextRetryAt(message: Message): Long =
        now() + sendBackoffMillis(message.attemptCount.toInt(), random)
}

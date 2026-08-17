package com.relay.push

import com.relay.call.CallEngine
import com.relay.db.MessageStore
import com.relay.network.SocketLifecycle
import com.relay.protocol.isoToEpochMillisOrNull
import com.relay.protocol.nowEpochMillis
import com.relay.repository.UserRepository
import com.relay.repository.UserResult
import com.relay.sync.SyncEngine

private const val DEFAULT_TITLE = "New message"
private const val DEFAULT_BODY = "You have a new message"
private const val UNKNOWN_CALLER = "Unknown caller"

sealed interface PushDisplay {
    data object Suppress : PushDisplay
    data class Message(
        val dialogId: String,
        val messageId: String,
        val title: String,
        val body: String
    ) : PushDisplay

    data class IncomingCall(
        val callId: String,
        val callerId: String,
        val callerName: String,
        val media: String
    ) : PushDisplay

    data class MissedCall(
        val callId: String,
        val callerId: String,
        val callerName: String
    ) : PushDisplay
}

class PushCoordinator(
    private val syncEngine: SyncEngine,
    private val store: MessageStore,
    private val presence: AppPresence,
    private val calls: CallEngine,
    private val connection: SocketLifecycle,
    private val users: UserRepository,
    private val now: () -> Long = ::nowEpochMillis
) {
    suspend fun handle(event: PushEvent, fallbackBody: String? = null): PushDisplay =
        when (event) {
            is PushEvent.NewMessage -> onNewMessage(event, fallbackBody)
            is PushEvent.IncomingCall -> onIncomingCall(event)
            is PushEvent.MissedCall -> onMissedCall(event)
            is PushEvent.Unknown -> PushDisplay.Suppress
        }

    private suspend fun onNewMessage(event: PushEvent.NewMessage, fallbackBody: String?): PushDisplay {
        syncEngine.wakeAndCatchUp(event.dialogId)
        if (presence.isShowing(event.dialogId)) return PushDisplay.Suppress
        val stored = store.findByServerId(event.messageId)
        return PushDisplay.Message(
            dialogId = event.dialogId,
            messageId = event.messageId,
            title = store.dialog(event.dialogId)?.title ?: DEFAULT_TITLE,
            body = stored?.text ?: fallbackBody ?: DEFAULT_BODY
        )
    }

    private suspend fun onIncomingCall(event: PushEvent.IncomingCall): PushDisplay {
        val expiresAt = event.ringExpiresAt?.let { isoToEpochMillisOrNull(it) }
        if (expiresAt != null && expiresAt <= now()) return PushDisplay.Suppress
        calls.start()
        connection.start()
        calls.onIncomingCallPush(
            callId = event.callId,
            callerId = event.callerId,
            media = event.media,
            ringExpiresAt = event.ringExpiresAt
        )
        return PushDisplay.IncomingCall(
            callId = event.callId,
            callerId = event.callerId,
            callerName = nameOf(event.callerId),
            media = event.media
        )
    }

    private suspend fun onMissedCall(event: PushEvent.MissedCall): PushDisplay =
        PushDisplay.MissedCall(
            callId = event.callId,
            callerId = event.callerId,
            callerName = nameOf(event.callerId)
        )

    private suspend fun nameOf(userId: String): String =
        when (val result = users.lookup(userId)) {
            is UserResult.Success -> result.value.displayName
            is UserResult.Failure -> UNKNOWN_CALLER
        }
}

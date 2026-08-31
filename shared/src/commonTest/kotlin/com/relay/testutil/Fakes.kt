package com.relay.testutil

import com.relay.auth.StoredTokens
import com.relay.auth.TokenStore
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import com.relay.network.AuthApi
import com.relay.network.AuthApiResult
import com.relay.network.ConnectionState
import com.relay.network.FallbackSendResponse
import com.relay.network.LoginRequest
import com.relay.network.MessageApi
import com.relay.network.MessageApiResult
import com.relay.network.NotificationApi
import com.relay.network.NotificationApiResult
import com.relay.network.OpenedDialogResponse
import com.relay.network.RegisterDeviceTokenRequest
import com.relay.network.RegisterRequest
import com.relay.network.SocketClient
import com.relay.network.TokenResponse
import com.relay.network.WireDialog
import com.relay.network.WireMessage
import com.relay.network.WireReadStateEntry
import com.relay.protocol.AckPayload
import com.relay.protocol.DialogDeletedPayload
import com.relay.protocol.Envelope
import com.relay.protocol.ErrorPayload
import com.relay.protocol.InboundFrame
import com.relay.protocol.MessageNewPayload
import com.relay.protocol.MessageReadReceiptPayload
import com.relay.protocol.MessageSystemPayload
import com.relay.protocol.PresenceStatusWire
import com.relay.protocol.PresenceUpdatePayload
import com.relay.protocol.TypingReceiptPayload
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

const val TEST_ISO = "2026-07-26T10:00:00Z"

class FakeSocket : SocketClient {
    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = mutableState.asStateFlow()

    private val mutableFrames = MutableSharedFlow<InboundFrame>(extraBufferCapacity = 64)
    override val frames: SharedFlow<InboundFrame> = mutableFrames.asSharedFlow()

    val sentFrames = mutableListOf<Envelope>()
    var sendResult = true

    override suspend fun sendFrame(envelope: Envelope): Boolean {
        sentFrames += envelope
        return sendResult
    }

    fun connect(userId: String = "user-1", sessionId: String = "session-1") {
        mutableState.value = ConnectionState.Connected(userId, sessionId)
    }

    fun disconnect() {
        mutableState.value = ConnectionState.Disconnected
    }

    fun connecting() {
        mutableState.value = ConnectionState.Connecting
    }

    suspend fun emitFrame(frame: InboundFrame) {
        mutableFrames.emit(frame)
    }
}

class FakeMessageApi : MessageApi {
    var dialogsHandler: suspend () -> MessageApiResult<List<WireDialog>> =
        { MessageApiResult.Success(emptyList()) }
    var afterHandler: suspend (String, String, Int) -> MessageApiResult<List<WireMessage>> =
        { _, _, _ -> MessageApiResult.Success(emptyList()) }
    var beforeHandler: suspend (String, String?, Int) -> MessageApiResult<List<WireMessage>> =
        { _, _, _ -> MessageApiResult.Success(emptyList()) }
    var readStateHandler: suspend (String) -> MessageApiResult<List<WireReadStateEntry>> =
        { MessageApiResult.Success(emptyList()) }

    var fallbackHandler: suspend (String, String, String) -> MessageApiResult<FallbackSendResponse> =
        { _, _, _ -> MessageApiResult.Unavailable("rest not reachable") }

    var openDialogHandler: suspend (String) -> MessageApiResult<OpenedDialogResponse> =
        { peerId -> MessageApiResult.Success(OpenedDialogResponse("dialog-for-$peerId", "direct", listOf(peerId), TEST_ISO)) }

    var createGroupHandler: suspend (String, String, List<String>) -> MessageApiResult<WireDialog> =
        { dialogId, title, memberIds ->
            MessageApiResult.Success(
                WireDialog(dialogId = dialogId, type = "group", participantIds = memberIds, title = title)
            )
        }

    var dialogHandler: suspend (String) -> MessageApiResult<WireDialog> =
        { MessageApiResult.Unavailable("no dialog stubbed") }

    var openDialogCalls = 0
    var openDialogPeers = mutableListOf<String>()
    var createGroupCalls = mutableListOf<Triple<String, String, List<String>>>()
    var dialogsCalls = 0
    var afterCalls = 0
    var beforeCalls = 0
    var readStateCalls = 0
    var fallbackCalls = 0

    override suspend fun openDirectDialog(peerId: String): MessageApiResult<OpenedDialogResponse> {
        openDialogCalls++
        openDialogPeers += peerId
        return openDialogHandler(peerId)
    }

    override suspend fun createGroupDialog(
        dialogId: String,
        title: String,
        memberIds: List<String>
    ): MessageApiResult<WireDialog> {
        createGroupCalls += Triple(dialogId, title, memberIds)
        return createGroupHandler(dialogId, title, memberIds)
    }

    override suspend fun dialog(dialogId: String): MessageApiResult<WireDialog> =
        dialogHandler(dialogId)

    override suspend fun dialogs(): MessageApiResult<List<WireDialog>> {
        dialogsCalls++
        return dialogsHandler()
    }

    override suspend fun messagesAfter(
        dialogId: String,
        after: String,
        limit: Int
    ): MessageApiResult<List<WireMessage>> {
        afterCalls++
        return afterHandler(dialogId, after, limit)
    }

    override suspend fun messagesBefore(
        dialogId: String,
        before: String?,
        limit: Int
    ): MessageApiResult<List<WireMessage>> {
        beforeCalls++
        return beforeHandler(dialogId, before, limit)
    }

    override suspend fun readState(dialogId: String): MessageApiResult<List<WireReadStateEntry>> {
        readStateCalls++
        return readStateHandler(dialogId)
    }

    override suspend fun sendFallback(
        clientMsgId: String,
        dialogId: String,
        text: String
    ): MessageApiResult<FallbackSendResponse> {
        fallbackCalls++
        return fallbackHandler(clientMsgId, dialogId, text)
    }
}

class FakeNotificationApi : NotificationApi {
    val registrations = mutableListOf<RegisterDeviceTokenRequest>()
    val unregistrations = mutableListOf<String>()

    var registerHandler: suspend (RegisterDeviceTokenRequest) -> NotificationApiResult =
        { NotificationApiResult.Success }

    override suspend fun registerDevice(request: RegisterDeviceTokenRequest): NotificationApiResult {
        registrations += request
        return registerHandler(request)
    }

    override suspend fun unregisterDevice(deviceId: String): NotificationApiResult {
        unregistrations += deviceId
        return NotificationApiResult.Success
    }
}

class StubAuthApi : AuthApi {
    override suspend fun login(request: LoginRequest): AuthApiResult<TokenResponse> =
        AuthApiResult.Unreachable("stub")

    override suspend fun register(request: RegisterRequest): AuthApiResult<TokenResponse> =
        AuthApiResult.Unreachable("stub")

    override suspend fun refresh(refreshToken: String): AuthApiResult<TokenResponse> =
        AuthApiResult.Unreachable("stub")

    override suspend fun logout(refreshToken: String) = Unit
}

class FakeTokenStore : TokenStore {
    var tokens: StoredTokens? = null

    override suspend fun save(tokens: StoredTokens) {
        this.tokens = tokens
    }

    override suspend fun load(): StoredTokens? = tokens

    override suspend fun clear() {
        tokens = null
    }
}

fun wireMessage(
    id: String,
    dialogId: String = "d1",
    senderId: String = "peer",
    text: String = "text-$id",
    createdAt: String = TEST_ISO,
    clientMsgId: String? = null
): WireMessage = WireMessage(id, dialogId, senderId, text, createdAt, clientMsgId)

fun messageNewFrame(
    id: String,
    dialogId: String = "d1",
    senderId: String = "peer",
    text: String = "text-$id",
    createdAt: String = TEST_ISO,
    clientMsgId: String? = null
): InboundFrame.MessageNew =
    InboundFrame.MessageNew(MessageNewPayload(id, dialogId, senderId, text, createdAt, clientMsgId))

fun ackFrame(
    clientMsgId: String,
    messageId: String,
    createdAt: String = TEST_ISO
): InboundFrame.Ack = InboundFrame.Ack(AckPayload(clientMsgId, messageId, createdAt))

fun readReceiptFrame(
    dialogId: String = "d1",
    userId: String = "peer",
    upToMessageId: String,
    readAt: String = TEST_ISO
): InboundFrame.MessageRead =
    InboundFrame.MessageRead(MessageReadReceiptPayload(dialogId, userId, upToMessageId, readAt))

fun errorFrame(code: String, refId: String?): InboundFrame.Error =
    InboundFrame.Error(ErrorPayload(code = code, message = null, refId = refId))

fun messageSystemFrame(
    id: String,
    dialogId: String = "g1",
    actorId: String = "actor",
    kind: String = "member_added",
    targetUserId: String? = null,
    title: String? = null,
    createdAt: String = TEST_ISO
): InboundFrame.MessageSystem =
    InboundFrame.MessageSystem(
        MessageSystemPayload(id, dialogId, actorId, kind, targetUserId, title, createdAt)
    )

fun dialogDeletedFrame(dialogId: String = "g1", actorId: String = "actor"): InboundFrame.DialogDeleted =
    InboundFrame.DialogDeleted(DialogDeletedPayload(dialogId, actorId))

fun presenceUpdateFrame(
    userId: String = "peer",
    status: String = PresenceStatusWire.ONLINE,
    lastSeen: String? = null
): InboundFrame.PresenceUpdate =
    InboundFrame.PresenceUpdate(PresenceUpdatePayload(userId, status, lastSeen))

fun typingFrame(
    dialogId: String = "d1",
    userId: String = "peer"
): InboundFrame.TypingStart =
    InboundFrame.TypingStart(TypingReceiptPayload(dialogId, userId))

@OptIn(ExperimentalEncodingApi::class)
fun testJwt(subject: String): String {
    val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    val header = encoder.encode("""{"alg":"none"}""".encodeToByteArray())
    val payload = encoder.encode("""{"sub":"$subject"}""".encodeToByteArray())
    return "$header.$payload.sig"
}

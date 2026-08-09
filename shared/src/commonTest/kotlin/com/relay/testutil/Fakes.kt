package com.relay.testutil

import com.relay.auth.StoredTokens
import com.relay.auth.TokenStore
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import com.relay.network.ConnectionState
import com.relay.network.FallbackSendResponse
import com.relay.network.MessageApi
import com.relay.network.MessageApiResult
import com.relay.network.SocketClient
import com.relay.network.WireDialog
import com.relay.network.WireMessage
import com.relay.protocol.AckPayload
import com.relay.protocol.Envelope
import com.relay.protocol.ErrorPayload
import com.relay.protocol.InboundFrame
import com.relay.protocol.MessageNewPayload
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
    var fallbackHandler: suspend (String, String, String) -> MessageApiResult<FallbackSendResponse> =
        { _, _, _ -> MessageApiResult.Unavailable("rest not reachable") }

    var dialogsCalls = 0
    var afterCalls = 0
    var beforeCalls = 0
    var fallbackCalls = 0

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

    override suspend fun sendFallback(
        clientMsgId: String,
        dialogId: String,
        text: String
    ): MessageApiResult<FallbackSendResponse> {
        fallbackCalls++
        return fallbackHandler(clientMsgId, dialogId, text)
    }
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

fun errorFrame(code: String, refId: String?): InboundFrame.Error =
    InboundFrame.Error(ErrorPayload(code = code, message = null, refId = refId))

@OptIn(ExperimentalEncodingApi::class)
fun testJwt(subject: String): String {
    val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    val header = encoder.encode("""{"alg":"none"}""".encodeToByteArray())
    val payload = encoder.encode("""{"sub":"$subject"}""".encodeToByteArray())
    return "$header.$payload.sig"
}

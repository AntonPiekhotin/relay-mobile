package com.relay.sync

import com.relay.db.MessageStore
import com.relay.network.WireMessage
import com.relay.protocol.MessageNewPayload
import com.relay.protocol.isoToEpochMillis

fun MessageNewPayload.toWireMessage(): WireMessage =
    WireMessage(
        messageId = messageId,
        dialogId = dialogId,
        senderId = senderId,
        text = text,
        createdAt = createdAt,
        clientMsgId = clientMsgId
    )

suspend fun MessageStore.applyWireMessage(message: WireMessage) {
    applyRemoteMessage(
        serverId = message.messageId,
        clientMsgId = message.clientMsgId,
        dialogId = message.dialogId,
        senderId = message.senderId,
        text = message.text,
        createdAt = isoToEpochMillis(message.createdAt)
    )
}

package com.relay.sync

import com.relay.db.MessageStore
import com.relay.network.ConnectionState
import com.relay.network.SocketClient
import com.relay.protocol.messageReadFrame

class ReadReceipts(
    private val store: MessageStore,
    private val socket: SocketClient
) {
    suspend fun mark(dialogId: String, upToMessageId: String, readAt: Long) {
        store.markSelfRead(dialogId, upToMessageId, readAt)
        flush()
    }

    suspend fun flush() {
        if (socket.state.value !is ConnectionState.Connected) return
        for (cursor in store.unsentReads()) {
            if (!socket.sendFrame(messageReadFrame(cursor.dialogId, cursor.upToMessageId))) return
            store.markSelfReadSent(cursor.dialogId, cursor.upToMessageId)
        }
    }
}

package com.relay.protocol

object FrameType {
    const val SESSION_CONNECTED = "session.connected"
    const val MESSAGE_SEND = "message.send"
    const val MESSAGE_NEW = "message.new"
    const val ACK = "ack"
    const val ERROR = "error"
    const val PING = "ping"
    const val PONG = "pong"
    const val NOTIFICATION_NEW = "notification.new"
}

object ErrorCode {
    const val BAD_FRAME = "BAD_FRAME"
    const val UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION"
    const val SEND_FAILED = "SEND_FAILED"
    const val DIALOG_NOT_FOUND = "DIALOG_NOT_FOUND"
    const val NOT_A_PARTICIPANT = "NOT_A_PARTICIPANT"
    const val INVALID_REQUEST = "INVALID_REQUEST"
    const val INTERNAL = "INTERNAL"

    val retryable = setOf(SEND_FAILED, INTERNAL)
}

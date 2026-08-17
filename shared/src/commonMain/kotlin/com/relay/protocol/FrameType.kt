package com.relay.protocol

object FrameType {
    const val SESSION_CONNECTED = "session.connected"
    const val MESSAGE_SEND = "message.send"
    const val MESSAGE_NEW = "message.new"
    const val MESSAGE_READ = "message.read"
    const val ACK = "ack"
    const val ERROR = "error"
    const val PING = "ping"
    const val PONG = "pong"
    const val NOTIFICATION_NEW = "notification.new"
    const val CALL_INVITE = "call.invite"
    const val CALL_ACCEPT = "call.accept"
    const val CALL_REJECT = "call.reject"
    const val CALL_ICE = "call.ice"
    const val CALL_HANGUP = "call.hangup"
    const val CALL_SIGNAL = "call.signal"
}

object ErrorCode {
    const val BAD_FRAME = "BAD_FRAME"
    const val UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION"
    const val SEND_FAILED = "SEND_FAILED"
    const val DIALOG_NOT_FOUND = "DIALOG_NOT_FOUND"
    const val NOT_A_PARTICIPANT = "NOT_A_PARTICIPANT"
    const val INVALID_REQUEST = "INVALID_REQUEST"
    const val INTERNAL = "INTERNAL"
    const val USER_BUSY = "USER_BUSY"
    const val CALL_NOT_FOUND = "CALL_NOT_FOUND"
    const val INVALID_CALL_STATE = "INVALID_CALL_STATE"
    const val CALL_SIGNAL_FAILED = "CALL_SIGNAL_FAILED"

    val retryable = setOf(SEND_FAILED, INTERNAL)
}

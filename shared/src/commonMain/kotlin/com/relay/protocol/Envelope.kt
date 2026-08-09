package com.relay.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

const val PROTOCOL_VERSION = 1

@Serializable
data class Envelope(
    val v: Int = PROTOCOL_VERSION,
    val type: String,
    val id: String? = null,
    val ts: Long,
    val payload: JsonElement? = null
)

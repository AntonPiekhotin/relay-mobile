package com.relay.protocol

import kotlinx.serialization.json.Json

val WireJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

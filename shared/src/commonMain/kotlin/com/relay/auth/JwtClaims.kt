package com.relay.auth

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object JwtClaims {

    @OptIn(ExperimentalEncodingApi::class)
    fun subjectOf(jwt: String): String? {
        val parts = jwt.split(".")
        if (parts.size < 2) return null
        return runCatching {
            val decoded = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
                .decode(parts[1])
                .decodeToString()
            Json.parseToJsonElement(decoded).jsonObject["sub"]?.jsonPrimitive?.content
        }.getOrNull()
    }
}

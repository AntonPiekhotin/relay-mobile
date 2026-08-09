package com.relay.auth

import kotlinx.serialization.Serializable

@Serializable
data class StoredTokens(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAtMillis: Long,
    val refreshExpiresAtMillis: Long
)

interface TokenStore {
    suspend fun save(tokens: StoredTokens)
    suspend fun load(): StoredTokens?
    suspend fun clear()
}

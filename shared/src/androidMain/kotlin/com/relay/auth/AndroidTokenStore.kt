package com.relay.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

private const val PREFS_FILE = "relay_secure"
private const val KEY_TOKENS = "session_tokens"

class AndroidTokenStore(context: Context) : TokenStore {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override suspend fun save(tokens: StoredTokens) = withContext(Dispatchers.IO) {
        prefs.edit()
            .putString(KEY_TOKENS, Json.encodeToString(StoredTokens.serializer(), tokens))
            .apply()
    }

    override suspend fun load(): StoredTokens? = withContext(Dispatchers.IO) {
        prefs.getString(KEY_TOKENS, null)?.let {
            runCatching { Json.decodeFromString(StoredTokens.serializer(), it) }.getOrNull()
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        prefs.edit().remove(KEY_TOKENS).apply()
    }
}

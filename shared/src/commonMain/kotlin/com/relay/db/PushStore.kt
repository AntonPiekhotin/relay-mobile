package com.relay.db

import com.relay.protocol.newFrameId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

data class PushDevice(
    val deviceId: String,
    val platform: String,
    val fcmToken: String?,
    val voipToken: String?,
    val registeredUser: String?,
    val registeredFcm: String?,
    val registeredVoip: String?
) {
    fun isRegisteredFor(userId: String): Boolean =
        registeredUser == userId && registeredFcm == fcmToken && registeredVoip == voipToken

    fun hasAnyToken(): Boolean = fcmToken != null || voipToken != null
}

class PushStore(
    private val db: RelayDb,
    private val dispatcher: CoroutineDispatcher,
    private val platform: String,
    private val newDeviceId: () -> String = ::newFrameId
) {
    suspend fun device(): PushDevice = withContext(dispatcher) {
        db.transactionWithResult {
            ensureRow()
            db.push_deviceQueries.select().executeAsOne().toDomain()
        }
    }

    suspend fun setFcmToken(token: String): Unit = withContext(dispatcher) {
        db.transaction {
            ensureRow()
            db.push_deviceQueries.setFcmToken(token)
        }
    }

    suspend fun setVoipToken(token: String): Unit = withContext(dispatcher) {
        db.transaction {
            ensureRow()
            db.push_deviceQueries.setVoipToken(token)
        }
    }

    suspend fun markRegistered(userId: String, fcmToken: String?, voipToken: String?): Unit =
        withContext(dispatcher) {
            db.transaction {
                ensureRow()
                db.push_deviceQueries.markRegistered(userId, fcmToken, voipToken)
            }
        }

    suspend fun clearRegistration(): Unit = withContext(dispatcher) {
        db.push_deviceQueries.clearRegistration()
    }

    private fun ensureRow() {
        db.push_deviceQueries.insertIfAbsent(newDeviceId(), platform)
    }
}

private fun Push_device.toDomain(): PushDevice =
    PushDevice(
        deviceId = device_id,
        platform = platform,
        fcmToken = fcm_token,
        voipToken = voip_token,
        registeredUser = registered_user,
        registeredFcm = registered_fcm,
        registeredVoip = registered_voip
    )

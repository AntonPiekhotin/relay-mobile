package com.relay.push

import com.relay.auth.SessionManager
import com.relay.auth.StoredTokens
import com.relay.db.PushStore
import com.relay.network.NotificationApiResult
import com.relay.testutil.FakeNotificationApi
import com.relay.testutil.FakeTokenStore
import com.relay.testutil.StubAuthApi
import com.relay.testutil.createTestDb
import com.relay.testutil.testJwt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private const val SELF = "user-1"

@OptIn(ExperimentalCoroutinesApi::class)
private class RegistrarHarness(scope: TestScope) {
    val api = FakeNotificationApi()
    val tokenStore = FakeTokenStore()
    val session = SessionManager(StubAuthApi(), tokenStore)
    val store = PushStore(
        db = createTestDb(),
        dispatcher = UnconfinedTestDispatcher(scope.testScheduler),
        platform = "android",
        newDeviceId = { "device-1" }
    )
    val registrar = DeviceTokenRegistrar(
        store = store,
        api = api,
        session = session,
        scope = scope.backgroundScope
    )

    suspend fun logIn(userId: String = SELF) {
        tokenStore.save(
            StoredTokens(
                accessToken = testJwt(userId),
                refreshToken = "refresh",
                accessExpiresAtMillis = Long.MAX_VALUE / 2,
                refreshExpiresAtMillis = Long.MAX_VALUE / 2
            )
        )
        session.restoreSession()
    }
}

private fun TestScope.settle() {
    advanceTimeBy(100)
    runCurrent()
}

class DeviceTokenRegistrarTest {

    @Test
    fun registersTheDeviceOnceWhenATokenArrivesAfterLogin() = runTest {
        val harness = RegistrarHarness(this)
        harness.logIn()
        harness.registrar.start()
        settle()

        harness.registrar.onFcmToken("fcm-1")
        settle()

        assertEquals(1, harness.api.registrations.size)
        val request = harness.api.registrations.single()
        assertEquals("device-1", request.deviceId)
        assertEquals("android", request.platform)
        assertEquals("fcm-1", request.fcmToken)
    }

    @Test
    fun aTokenThatArrivesBeforeLoginIsRegisteredAfterAuthentication() = runTest {
        val harness = RegistrarHarness(this)
        harness.registrar.start()
        settle()

        harness.registrar.onFcmToken("fcm-1")
        settle()
        assertTrue(harness.api.registrations.isEmpty())

        harness.logIn()
        settle()

        assertEquals(1, harness.api.registrations.size)
        assertEquals("fcm-1", harness.api.registrations.single().fcmToken)
    }

    @Test
    fun anUnchangedRegistrationIsNotResent() = runTest {
        val harness = RegistrarHarness(this)
        harness.logIn()
        harness.registrar.start()
        settle()
        harness.registrar.onFcmToken("fcm-1")
        settle()

        harness.registrar.wake()
        harness.registrar.onFcmToken("fcm-1")
        settle()

        assertEquals(1, harness.api.registrations.size)
    }

    @Test
    fun aRotatedTokenIsReRegistered() = runTest {
        val harness = RegistrarHarness(this)
        harness.logIn()
        harness.registrar.start()
        settle()
        harness.registrar.onFcmToken("fcm-1")
        settle()

        harness.registrar.onFcmToken("fcm-2")
        settle()

        assertEquals(listOf("fcm-1", "fcm-2"), harness.api.registrations.map { it.fcmToken })
    }

    @Test
    fun anUnreachableServerIsRetriedUntilItSucceeds() = runTest {
        val harness = RegistrarHarness(this)
        var attempts = 0
        harness.api.registerHandler = {
            attempts++
            if (attempts == 1) NotificationApiResult.Unavailable("offline") else NotificationApiResult.Success
        }
        harness.logIn()
        harness.registrar.start()
        settle()
        harness.registrar.onFcmToken("fcm-1")
        settle()
        assertEquals(1, harness.api.registrations.size)

        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(2, harness.api.registrations.size)
        assertEquals(SELF, harness.store.device().registeredUser)
    }

    @Test
    fun aRejectedRegistrationIsNotRetried() = runTest {
        val harness = RegistrarHarness(this)
        harness.api.registerHandler = { NotificationApiResult.Rejected(400) }
        harness.logIn()
        harness.registrar.start()
        settle()
        harness.registrar.onFcmToken("fcm-1")
        settle()
        assertEquals(1, harness.api.registrations.size)

        advanceTimeBy(120_000)
        runCurrent()

        assertEquals(1, harness.api.registrations.size)
    }

    @Test
    fun logoutUnregistersTheDeviceAndClearsTheLocalRegistration() = runTest {
        val harness = RegistrarHarness(this)
        harness.logIn()
        harness.registrar.start()
        settle()
        harness.registrar.onFcmToken("fcm-1")
        settle()

        harness.registrar.unregister()

        assertEquals(listOf("device-1"), harness.api.unregistrations)
        val device = harness.store.device()
        assertEquals(null, device.registeredUser)
        assertEquals("fcm-1", device.fcmToken)
    }

    @Test
    fun aTokenStoredBeforeAnyRegistrationKeepsTheSameDeviceId() = runTest {
        val harness = RegistrarHarness(this)
        harness.registrar.onFcmToken("fcm-1")
        assertEquals("device-1", harness.registrar.deviceId())
        assertEquals("fcm-1", harness.store.device().fcmToken)
    }
}

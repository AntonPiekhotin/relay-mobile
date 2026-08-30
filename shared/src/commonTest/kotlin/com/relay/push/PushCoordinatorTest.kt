package com.relay.push

import com.relay.call.CallEngine
import com.relay.call.GroupCallEngine
import com.relay.db.MessageStore
import com.relay.network.MessageApiResult
import com.relay.sync.Outbox
import com.relay.sync.ReadReceipts
import com.relay.sync.SyncEngine
import com.relay.testutil.FakeCallApi
import com.relay.testutil.FakeGroupCallApi
import com.relay.testutil.FakeMessageApi
import com.relay.testutil.FakeRtcClientFactory
import com.relay.testutil.FakeSfuClientFactory
import com.relay.testutil.FakeSocket
import com.relay.testutil.FakeSocketLifecycle
import com.relay.testutil.FakeUserRepository
import com.relay.testutil.createTestDb
import com.relay.testutil.wireMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private const val DIALOG = "d1"
private const val SELF = "me"

private class CoordinatorHarness(scope: TestScope) {
    val store = MessageStore(createTestDb(), UnconfinedTestDispatcher(scope.testScheduler))
    val socket = FakeSocket()
    val api = FakeMessageApi()
    val presence = AppPresence()
    val outbox = Outbox(
        store = store,
        socket = socket,
        api = api,
        scope = scope.backgroundScope,
        now = { scope.testScheduler.currentTime }
    )
    val engine = SyncEngine(
        store = store,
        socket = socket,
        api = api,
        outbox = outbox,
        readReceipts = ReadReceipts(store, socket),
        scope = scope.backgroundScope,
        authenticatedUserId = { SELF }
    )
    val calls = CallEngine(
        socket = socket,
        api = FakeCallApi(),
        rtcFactory = FakeRtcClientFactory(),
        scope = scope.backgroundScope,
        now = { scope.testScheduler.currentTime }
    )
    val groupCalls = GroupCallEngine(
        socket = socket,
        api = FakeGroupCallApi(),
        sfuFactory = FakeSfuClientFactory(),
        scope = scope.backgroundScope,
        now = { scope.testScheduler.currentTime }
    )
    val lifecycle = FakeSocketLifecycle()
    val users = FakeUserRepository()
    val coordinator = PushCoordinator(
        syncEngine = engine,
        store = store,
        presence = presence,
        calls = calls,
        groupCalls = groupCalls,
        connection = lifecycle,
        users = users,
        now = { scope.testScheduler.currentTime }
    )

    fun serveHistory(vararg messages: String) {
        api.beforeHandler = { _, _, _ ->
            MessageApiResult.Success(messages.map { wireMessage(it, dialogId = DIALOG) })
        }
    }
}

class PushCoordinatorTest {

    @Test
    fun aMessagePushCatchesUpOverRestAndAsksForANotification() = runTest {
        val harness = CoordinatorHarness(this)
        harness.serveHistory("srv-1")

        val display = harness.coordinator.handle(
            PushEvent.NewMessage(DIALOG, "srv-1", "peer")
        )

        assertEquals(1, harness.store.countMessages(DIALOG))
        val message = assertIs<PushDisplay.Message>(display)
        assertEquals(DIALOG, message.dialogId)
        assertEquals("text-srv-1", message.body)
    }

    @Test
    fun theDialogTitleIsUsedAsTheNotificationTitleOnceKnown() = runTest {
        val harness = CoordinatorHarness(this)
        harness.store.upsertDialog(DIALOG, "direct", "Ada Lovelace", 1, peerId = "peer")
        harness.serveHistory("srv-1")

        val display = harness.coordinator.handle(PushEvent.NewMessage(DIALOG, "srv-1", "peer"))

        assertEquals("Ada Lovelace", assertIs<PushDisplay.Message>(display).title)
    }

    @Test
    fun noNotificationIsPostedWhileThatChatIsOnScreen() = runTest {
        val harness = CoordinatorHarness(this)
        harness.serveHistory("srv-1")
        harness.presence.onForeground()
        harness.presence.onDialogOpened(DIALOG)

        val display = harness.coordinator.handle(PushEvent.NewMessage(DIALOG, "srv-1", "peer"))

        assertEquals(PushDisplay.Suppress, display)
        assertEquals(1, harness.store.countMessages(DIALOG))
    }

    @Test
    fun anotherDialogBeingOpenStillNotifies() = runTest {
        val harness = CoordinatorHarness(this)
        harness.serveHistory("srv-1")
        harness.presence.onForeground()
        harness.presence.onDialogOpened("other")

        val display = harness.coordinator.handle(PushEvent.NewMessage(DIALOG, "srv-1", "peer"))

        assertIs<PushDisplay.Message>(display)
    }

    @Test
    fun aBackgroundedAppNotifiesEvenForTheLastOpenDialog() = runTest {
        val harness = CoordinatorHarness(this)
        harness.serveHistory("srv-1")
        harness.presence.onDialogOpened(DIALOG)
        harness.presence.onBackground()

        val display = harness.coordinator.handle(PushEvent.NewMessage(DIALOG, "srv-1", "peer"))

        assertIs<PushDisplay.Message>(display)
    }

    @Test
    fun theSenderIsRecordedAsThePeerSoTheDialogCanBeNamed() = runTest {
        val harness = CoordinatorHarness(this)
        harness.serveHistory("srv-1")

        harness.coordinator.handle(PushEvent.NewMessage(DIALOG, "srv-1", "peer"))

        assertEquals("peer", harness.store.observeDialogs().first().single().peerId)
    }

    @Test
    fun anUnreachableServerStillNotifiesFromThePushPayload() = runTest {
        val harness = CoordinatorHarness(this)
        harness.api.beforeHandler = { _, _, _ -> MessageApiResult.Unavailable("offline") }

        val display = harness.coordinator.handle(
            PushEvent.NewMessage(DIALOG, "srv-1", "peer"),
            fallbackBody = "hello from the push"
        )

        assertEquals("hello from the push", assertIs<PushDisplay.Message>(display).body)
    }

    @Test
    fun aCallPushOpensTheSocketAndAsksForARingingNotification() = runTest {
        val harness = CoordinatorHarness(this)

        val display = harness.coordinator.handle(
            PushEvent.IncomingCall("c1", "peer", "audio", null)
        )

        runCurrent()

        val incoming = assertIs<PushDisplay.IncomingCall>(display)
        assertEquals("c1", incoming.callId)
        assertEquals(1, harness.lifecycle.starts)
        assertEquals("c1", harness.calls.session.value?.callId)
    }

    @Test
    fun aCallPushThatAlreadyRangOutIsDropped() = runTest {
        val harness = CoordinatorHarness(this)

        val display = harness.coordinator.handle(
            PushEvent.IncomingCall("c2", "peer", "audio", ringExpiresAt = "1970-01-01T00:00:00Z")
        )

        assertEquals(PushDisplay.Suppress, display)
        assertEquals(0, harness.lifecycle.starts)
    }

    @Test
    fun aMissedCallPushAsksForANotification() = runTest {
        val harness = CoordinatorHarness(this)

        val display = harness.coordinator.handle(PushEvent.MissedCall("c3", "peer", "audio"))

        assertEquals("c3", assertIs<PushDisplay.MissedCall>(display).callId)
    }
}

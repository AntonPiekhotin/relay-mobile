package com.relay.call

import com.relay.network.GroupCallApiResult
import com.relay.protocol.CallEndReason
import com.relay.protocol.CallSignal
import com.relay.protocol.GroupParticipantState
import com.relay.protocol.GroupParticipantWire
import com.relay.testutil.FakeGroupCallApi
import com.relay.testutil.FakeSfuClientFactory
import com.relay.testutil.FakeSocket
import com.relay.testutil.groupCallResponse
import com.relay.testutil.signalFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private const val SELF = "me"
private const val CALLER = "caller-1"
private const val PEER_A = "peer-a"
private const val PEER_B = "peer-b"
private const val CALL_ID = "group-call-1"

@OptIn(ExperimentalCoroutinesApi::class)
private class GroupHarness(scope: TestScope, otherCallActive: () -> Boolean = { false }) {
    val socket = FakeSocket()
    val api = FakeGroupCallApi()
    val sfu = FakeSfuClientFactory()
    val engine = GroupCallEngine(
        socket = socket,
        api = api,
        sfuFactory = sfu,
        scope = scope.backgroundScope,
        otherCallActive = otherCallActive,
        now = { scope.testScheduler.currentTime }
    )

    fun startConnected() {
        engine.start()
        socket.connect(userId = SELF)
    }

    suspend fun emitGroupInvite(
        callId: String = CALL_ID,
        participants: List<GroupParticipantWire> = listOf(
            GroupParticipantWire(CALLER, GroupParticipantState.JOINED),
            GroupParticipantWire(SELF, GroupParticipantState.INVITED),
            GroupParticipantWire(PEER_A, GroupParticipantState.INVITED)
        ),
        ringExpiresAt: String? = null
    ) {
        socket.emitFrame(
            signalFrame(
                callId,
                CALLER,
                CallSignal.GroupInvite(
                    media = "audio",
                    startedAt = null,
                    ringExpiresAt = ringExpiresAt,
                    participants = participants
                )
            )
        )
    }
}

class GroupCallEngineTest {

    @Test
    fun placingAGroupCallCreatesItOverRestAndConnectsToTheSfu() = runTest {
        val harness = GroupHarness(this)
        harness.startConnected()
        runCurrent()

        val result = harness.engine.place(listOf(PEER_A, PEER_B))
        runCurrent()

        assertIs<PlaceCallResult.Started>(result)
        val (callId, invitees) = harness.api.creates.single()
        assertEquals(result.callId, callId)
        assertEquals(listOf(PEER_A, PEER_B), invitees)
        assertEquals("token-$callId", harness.sfu.last().token)
        assertEquals(GroupCallStage.ACTIVE, harness.engine.session.value?.stage)
        assertEquals(2, harness.engine.session.value?.others?.size)
    }

    @Test
    fun aFailedCreateEndsTheCallWithTheFailure() = runTest {
        val harness = GroupHarness(this)
        harness.startConnected()
        runCurrent()
        harness.api.createHandler = { _, _, _, _ -> GroupCallApiResult.Failure(409, "You are already in a call") }

        harness.engine.place(listOf(PEER_A))
        runCurrent()

        val session = harness.engine.session.value
        assertEquals(GroupCallStage.ENDED, session?.stage)
        assertEquals("You are already in a call", session?.failure)
        assertTrue(harness.sfu.created.isEmpty())
    }

    @Test
    fun aGroupInviteRingsWithTheRoster() = runTest {
        val harness = GroupHarness(this)
        harness.startConnected()
        runCurrent()

        harness.emitGroupInvite()
        runCurrent()

        val session = harness.engine.session.value
        assertEquals(GroupCallStage.INCOMING, session?.stage)
        assertEquals(CALLER, session?.initiatorId)
        assertEquals(3, session?.participants?.size)
    }

    @Test
    fun acceptingJoinsOverRestAndConnectsToTheSfu() = runTest {
        val harness = GroupHarness(this)
        harness.startConnected()
        runCurrent()
        harness.emitGroupInvite()
        runCurrent()

        harness.engine.accept()
        runCurrent()

        assertEquals(listOf(CALL_ID), harness.api.joins)
        assertEquals("token-$CALL_ID", harness.sfu.last().token)
        assertEquals(GroupCallStage.ACTIVE, harness.engine.session.value?.stage)
    }

    @Test
    fun decliningTellsTheServerAndEndsLocally() = runTest {
        val harness = GroupHarness(this)
        harness.startConnected()
        runCurrent()
        harness.emitGroupInvite()
        runCurrent()

        harness.engine.decline()
        runCurrent()

        assertEquals(CALL_ID to CallEndReason.DECLINED, harness.api.declines.single())
        assertEquals(GroupCallStage.ENDED, harness.engine.session.value?.stage)
        assertEquals(CallEndReason.DECLINED, harness.engine.session.value?.endReason)
    }

    @Test
    fun leavingAnActiveCallTellsTheServerAndClosesTheSfu() = runTest {
        val harness = GroupHarness(this)
        harness.startConnected()
        runCurrent()
        harness.engine.place(listOf(PEER_A))
        runCurrent()

        harness.engine.leave()
        runCurrent()

        assertEquals(1, harness.api.leaves.size)
        assertEquals(1, harness.sfu.last().closedCount)
        assertEquals(GroupCallStage.ENDED, harness.engine.session.value?.stage)
        advanceTimeBy(ENDED_VISIBLE_MILLIS + 1)
        runCurrent()
        assertNull(harness.engine.session.value)
    }

    @Test
    fun rosterDeltasUpdateTheParticipants() = runTest {
        val harness = GroupHarness(this)
        harness.startConnected()
        runCurrent()
        val started = harness.engine.place(listOf(PEER_A, PEER_B)) as PlaceCallResult.Started
        runCurrent()

        harness.socket.emitFrame(signalFrame(started.callId, PEER_A, CallSignal.ParticipantJoined(PEER_A)))
        harness.socket.emitFrame(
            signalFrame(started.callId, PEER_B, CallSignal.ParticipantDeclined(PEER_B, null))
        )
        runCurrent()

        val byId = harness.engine.session.value!!.participants.associate { it.userId to it.state }
        assertEquals(GroupParticipantState.JOINED, byId[PEER_A])
        assertEquals(GroupParticipantState.DECLINED, byId[PEER_B])
    }

    @Test
    fun groupEndedEndsTheCallForEveryone() = runTest {
        val harness = GroupHarness(this)
        harness.startConnected()
        runCurrent()
        val started = harness.engine.place(listOf(PEER_A)) as PlaceCallResult.Started
        runCurrent()

        harness.socket.emitFrame(
            signalFrame(started.callId, CALLER, CallSignal.GroupEnded(CallEndReason.ALL_LEFT, 12))
        )
        runCurrent()

        assertEquals(GroupCallStage.ENDED, harness.engine.session.value?.stage)
        assertEquals(CallEndReason.ALL_LEFT, harness.engine.session.value?.endReason)
        assertEquals(1, harness.sfu.last().closedCount)
        assertTrue(harness.api.leaves.isEmpty())
    }

    @Test
    fun beingMissedWhileRingingEndsTheCall() = runTest {
        val harness = GroupHarness(this)
        harness.startConnected()
        runCurrent()
        harness.emitGroupInvite()
        runCurrent()

        harness.socket.emitFrame(signalFrame(CALL_ID, CALLER, CallSignal.ParticipantMissed(SELF)))
        runCurrent()

        assertEquals(GroupCallStage.ENDED, harness.engine.session.value?.stage)
        assertEquals(CallEndReason.RING_TIMEOUT, harness.engine.session.value?.endReason)
    }

    @Test
    fun anotherInviteeRingingOutIsOnlyARosterDelta() = runTest {
        val harness = GroupHarness(this)
        harness.startConnected()
        runCurrent()
        val started = harness.engine.place(listOf(PEER_A, PEER_B)) as PlaceCallResult.Started
        runCurrent()
        harness.socket.emitFrame(signalFrame(started.callId, PEER_A, CallSignal.ParticipantJoined(PEER_A)))
        runCurrent()

        harness.socket.emitFrame(signalFrame(started.callId, CALLER, CallSignal.ParticipantMissed(PEER_B)))
        runCurrent()

        val session = harness.engine.session.value!!
        assertEquals(GroupCallStage.ACTIVE, session.stage)
        assertEquals(
            GroupParticipantState.MISSED,
            session.participants.first { it.userId == PEER_B }.state
        )
    }

    @Test
    fun cancelFromAnotherDeviceStopsTheRinging() = runTest {
        val harness = GroupHarness(this)
        harness.startConnected()
        runCurrent()
        harness.emitGroupInvite()
        runCurrent()

        harness.socket.emitFrame(
            signalFrame(CALL_ID, SELF, CallSignal.Cancel(CallEndReason.SETTLED_ELSEWHERE))
        )
        runCurrent()

        assertEquals(GroupCallStage.ENDED, harness.engine.session.value?.stage)
        assertEquals(CallEndReason.SETTLED_ELSEWHERE, harness.engine.session.value?.endReason)
    }

    @Test
    fun anInviteWhileBusyIsDeclinedAsBusy() = runTest {
        val harness = GroupHarness(this, otherCallActive = { true })
        harness.startConnected()
        runCurrent()

        harness.emitGroupInvite()
        runCurrent()

        assertNull(harness.engine.session.value)
        assertEquals(CALL_ID to "busy", harness.api.declines.single())
    }

    @Test
    fun placingWhileAnotherCallIsLiveIsRejected() = runTest {
        val harness = GroupHarness(this, otherCallActive = { true })
        harness.startConnected()
        runCurrent()

        val result = harness.engine.place(listOf(PEER_A))
        runCurrent()

        assertIs<PlaceCallResult.Rejected>(result)
        assertTrue(harness.api.creates.isEmpty())
    }

    @Test
    fun ringingOutLocallyEndsAnUnansweredIncomingCall() = runTest {
        val harness = GroupHarness(this)
        harness.startConnected()
        runCurrent()
        harness.emitGroupInvite()
        runCurrent()

        advanceTimeBy(DEFAULT_RING_MILLIS + 1)
        runCurrent()

        assertEquals(GroupCallStage.ENDED, harness.engine.session.value?.stage)
        assertEquals(CallEndReason.RING_TIMEOUT, harness.engine.session.value?.endReason)
    }

    @Test
    fun aPushWokenRingFetchesTheRosterOverRest() = runTest {
        val harness = GroupHarness(this)
        harness.startConnected()
        runCurrent()

        harness.engine.onIncomingGroupCallPush(CALL_ID, CALLER, "audio", null)
        runCurrent()

        assertEquals(GroupCallStage.INCOMING, harness.engine.session.value?.stage)
        assertEquals(1, harness.api.describes)
        assertEquals(2, harness.engine.session.value?.participants?.size)
    }

    @Test
    fun aTerminalDescribeEndsTheCall() = runTest {
        val harness = GroupHarness(this)
        harness.startConnected()
        runCurrent()
        val started = harness.engine.place(listOf(PEER_A)) as PlaceCallResult.Started
        runCurrent()
        harness.api.describeHandler = { callId ->
            GroupCallApiResult.Success(
                groupCallResponse(
                    callId = callId,
                    status = "ended",
                    initiator = SELF,
                    endReason = CallEndReason.ALL_LEFT
                )
            )
        }

        harness.socket.disconnect()
        runCurrent()
        assertEquals(GroupCallStage.ACTIVE, harness.engine.session.value?.stage)

        harness.socket.connect(userId = SELF)
        runCurrent()

        assertEquals(GroupCallStage.ENDED, harness.engine.session.value?.stage)
        assertEquals(CallEndReason.ALL_LEFT, harness.engine.session.value?.endReason)
        assertEquals(started.callId, harness.engine.session.value?.callId)
    }

    @Test
    fun anSfuFailureEndsTheCallAndTellsTheServer() = runTest {
        val harness = GroupHarness(this)
        harness.startConnected()
        runCurrent()
        harness.engine.place(listOf(PEER_A))
        runCurrent()

        harness.sfu.last().emitFailure("simulated media failure")
        runCurrent()

        val session = harness.engine.session.value
        assertEquals(GroupCallStage.ENDED, session?.stage)
        assertEquals("simulated media failure", session?.failure)
        assertEquals(1, harness.api.leaves.size)
    }
}

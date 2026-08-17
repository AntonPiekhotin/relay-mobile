package com.relay.call

import com.relay.protocol.CallEndReason
import com.relay.protocol.CallSignal
import com.relay.protocol.ErrorCode
import com.relay.protocol.ErrorPayload
import com.relay.protocol.FrameType
import com.relay.protocol.InboundFrame
import com.relay.testutil.FakeCallApi
import com.relay.testutil.FakeRtcClientFactory
import com.relay.testutil.FakeSocket
import com.relay.testutil.TEST_ANSWER
import com.relay.testutil.TEST_OFFER
import com.relay.testutil.candidateJson
import com.relay.testutil.inviteSignal
import com.relay.testutil.signalFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val PEER = "peer-1"
private const val DIALOG = "dialog-1"

@OptIn(ExperimentalCoroutinesApi::class)
private class CallHarness(scope: TestScope) {
    val socket = FakeSocket()
    val api = FakeCallApi()
    val rtc = FakeRtcClientFactory()
    val engine = CallEngine(
        socket = socket,
        api = api,
        rtcFactory = rtc,
        scope = scope.backgroundScope,
        now = { scope.testScheduler.currentTime }
    )

    fun startConnected() {
        engine.start()
        socket.connect()
    }

    fun framesOfType(type: String) = socket.sentFrames.filter { it.type == type }
}

class CallEngineTest {

    @Test
    fun placingACallSendsAnInviteCarryingTheLocalOffer() = runTest {
        val harness = CallHarness(this)
        harness.startConnected()
        runCurrent()

        val result = harness.engine.place(PEER, DIALOG)
        runCurrent()

        assertIs<PlaceCallResult.Started>(result)
        val invite = harness.framesOfType(FrameType.CALL_INVITE).single()
        val payload = assertNotNull(invite.payload).jsonObject
        assertEquals(result.callId, payload["call_id"]?.jsonPrimitive?.content)
        assertNotNull(invite.id)
        assertEquals(PEER, payload["callee_id"]?.jsonPrimitive?.content)
        assertEquals(TEST_OFFER, payload["sdp"]?.jsonPrimitive?.content)
        assertEquals(DIALOG, payload["dialog_id"]?.jsonPrimitive?.content)
        assertEquals(CallStage.RINGING, harness.engine.session.value?.stage)
    }

    @Test
    fun theInviteReusesOneCallIdAcrossRetries() = runTest {
        val harness = CallHarness(this)
        harness.startConnected()
        runCurrent()
        val started = harness.engine.place(PEER, DIALOG)
        runCurrent()

        advanceTimeBy(INVITE_RETRY_MILLIS + 1)
        runCurrent()

        val invites = harness.framesOfType(FrameType.CALL_INVITE)
        assertEquals(2, invites.size)
        val callIds = invites.map { it.payload!!.jsonObject["call_id"]?.jsonPrimitive?.content }
        assertEquals(listOf((started as PlaceCallResult.Started).callId), callIds.distinct())
    }

    @Test
    fun acceptingTheRemoteAnswerHandsTheSdpToTheMediaLayer() = runTest {
        val harness = CallHarness(this)
        harness.startConnected()
        runCurrent()
        val started = harness.engine.place(PEER, DIALOG) as PlaceCallResult.Started
        runCurrent()

        harness.socket.emitFrame(
            signalFrame(started.callId, PEER, CallSignal.Accept(TEST_ANSWER))
        )
        runCurrent()

        assertEquals(TEST_ANSWER, harness.rtc.last().answerFromPeer)
        assertEquals(CallStage.CONNECTING, harness.engine.session.value?.stage)

        harness.rtc.last().emitConnected()
        runCurrent()
        assertEquals(CallStage.ACTIVE, harness.engine.session.value?.stage)
    }

    @Test
    fun localCandidatesGoOutAsIceFrames() = runTest {
        val harness = CallHarness(this)
        harness.startConnected()
        runCurrent()
        harness.engine.place(PEER, DIALOG)
        runCurrent()

        harness.rtc.last().emitCandidate("candidate:9 1 udp")
        runCurrent()

        val ice = harness.framesOfType(FrameType.CALL_ICE).single()
        val candidate = ice.payload!!.jsonObject["candidate"]!!.jsonObject
        assertEquals("candidate:9 1 udp", candidate["candidate"]?.jsonPrimitive?.content)
    }

    @Test
    fun remoteCandidatesArrivingBeforeTheAnswerAreBufferedThenFlushed() = runTest {
        val harness = CallHarness(this)
        harness.startConnected()
        runCurrent()
        val started = harness.engine.place(PEER, DIALOG) as PlaceCallResult.Started
        runCurrent()

        harness.socket.emitFrame(
            signalFrame(started.callId, PEER, CallSignal.Ice(candidateJson("candidate:early")))
        )
        runCurrent()
        assertTrue(harness.rtc.last().remoteCandidates.isEmpty())

        harness.socket.emitFrame(
            signalFrame(started.callId, PEER, CallSignal.Accept(TEST_ANSWER))
        )
        runCurrent()

        assertEquals(1, harness.rtc.last().remoteCandidates.size)
        assertTrue(harness.rtc.last().remoteCandidates.single().contains("candidate:early"))
    }

    @Test
    fun anIncomingInviteRingsAndAcceptingAnswersWithTheLocalSdp() = runTest {
        val harness = CallHarness(this)
        harness.startConnected()
        runCurrent()

        harness.socket.emitFrame(inviteSignal("call-9", PEER, dialogId = DIALOG))
        runCurrent()

        val ringing = assertNotNull(harness.engine.session.value)
        assertEquals(CallStage.INCOMING, ringing.stage)
        assertEquals(CallDirection.INCOMING, ringing.direction)

        harness.engine.accept()
        runCurrent()

        assertEquals(TEST_OFFER, harness.rtc.last().offerFromPeer)
        val accept = harness.framesOfType(FrameType.CALL_ACCEPT).single()
        assertEquals(TEST_ANSWER, accept.payload!!.jsonObject["sdp"]?.jsonPrimitive?.content)
    }

    @Test
    fun decliningSendsARejectAndEndsTheCall() = runTest {
        val harness = CallHarness(this)
        harness.startConnected()
        runCurrent()
        harness.socket.emitFrame(inviteSignal("call-9", PEER))
        runCurrent()

        harness.engine.reject()
        runCurrent()

        assertEquals(1, harness.framesOfType(FrameType.CALL_REJECT).size)
        assertEquals(CallStage.ENDED, harness.engine.session.value?.stage)
        assertEquals(CallEndReason.DECLINED, harness.engine.session.value?.endReason)
    }

    @Test
    fun aPushWokenCallAcceptsAsSoonAsTheInviteArrives() = runTest {
        val harness = CallHarness(this)
        harness.startConnected()
        runCurrent()

        harness.engine.onIncomingCallPush("call-7", PEER, "audio", null)
        runCurrent()
        harness.engine.accept()
        runCurrent()

        assertEquals(CallStage.CONNECTING, harness.engine.session.value?.stage)
        assertTrue(harness.framesOfType(FrameType.CALL_ACCEPT).isEmpty())

        harness.socket.emitFrame(inviteSignal("call-7", PEER))
        runCurrent()

        assertEquals(1, harness.framesOfType(FrameType.CALL_ACCEPT).size)
        assertEquals(TEST_OFFER, harness.rtc.last().offerFromPeer)
    }

    @Test
    fun aHangupFromTheOtherPartyEndsTheCallWithoutSendingAnything() = runTest {
        val harness = CallHarness(this)
        harness.startConnected()
        runCurrent()
        val started = harness.engine.place(PEER, DIALOG) as PlaceCallResult.Started
        runCurrent()
        harness.socket.emitFrame(signalFrame(started.callId, PEER, CallSignal.Accept(TEST_ANSWER)))
        runCurrent()

        harness.socket.emitFrame(
            signalFrame(started.callId, PEER, CallSignal.Hangup(CallEndReason.HANGUP, 12))
        )
        runCurrent()

        assertEquals(CallStage.ENDED, harness.engine.session.value?.stage)
        assertTrue(harness.framesOfType(FrameType.CALL_HANGUP).isEmpty())
        assertEquals(1, harness.rtc.last().closedCount)
    }

    @Test
    fun cancelFromAnotherDeviceStopsTheRinging() = runTest {
        val harness = CallHarness(this)
        harness.startConnected()
        runCurrent()
        harness.socket.emitFrame(inviteSignal("call-4", PEER))
        runCurrent()

        harness.socket.emitFrame(
            signalFrame("call-4", PEER, CallSignal.Cancel(CallEndReason.ANSWERED_ELSEWHERE))
        )
        runCurrent()

        assertEquals(CallStage.ENDED, harness.engine.session.value?.stage)
        assertEquals(CallEndReason.ANSWERED_ELSEWHERE, harness.engine.session.value?.endReason)
    }

    @Test
    fun aBusyErrorOnOurInviteFailsTheCall() = runTest {
        val harness = CallHarness(this)
        harness.startConnected()
        runCurrent()
        harness.engine.place(PEER, DIALOG)
        runCurrent()

        val inviteId = harness.framesOfType(FrameType.CALL_INVITE).single().id
        harness.socket.emitFrame(
            InboundFrame.Error(ErrorPayload(code = ErrorCode.USER_BUSY, refId = inviteId))
        )
        runCurrent()

        val ended = assertNotNull(harness.engine.session.value)
        assertEquals(CallStage.ENDED, ended.stage)
        assertNotNull(ended.failure)
    }

    @Test
    fun anErrorForSomebodyElsesFrameIsIgnored() = runTest {
        val harness = CallHarness(this)
        harness.startConnected()
        runCurrent()
        harness.engine.place(PEER, DIALOG)
        runCurrent()

        harness.socket.emitFrame(
            InboundFrame.Error(ErrorPayload(code = ErrorCode.SEND_FAILED, refId = "unrelated"))
        )
        runCurrent()

        assertEquals(CallStage.RINGING, harness.engine.session.value?.stage)
    }

    @Test
    fun losingTheSocketEndsAnActiveCall() = runTest {
        val harness = CallHarness(this)
        harness.startConnected()
        runCurrent()
        harness.engine.place(PEER, DIALOG)
        runCurrent()

        harness.socket.disconnect()
        runCurrent()

        assertEquals(CallStage.ENDED, harness.engine.session.value?.stage)
    }

    @Test
    fun ringingOutEndsTheCallAtTheServerDeadline() = runTest {
        val harness = CallHarness(this)
        harness.startConnected()
        runCurrent()
        harness.engine.place(PEER, DIALOG)
        runCurrent()

        advanceTimeBy(DEFAULT_RING_MILLIS + 1)
        runCurrent()

        assertEquals(CallStage.ENDED, harness.engine.session.value?.stage)
        assertEquals(CallEndReason.RING_TIMEOUT, harness.engine.session.value?.endReason)
    }

    @Test
    fun anEndedCallClearsItselfSoTheNextOneCanStart() = runTest {
        val harness = CallHarness(this)
        harness.startConnected()
        runCurrent()
        harness.socket.emitFrame(inviteSignal("call-2", PEER))
        runCurrent()
        harness.engine.reject()
        runCurrent()

        advanceTimeBy(ENDED_VISIBLE_MILLIS + 1)
        runCurrent()

        assertNull(harness.engine.session.value)
    }

    @Test
    fun aSecondCallIsRefusedWhileOneIsInProgress() = runTest {
        val harness = CallHarness(this)
        harness.startConnected()
        runCurrent()
        harness.engine.place(PEER, DIALOG)
        runCurrent()

        val second = harness.engine.place("peer-2", null)
        runCurrent()

        assertIs<PlaceCallResult.Rejected>(second)
    }

    @Test
    fun callingWithNoSocketIsRefusedBeforeAnyMediaIsSetUp() = runTest {
        val harness = CallHarness(this)
        harness.engine.start()
        runCurrent()

        val result = harness.engine.place(PEER, DIALOG)
        runCurrent()

        assertIs<PlaceCallResult.Rejected>(result)
        assertTrue(harness.rtc.created.isEmpty())
        assertEquals(0, harness.api.iceCalls)
    }

    @Test
    fun mutingIsAppliedToTheMediaLayerAndReflectedInTheSession() = runTest {
        val harness = CallHarness(this)
        harness.startConnected()
        runCurrent()
        harness.engine.place(PEER, DIALOG)
        runCurrent()

        harness.engine.setMuted(true)
        runCurrent()

        assertTrue(harness.rtc.last().micMuted)
        assertEquals(true, harness.engine.session.value?.muted)
    }

    @Test
    fun anInviteWhileBusyIsRejectedWithoutDisturbingTheCurrentCall() = runTest {
        val harness = CallHarness(this)
        harness.startConnected()
        runCurrent()
        val started = harness.engine.place(PEER, DIALOG) as PlaceCallResult.Started
        runCurrent()

        harness.socket.emitFrame(inviteSignal("other-call", "peer-3"))
        runCurrent()

        assertEquals(started.callId, harness.engine.session.value?.callId)
        assertEquals(1, harness.framesOfType(FrameType.CALL_REJECT).size)
    }
}

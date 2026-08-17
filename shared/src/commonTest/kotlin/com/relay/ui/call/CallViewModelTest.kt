package com.relay.ui.call

import com.relay.call.CallDirection
import com.relay.call.CallSession
import com.relay.call.CallStage
import com.relay.call.MicPermission
import com.relay.protocol.CallEndReason
import com.relay.protocol.MEDIA_AUDIO
import com.relay.testutil.FakeCallRepository
import com.relay.ui.state.CallActionsUi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

private const val PEER = "peer-1"
private const val FIXED_NOW = 1_785_000_000_000L

private fun session(
    stage: CallStage,
    direction: CallDirection = CallDirection.INCOMING,
    answeredAt: Long? = null,
    endReason: String? = null
) = CallSession(
    callId = "call-1",
    peerId = PEER,
    dialogId = null,
    media = MEDIA_AUDIO,
    direction = direction,
    stage = stage,
    startedAt = FIXED_NOW,
    answeredAt = answeredAt,
    endReason = endReason
)

@OptIn(ExperimentalCoroutinesApi::class)
class CallViewModelTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        calls: FakeCallRepository,
        mic: MicPermission = MicPermission(grantedByPlatform = true)
    ) = CallViewModel(calls = calls, mic = mic)

    @Test
    fun noCallMeansNoOverlay() = runTest {
        val calls = FakeCallRepository()
        val model = viewModel(calls)
        runCurrent()

        assertFalse(model.state.value.visible)
    }

    @Test
    fun anIncomingCallShowsTheAnswerActionsAndThePeerName() = runTest {
        val calls = FakeCallRepository()
        calls.nameHandler = { "Ada Lovelace" }
        val model = viewModel(calls)

        calls.emit(session(CallStage.INCOMING))
        runCurrent()

        val state = model.state.value
        assertTrue(state.visible)
        assertEquals("Ada Lovelace", state.peerName)
        assertEquals(CallActionsUi.INCOMING, state.actions)
        assertEquals("Incoming call", state.status)
    }

    @Test
    fun anActiveCallExposesTheAnswerInstantSoTheTimerCanRun() = runTest {
        val calls = FakeCallRepository()
        val model = viewModel(calls)

        calls.emit(session(CallStage.ACTIVE, answeredAt = FIXED_NOW - 65_000))
        runCurrent()

        assertEquals(FIXED_NOW - 65_000, model.state.value.answeredAt)
        assertEquals(CallActionsUi.IN_PROGRESS, model.state.value.actions)
    }

    @Test
    fun aRingingCallHasNoTimer() = runTest {
        val calls = FakeCallRepository()
        val model = viewModel(calls)

        calls.emit(session(CallStage.RINGING, direction = CallDirection.OUTGOING))
        runCurrent()

        assertNull(model.state.value.answeredAt)
        assertEquals("Ringing…", model.state.value.status)
    }

    @Test
    fun anUnansweredOutgoingCallReadsAsNoAnswer() = runTest {
        val calls = FakeCallRepository()
        val model = viewModel(calls)

        calls.emit(
            session(
                stage = CallStage.ENDED,
                direction = CallDirection.OUTGOING,
                endReason = CallEndReason.RING_TIMEOUT
            )
        )
        runCurrent()

        assertEquals("No answer", model.state.value.status)
        assertEquals(CallActionsUi.ENDED, model.state.value.actions)
    }

    @Test
    fun answeringAsksForTheMicrophoneFirst() = runTest {
        val calls = FakeCallRepository()
        val mic = MicPermission(grantedByPlatform = false)
        val model = viewModel(calls, mic)
        backgroundScope.launch { mic.prompts.collect { mic.onResult(true) } }
        calls.emit(session(CallStage.INCOMING))
        runCurrent()

        model.accept()
        runCurrent()

        assertEquals(1, calls.accepts)
        assertEquals(0, calls.rejects)
    }

    @Test
    fun aDeniedMicrophoneDeclinesTheCallAndSaysWhy() = runTest {
        val calls = FakeCallRepository()
        val mic = MicPermission(grantedByPlatform = false)
        val model = viewModel(calls, mic)
        backgroundScope.launch { mic.prompts.collect { mic.onResult(false) } }
        calls.emit(session(CallStage.INCOMING))
        runCurrent()

        model.accept()
        runCurrent()

        assertEquals(0, calls.accepts)
        assertEquals(1, calls.rejects)
        assertNotNull(model.state.value.failure)
    }

    @Test
    fun togglingMuteFlipsTheRepositoryFlag() = runTest {
        val calls = FakeCallRepository()
        val model = viewModel(calls)
        calls.emit(session(CallStage.ACTIVE, answeredAt = FIXED_NOW))
        runCurrent()

        model.toggleMute()
        runCurrent()

        assertTrue(calls.micMuted)
    }
}

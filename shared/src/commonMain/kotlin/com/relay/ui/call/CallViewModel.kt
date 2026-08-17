package com.relay.ui.call

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.relay.call.CallSession
import com.relay.call.CallStage
import com.relay.call.MicPermission
import com.relay.protocol.CallEndReason
import com.relay.repository.CallRepository
import com.relay.ui.state.CallActionsUi
import com.relay.ui.state.CallUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val UNKNOWN_PEER = "Unknown"
private const val MIC_DENIED = "Microphone access is needed for calls"

class CallViewModel(
    private val calls: CallRepository,
    private val mic: MicPermission
) : ViewModel() {

    private val peerNames = MutableStateFlow<Map<String, String>>(emptyMap())
    private val micDenied = MutableStateFlow(false)
    private val mutableState = MutableStateFlow(CallUiState())
    val state: StateFlow<CallUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(calls.session, peerNames, micDenied) { session, names, denied ->
                build(session, names, denied)
            }.collect { mutableState.value = it }
        }
        viewModelScope.launch {
            calls.session
                .map { it?.peerId }
                .distinctUntilChanged()
                .collect { peerId -> if (peerId != null) resolveName(peerId) }
        }
    }

    fun accept() {
        viewModelScope.launch {
            if (mic.ensureGranted()) {
                micDenied.value = false
                calls.accept()
            } else {
                micDenied.value = true
                calls.reject()
            }
        }
    }

    fun decline() {
        calls.reject()
    }

    fun hangup() {
        calls.hangup()
    }

    fun toggleMute() {
        calls.setMuted(!mutableState.value.muted)
    }

    fun toggleSpeaker() {
        calls.setSpeakerOn(!mutableState.value.speakerOn)
    }

    private suspend fun resolveName(peerId: String) {
        if (peerNames.value.containsKey(peerId)) return
        val name = calls.peerName(peerId) ?: return
        peerNames.value = peerNames.value + (peerId to name)
    }

    private fun build(
        session: CallSession?,
        names: Map<String, String>,
        denied: Boolean
    ): CallUiState {
        if (session == null) return CallUiState()
        return CallUiState(
            visible = true,
            peerName = names[session.peerId] ?: UNKNOWN_PEER,
            status = statusOf(session),
            answeredAt = session.answeredAt.takeIf { session.stage == CallStage.ACTIVE },
            actions = when (session.stage) {
                CallStage.ENDED -> CallActionsUi.ENDED
                CallStage.INCOMING -> CallActionsUi.INCOMING
                else -> CallActionsUi.IN_PROGRESS
            },
            muted = session.muted,
            speakerOn = session.speakerOn,
            failure = session.failure ?: MIC_DENIED.takeIf { denied }
        )
    }

    private fun statusOf(session: CallSession): String =
        when (session.stage) {
            CallStage.DIALING -> "Calling…"
            CallStage.RINGING -> "Ringing…"
            CallStage.INCOMING -> "Incoming call"
            CallStage.CONNECTING -> "Connecting…"
            CallStage.ACTIVE -> "Connected"
            CallStage.ENDED -> endedLabel(session)
        }

    private fun endedLabel(session: CallSession): String =
        when (session.endReason) {
            CallEndReason.RING_TIMEOUT -> if (session.isOutgoing) "No answer" else "Missed call"
            CallEndReason.DECLINED -> if (session.isOutgoing) "Call declined" else "Call ended"
            CallEndReason.ANSWERED_ELSEWHERE -> "Answered on another device"
            CallEndReason.SETTLED_ELSEWHERE -> "Handled on another device"
            else -> "Call ended"
        }
}

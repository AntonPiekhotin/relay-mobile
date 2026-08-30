package com.relay.ui.call

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.relay.call.CallDirection
import com.relay.call.GroupCallSession
import com.relay.call.GroupCallStage
import com.relay.call.MicPermission
import com.relay.protocol.CallEndReason
import com.relay.protocol.GroupParticipantState
import com.relay.repository.GroupCallRepository
import com.relay.ui.state.CallActionsUi
import com.relay.ui.state.GroupCallParticipantUi
import com.relay.ui.state.GroupCallUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val UNKNOWN_PEER = "Unknown"
private const val MIC_DENIED = "Microphone access is needed for calls"
private const val GROUP_CALL_TITLE = "Group call"

class GroupCallViewModel(
    private val calls: GroupCallRepository,
    private val mic: MicPermission
) : ViewModel() {

    private val names = MutableStateFlow<Map<String, String>>(emptyMap())
    private val micDenied = MutableStateFlow(false)
    private val mutableState = MutableStateFlow(GroupCallUiState())
    val state: StateFlow<GroupCallUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(calls.session, names, micDenied) { session, resolved, denied ->
                build(session, resolved, denied)
            }.collect { mutableState.value = it }
        }
        viewModelScope.launch {
            calls.session
                .map { session -> session?.participants?.map { it.userId }?.toSet() ?: emptySet() }
                .distinctUntilChanged()
                .collect { ids -> ids.forEach { resolveName(it) } }
        }
    }

    fun accept() {
        viewModelScope.launch {
            if (mic.ensureGranted()) {
                micDenied.value = false
                calls.accept()
            } else {
                micDenied.value = true
                calls.decline()
            }
        }
    }

    fun decline() {
        calls.decline()
    }

    fun hangup() {
        calls.leave()
    }

    fun toggleMute() {
        calls.setMuted(!mutableState.value.muted)
    }

    fun toggleSpeaker() {
        calls.setSpeakerOn(!mutableState.value.speakerOn)
    }

    private fun resolveName(userId: String) {
        if (userId.isEmpty() || names.value.containsKey(userId)) return
        viewModelScope.launch {
            val name = calls.nameOf(userId) ?: return@launch
            names.value = names.value + (userId to name)
        }
    }

    private fun build(
        session: GroupCallSession?,
        resolved: Map<String, String>,
        denied: Boolean
    ): GroupCallUiState {
        if (session == null) return GroupCallUiState()
        return GroupCallUiState(
            visible = true,
            title = titleOf(session, resolved),
            status = statusOf(session),
            answeredAt = session.answeredAt.takeIf { showsTimer(session) },
            actions = when (session.stage) {
                GroupCallStage.ENDED -> CallActionsUi.ENDED
                GroupCallStage.INCOMING -> CallActionsUi.INCOMING
                else -> CallActionsUi.IN_PROGRESS
            },
            participants = session.others.map { participant ->
                GroupCallParticipantUi(
                    userId = participant.userId,
                    name = resolved[participant.userId] ?: UNKNOWN_PEER,
                    stateLabel = stateLabelOf(participant.state),
                    isJoined = participant.state == GroupParticipantState.JOINED
                )
            },
            muted = session.muted,
            speakerOn = session.speakerOn,
            failure = session.failure ?: MIC_DENIED.takeIf { denied }
        )
    }

    private fun stateLabelOf(state: String): String =
        when (state) {
            GroupParticipantState.INVITED -> "Ringing…"
            GroupParticipantState.JOINED -> "In call"
            GroupParticipantState.DECLINED -> "Declined"
            GroupParticipantState.MISSED -> "No answer"
            GroupParticipantState.LEFT -> "Left"
            else -> state
        }

    private fun titleOf(session: GroupCallSession, resolved: Map<String, String>): String =
        if (session.stage == GroupCallStage.INCOMING) {
            resolved[session.initiatorId] ?: UNKNOWN_PEER
        } else {
            GROUP_CALL_TITLE
        }

    private fun showsTimer(session: GroupCallSession): Boolean =
        session.stage == GroupCallStage.ACTIVE &&
            (session.direction == CallDirection.INCOMING || session.joinedOthersCount > 0)

    private fun statusOf(session: GroupCallSession): String =
        when (session.stage) {
            GroupCallStage.STARTING -> "Calling…"
            GroupCallStage.INCOMING -> "Incoming group call"
            GroupCallStage.CONNECTING -> "Connecting…"
            GroupCallStage.ACTIVE -> if (session.joinedOthersCount == 0 &&
                session.direction == CallDirection.OUTGOING
            ) {
                "Ringing…"
            } else {
                "Connected"
            }
            GroupCallStage.ENDED -> endedLabel(session)
        }

    private fun endedLabel(session: GroupCallSession): String =
        when (session.endReason) {
            CallEndReason.RING_TIMEOUT ->
                if (session.direction == CallDirection.OUTGOING) "No answer" else "Missed call"
            CallEndReason.ALL_DECLINED -> "Call declined"
            CallEndReason.DECLINED -> "Call declined"
            CallEndReason.ANSWERED_ELSEWHERE -> "Answered on another device"
            CallEndReason.SETTLED_ELSEWHERE -> "Handled on another device"
            else -> "Call ended"
        }
}

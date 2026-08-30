import AVFoundation
import Foundation
import LiveKit
import Shared

final class RelaySfuFactory: NSObject, SfuClientFactory {
    func create() -> any SfuClient {
        RelaySfuClient()
    }
}

final class RelaySfuClient: NSObject, SfuClient {

    private let room = Room()
    private var listener: SfuListener?
    private var closed = false

    func connect(url: String, token: String, listener: any SfuListener) {
        self.listener = listener
        room.add(delegate: self)
        listener.onConnectionState(state_: SfuConnectionState.connecting)
        Task { [weak self] in
            guard let self else { return }
            do {
                try await self.room.connect(url: url, token: token)
                try await self.room.localParticipant.setMicrophone(enabled: true)
                self.listener?.onConnectionState(state_: SfuConnectionState.connected)
            } catch {
                self.listener?.onFailure(reason: error.localizedDescription)
            }
        }
    }

    func setMicrophoneMuted(muted: Bool) {
        Task { [weak self] in
            try? await self?.room.localParticipant.setMicrophone(enabled: !muted)
        }
    }

    func setSpeakerphoneOn(enabled: Bool) {
        try? AVAudioSession.sharedInstance().overrideOutputAudioPort(enabled ? .speaker : .none)
    }

    func close() {
        guard !closed else { return }
        closed = true
        listener = nil
        let room = self.room
        Task { await room.disconnect() }
    }
}

extension RelaySfuClient: RoomDelegate {

    func room(
        _ room: Room,
        didUpdateConnectionState connectionState: LiveKit.ConnectionState,
        from oldConnectionState: LiveKit.ConnectionState
    ) {
        switch connectionState {
        case .reconnecting:
            listener?.onConnectionState(state_: SfuConnectionState.reconnecting)
        case .connected:
            if oldConnectionState == .reconnecting {
                listener?.onConnectionState(state_: SfuConnectionState.connected)
            }
        case .disconnected:
            if oldConnectionState == .connected || oldConnectionState == .reconnecting {
                listener?.onConnectionState(state_: SfuConnectionState.disconnected)
            }
        default:
            break
        }
    }
}

import AVFoundation
import Foundation
import Shared
import WebRTC

private let audioTrackId = "relay-audio"
private let streamId = "relay-stream"
private let setupFailed = "The call could not be set up on this device"

final class RelayRtcFactory: NSObject, RtcClientFactory {
    func create() -> any RtcClient {
        RelayRtcClient()
    }
}

final class RelayRtcClient: NSObject, RtcClient {

    private static let factory: RTCPeerConnectionFactory = {
        RTCInitializeSSL()
        return RTCPeerConnectionFactory(
            encoderFactory: RTCDefaultVideoEncoderFactory(),
            decoderFactory: RTCDefaultVideoDecoderFactory()
        )
    }()

    private var peerConnection: RTCPeerConnection?
    private var audioTrack: RTCAudioTrack?
    private var listener: RtcListener?
    private var closed = false

    func start(config: RtcConfig, role: RtcRole, listener: any RtcListener) {
        self.listener = listener
        configureAudioSession()

        let configuration = RTCConfiguration()
        configuration.iceServers = config.iceServers.map { server in
            if let username = server.username, let credential = server.credential {
                return RTCIceServer(urlStrings: server.urls, username: username, credential: credential)
            }
            return RTCIceServer(urlStrings: server.urls)
        }
        configuration.sdpSemantics = .unifiedPlan
        configuration.bundlePolicy = .maxBundle
        configuration.rtcpMuxPolicy = .require
        configuration.continualGatheringPolicy = .gatherContinually

        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        guard let connection = Self.factory.peerConnection(
            with: configuration,
            constraints: constraints,
            delegate: self
        ) else {
            listener.onFailure(reason: setupFailed)
            return
        }
        peerConnection = connection

        let source = Self.factory.audioSource(with: constraints)
        let track = Self.factory.audioTrack(with: source, trackId: audioTrackId)
        audioTrack = track
        connection.add(track, streamIds: [streamId])

        if role == RtcRole.caller {
            createOffer(on: connection)
        }
    }

    func setRemoteOffer(sdp: String) {
        guard let connection = peerConnection else { return }
        let description = RTCSessionDescription(type: .offer, sdp: sdp)
        connection.setRemoteDescription(description) { [weak self] error in
            guard let self else { return }
            if let error {
                self.listener?.onFailure(reason: error.localizedDescription)
                return
            }
            self.createAnswer(on: connection)
        }
    }

    func setRemoteAnswer(sdp: String) {
        guard let connection = peerConnection else { return }
        let description = RTCSessionDescription(type: .answer, sdp: sdp)
        connection.setRemoteDescription(description) { [weak self] error in
            if let error {
                self?.listener?.onFailure(reason: error.localizedDescription)
            }
        }
    }

    func addRemoteCandidate(candidateJson: String) {
        guard
            let connection = peerConnection,
            let data = candidateJson.data(using: .utf8),
            let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            let sdp = parsed["candidate"] as? String
        else { return }
        let candidate = RTCIceCandidate(
            sdp: sdp,
            sdpMLineIndex: Int32(parsed["sdpMLineIndex"] as? Int ?? 0),
            sdpMid: parsed["sdpMid"] as? String
        )
        connection.add(candidate) { _ in }
    }

    func setMicrophoneMuted(muted: Bool) {
        audioTrack?.isEnabled = !muted
    }

    func setSpeakerphoneOn(enabled: Bool) {
        let session = RTCAudioSession.sharedInstance()
        session.lockForConfiguration()
        defer { session.unlockForConfiguration() }
        try? session.overrideOutputAudioPort(enabled ? .speaker : .none)
    }

    func close() {
        guard !closed else { return }
        closed = true
        listener = nil
        audioTrack = nil
        peerConnection?.close()
        peerConnection = nil
        releaseAudioSession()
    }

    private func createOffer(on connection: RTCPeerConnection) {
        let constraints = RTCMediaConstraints(
            mandatoryConstraints: ["OfferToReceiveAudio": "true"],
            optionalConstraints: nil
        )
        connection.offer(for: constraints) { [weak self] description, error in
            self?.handleLocal(description: description, error: error, on: connection)
        }
    }

    private func createAnswer(on connection: RTCPeerConnection) {
        let constraints = RTCMediaConstraints(
            mandatoryConstraints: ["OfferToReceiveAudio": "true"],
            optionalConstraints: nil
        )
        connection.answer(for: constraints) { [weak self] description, error in
            self?.handleLocal(description: description, error: error, on: connection)
        }
    }

    private func handleLocal(
        description: RTCSessionDescription?,
        error: Error?,
        on connection: RTCPeerConnection
    ) {
        if let error {
            listener?.onFailure(reason: error.localizedDescription)
            return
        }
        guard let description else { return }
        connection.setLocalDescription(description) { [weak self] setError in
            guard let self else { return }
            if let setError {
                self.listener?.onFailure(reason: setError.localizedDescription)
                return
            }
            self.listener?.onLocalDescription(sdp: description.sdp)
        }
    }

    private func configureAudioSession() {
        let session = RTCAudioSession.sharedInstance()
        session.lockForConfiguration()
        defer { session.unlockForConfiguration() }
        try? session.setCategory(.playAndRecord, with: [.allowBluetooth, .duckOthers])
        try? session.setMode(.voiceChat)
        try? session.setActive(true)
    }

    private func releaseAudioSession() {
        let session = RTCAudioSession.sharedInstance()
        session.lockForConfiguration()
        defer { session.unlockForConfiguration() }
        try? session.setActive(false)
    }
}

extension RelayRtcClient: RTCPeerConnectionDelegate {

    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        let payload: [String: Any] = [
            "candidate": candidate.sdp,
            "sdpMid": candidate.sdpMid ?? "0",
            "sdpMLineIndex": Int(candidate.sdpMLineIndex)
        ]
        guard
            let data = try? JSONSerialization.data(withJSONObject: payload),
            let json = String(data: data, encoding: .utf8)
        else { return }
        listener?.onLocalCandidate(candidateJson: json)
    }

    func peerConnection(
        _ peerConnection: RTCPeerConnection,
        didChange newState: RTCPeerConnectionState
    ) {
        listener?.onConnectionState(state: newState.relayState)
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
}

private extension RTCPeerConnectionState {
    var relayState: RtcConnectionState {
        switch self {
        case .new: return RtcConnectionState.idle
        case .connecting: return RtcConnectionState.connecting
        case .connected: return RtcConnectionState.connected
        case .disconnected: return RtcConnectionState.disconnected
        case .failed: return RtcConnectionState.failed
        case .closed: return RtcConnectionState.closed
        @unknown default: return RtcConnectionState.idle
        }
    }
}

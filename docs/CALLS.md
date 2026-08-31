# Calls

1:1 audio calls: signaling over the WebSocket, media peer-to-peer over WebRTC. Group audio calls:
control over REST, media through a LiveKit SFU (§8). The wire contract is `docs/PROTOCOL.md` §4.4,
§4.5 and §5.0 — this document is the client half.

**As built: audio only, foreground only, no CallKit and no ConnectionService.** See §7 for
what that costs and what closes each gap.

---

## 1. The shape of it

```
        UI (CallHost overlay, CallActivity)
                     │  observes
        CallRepository ─── CallEngine ─── SocketClient   (call.* frames)
                                │
                                ├──────── CallApi        (GET /call/ice-servers)
                                │
                                └──────── RtcClient      ← platform, injected
                                          ├─ androidMain: WebRtcAudioClient
                                          └─ iosApp:      RelayRtcClient.swift

        GroupCallRepository ── GroupCallEngine ── SocketClient   (call.signal, read-only)
                                │
                                ├──────── GroupCallApi   (REST: create/join/decline/leave)
                                │
                                └──────── SfuClient      ← platform, injected
                                          ├─ androidMain: LivekitSfuClient
                                          └─ iosApp:      RelaySfu.swift
```

`CallEngine` owns every decision. It is the only thing that reads a signal, decides a stage, or
sends a frame. Both platforms plug in one interface and hold no call logic of their own.
`GroupCallEngine` is its sibling, built beside it rather than into it — the flows share almost
nothing (REST-driven vs frame-driven, a roster instead of a peer, no SDP/ICE machinery). Each
engine refuses to start or ring while the other holds a live session, via an injected
`otherCallActive` probe wired in DI.

**There is no call table.** Unlike messages, a call has no offline meaning: it exists while both
parties are connected and is settled by the server the moment either leaves. Live state is in
memory; a missed call arrives as a `MISSED_CALL` push. The call log endpoint
(`GET /api/v1/call/calls`) is read by the Calls tab (`CallsViewModel`) — history straight from REST
into screen state, no local table, refreshed on entry and by the retry affordance.

---

## 2. The platform port

```kotlin
interface RtcClient {
    fun start(config: RtcConfig, role: RtcRole, listener: RtcListener)
    fun setRemoteOffer(sdp: String)          // callee: also triggers the answer
    fun setRemoteAnswer(sdp: String)         // caller
    fun addRemoteCandidate(candidateJson: String)
    fun setMicrophoneMuted(muted: Boolean)
    fun setSpeakerphoneOn(enabled: Boolean)
    fun close()
}
```

**Nothing here is `suspend`, and that is deliberate** — it is the one place the codebase's
"no callbacks in `commonMain`" rule is relaxed. WebRTC is callback-shaped on both platforms, and a
`suspend` member becomes a completion handler in the generated Objective-C header, which a Swift
class cannot conform to cleanly. Results come back through `RtcListener`
(`onLocalDescription`, `onLocalCandidate`, `onConnectionState`, `onFailure`), which `CallEngine`
funnels straight into its event channel — so the callback style stops at the engine boundary and
the UI still sees a `StateFlow`.

**Candidates cross this boundary as JSON strings**, not typed objects: the server treats a candidate
as opaque, and a string survives the Kotlin↔Swift bridge without a mapper on either side.

`RtcClientFactory` creates one client per call — a `PeerConnection` is not reusable. Android binds
`WebRtcClientFactory` in `Modules.android.kt`; iOS binds `BridgedRtcClientFactory`, which delegates
to whatever Swift registered through `SharedBridge.registerRtcFactory`, and falls back to
`UnavailableRtcClient` if Swift never did.

---

## 3. The state machine

`CallStage` is `DIALING → RINGING → CONNECTING → ACTIVE → ENDED` outgoing, and
`INCOMING → CONNECTING → ACTIVE → ENDED` incoming. `CallEngine.session` is `null` when idle, and an
`ENDED` session clears itself after `ENDED_VISIBLE_MILLIS` so the user sees why the call stopped.

**Placing a call:** generate `call_id` locally → fetch ICE servers → build the peer connection →
`call.invite` carries the offer. The `call_id` is ours because trickle ICE starts before any round
trip could hand one back (`docs/PROTOCOL.md` §4.4).

**The invite is re-sent every `INVITE_RETRY_MILLIS` while ringing.** This is not a timeout retry —
the server re-relays an invite for a call that is still ringing, so a callee whose socket connected
*after* the first invite still gets one. That is the only way a push-woken device ever obtains the
caller's SDP: the push carries identifiers, never the offer.

**Answering after a push:** `PushCoordinator` starts the socket and hands the engine
`onIncomingCallPush`, which rings on identifiers alone with no SDP. If the user answers before the
invite lands, the accept is held (`acceptWhenOfferArrives`) and fires the moment it does.

**ICE:** local candidates go out immediately — the server buffers candidates for a call it has not
seen yet (~5s). Remote candidates arriving before we have applied a remote description are buffered
in the engine and flushed when it is set, because a `PeerConnection` rejects them before then.

**Ending.** Whoever hangs up sends `call.hangup`; everyone else learns from a signal and sends
nothing. `cancel` means another of your own devices settled it. `missed` is the server's 40s ring
deadline. The engine also runs its own timer from `ring_expires_at` so a dropped `missed` signal
still stops the ringing — but it never rings *shorter* than the server says.

**Errors are terminal.** `USER_BUSY`, `CALL_NOT_FOUND`, `INVALID_CALL_STATE` and friends end the
call with a human-readable reason. Nothing about a call is retried (`docs/PROTOCOL.md` §8): by the
time a signal has failed, the other side is negotiating a peer connection that no longer exists.
Errors are matched by `ref_id` against the frame ids this engine sent, so an unrelated failure
cannot kill a call.

**Losing the socket ends the call.** Signaling ordering is already lost; there is no resumption path.

---

## 4. Android

| Piece | Where | Does |
|---|---|---|
| `WebRtcAudioClient` | shared `androidMain/call/` | `io.getstream:stream-webrtc-android`, audio track, audio routing |
| `CallActivity` | androidApp | the call screen over the lock screen, `setShowWhenLocked` |
| `CallNotifier` | androidApp | `CallStyle` full-screen-intent ring, missed-call and ongoing-call notifications |
| `IncomingCallRinger` | androidApp | loops the ringtone and vibration itself; the call channel is silent |
| `OutgoingRingbackTone` | androidApp | `TONE_SUP_RINGTONE` ringback while an outgoing call waits |
| `CallActionReceiver` | androidApp | Decline from the notification |
| `FullScreenIntentAccess` | androidApp | reports and requests Android 14+ full-screen-intent access |
| `CallForegroundService` | androidApp | `microphone` FGS so a backgrounded call keeps its mic |
| `MicPermissionBinder` | androidApp | `RECORD_AUDIO` prompt, wired to shared `MicPermission` |

**Two paths ring the phone, and both are needed.** A push (`INCOMING_CALL`) covers a device with no
socket. But the gateway only pushes when the callee is *unreachable* — a backgrounded app that still
holds its socket gets the invite as a frame and no push at all, so `RelayApplication` also watches
`CallRepository.session` and posts the same full-screen notification when a call reaches `INCOMING`
while `AppPresence` says the app is not foregrounded.

`USE_FULL_SCREEN_INTENT` is granted by default only to calling and alarm apps on Android 14+. When
it is not held the notification degrades to a heads-up — the call still works, it just does not take
over the screen. `MainActivity` asks for it once per install via
`ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`; `CallNotifier` logs a warning whenever it posts without
it, so a missing grant reads as a permission problem instead of a broken ring.

**Ringing is the client's own audio, not a notification sound.** A channel sound plays once and
cannot loop, which is why the `calls_v2` channel is silent and `IncomingCallRinger` loops the
ringtone and vibration for as long as a session sits at `INCOMING`. The ringer is driven from
`CallRepository.session` / `GroupCallRepository.session` alone — the push path already rings the
engine before `PushCoordinator` returns a display, so session state, not the push, is the single
trigger. It therefore rings in the foreground too, alongside the in-app overlay.

**The caller hears a ringback for the same reason.** `OutgoingRingbackTone` runs
`ToneGenerator.TONE_SUP_RINGTONE` on `STREAM_VOICE_CALL` while a direct session is `DIALING` or
`RINGING`, and stops at every other stage. That tone repeats forever on its own, so there is no timer
to manage, and the voice-call stream puts it wherever the call audio already is — earpiece by
default, following `setSpeakerphoneOn`. It takes no audio focus: `WebRtcAudioClient` claims
`MODE_IN_COMMUNICATION` but no focus, so grabbing focus for the tone alone would hand it back the
moment the call connected, which is worse than not taking it.

Group calls have no ringback. The initiator joins the SFU immediately and the session is `ACTIVE`
with the roster showing who has not answered — there is live media to talk over, not silence to
fill.

**The foreground service starts at `CONNECTING`, never while ringing.** A `microphone` service
started before `RECORD_AUDIO` is granted throws `SecurityException` on Android 14, and starting any
service from the background can throw when no push exemption applies. Both are caught and degrade to
no service rather than a crash — a foregrounded call keeps working either way.

---

## 5. iOS

`RelayRtc.swift` implements the exported `RtcClient` protocol against the WebRTC binary
(`github.com/stasel/WebRTC`, added as a Swift package to `iosApp.xcodeproj`) and registers itself in
`AppDelegate` via `SharedBridge.registerRtcFactory`. `Info.plist` carries
`NSMicrophoneUsageDescription` and the `audio` background mode; iOS raises the microphone prompt
itself when the audio session activates.

**Calls work while the app is foregrounded, and only then.** An incoming call for a backgrounded app
needs a PushKit VoIP push reported to CallKit, and PushKit needs the `aps-environment` entitlement —
the same paid-account blocker as `docs/IOS.md` §1. Until that is real there is nothing to report and
nothing to wake.

---

## 6. Testing

`CallEngineTest` (`commonTest`) drives the whole machine over `FakeSocket` + `FakeRtcClient`: invite
and answer, candidate buffering, the retry reusing one `call_id`, push-woken accept, `cancel` from
another device, `USER_BUSY`, ring-out, socket loss. `CallFrameTest` round-trips the frames and the
signal verbs, including an unknown verb degrading to `Unknown` rather than failing the frame.

**Do not put an unbounded ticker in a `ViewModel`.** The in-call timer lives in `CallScreen` as a
`produceState` loop for exactly this reason — an infinite `flow` in a `combine` inside
`viewModelScope` hangs `runTest` rather than failing it, which costs a lot of time to diagnose.

---

## 7. What is deliberately missing

- **CallKit / ConnectionService.** No system call UI, no interaction with cellular calls, no entry in
  the system call log. Android uses a full-screen intent instead; iOS uses the in-app overlay.
- **Video.** The wire protocol carries `media` and the client always sends `audio`. Video needs
  camera permission, video tracks, and platform renderer views — for group calls the LiveKit SDK
  makes most of that cheap, so video lands there first when it lands.
- **Call history.** `CallApi.history` exists and is unused; there is no local table and no screen.

---

## 8. Group calls

The wire contract is `docs/PROTOCOL.md` §4.5. Client shape, and where it deliberately differs from
1:1:

**Control is REST, not frames.** `GroupCallApi` calls create/join/decline/leave/describe; the
socket only *delivers* — `group_invite` rings, roster deltas update the participant list,
`group_ended` and `cancel` end it. `GroupCallEngine` sends no frames at all, so it has no
`ref_id`-matched error handling.

**Media is the SFU's job.** The platform port is `SfuClient` — connect(url, token), mute, speaker,
close — far smaller than `RtcClient` because LiveKit negotiates its own transport. Same non-suspend
callback style, same bridge: Android binds `LivekitSfuClientFactory` (the `livekit-android` SDK,
JitPack repo needed for its `audioswitch` dependency); iOS registers `RelaySfuFactory` from
`RelaySfu.swift` (the `client-sdk-swift` package) through `SharedBridge.registerSfuFactory`.
`UnavailableSfuClient` is the fallback when Swift never registers.

**Stages:** outgoing `STARTING → CONNECTING → ACTIVE`, incoming `INCOMING → CONNECTING → ACTIVE`,
both ending in `ENDED`. The initiator is in the call from the first moment — while invitees still
ring, the session is `ACTIVE` with the roster showing who has not answered. Accept = REST join, so
a push-woken answer needs no held-offer dance; the push rings on identifiers and `describe` fills
the roster.

**Losing the socket does NOT end a group call** — unlike 1:1, media rides LiveKit's own connection
and every action goes over REST. On reconnect the engine re-`describe`s the call and applies the
roster, or ends it if the server says it is over. The local ring timer still bounds an unanswered
ring.

**Busy is mutual but decided in two places.** Locally, each engine refuses while the other is live
(an incoming `group_invite` while busy is declined with reason `busy`). Authoritatively, the server
refuses at join with `409` — a ringing invitee is never busy.

**UI:** `CallHost` overlays whichever session is live (direct wins if both, which the gates make
unreachable). `GroupCallScreen` shows the roster with per-participant state; the entry point is the
phone glyph in a **group chat's** top bar (`ChatViewModel.call()`), which fetches the group's
members via `GET /api/v1/message/dialogs/{id}` and invites everyone but the caller. The server
still enforces the cap of 16 participants including self — calling a larger group is rejected and
the reason surfaces in the chat's error banner. The dock's Groups tab creates group *dialogs* now,
not calls (`docs/UI.md` §5).

**Testing:** `GroupCallEngineTest` drives the machine over `FakeSocket` + `FakeGroupCallApi` +
`FakeSfuClient`: create/join/decline/leave, roster deltas, `group_ended`, busy in both directions,
ring-out, push-woken describe, terminal describe after reconnect, SFU failure.

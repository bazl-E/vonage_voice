# Vonage Voice Plugin — Android Debugging Guide

A reference for reading logcat output while developing or troubleshooting the
Vonage VoIP integration. All tags listed here are emitted by the native Android
Kotlin code only (`plugins/vonage_voice/android/`).

---

## Quick-start commands

```bash
# Clear old logs, then watch everything Vonage-related
adb logcat -c && adb logcat -v time \
  -s VonagePlugin:V VonageFCM:V TVConnectionService:V \
     TVCallConnection:V TVCallInviteConnection:V VonageVoice:V flutter:V

# Hangup path only (answers the question "WebSocket or FCM?")
adb logcat -v time -s VonagePlugin:I VonageFCM:I \
  | grep -E "HANGUP|BROADCAST|CALL_ENDED|EVENT_CALL_ENDED"

# Incoming call flow (FCM → ringtone → Flutter)
adb logcat -v time -s VonageFCM:V TVConnectionService:V VonagePlugin:V \
  | grep -E "FCM-[1-6]|INCOMING|INVITE|SESSION|BROADCAST"

# Answer call flow
adb logcat -v time -s VonagePlugin:V TVConnectionService:V \
  | grep -E "ANSWER|CONNECT|doAnswer|ACTION_ANSWER"

# Audio routing (speaker / Bluetooth / earpiece)
adb logcat -v time -s TVConnectionService:V VonagePlugin:V \
  | grep -E "AUDIO|SPEAKER|BLUETOOTH|MUTE|audio"

# Save to file (scroll back later)
adb logcat -v time \
  -s VonagePlugin:V VonageFCM:V TVConnectionService:V TVCallConnection:V flutter:V \
  | tee ~/vonage_debug.txt
```

---

## Log tags

| Tag | File | What it covers |
|-----|------|----------------|
| `VonagePlugin` | `VonageVoicePlugin.kt` | WebSocket hangup path, broadcast receiver, events emitted to Flutter Dart side |
| `VonageFCM` | `VonageFirebaseMessagingService.kt` | FCM push path (app killed / WebSocket down), session restore, invite processing |
| `TVConnectionService` | `TVConnectionService.kt` | Call setup, ringing, audio routing, Telecom integration, foreground service lifecycle |
| `TVCallConnection` | `TVCallConnection.kt` | Individual active-call disconnect, audio mode changes |
| `TVCallInviteConnection` | `TVCallInviteConnection.kt` | Pending invite cancel / answer transitions |
| `VonageVoice` | misc | General SDK-level messages |
| `flutter` | Dart side | `VonageCallEventBloc` and `debugPrint` output from Flutter |

---

## Scenario reference

### 1 — Incoming call (app in foreground)

Flutter is running and the WebSocket is connected. The SDK delivers the invite
directly via `setCallInviteListener` in `VonageVoicePlugin`.

**Expected log sequence:**
```
VonageFCM   [FCM-1] onMessageReceived — keys={nexmo, ...}
VonageFCM   [FCM-1] Active Vonage call exists — skipping 2nd VoIP invite   ← only if already in a call
VonageFCM   [FCM-2] ✓ Vonage push detected
VonageFCM   [FCM-4] VoiceClient alive and session ready — foreground/background path
VonageFCM   [FCM-5] Flutter in foreground — plugin listener will handle callId=xxx
TVConnectionService  [INCOMING-REAL] Flutter in foreground with active EventSink — skipping native UI
VonagePlugin  BROADCAST_CALL_INVITE -- callId: xxx, from: +1..., to: +1...
```

**Flutter side (Dart):**
```
flutter  VonageCallEventBloc: CallEvent.callInviteReceived callId=xxx
```

---

### 2 — Incoming call (app killed / background, FCM path)

WebSocket is down. Vonage sends an FCM push. The native service processes it,
restores the JWT session, and starts the foreground ringtone.

**Expected log sequence:**
```
VonageFCM   [FCM-1] onMessageReceived — keys={nexmo, ...}
VonageFCM   [FCM-2] ✓ Vonage push detected
VonageFCM   [FCM-3] Starting placeholder foreground service (client/session not ready)
VonageFCM   [FCM-3] ✓ Placeholder foreground service started
VonageFCM   [FCM-4] Creating new VoiceClient (boot/killed path)
VonageFCM   [FCM-5] Stored JWT found (xxx chars) — restoring session...
VonageFCM   [FCM-5a] Calling createSession (retriesLeft=1)...
VonageFCM   [FCM-5a] ✓ createSession succeeded: sessionId=xxx
VonageFCM   [FCM-6] Calling processPushCallInvite...
VonageFCM   [FCM-6] Incoming call processed with callId: xxx
TVConnectionService  [INCOMING-REAL] callId=xxx, from=+1...
TVConnectionService  [INCOMING-REAL] ✓ addNewIncomingCall() succeeded callId=xxx
```

**Failure indicators:**
```
VonageFCM   [FCM-5] ✗ No stored JWT — cannot restore session   ← user logged out / first install
VonageFCM   [FCM-5a] ✗ createSession exhausted retries         ← network issue
VonageFCM   [FCM-5] processPushCallInvite threw: ...            ← SDK error
TVConnectionService  [INCOMING-REAL] ✗ addNewIncomingCall() failed ← Telecom permission missing
```

---

### 3 — Answer call

User taps Answer (or lock-screen button). `VonageVoicePlugin.handleAnswer()` →
`TVConnectionService.ACTION_ANSWER` → SDK `client.answer()`.

**Expected log sequence:**
```
TVConnectionService  ACTION_ANSWER received callId=xxx
VonagePlugin  doAnswer: callId=xxx
VonagePlugin  doAnswer: ✓ answer() succeeded callId=xxx
TVConnectionService  [INCOMING-REAL] call connected callId=xxx
**Note:** If you see `[HANGUP][BROADCAST] CALL_ENDED` immediately after `doAnswer` succeeded, the call was cancelled remotely before the answer completed.
```

**Flutter side (Dart):**
```
flutter  VonageCallEventBloc: CallEvent.callConnected callId=xxx
```

**Failure indicators:**
```
TVConnectionService  Failed to send ACTION_ANSWER: ...    ← service not running
VonagePlugin  doAnswer: ✗ answer() failed: ...            ← session invalid / network
```

---

### 4 — Remote hangup (B ends the call)

This is the most important flow for the bug fixed in this branch. Exactly one
of the two paths below should fire, never both.

#### Path A — WebSocket active (app in foreground)
The Vonage SDK fires `setOnCallHangupListener` in `VonageVoicePlugin`.

```
VonagePlugin  [HANGUP][WEBSOCKET] ▶ hangup via active-session / WebSocket path callId=xxx reason=remoteHangup hasConnection=true hasPendingInvite=false
VonagePlugin  [HANGUP][WEBSOCKET] ✓ active connection found — disconnecting + broadcasting CALL_ENDED
VonagePlugin  [HANGUP][BROADCAST] CALL_ENDED broadcast received — activeCallId=xxx isMuted=false ...
VonagePlugin  [HANGUP][BROADCAST] ✓ emitting EVENT_CALL_ENDED to Flutter
```

#### Path B — FCM push (WebSocket was down / app killed)
`VonageFirebaseMessagingService.setOnCallHangupListener` fires instead.

```
VonageFCM   [HANGUP][FCM] ▶ hangup via FCM push path (WebSocket down / app killed) callId=xxx reason=remoteHangup hasConnection=true hasPendingInvite=false
VonageFCM   [HANGUP][FCM] ✓ active connection found — disconnecting + broadcasting CALL_ENDED
VonagePlugin  [HANGUP][BROADCAST] CALL_ENDED broadcast received — activeCallId=xxx ...
VonagePlugin  [HANGUP][BROADCAST] ✓ emitting EVENT_CALL_ENDED to Flutter
```

#### Race / double-cleanup (fallback — should be rare)
Both paths ran; the second one finds no connection. Flutter still gets the event.

```
VonagePlugin  [HANGUP][WEBSOCKET] ⚠ no connection/invite found for callId=xxx — emitting fallback CALL_ENDED (race/double-cleanup)
```

#### Bug: Flutter stuck on call screen
If you see Path A or B **without** the `[HANGUP][BROADCAST] ✓ emitting EVENT_CALL_ENDED` line, the broadcast receiver guard blocked it. Look for:

```
VonagePlugin  [HANGUP][BROADCAST] ⚠ guard triggered: activeCallId is null — skipping duplicate
```
This means `activeCallId` was null when the broadcast arrived — the plugin had
already cleaned up (a previous duplicate fired first). This is expected when
local hangup and remote hangup race.

---

### 5 — Local hangup (A ends the call)

User taps End. `VonageVoicePlugin.handleHangup()` → `TVConnectionService.ACTION_HANGUP`
→ SDK `client.hangup()` → SDK fires `setOnCallHangupListener` as confirmation.

```
TVConnectionService  handleHangup: callId=xxx
TVConnectionService  handleHangup: ✓ disconnect() called
VonagePlugin  [HANGUP][WEBSOCKET] ▶ ... hasConnection=false hasPendingInvite=false
VonagePlugin  [HANGUP][WEBSOCKET] ⚠ no connection/invite found ...  ← normal: already removed by handleHangup
VonagePlugin  [HANGUP][BROADCAST] CALL_ENDED broadcast received ...
VonagePlugin  [HANGUP][BROADCAST] ✓ emitting EVENT_CALL_ENDED to Flutter
```

---

### 6 — Incoming call cancelled (B cancels before A answers)

B ends / cancels the ringing call before A picks up. SDK fires
`setCallInviteCancelListener` (if WebSocket is up) or FCM sends a cancel push.

#### WebSocket cancel
```
VonagePlugin  setCallInviteCancelListener: callId=xxx
TVConnectionService  handleCancelCallInvite: callId=xxx
VonagePlugin  [HANGUP][BROADCAST] CALL_ENDED broadcast received ...
```

#### FCM cancel push (WebSocket was down)
```
VonageFCM   [FCM-1] onMessageReceived — keys={nexmo, ...}
VonageFCM   isInvitePush=false  ← cancel/hangup push — bypasses active-call guard
VonageFCM   [HANGUP][FCM] ▶ hangup via FCM push path ...  hasPendingInvite=true
VonageFCM   [HANGUP][FCM] ✓ pending invite found (answered elsewhere) — cancelling invite
```

---

### 7 — Mute / Unmute

```
VonagePlugin  handleMute: callId=xxx mute=true
VonagePlugin  handleMute: ✓ mute applied
flutter  VonageCallEventBloc: CallEvent.muted
```

---

### 8 — Speaker / Audio routing

```
TVConnectionService  selectAudioDevice: route=speaker
TVConnectionService  AUDIO: setSpeakerphoneOn=true
TVConnectionService  AUDIO: ✓ speaker activated
```

---

### 9 — Stale invite guard (FCM active-call guard)

Fires when a second FCM push arrives while a call is already active/pending.

```
VonageFCM   [FCM-GUARD] Clearing stale invite before guard: callId=xxx   ← invite was >60s old
VonageFCM   [FCM-1] Active Vonage call exists — skipping 2nd VoIP invite  ← duplicate blocked
```

---

### 10 — Service cleanup (no calls remaining)

After all calls end, the foreground service stops itself.

```
TVConnectionService  handleCleanup: no active calls — stopping foreground service
TVConnectionService  stopForeground: notification cancelled
```

---

## Common problems and what to look for

| Symptom | Look for in logcat |
|---------|--------------------|
| Call screen doesn't dismiss after B hangs up | Missing `[HANGUP][BROADCAST] ✓ emitting EVENT_CALL_ENDED` |
| Second incoming call never rings | `[FCM-1] Active Vonage call exists — skipping 2nd VoIP invite` with stale invite in `pendingInvites` |
| Answer button does nothing | `doAnswer: ✗ answer() failed` — check JWT / network |
| Ringtone starts but no call screen shown | `[INCOMING-REAL] ✗ addNewIncomingCall() failed` — check `READ_PHONE_STATE` permission |
| No incoming call at all (killed app) | Missing `[FCM-2] ✓ Vonage push detected` — FCM token stale or not registered |
| Call stuck in connecting | Missing `call connected callId=xxx` after `doAnswer` succeeded — SDK session issue |
| Flutter receives EVENT_CALL_ENDED twice | Two `[HANGUP][BROADCAST]` lines without the guard catching the second — check duplicate broadcast path |

---

## Dart-side events (flutter tag)

These map 1-to-1 to what `VonageCallEventBloc` processes:

| Native event emitted | Flutter `CallEvent` |
|----------------------|---------------------|
| `EVENT_CALL_INVITE` | `CallEvent.callInviteReceived` |
| `EVENT_CALL_CONNECTED` | `CallEvent.callConnected` |
| `EVENT_CALL_ENDED` | `CallEvent.callEnded` |
| `EVENT_MUTE` | `CallEvent.muted` |
| `EVENT_UNMUTE` | `CallEvent.unmuted` |
| `EVENT_SPEAKER_ON` | `CallEvent.speakerOn` |
| `EVENT_SPEAKER_OFF` | `CallEvent.speakerOff` |
| `EVENT_BT_ON` | `CallEvent.bluetoothOn` |
| `EVENT_BT_OFF` | `CallEvent.bluetoothOff` |

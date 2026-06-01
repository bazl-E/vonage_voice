// ┌──────────────────────────────────────────────────────────────────────┐
// │  VonageVoicePlugin.swift                                           │
// │                                                                    │
// │  Flutter plugin that bridges the Vonage Voice SDK on iOS.          │
// │  Handles:                                                          │
// │    • CallKit integration (incoming / outgoing calls)               │
// │    • PushKit VoIP push delivery                                    │
// │    • Audio routing (speaker, Bluetooth, earpiece)                  │
// │    • Session lifecycle (login, refresh, logout)                    │
// │    • Killed / background / locked state recovery                   │
// │                                                                    │
// │  Architecture overview:                                            │
// │    1. PushKit push arrives → report to CallKit immediately         │
// │    2. Vonage SDK fires didReceiveInviteForCall                     │
// │    3. CallKit shows the native incoming-call UI                    │
// │    4. User answers/declines → CXProviderDelegate methods fire      │
// │    5. Events forwarded to Flutter via EventChannel                 │
// └──────────────────────────────────────────────────────────────────────┘

import Flutter
import UIKit
import AVFoundation
import PushKit
import CallKit
import VonageClientSDKVoice


// ═══════════════════════════════════════════════════════════════════════
// MARK: - Main Plugin Class
// ═══════════════════════════════════════════════════════════════════════

public class VonageVoicePlugin: NSObject, FlutterPlugin, FlutterStreamHandler {

    // ─── Shared Instance ─────────────────────────────────────────────
    // Accessible from AppDelegate to forward PushKit events when needed.
    public static var sharedInstance: VonageVoicePlugin?

    /// When `true`, the AppDelegate owns the PKPushRegistry for `.voIP`
    /// and forwards pushes/tokens to this plugin. The plugin will NOT
    /// create its own PKPushRegistry. Set from AppDelegate before
    /// GeneratedPluginRegistrant.register().
    public static var pushKitSetupByAppDelegate = false

    /// VoIP push token received by AppDelegate before the plugin was ready.
    /// Consumed in `register(with:)`.
    public static var pendingVoipToken: Data?

    /// VoIP push payload received by AppDelegate before the plugin was ready.
    /// Consumed in `register(with:)`.
    public static var pendingVoipPushPayload: PKPushPayload?

    /// VoIP push completion handler deferred by AppDelegate.
    public static var pendingVoipPushCompletion: (() -> Void)?

    // ─── Flutter Communication ───────────────────────────────────────
    /// Sends events (call state, audio, errors) to Flutter via EventChannel.
    private var eventSink: FlutterEventSink?

    /// Events queued while Flutter isn't listening (e.g. app was killed).
    /// Replayed automatically when the EventChannel reconnects.
    private var pendingEvents: [Any] = []
    private static let maxPendingEvents = 50

    /// Broadcast by Vonage when its CXProvider is torn down so Twilio knows its own
    /// TUCallCenter slot was stolen and must allocate a fresh CXProvider on the next call.
    static let cxProviderInvalidatedNotification = NSNotification.Name(
        "com.vonage.voice.CXProviderInvalidated"
    )

    // ─── Vonage SDK ──────────────────────────────────────────────────
    private var voiceClient: VGVoiceClient!

    /// The JWT used to authenticate with Vonage. `nil` when logged out.
    private var accessToken: String?

    /// The Vonage device-id returned after registering a VoIP push token.
    private var deviceId: String?

    /// `true` once `createSession()` succeeds — prevents double creation
    /// when PushKit restores the session before Flutter calls `tokens()`.
    private var isSessionReady = false

    // ─── CallKit ─────────────────────────────────────────────────────
    // Lazily initialised via setupCallKit() — only when Vonage tokens
    // are set or a Vonage VoIP push arrives. Keeping these nil at
    // startup prevents Vonage's CXProvider from intercepting Twilio's
    // CXStartCallAction on cold start when no Vonage tokens exist.
    private var callKitProvider: CXProvider?
    private var callKitCallController: CXCallController?
    private var callKitCompletionCallback: ((Bool) -> Void)?

    /// Set to true immediately before we call callKitProvider.invalidate() ourselves.
    /// Suppresses the full state-reset in providerDidReset (Vonage session remains valid).
    private var isIntentionalProviderReset: Bool = false

    // ─── Killed-State Push Coordination ──────────────────────────────
    // When the app is killed and a VoIP push arrives, iOS launches the
    // app in the background. We must report to CallKit *immediately*
    // (before the async session restore finishes). These properties
    // coordinate between the push receipt and didReceiveInviteForCall.

    /// PushKit completion handler — deferred until the invite arrives.
    private var pendingPushCompletion: (() -> Void)?

    /// UUID of the call pre-reported to CallKit before session restore.
    private var pendingPushUUID: UUID?

    /// Safety timer: ends the pre-reported call if didReceiveInviteForCall never fires.
    private var pushCompletionTimer: Timer?

    /// Answer action queued because the user tapped Answer before the callId was available.
    private var pendingAnswerAction: CXAnswerCallAction?

    /// Decline action queued because the user tapped Decline before the callId was available.
    private var pendingEndAction: CXEndCallAction?

    // ─── PushKit ─────────────────────────────────────────────────────
    private var voipRegistry: PKPushRegistry!

    /// VoIP push token persisted in UserDefaults so it survives app restarts.
    private var deviceToken: Data? {
        get { UserDefaults.standard.data(forKey: Keys.cachedDeviceToken) }
        set { UserDefaults.standard.set(newValue, forKey: Keys.cachedDeviceToken) }
    }

    /// Whether to register VoIP tokens against Apple's sandbox APNS.
    /// Set from Dart via the `isSandbox` parameter. Defaults to `false` (production).
    private var isSandbox = false

    // ─── Call State ──────────────────────────────────────────────────
    /// Pending call invites keyed by CallKit UUID.
    private var callInvites: [UUID: CallInviteInfo] = [:]

    /// Active (answered/connected) calls: CallKit UUID → Vonage callId.
    private var activeCalls: [UUID: String] = [:]

    /// Reverse lookup: Vonage callId → CallKit UUID.
    private var callIdToUUID: [String: UUID] = [:]

    /// The UUID of the currently active (foreground) call.
    private var activeCallUUID: UUID?

    /// True while voiceClient.answer() is in-flight but hasn't completed yet.
    /// Prevents didReceiveInviteCancelForCall from ending a call that is
    /// being answered on this device (race between answer callback and
    /// server-side invite cleanup).
    private var isAnsweringInProgress = false

    /// Outgoing call state — set before the CXStartCallAction fires.
    private var callOutgoing = false
    private var userInitiatedDisconnect = false
    private var callTo = ""
    private var identity = ""
    private var callArgs: [String: Any] = [:]
    private var outgoingCallerName = ""

    /// UUID of the outgoing call that THIS plugin initiated via performStartCallAction.
    /// Guards against iOS broadcasting CXStartCallAction to all CXProvider delegates:
    /// if the UUID doesn't match, we know this action belongs to Twilio, not Vonage.
    private var pendingOutgoingUUID: UUID?

    // ─── Invite Keep-Alive ──────────────────────────────────────────
    // When the device is locked, iOS suspends the app after PushKit
    // completion — killing the WebSocket. The cancel event from the
    // server (answered elsewhere / caller hung up) never arrives and
    // CallKit keeps ringing. A background task keeps the process alive
    // long enough for the cancel to be delivered. A ringing timeout
    // acts as a safety net if the background task expires first.

    /// Background task that keeps the app alive while a call invite is pending.
    private var inviteBackgroundTask: UIBackgroundTaskIdentifier = .invalid

    /// Safety-net timer: ends the pending invite if it isn't resolved
    /// within this interval (e.g. cancel push lost, WebSocket dead).
    private var ringingTimeoutTimer: Timer?
    private static let ringingTimeoutSeconds: TimeInterval = 60

    // ─── Audio State ─────────────────────────────────────────────────
    private var isMuted = false
    private var desiredSpeakerState = false
    private var desiredBluetoothState = false

    /// `true` when the user explicitly changed the route (prevents echo from OS notification).
    private var userExplicitlyChangedAudioRoute = false
    private var isChangingAudioRoute = false

    // ─── Caller Registry ─────────────────────────────────────────────
    /// Maps caller IDs to display names (persisted in UserDefaults).
    private var clients: [String: String] = [:]
    private var defaultCaller = "Unknown Caller"

    // ─── Bluetooth Port Types ────────────────────────────────────────
    /// All Bluetooth AVAudioSession port types in one place.
    /// Used throughout the audio management code instead of repeating the check.
    private static let bluetoothPorts: Set<AVAudioSession.Port> = [
        .bluetoothHFP, .bluetoothA2DP, .bluetoothLE
    ]

    // ─── UserDefaults Keys ───────────────────────────────────────────
    /// Centralised constants for all UserDefaults keys used by the plugin.
    private enum Keys {
        static let cachedDeviceToken = "VonageCachedDeviceToken"
        static let clientList        = "VonageContactList"
        static let cachedJwt         = "VonageCachedJwt"
        static let cachedDeviceId    = "VonageCachedDeviceId"
        static let defaultCallKitIcon = "callkit_icon"
    }

    // ─── App Name ────────────────────────────────────────────────────
    static var appName: String {
        (Bundle.main.infoDictionary?["CFBundleName"] as? String) ?? "Vonage Voice"
    }


    // ═════════════════════════════════════════════════════════════════
    // MARK: - Initialisation
    // ═════════════════════════════════════════════════════════════════

    public override init() {
        // PushKit registry — receives VoIP pushes even when app is killed.
        voipRegistry = PKPushRegistry(queue: .main)

        // Restore persisted caller registry from disk.
        clients = UserDefaults.standard.object(forKey: Keys.clientList) as? [String: String] ?? [:]

        // NOTE: callKitProvider and callKitCallController are NOT created here.
        // They are lazily initialised in setupCallKit() when Vonage tokens are
        // provided or a Vonage VoIP push arrives. This prevents Vonage's
        // CXProvider from intercepting Twilio's CXStartCallAction on cold start
        // when no Vonage tokens exist.

        super.init()

        // Create the Vonage voice client.
        voiceClient = VGVoiceClient()
        voiceClient.delegate = self

        // Listen for hardware audio-route changes (e.g. headphones plugged in).
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAudioRouteChange),
            name: AVAudioSession.routeChangeNotification,
            object: nil
        )
    }

    deinit {
        callKitProvider?.invalidate()
        NotificationCenter.default.removeObserver(self)
    }

    // ═════════════════════════════════════════════════════════════════
    // MARK: - Lazy CallKit Setup
    // ═════════════════════════════════════════════════════════════════

    /// Creates the CXProvider and CXCallController on demand.
    ///
    /// Called from:
    ///   - `handleTokens()` — when Flutter provides a valid Vonage JWT
    ///   - `pushRegistry(_:didReceiveIncomingPushWith:for:completion:)` — when a
    ///     Vonage VoIP push arrives (killed/background state)
    ///
    /// Idempotent: subsequent calls are no-ops.
    private func setupCallKit() {
        guard callKitProvider == nil else { return }

        let config = CXProviderConfiguration(localizedName: VonageVoicePlugin.appName)
        config.maximumCallGroups = 1
        config.maximumCallsPerCallGroup = 1
        config.supportedHandleTypes = [.phoneNumber, .generic]
        config.supportsVideo = false

        callKitProvider = CXProvider(configuration: config)
        callKitProvider?.setDelegate(self, queue: nil)
        callKitCallController = CXCallController()

        // Tell the Vonage SDK that CallKit manages audio.
        // Must be set when CallKit is actually available.
        VGVoiceClient.isUsingCallKit = true

        // Restore the CallKit icon (if one was set previously).
        let iconName = UserDefaults.standard.string(forKey: Keys.defaultCallKitIcon) ?? Keys.defaultCallKitIcon
        _ = updateCallKitIcon(icon: iconName)

        NSLog("[VonageVoice] setupCallKit: CXProvider registered")
    }

    // NOTE: teardownCallKit() was removed.
    // Explicitly deactivating AVAudioSession and resetting state mid-session
    // caused instability. CallKit's own didDeactivateAudioSession is sufficient.
    // The Dart method channel case "teardownCallKit" is kept as a no-op for
    // backward compatibility.


    // ═════════════════════════════════════════════════════════════════
    // MARK: - Flutter Plugin Registration
    // ═════════════════════════════════════════════════════════════════

    public static func register(with registrar: FlutterPluginRegistrar) {
        let instance = VonageVoicePlugin()

        // Method channel: receives commands from Flutter (e.g. answer, hangUp).
        let methodChannel = FlutterMethodChannel(
            name: "vonage_voice/messages",
            binaryMessenger: registrar.messenger()
        )

        // Event channel: sends call events to Flutter (e.g. Connected, Call Ended).
        let eventChannel = FlutterEventChannel(
            name: "vonage_voice/events",
            binaryMessenger: registrar.messenger()
        )

        eventChannel.setStreamHandler(instance)
        registrar.addMethodCallDelegate(instance, channel: methodChannel)
        registrar.addApplicationDelegate(instance)

        sharedInstance = instance

        // ── PushKit ──────────────────────────────────────────────────────
        // Apple mandates ONE PKPushRegistry per push type. If AppDelegate
        // already owns .voIP (it does — for Twilio/Vonage routing), we
        // skip creating our own and rely on AppDelegate to forward events.
        if VonageVoicePlugin.pushKitSetupByAppDelegate {
            NSLog("[VonageVoice] PushKit managed by AppDelegate — skipping own PKPushRegistry")

            // Consume VoIP token that arrived before plugin was ready.
            if let pendingToken = VonageVoicePlugin.pendingVoipToken {
                VonageVoicePlugin.pendingVoipToken = nil
                instance.deviceToken = pendingToken
                let hexToken = pendingToken.map { String(format: "%02x", $0) }.joined()
                NSLog("[VonageVoice] Consumed pending VoIP token (%d bytes) hex=%@",
                      pendingToken.count, hexToken)
            }

            // Consume VoIP push that arrived before plugin was ready.
            if let pendingPayload = VonageVoicePlugin.pendingVoipPushPayload {
                let pendingCompletion = VonageVoicePlugin.pendingVoipPushCompletion ?? {}
                VonageVoicePlugin.pendingVoipPushPayload = nil
                VonageVoicePlugin.pendingVoipPushCompletion = nil
                NSLog("[VonageVoice] Processing pending VoIP push from AppDelegate")
                instance.pushRegistry(PKPushRegistry(queue: .main),
                                      didReceiveIncomingPushWith: pendingPayload,
                                      for: .voIP,
                                      completion: pendingCompletion)
            }
        } else {
            // No AppDelegate PushKit — plugin owns the registry.
            instance.voipRegistry.delegate = instance
            instance.voipRegistry.desiredPushTypes = [.voIP]
        }
    }


    // ═════════════════════════════════════════════════════════════════
    // MARK: - FlutterStreamHandler (EventChannel)
    // ═════════════════════════════════════════════════════════════════

    /// Called when Flutter starts listening on the EventChannel.
    public func onListen(withArguments arguments: Any?,
                         eventSink: @escaping FlutterEventSink) -> FlutterError? {
        self.eventSink = eventSink

        // Replay any events that were queued while Flutter wasn't listening.
        if !pendingEvents.isEmpty {
            let queued = pendingEvents
            pendingEvents.removeAll()
            for event in queued {
                DispatchQueue.main.async { eventSink(event) }
            }
        }
        return nil
    }

    /// Called when Flutter stops listening (e.g. app goes to background).
    public func onCancel(withArguments arguments: Any?) -> FlutterError? {
        eventSink = nil
        return nil
    }


    // ═════════════════════════════════════════════════════════════════
    // MARK: - Event Sending (Native → Flutter)
    // ═════════════════════════════════════════════════════════════════

    /// Logs the event to NSLog and sends it to Flutter.
    private func sendPhoneCallEvents(description: String, isError: Bool = false) {
        NSLog("[VonageVoice] \(description)")
        if isError {
            sendEvent(FlutterError(code: "unavailable", message: description, details: nil))
        } else {
            sendEvent(description)
        }
    }

    /// Dispatches an event to Flutter, or queues it if Flutter isn't listening.
    /// LOG events are never queued — only critical call-state events are preserved.
    private func sendEvent(_ event: Any) {
        guard let sink = eventSink else {
            // Only queue non-LOG events (critical call state changes).
            if let str = event as? String, !str.hasPrefix("LOG|") {
                if pendingEvents.count < VonageVoicePlugin.maxPendingEvents {
                    pendingEvents.append(event)
                } else {
                    pendingEvents.removeFirst()
                    pendingEvents.append(event)
                }
            }
            return
        }
        DispatchQueue.main.async { sink(event) }
    }


    // ═════════════════════════════════════════════════════════════════
    // MARK: - Method Channel Router (Flutter → Native)
    // ═════════════════════════════════════════════════════════════════

    /// Receives a command from Flutter and dispatches it to the correct handler.
    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        let arguments = call.arguments as? [String: Any] ?? [:]

        switch call.method {

        // ── Session ──────────────────────────────────────────────────
        case "tokens":
            handleTokens(arguments: arguments, result: result)
        case "unregister":
            handleUnregister(arguments: arguments, result: result)
        case "refreshSession":
            handleRefreshSession(arguments: arguments, result: result)
        case "getDeviceId":
            let storedId = deviceId ?? UserDefaults.standard.string(forKey: Keys.cachedDeviceId)
            result(storedId)

        // ── Calls ────────────────────────────────────────────────────
        case "makeCall":
            handleMakeCall(arguments: arguments, result: result)
        case "hangUp":
            handleHangUp(result: result)
        case "answer":
            handleAnswer(result: result)
        case "isOnCall":
            result(!activeCalls.isEmpty)
        case "call-sid":
            if let uuid = activeCallUUID, let callId = activeCalls[uuid] {
                result(callId)
            } else {
                result(nil)
            }

        // ── Mute ─────────────────────────────────────────────────────
        case "toggleMute":
            handleToggleMute(arguments: arguments, result: result)
        case "isMuted":
            result(isMuted)

        // ── Speaker ──────────────────────────────────────────────────
        case "toggleSpeaker":
            handleToggleSpeaker(arguments: arguments, result: result)
        case "isOnSpeaker":
            result(isSpeakerOn())

        // ── Bluetooth ────────────────────────────────────────────────
        case "toggleBluetooth":
            handleToggleBluetooth(arguments: arguments, result: result)
        case "isBluetoothOn":
            result(isBluetoothOn())
        case "isBluetoothAvailable":
            result(isBluetoothAvailable())
        case "isBluetoothEnabled":
            // iOS doesn't expose BT adapter state — return BT audio availability instead.
            result(isBluetoothAvailable())
        case "showBluetoothEnablePrompt":
            // Not available on iOS.
            result(false)
        case "openBluetoothSettings":
            if let url = URL(string: UIApplication.openSettingsURLString) {
                UIApplication.shared.open(url, options: [:], completionHandler: nil)
            }
            result(true)

        // ── Audio Device Management ──────────────────────────────────
        case "getAudioDevices":
            result(getAudioDevicesList())
        case "selectAudioDevice":
            handleSelectAudioDevice(arguments: arguments, result: result)

        // ── DTMF ─────────────────────────────────────────────────────
        case "sendDigits":
            handleSendDigits(arguments: arguments, result: result)

        // ── Permissions ──────────────────────────────────────────────
        case "hasMicPermission":
            result(AVAudioSession.sharedInstance().recordPermission == .granted)
        case "requestMicPermission":
            handleRequestMicPermission(result: result)
        case "hasBluetoothPermission", "requestBluetoothPermission":
            // iOS doesn't require explicit Bluetooth permission for audio routing.
            result(true)

        // ── Caller Registry ──────────────────────────────────────────
        case "registerClient":
            handleRegisterClient(arguments: arguments, result: result)
        case "unregisterClient":
            handleUnregisterClient(arguments: arguments, result: result)
        case "defaultCaller":
            handleDefaultCaller(arguments: arguments, result: result)

        // ── Notifications ────────────────────────────────────────────
        case "showNotifications":
            let show = arguments["show"] as? Bool ?? true
            UserDefaults.standard.set(show, forKey: "vonage-show-notifications")
            result(true)

        // ── CallKit Icon ─────────────────────────────────────────────
        case "updateCallKitIcon":
            let icon = arguments["icon"] as? String ?? Keys.defaultCallKitIcon
            result(updateCallKitIcon(icon: icon))

        // ── Push Processing ──────────────────────────────────────────
        case "processVonagePush":
            handleProcessPush(arguments: arguments, result: result)

        // ── Android-Only Methods: return safe defaults ────────────────
        //
        // These methods exist on Android (Telecom PhoneAccount, extra
        // runtime permissions, battery optimization, full-screen intent)
        // but have no iOS equivalent.
        //
        // Return sensible defaults so Flutter Dart code can call the same
        // API on both platforms without platform checks:
        //   • Permission checks → true  (iOS doesn't need them)
        //   • Settings screens  → true  (no-op on iOS)
        //   • Battery optimized → false (not an issue on iOS)
        //
        // Deprecated methods (backgroundCallUI, hasBluetoothPermission,
        // requestBluetoothPermission) also return safe defaults here.
        // See the Dart @Deprecated annotations for full migration docs.

        // PhoneAccount (Android Telecom) — iOS uses CallKit instead.
        case "hasRegisteredPhoneAccount", "registerPhoneAccount",
             "isPhoneAccountEnabled", "openPhoneAccountSettings":
            result(true)

        // Android runtime permissions — iOS handles these via Info.plist.
        case "hasReadPhoneStatePermission", "requestReadPhoneStatePermission",
             "hasCallPhonePermission", "requestCallPhonePermission",
             "hasManageOwnCallsPermission", "requestManageOwnCallsPermission",
             "hasReadPhoneNumbersPermission", "requestReadPhoneNumbersPermission",
             "hasNotificationPermission", "requestNotificationPermission":
            result(true)

        // Call rejection on no permissions — not needed on iOS.
        case "rejectCallOnNoPermissions":
            result(true)
        case "isRejectingCallOnNoPermissions":
            result(false)

        // Battery optimization — not an issue on iOS.
        case "isBatteryOptimized":
            result(false)
        case "requestBatteryOptimizationExemption":
            result(true)

        // Full-screen intent (Android 14+) — iOS always shows full-screen.
        case "canUseFullScreenIntent":
            result(true)
        case "openFullScreenIntentSettings":
            result(true)

        // Deprecated: custom background call UI overlay (Android only).
        // Now handled by ConnectionService on Android, CallKit on iOS.
        case "backgroundCallUI":
            result(true)

        // ── Platform Version ─────────────────────────────────────────
        case "getPlatformVersion":
            result("iOS " + UIDevice.current.systemVersion)

        // ── CallKit Lifecycle ─────────────────────────────────────────
        case "teardownCallKit":
            // No-op: teardownCallKit() was removed. Kept for Dart backward compat.
            result(nil)

        default:
            result(FlutterMethodNotImplemented)
        }
    }
}


// ═══════════════════════════════════════════════════════════════════════
// MARK: - Session Management
// ═══════════════════════════════════════════════════════════════════════
//
//  Login flow:
//    1. Flutter calls `tokens(jwt:)` → handleTokens()
//    2. We store the JWT, create a Vonage session, register VoIP token.
//
//  Logout flow:
//    1. Flutter calls `unregister()` → handleUnregister()
//    2. We unregister the push token, delete the session, clear stored data.
//

extension VonageVoicePlugin {

    private func handleTokens(arguments: [String: Any], result: @escaping FlutterResult) {
        guard let jwt = arguments["jwt"] as? String else {
            result(FlutterError(code: "INVALID_ARGUMENTS", message: "Missing jwt", details: nil))
            return
        }

        // NOTE: setupCallKit() is intentionally NOT called here.
        // Registering Vonage's CXProvider at token-fetch time means it is active during
        // Twilio outgoing calls. iOS then broadcasts CXStartCallAction to Vonage's delegate.
        // Even with a bare-return guard, CallKit requires every registered CXProviderDelegate
        // to respond (fulfill/fail) within a timeout — a silent return causes a pending-action
        // timeout that retries the Twilio transaction and ultimately fails the call.
        //
        // setupCallKit() is called lazily at the correct callsites instead:
        //   • performStartCallAction(uuid:handle:)  — just before a Vonage OUTGOING call
        //   • pushRegistry(didReceiveIncomingPushWith:) — when a Vonage INCOMING push arrives

        accessToken = jwt
        isSandbox = arguments["isSandbox"] as? Bool ?? false
        storeJwt(jwt)

        // If PushKit already restored the session (killed-state), skip creation.
        if isSessionReady {
            sendPhoneCallEvents(description: "LOG|tokens: Session already ready (restored by PushKit), skipping createSession")
            if let token = deviceToken {
                registerPushToken(token)
            }
            result(true)
            return
        }

        if pendingPushUUID != nil {
            sendPhoneCallEvents(description: "LOG|tokens: Session restore in progress via PushKit push — skipping duplicate createSession")
            result(true)
            return
        }

        sendPhoneCallEvents(description: "LOG|tokens: Creating session with Vonage")

        voiceClient.createSession(jwt) { [weak self] error, sessionId in
            guard let self = self else { return }
            if let error = error {
                self.sendPhoneCallEvents(description: "LOG|createSession failed: \(error.localizedDescription)")
                result(FlutterError(code: "SESSION_ERROR", message: error.localizedDescription, details: nil))
                return
            }

            self.isSessionReady = true
            self.sendPhoneCallEvents(description: "LOG|Session created successfully: \(sessionId ?? "nil")")

            if let token = self.deviceToken {
                self.registerPushToken(token)
            }
            result(true)
        }
    }

    private func handleUnregister(arguments: [String: Any], result: @escaping FlutterResult) {
        // Capture deviceId BEFORE clearing local state so the SDK call can still use it.
        let effectiveDeviceId = deviceId ?? UserDefaults.standard.string(forKey: Keys.cachedDeviceId)

        // Clear all local session state immediately.
        // isSessionReady = false ensures any incoming push received during the
        // async SDK calls below is ignored.
        accessToken = nil
        deviceId = nil
        isSessionReady = false
        UserDefaults.standard.removeObject(forKey: Keys.cachedDeviceId)
        clearStoredJwt()

        // Release the CXProvider when no real Vonage call is active.
        // Without this, any .incomingCall VoIP push arriving after logout would hit
        // the "using existing provider" path in reportDummyCallAndEnd (because
        // callKitProvider is still non-nil), firing providerDidBegin and making the
        // brief "Unknown" dummy call more prominent. Clearing it here ensures the
        // temp-provider path is always used for post-logout pushes.
        if activeCalls.isEmpty && callInvites.isEmpty, callKitProvider != nil {
            isIntentionalProviderReset = true
            let providerToInvalidate = callKitProvider
            callKitProvider = nil
            callKitCallController = nil
            providerToInvalidate?.setDelegate(nil, queue: nil)
            DispatchQueue.main.async {
                providerToInvalidate?.invalidate()
                VGVoiceClient.isUsingCallKit = false
            }
        }

        // Deletes the Vonage session and resolves the Dart future.
        // Called after unregisterDeviceTokens completes (or is skipped when no deviceId).
        // IMPORTANT: result() is called here — inside the last callback — so the
        // Dart `await` only resolves once the SDK network calls have actually finished.
        let deleteSessionAndResolve = { [weak self] in
            guard let self = self else { result(true); return }
            self.voiceClient.deleteSession { [weak self] error in
                if let error = error {
                    self?.sendPhoneCallEvents(description: "LOG|deleteSession failed: \(error.localizedDescription)")
                } else {
                    self?.sendPhoneCallEvents(description: "LOG|Session deleted successfully")
                }
                result(true)
            }
        }

        if let id = effectiveDeviceId {
            // De-register this device's push token from Vonage's servers so it stops
            // routing calls here. Chain deleteSession after — result() fires only once both complete.
            voiceClient.unregisterDeviceTokens(byDeviceId: id) { [weak self] error in
                if let error = error {
                    self?.sendPhoneCallEvents(description: "LOG|unregisterDeviceTokens failed: \(error.localizedDescription)")
                } else {
                    self?.sendPhoneCallEvents(description: "LOG|unregisterDeviceTokens succeeded for deviceId: \(id)")
                }
                deleteSessionAndResolve()
            }
        } else {
            sendPhoneCallEvents(description: "LOG|handleUnregister: no deviceId in memory or UserDefaults — skipping unregisterDeviceTokens")
            deleteSessionAndResolve()
        }
    }

    private func handleRefreshSession(arguments: [String: Any], result: @escaping FlutterResult) {
        guard let jwt = arguments["jwt"] as? String else {
            result(FlutterError(code: "INVALID_ARGUMENTS", message: "Missing jwt", details: nil))
            return
        }

        accessToken = jwt
        storeJwt(jwt)

        voiceClient.refreshSession(jwt) { [weak self] error in
            if let error = error {
                self?.sendPhoneCallEvents(description: "LOG|refreshSession failed: \(error.localizedDescription)")
                result(FlutterError(code: "REFRESH_ERROR", message: error.localizedDescription, details: nil))
            } else {
                self?.sendPhoneCallEvents(description: "LOG|Session refreshed successfully")
                result(true)
            }
        }
    }

    // ─── Push Token Registration ─────────────────────────────────────

    /// Unregisters any old deviceId first (to free a device slot),
    /// then registers the current token.
    private func registerPushToken(_ token: Data) {
        let storedId = UserDefaults.standard.string(forKey: Keys.cachedDeviceId)
        if let oldId = storedId, oldId != deviceId {
            sendPhoneCallEvents(description: "LOG|Unregistering old deviceId before re-registering: \(oldId)")
            voiceClient.unregisterDeviceTokens(byDeviceId: oldId) { [weak self] error in
                if let error = error {
                    self?.sendPhoneCallEvents(description: "LOG|Old device unregister failed (non-fatal): \(error.localizedDescription)")
                } else {
                    self?.sendPhoneCallEvents(description: "LOG|Old device unregistered successfully")
                }
                self?.doRegisterVoipToken(token, isRetry: false)
            }
        } else {
            doRegisterVoipToken(token, isRetry: false)
        }
    }

    /// Actually registers the VoIP token with Vonage.
    /// If the first attempt fails with "max-device-limit", unregisters the old device and retries once.
    private func doRegisterVoipToken(_ token: Data, isRetry: Bool) {
        let hexToken = token.map { String(format: "%02x", $0) }.joined()
        NSLog("[VonageVoice-Push] registerVoipToken sandbox=%d retry=%d tokenLen=%d hex=%@",
              isSandbox ? 1 : 0, isRetry ? 1 : 0, token.count, hexToken)
        sendPhoneCallEvents(description: "LOG|Registering VoIP push token with Vonage (sandbox=\(isSandbox), retry=\(isRetry)) hex=\(hexToken)")

        voiceClient.registerVoipToken(token, isSandbox: isSandbox) { [weak self] error, newDeviceId in
            guard let self = self else { return }

            if let error = error {
                let desc = error.localizedDescription
                self.sendPhoneCallEvents(description: "LOG|registerVoipToken failed: \(desc)")

                // Auto-recover from max-device-limit by unregistering the old device and retrying.
                if !isRetry && desc.contains("max-device-limit") {
                    self.sendPhoneCallEvents(description: "LOG|Max device limit reached — attempting to unregister stored device and retry")
                    if let oldId = self.deviceId ?? UserDefaults.standard.string(forKey: Keys.cachedDeviceId) {
                        self.voiceClient.unregisterDeviceTokens(byDeviceId: oldId) { [weak self] unregError in
                            if let unregError = unregError {
                                self?.sendPhoneCallEvents(description: "LOG|Unregister for retry failed: \(unregError.localizedDescription)")
                            }
                            self?.doRegisterVoipToken(token, isRetry: true)
                        }
                    } else {
                        self.sendPhoneCallEvents(description: "LOG|No stored deviceId to unregister — cannot auto-recover from max-device-limit. Use Vonage REST API to clear old devices.")
                    }
                }
                return
            }

            // Success — persist the new deviceId.
            self.deviceId = newDeviceId
            if let id = newDeviceId {
                UserDefaults.standard.set(id, forKey: Keys.cachedDeviceId)
            }
            self.sendPhoneCallEvents(description: "LOG|VoIP push token registered. deviceId=\(newDeviceId ?? "nil")")
        }
    }
}


// ═══════════════════════════════════════════════════════════════════════
// MARK: - Call Management
// ═══════════════════════════════════════════════════════════════════════
//
//  All call actions go through CallKit so iOS manages the call lifecycle:
//    • makeCall   → CXStartCallAction  → provider(perform:startCall)
//    • answer     → CXAnswerCallAction → provider(perform:answerCall)
//    • hangUp     → CXEndCallAction    → provider(perform:endCall)
//

extension VonageVoicePlugin {

    // ─── Outgoing Call ───────────────────────────────────────────────

    /// Handles the `makeCall` method from Flutter.
    ///
    /// Flow:
    ///   1. Validates the `to` parameter from Flutter arguments.
    ///   2. Stores caller identity, callee, and extra options.
    ///   3. Requests CallKit to start an outgoing call via `CXStartCallAction`.
    ///   4. CallKit triggers `provider(perform: CXStartCallAction)` where
    ///      the actual Vonage `serverCall()` is made.
    ///
    /// - Parameter arguments: `["to": String, "from": String?, "CallerName": String?, ...]`
    /// - Parameter result: Returns `true` on success, `FlutterError` on failure.

    private func handleMakeCall(arguments: [String: Any], result: @escaping FlutterResult) {
        sendPhoneCallEvents(description: "LOG|handleMakeCall arguments: \(arguments)")

        // ── Reset audio state for new outgoing call ──────────────────────
        // Without this, a stale `desiredSpeakerState = true` from a
        // previous call could cause the loudspeaker to activate during the
        // outgoing ringing phase when `didActivateAudioSession` fires.
        // Every new call starts with the default audio priority:
        //   Bluetooth (if connected) → Earpiece → never Speaker.
        desiredSpeakerState = false
        desiredBluetoothState = false
        userExplicitlyChangedAudioRoute = false

        let from = arguments["from"] as? String ?? ""
        let callerName = arguments["CallerName"] as? String ?? ""

        // Debug: log each argument key, value, and Swift type
        for (key, value) in arguments {
            sendPhoneCallEvents(description: "LOG|  arg[\(key)] = \(value) (type: \(type(of: value)))")
        }

        guard let to = arguments["to"] as? String else {
            sendPhoneCallEvents(description: "LOG|handleMakeCall FAILED — 'to' key not found or not String. Keys: \(arguments.keys.sorted())")
            // Try to log what 'to' actually is
            if let raw = arguments["to"] {
                sendPhoneCallEvents(description: "LOG|  'to' raw value=\(raw), type=\(type(of: raw))")
            }
            result(FlutterError(code: "INVALID_ARGUMENTS", message: "Missing 'to' parameter", details: nil))
            return
        }

        sendPhoneCallEvents(description: "LOG|handleMakeCall resolved: from=\(from) to=\(to) callerName=\(callerName)")
        sendPhoneCallEvents(description: "LOG|handleMakeCall state: isSessionReady=\(isSessionReady) activeCalls=\(activeCalls.count) callInvites=\(callInvites.count)")

        callArgs = arguments
        callOutgoing = true
        callTo = to
        identity = from
        outgoingCallerName = callerName

        // Can't make a call while there's a pending incoming call.
        if !callInvites.isEmpty {
            sendPhoneCallEvents(description: "LOG|Cannot make call - pending incoming call exists")
            result(FlutterError(code: "CALL_IN_PROGRESS", message: "Cannot make call while there's a pending incoming call", details: nil))
            return
        }

        performStartCallAction(uuid: UUID(), handle: to)
        result(true)
    }

    // ─── Hang Up ─────────────────────────────────────────────────────

    /// Handles the `hangUp` method from Flutter.
    ///
    /// If there's an active (answered) call, ends it via `CXEndCallAction`.
    /// If there's only a pending incoming invite, rejects it instead.
    /// Both paths go through CallKit so the system call UI is updated.
    ///
    /// - Parameter result: Returns `true` immediately (async completion via CallKit).

    private func handleHangUp(result: @escaping FlutterResult) {
        if let uuid = activeCallUUID, activeCalls[uuid] != nil {
            // End an active (answered) call.
            sendPhoneCallEvents(description: "LOG|hangUp: ending active call uuid=\(uuid)")
            userInitiatedDisconnect = true
            performEndCallAction(uuid: uuid)
        } else if let (uuid, _) = callInvites.first {
            // Reject a pending incoming invite.
            sendPhoneCallEvents(description: "LOG|hangUp: rejecting pending invite uuid=\(uuid)")
            performEndCallAction(uuid: uuid)
        }
        result(true)
    }

    // ─── Answer ──────────────────────────────────────────────────────

    /// Handles the `answer` method from Flutter.
    ///
    /// Sends a `CXAnswerCallAction` to CallKit for the first pending
    /// incoming invite. CallKit then triggers
    /// `provider(perform: CXAnswerCallAction)` where the actual
    /// `voiceClient.answer()` is called.
    ///
    /// - Parameter result: Returns `true` on success, `FlutterError` if no invite exists.

    private func handleAnswer(result: @escaping FlutterResult) {
        if let (uuid, _) = callInvites.first {
            sendPhoneCallEvents(description: "LOG|answer: answering invite uuid=\(uuid)")

            // ── Reset audio state for answered call ──────────────────────
            // Ensures the answered call starts with the correct audio priority
            // (Bluetooth → Earpiece) and doesn't inherit speaker state from
            // a previous call.
            desiredSpeakerState = false
            desiredBluetoothState = false
            userExplicitlyChangedAudioRoute = false

            let answerAction = CXAnswerCallAction(call: uuid)
            let transaction = CXTransaction(action: answerAction)
            callKitCallController?.request(transaction) { [weak self] error in
                if let error = error {
                    self?.sendPhoneCallEvents(description: "LOG|AnswerCallAction failed: \(error.localizedDescription)")
                }
            }
        } else {
            result(FlutterError(code: "ANSWER_ERROR", message: "No call invite to answer", details: nil))
            return
        }
        result(true)
    }
    
    // ─── Mute ────────────────────────────────────────────────────────

    /// Handles the `toggleMute` method from Flutter.
    ///
    /// Calls `voiceClient.mute()` or `voiceClient.unmute()` on the
    /// active Vonage call. Sends `"Mute"` / `"Unmute"` events to Flutter.
    ///
    /// - Parameter arguments: `["muted": Bool]`
    /// - Parameter result: Returns `true` on success, `FlutterError` if no call.

    private func handleToggleMute(arguments: [String: Any], result: @escaping FlutterResult) {
        guard let muted = arguments["muted"] as? Bool else {
            result(false)
            return
        }

        guard let uuid = activeCallUUID, let callId = activeCalls[uuid] else {
            result(FlutterError(code: "MUTE_ERROR", message: "No active call", details: nil))
            return
        }

        if muted {
            voiceClient.mute(callId) { [weak self] error in
                if let error = error {
                    self?.sendPhoneCallEvents(description: "LOG|Mute failed: \(error.localizedDescription)")
                } else {
                    self?.isMuted = true
                    self?.sendPhoneCallEvents(description: "Mute")
                }
            }
        } else {
            voiceClient.unmute(callId) { [weak self] error in
                if let error = error {
                    self?.sendPhoneCallEvents(description: "LOG|Unmute failed: \(error.localizedDescription)")
                } else {
                    self?.isMuted = false
                    self?.sendPhoneCallEvents(description: "Unmute")
                }
            }
        }
        result(true)
    }

    // ─── DTMF ────────────────────────────────────────────────────────

    /// Handles the `sendDigits` method from Flutter.
    ///
    /// Sends DTMF tones via `voiceClient.sendDTMF()` for IVR navigation.
    /// Valid characters: `0-9`, `*`, `#`.
    ///
    /// - Parameter arguments: `["digits": String]`
    /// - Parameter result: Returns `true` on success.

    private func handleSendDigits(arguments: [String: Any], result: @escaping FlutterResult) {
        guard let digits = arguments["digits"] as? String,
              let uuid = activeCallUUID,
              let callId = activeCalls[uuid] else {
            result(false)
            return
        }
        voiceClient.sendDTMF(callId, withDigits: digits) { [weak self] error in
            if let error = error {
                self?.sendPhoneCallEvents(description: "LOG|sendDTMF failed: \(error.localizedDescription)")
            }
        }
        result(true)
    }

    // ─── Push Processing ─────────────────────────────────────────────

    /// Handles the `processVonagePush` method from Flutter.
    ///
    /// When `firebase_messaging` intercepts a push notification in Dart,
    /// the native Vonage SDK never sees it. This method forwards the raw
    /// push payload to `voiceClient.processCallInvitePushData()` so the
    /// SDK can detect incoming call invites.
    ///
    /// On iOS, PushKit typically handles VoIP pushes directly, but this
    /// is available as a fallback path for cross-platform consistency.
    ///
    /// Returns `"processed"` if the push was a Vonage call invite, `nil` otherwise.

    private func handleProcessPush(arguments: [String: Any], result: @escaping FlutterResult) {
        guard let data = arguments["data"] as? [String: Any] else {
            result(nil)
            return
        }
        if VGBaseClient.vonagePushType(data) == .incomingCall {
            voiceClient.processCallInvitePushData(data)
            result("processed")
        } else {
            result(nil)
        }
    }
}


// ═══════════════════════════════════════════════════════════════════════
// MARK: - Audio Management
// ═══════════════════════════════════════════════════════════════════════
//
//  iOS audio routing uses AVAudioSession.
//  CallKit owns the audio session lifecycle — we only override the
//  output port or set a preferred input *after* CallKit activates it.
//

extension VonageVoicePlugin {

    // ─── Flutter Method Handlers ─────────────────────────────────────

    private func handleToggleSpeaker(arguments: [String: Any], result: @escaping FlutterResult) {
        guard let speakerOn = arguments["speakerIsOn"] as? Bool else {
            result(false)
            return
        }

        desiredSpeakerState = speakerOn
        if speakerOn { desiredBluetoothState = false }
        userExplicitlyChangedAudioRoute = true
        applySpeakerSetting(toSpeaker: speakerOn)
        result(true)
    }

    private func handleToggleBluetooth(arguments: [String: Any], result: @escaping FlutterResult) {
        guard let bluetoothOn = arguments["bluetoothOn"] as? Bool else {
            result(false)
            return
        }

        if bluetoothOn {
            desiredBluetoothState = true
            desiredSpeakerState = false
        } else {
            desiredBluetoothState = false
        }
        userExplicitlyChangedAudioRoute = true
        toggleBluetoothAudio(bluetoothOn: bluetoothOn)
        result(true)
    }

    // ─── Speaker ─────────────────────────────────────────────────────

    /// Toggles the speakerphone using `AVAudioSession.overrideOutputAudioPort()`.
    ///
    /// When enabling speaker: overrides to `.speaker`.
    /// When disabling: overrides to `.none` and selects built-in mic
    /// as preferred input to force earpiece mode.

    private func applySpeakerSetting(toSpeaker: Bool) {
        let session = AVAudioSession.sharedInstance()
        do {
            if toSpeaker {
                try session.overrideOutputAudioPort(.speaker)
                sendPhoneCallEvents(description: "Speaker On")
            } else {
                try session.overrideOutputAudioPort(.none)
                // Route to earpiece by selecting the built-in mic as preferred input.
                try setPreferredInput(portType: .builtInMic)
                sendPhoneCallEvents(description: "Speaker Off")
            }
        } catch {
            sendPhoneCallEvents(description: "LOG|applySpeakerSetting failed: \(error.localizedDescription)")
        }
    }

    // ─── Bluetooth ───────────────────────────────────────────────────

    /// Toggles Bluetooth audio using `AVAudioSession` category options.
    ///
    /// When enabling: sets `.allowBluetooth` + `.allowBluetoothA2DP`
    /// category options and selects the first available Bluetooth input.
    /// When disabling: removes BT options and routes back to earpiece.
    ///
    /// Supported Bluetooth types: HFP, A2DP, LE Audio.

    private func toggleBluetoothAudio(bluetoothOn: Bool) {
        let session = AVAudioSession.sharedInstance()
        do {
            if bluetoothOn {
                try session.setCategory(.playAndRecord, mode: .voiceChat,
                                        options: [.allowBluetooth, .allowBluetoothA2DP])
                // Find and select the first available Bluetooth input.
                try setPreferredInput(portTypes: Self.bluetoothPorts)
                try session.overrideOutputAudioPort(.none)
                sendPhoneCallEvents(description: "Bluetooth On")
            } else {
                // Route back to earpiece.
                try session.setCategory(.playAndRecord, mode: .voiceChat, options: [])
                try setPreferredInput(portType: .builtInMic)
                try session.overrideOutputAudioPort(.none)
                sendPhoneCallEvents(description: "Bluetooth Off")
            }
        } catch {
            sendPhoneCallEvents(description: "LOG|toggleBluetoothAudio failed: \(error.localizedDescription)")
        }
    }

    // ─── Audio Route Queries ─────────────────────────────────────────

    private func isSpeakerOn() -> Bool {
        AVAudioSession.sharedInstance().currentRoute.outputs
            .contains { $0.portType == .builtInSpeaker }
    }

    private func isBluetoothOn() -> Bool {
        AVAudioSession.sharedInstance().currentRoute.outputs
            .contains { Self.bluetoothPorts.contains($0.portType) }
    }

    private func isBluetoothAvailable() -> Bool {
        let session = AVAudioSession.sharedInstance()
        let route = session.currentRoute

        // Check current outputs and inputs.
        if route.outputs.contains(where: { Self.bluetoothPorts.contains($0.portType) }) { return true }
        if route.inputs.contains(where: { Self.bluetoothPorts.contains($0.portType) }) { return true }

        // Check available inputs (paired but not currently active).
        if let inputs = session.availableInputs,
           inputs.contains(where: { Self.bluetoothPorts.contains($0.portType) }) { return true }

        return false
    }

    /// Returns the current audio route as a human-readable string.
    private func getAudioRoute() -> String {
        for output in AVAudioSession.sharedInstance().currentRoute.outputs {
            switch output.portType {
            case _ where Self.bluetoothPorts.contains(output.portType):
                return "bluetooth"
            case .builtInSpeaker:
                return "speaker"
            case .headphones, .headsetMic:
                return "wired_headset"
            default:
                break
            }
        }
        // Fallback: check desired state.
        if desiredSpeakerState { return "speaker" }
        if desiredBluetoothState { return "bluetooth" }
        return "earpiece"
    }

    // ─── OS Audio Route Change Notification ──────────────────────────

    @objc private func handleAudioRouteChange(notification: Notification) {
        guard !isChangingAudioRoute else { return }
        guard !userExplicitlyChangedAudioRoute else {
            userExplicitlyChangedAudioRoute = false
            return
        }
        // Only send audio route events when there are active calls.
        guard !activeCalls.isEmpty else { return }

        // Extract the change reason for diagnostics and proper handling.
        let reasonValue = (notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt)
            ?? UInt.max
        let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue)

        switch reason {
        case .routeConfigurationChange:
            // Reason 8: Another SDK (e.g. Twilio) or CallKit reconfigured the
            // shared AVAudioSession's category/mode/options/route. Log full
            // state so we can diagnose cross-SDK desynchronisation.
            logFullAudioSessionState(label: "routeConfigurationChange(8)")

            // Re-sync cached state with the ACTUAL system route.
            // Without this, desiredSpeakerState / desiredBluetoothState can
            // drift from reality when the other SDK changes the session.
            let actualSpeaker = isSpeakerOn()
            let actualBluetooth = isBluetoothOn()
            if actualSpeaker != desiredSpeakerState {
                sendPhoneCallEvents(description:
                    "LOG|routeConfigurationChange: resyncing desiredSpeakerState " +
                    "\(desiredSpeakerState) → \(actualSpeaker)")
                desiredSpeakerState = actualSpeaker
            }
            if actualBluetooth != desiredBluetoothState {
                sendPhoneCallEvents(description:
                    "LOG|routeConfigurationChange: resyncing desiredBluetoothState " +
                    "\(desiredBluetoothState) → \(actualBluetooth)")
                desiredBluetoothState = actualBluetooth
            }
            // Send the corrected route event to Flutter.
            sendPhoneCallEvents(description: "AudioRoute|\(getAudioRoute())|bluetoothAvailable=\(isBluetoothAvailable())")

        case .oldDeviceUnavailable:
            sendPhoneCallEvents(description: "LOG|handleAudioRouteChange reason=oldDeviceUnavailable")
            // A device was disconnected — update BT/speaker state.
            desiredBluetoothState = isBluetoothOn()
            sendPhoneCallEvents(description: "AudioRoute|\(getAudioRoute())|bluetoothAvailable=\(isBluetoothAvailable())")

        case .newDeviceAvailable:
            sendPhoneCallEvents(description: "LOG|handleAudioRouteChange reason=newDeviceAvailable")
            sendPhoneCallEvents(description: "AudioRoute|\(getAudioRoute())|bluetoothAvailable=\(isBluetoothAvailable())")

        default:
            sendPhoneCallEvents(description: "LOG|handleAudioRouteChange reason=\(reasonValue)")
            sendPhoneCallEvents(description: "AudioRoute|\(getAudioRoute())|bluetoothAvailable=\(isBluetoothAvailable())")
        }
    }

    // ─── Audio Session Diagnostics ───────────────────────────────

    /// Logs the complete AVAudioSession state for cross-SDK diagnostics.
    /// Call this before/after critical audio lifecycle transitions to
    /// verify the session is in the expected state.
    private func logFullAudioSessionState(label: String) {
        let session = AVAudioSession.sharedInstance()
        let outputs = session.currentRoute.outputs.map { $0.portType.rawValue }
        let inputs  = session.currentRoute.inputs.map  { $0.portType.rawValue }
        let availInputs = session.availableInputs?.map { $0.portType.rawValue } ?? []
        sendPhoneCallEvents(description:
            "LOG|[\(label)] " +
            "category=\(session.category.rawValue) " +
            "mode=\(session.mode.rawValue) " +
            "options=\(session.categoryOptions.rawValue) " +
            "outputs=\(outputs) inputs=\(inputs) " +
            "availableInputs=\(availInputs) " +
            "preferredInput=\(session.preferredInput?.portType.rawValue ?? "nil") " +
            "isOtherAudioPlaying=\(session.isOtherAudioPlaying) " +
            "secondaryAudioHint=\(session.secondaryAudioShouldBeSilencedHint) " +
            "desiredSpeaker=\(desiredSpeakerState) desiredBT=\(desiredBluetoothState)")
    }

    // ─── Audio Input Helpers ─────────────────────────────────────────

    /// Finds the first available input matching `portType` and sets it as preferred.
    private func setPreferredInput(portType: AVAudioSession.Port) throws {
        guard let inputs = AVAudioSession.sharedInstance().availableInputs else { return }
        if let match = inputs.first(where: { $0.portType == portType }) {
            try AVAudioSession.sharedInstance().setPreferredInput(match)
        }
    }

    /// Finds the first available input matching any of `portTypes` and sets it as preferred.
    private func setPreferredInput(portTypes: Set<AVAudioSession.Port>) throws {
        guard let inputs = AVAudioSession.sharedInstance().availableInputs else { return }
        if let match = inputs.first(where: { portTypes.contains($0.portType) }) {
            try AVAudioSession.sharedInstance().setPreferredInput(match)
        }
    }

    // ─── Audio Device Listing ────────────────────────────────────────

    /// Returns all available audio output devices as a list of dictionaries.
    ///
    /// Each dictionary contains:
    ///   - id: String (port UID)
    ///   - type: String ("earpiece", "speaker", "bluetooth", "wiredHeadset", "unknown")
    ///   - name: String (port name, e.g. "AirPods Pro")
    ///   - isActive: Bool (true if currently routing audio)
    private func getAudioDevicesList() -> [[String: Any]] {
        let session = AVAudioSession.sharedInstance()
        let currentOutputs = session.currentRoute.outputs
        let activePortUIDs = Set(currentOutputs.map { $0.uid })

        var devices: [[String: Any]] = []
        var seenUIDs: Set<String> = []

        // 1) Current outputs (active devices)
        for output in currentOutputs {
            let type = mapPortType(output.portType)
            if seenUIDs.insert(output.uid).inserted {
                devices.append([
                    "id": output.uid,
                    "type": type,
                    "name": output.portName,
                    "isActive": true
                ])
            }
        }

        // 2) Available inputs — surfaces additional Bluetooth and wired devices
        if let inputs = session.availableInputs {
            for input in inputs {
                // Skip input-only ports — only list output devices
                if input.portType == .builtInMic || input.portType == .headsetMic { continue }
                let type = mapPortType(input.portType)
                if type == "unknown" { continue }
                if seenUIDs.insert(input.uid).inserted {
                    devices.append([
                        "id": input.uid,
                        "type": type,
                        "name": input.portName,
                        "isActive": false
                    ])
                }
            }
        }

        // 3) Ensure earpiece is always listed (builtInReceiver via output when no input match)
        if !devices.contains(where: { ($0["type"] as? String) == "earpiece" }) {
            devices.insert([
                "id": "earpiece",
                "type": "earpiece",
                "name": "Earpiece",
                "isActive": !isSpeakerOn() && !isBluetoothOn()
            ], at: 0)
        }

        // 4) Ensure speaker is always listed
        if !devices.contains(where: { ($0["type"] as? String) == "speaker" }) {
            devices.append([
                "id": "speaker",
                "type": "speaker",
                "name": "Speaker",
                "isActive": isSpeakerOn()
            ])
        }

        return devices
    }

    /// Maps an AVAudioSession.Port to our device type string.
    private func mapPortType(_ portType: AVAudioSession.Port) -> String {
        switch portType {
        case .builtInReceiver, .builtInMic:
            return "earpiece"
        case .builtInSpeaker:
            return "speaker"
        case _ where Self.bluetoothPorts.contains(portType):
            return "bluetooth"
        case .headphones, .headsetMic:
            return "wiredHeadset"
        case .usbAudio:
            return "wiredHeadset"
        default:
            return "unknown"
        }
    }

    /// Selects a specific audio output device by its UID.
    private func handleSelectAudioDevice(arguments: [String: Any], result: @escaping FlutterResult) {
        guard let deviceId = arguments["deviceId"] as? String else {
            result(false)
            return
        }

        let session = AVAudioSession.sharedInstance()

        userExplicitlyChangedAudioRoute = true

        // Handle built-in devices by type
        if deviceId == "speaker" || isBuiltInSpeaker(uid: deviceId, session: session) {
            desiredSpeakerState = true
            desiredBluetoothState = false
            applySpeakerSetting(toSpeaker: true)
            result(true)
            return
        }

        if deviceId == "earpiece" || isBuiltInEarpiece(uid: deviceId, session: session) {
            desiredSpeakerState = false
            desiredBluetoothState = false
            applySpeakerSetting(toSpeaker: false)
            result(true)
            return
        }

        // Try to find matching input by UID (Bluetooth / wired)
        if let inputs = session.availableInputs,
           let match = inputs.first(where: { $0.uid == deviceId }) {
            let isBluetooth = Self.bluetoothPorts.contains(match.portType)
            do {
                if isBluetooth {
                    try session.setCategory(.playAndRecord, mode: .voiceChat,
                                            options: [.allowBluetooth, .allowBluetoothA2DP])
                }
                try session.setPreferredInput(match)
                try session.overrideOutputAudioPort(.none)

                desiredBluetoothState = isBluetooth
                desiredSpeakerState = false

                if isBluetooth {
                    sendPhoneCallEvents(description: "Bluetooth On")
                }
                result(true)
            } catch {
                sendPhoneCallEvents(description: "LOG|selectAudioDevice failed: \(error.localizedDescription)")
                result(false)
            }
            return
        }

        // Check current outputs (for speaker UID match)
        for output in session.currentRoute.outputs {
            if output.uid == deviceId {
                if output.portType == .builtInSpeaker {
                    desiredSpeakerState = true
                    desiredBluetoothState = false
                    applySpeakerSetting(toSpeaker: true)
                    result(true)
                    return
                }
            }
        }

        result(false)
    }

    /// Checks if a UID corresponds to the built-in speaker.
    private func isBuiltInSpeaker(uid: String, session: AVAudioSession) -> Bool {
        session.currentRoute.outputs.contains { $0.uid == uid && $0.portType == .builtInSpeaker }
    }

    /// Checks if a UID corresponds to the built-in earpiece (receiver).
    private func isBuiltInEarpiece(uid: String, session: AVAudioSession) -> Bool {
        // Earpiece appears as builtInReceiver in outputs or builtInMic in inputs
        if session.currentRoute.outputs.contains(where: { $0.uid == uid && $0.portType == .builtInReceiver }) {
            return true
        }
        if let inputs = session.availableInputs {
            return inputs.contains { $0.uid == uid && $0.portType == .builtInMic }
        }
        return false
    }
}


// ═══════════════════════════════════════════════════════════════════════
// MARK: - Permissions, Caller Registry & Utilities
// ═══════════════════════════════════════════════════════════════════════
//
//  Permissions:
//    iOS only requires microphone permission for voice calls.
//    All other Android-specific permissions (phone state, call phone,
//    manage own calls, etc.) return `true` as safe defaults in the
//    method channel router above.
//
//  Caller Registry:
//    Maps Vonage user IDs → human-readable display names.
//    Stored in UserDefaults and used to show "John Smith" instead of
//    "user-abc-123" on the CallKit incoming-call screen.
//

extension VonageVoicePlugin {

    // ─── Permissions ─────────────────────────────────────────────────

    /// Handles the `requestMicPermission` method from Flutter.
    ///
    /// Checks `AVAudioSession.recordPermission` and either:
    ///   - Returns `true` immediately if already granted.
    ///   - Returns `false` if previously denied.
    ///   - Shows the system microphone dialog if undetermined.
    private func handleRequestMicPermission(result: @escaping FlutterResult) {
        switch AVAudioSession.sharedInstance().recordPermission {
        case .granted:
            result(true)
        case .denied:
            result(false)
        case .undetermined:
            AVAudioSession.sharedInstance().requestRecordPermission { granted in
                result(granted)
            }
        @unknown default:
            result(false)
        }
    }

    // ─── Caller Registry ─────────────────────────────────────────────
    //
    // Maps Vonage user IDs to human-readable display names.
    // Used to show "John Smith" instead of "user-abc-123" on the CallKit UI.
    // Persisted in UserDefaults (key: "VonageContactList").

    /// Registers a caller display name.
    ///
    /// Stores `clientId → clientName` in the caller registry.
    /// Only writes to UserDefaults if the mapping is new or changed.
    ///
    /// - Parameter arguments: `["id": String, "name": String]`
    /// - Parameter result: Returns `true` on success.

    private func handleRegisterClient(arguments: [String: Any], result: @escaping FlutterResult) {
        guard let clientId = arguments["id"] as? String,
              let clientName = arguments["name"] as? String else {
            result(false)
            return
        }
        if clients[clientId] == nil || clients[clientId] != clientName {
            clients[clientId] = clientName
            UserDefaults.standard.set(clients, forKey: Keys.clientList)
        }
        result(true)
    }

    private func handleUnregisterClient(arguments: [String: Any], result: @escaping FlutterResult) {
        guard let clientId = arguments["id"] as? String else {
            result(false)
            return
        }
        clients.removeValue(forKey: clientId)
        UserDefaults.standard.set(clients, forKey: Keys.clientList)
        result(true)
    }

    private func handleDefaultCaller(arguments: [String: Any], result: @escaping FlutterResult) {
        guard let caller = arguments["defaultCaller"] as? String else {
            result(false)
            return
        }
        defaultCaller = caller
        if clients["defaultCaller"] == nil || clients["defaultCaller"] != defaultCaller {
            clients["defaultCaller"] = defaultCaller
            UserDefaults.standard.set(clients, forKey: Keys.clientList)
        }
        result(true)
    }

    // ─── CallKit Icon ────────────────────────────────────────────────

    private func updateCallKitIcon(icon: String) -> Bool {
        guard let newIcon = UIImage(named: icon) else { return false }
        guard let provider = callKitProvider else { return false }
        let configuration = provider.configuration
        configuration.iconTemplateImageData = newIcon.pngData()
        provider.configuration = configuration
        UserDefaults.standard.set(icon, forKey: Keys.defaultCallKitIcon)
        return true
    }

    // ─── JWT Persistence ─────────────────────────────────────────────
    // The JWT is stored in UserDefaults so that when the app is killed
    // and a VoIP push wakes it, we can restore the Vonage session
    // without needing Flutter to provide the token again.

    private func storeJwt(_ jwt: String) {
        UserDefaults.standard.set(jwt, forKey: Keys.cachedJwt)
    }

    private func getStoredJwt() -> String? {
        UserDefaults.standard.string(forKey: Keys.cachedJwt)
    }

    private func clearStoredJwt() {
        UserDefaults.standard.removeObject(forKey: Keys.cachedJwt)
    }
}


// ═══════════════════════════════════════════════════════════════════════
// MARK: - Killed-State Push Helpers
// ═══════════════════════════════════════════════════════════════════════
//
//  When the app is killed and a VoIP push arrives:
//    1. We report to CallKit immediately (with a placeholder caller name)
//    2. We restore the Vonage session from stored JWT
//    3. didReceiveInviteForCall fires → we update the real caller info
//    4. If the user already tapped Answer/Decline, we fulfil that action
//

extension VonageVoicePlugin {

    // ─── Invite Keep-Alive Helpers ───────────────────────────────────

    /// Starts a background task that keeps the app alive while a call
    /// invite is pending. Without this, iOS suspends the app when the
    /// device is locked and the WebSocket cancel event never arrives —
    /// causing CallKit to ring indefinitely.
    private func beginInviteBackgroundTask() {
        guard inviteBackgroundTask == .invalid else {
            sendPhoneCallEvents(description: "LOG|beginInviteBackgroundTask — already active, skipping")
            return
        }
        sendPhoneCallEvents(description: "LOG|beginInviteBackgroundTask — starting")
        inviteBackgroundTask = UIApplication.shared.beginBackgroundTask(withName: "VonageVoiceInvitePending") { [weak self] in
            // iOS is about to suspend us. End any pending invite so the
            // user doesn't see a ghost call on the lock screen forever.
            self?.handleInviteBackgroundTaskExpired()
        }
    }

    /// Ends the background task if one is active. Safe to call multiple times.
    private func endInviteBackgroundTask() {
        guard inviteBackgroundTask != .invalid else { return }
        sendPhoneCallEvents(description: "LOG|endInviteBackgroundTask — ending")
        UIApplication.shared.endBackgroundTask(inviteBackgroundTask)
        inviteBackgroundTask = .invalid
    }

    /// Called when iOS is about to kill the background task.
    /// Ends all pending invites as missed calls so CallKit stops ringing.
    private func handleInviteBackgroundTaskExpired() {
        sendPhoneCallEvents(description: "LOG|Background task expired — ending pending invites")

        // End all pending invites (there should be at most one).
        let pendingUUIDs = callInvites.keys.filter { activeCalls[$0] == nil }
        for uuid in pendingUUIDs {
            let invite = callInvites[uuid]
            callKitProvider?.reportCall(with: uuid, endedAt: Date(), reason: .remoteEnded)
            if let invite = invite {
                callIdToUUID.removeValue(forKey: invite.callId)
            }
            callInvites.removeValue(forKey: uuid)
            callDisconnected(uuid: uuid)
        }
        if !pendingUUIDs.isEmpty {
            sendPhoneCallEvents(description: "Missed Call")
        }

        cancelRingingTimeout()
        endInviteBackgroundTask()
    }

    /// Starts a safety-net timer. If the call invite isn't resolved within
    /// the timeout (e.g. WebSocket cancel was lost), end it as a missed call.
    private func startRingingTimeout() {
        // Timer must be scheduled on the main run loop so it actually fires.
        // SDK callbacks (e.g. createSession completion) may run on background
        // threads whose run loops are not active.
        guard Thread.isMainThread else {
            DispatchQueue.main.async { [weak self] in self?.startRingingTimeout() }
            return
        }
        cancelRingingTimeout()
        sendPhoneCallEvents(description: "LOG|startRingingTimeout — \(VonageVoicePlugin.ringingTimeoutSeconds)s")
        ringingTimeoutTimer = Timer.scheduledTimer(withTimeInterval: VonageVoicePlugin.ringingTimeoutSeconds, repeats: false) { [weak self] _ in
            self?.handleRingingTimeout()
        }
    }

    /// Called when the ringing timeout fires.
    private func handleRingingTimeout() {
        sendPhoneCallEvents(description: "LOG|Ringing timeout fired — ending pending invites")
        ringingTimeoutTimer = nil

        let pendingUUIDs = callInvites.keys.filter { activeCalls[$0] == nil }
        for uuid in pendingUUIDs {
            let invite = callInvites[uuid]
            callKitProvider?.reportCall(with: uuid, endedAt: Date(), reason: .remoteEnded)
            if let invite = invite {
                callIdToUUID.removeValue(forKey: invite.callId)
            }
            callInvites.removeValue(forKey: uuid)
            callDisconnected(uuid: uuid)
        }
        if !pendingUUIDs.isEmpty {
            sendPhoneCallEvents(description: "Missed Call")
        }

        endInviteBackgroundTask()
    }

    /// Cancels the ringing timeout if active.
    private func cancelRingingTimeout() {
        // Must invalidate on the same thread where the timer was scheduled (main).
        guard Thread.isMainThread else {
            DispatchQueue.main.async { [weak self] in self?.cancelRingingTimeout() }
            return
        }
        ringingTimeoutTimer?.invalidate()
        ringingTimeoutTimer = nil
    }

    /// Cleans up all pending push coordination state.
    private func cleanupPushState() {
        pushCompletionTimer?.invalidate()
        pushCompletionTimer = nil
        pendingPushCompletion = nil
        pendingPushUUID = nil
        pendingAnswerAction = nil
        pendingEndAction = nil
        cancelRingingTimeout()
        endInviteBackgroundTask()
    }

    /// Ends the pre-reported call when something goes wrong (timeout or session restore failure).
    /// Called by both the safety timer and the session-restore error handler.
    private func endPreReportedCall(reason: String) {
        guard let uuid = pendingPushUUID else { return }
        sendPhoneCallEvents(description: "LOG|\(reason)")
        callKitProvider?.reportCall(with: uuid, endedAt: Date(), reason: .failed)
        callDisconnected(uuid: uuid)
        sendPhoneCallEvents(description: "Missed Call")
        pendingPushCompletion?()
        cancelRingingTimeout()
        endInviteBackgroundTask()
        cleanupPushState()
    }
}


// ═══════════════════════════════════════════════════════════════════════
// MARK: - CallKit Actions
// ═══════════════════════════════════════════════════════════════════════
//
//  These are internal helpers that request CallKit actions.
//  The actual handling happens in the CXProviderDelegate methods below.
//

extension VonageVoicePlugin {

    /// Requests CallKit to start an outgoing call.
    private func performStartCallAction(uuid: UUID, handle: String) {
        // Ensure Vonage's CXProvider is registered before submitting the CXTransaction.
        // setupCallKit() is idempotent (guarded by `callKitProvider == nil`), so this
        // is safe whether or not an incoming push already called it.
        // This is the correct callsite — registering at token-fetch time would keep
        // Vonage's CXProvider active during Twilio calls, causing CXStartCallAction
        // broadcast interference that silently fails Twilio outgoing calls.
        setupCallKit()

        // Mark this UUID as ours BEFORE submitting the CXTransaction.
        // iOS broadcasts CXStartCallAction to every CXProvider in the process,
        // including Twilio's. We use this to ignore actions we didn't initiate.
        pendingOutgoingUUID = uuid
        sendPhoneCallEvents(description: "LOG|performStartCallAction uuid=\(uuid) handle=\(handle)")
        let callHandle = CXHandle(type: .generic, value: handle)
        let startCallAction = CXStartCallAction(call: uuid, handle: callHandle)
        let transaction = CXTransaction(action: startCallAction)

        callKitCallController?.request(transaction) { [weak self] error in
            guard let self = self else { return }
            if let error = error {
                self.sendPhoneCallEvents(description: "LOG|StartCallAction failed: \(error.localizedDescription)")
                return
            }

            self.sendPhoneCallEvents(description: "LOG|StartCallAction successful")

            // Update the CallKit UI with caller name.
            let callUpdate = CXCallUpdate()
            callUpdate.remoteHandle = callHandle
            callUpdate.localizedCallerName = self.outgoingCallerName.isEmpty ? handle : self.outgoingCallerName
            callUpdate.supportsDTMF = true
            callUpdate.supportsHolding = false
            callUpdate.supportsGrouping = false
            callUpdate.supportsUngrouping = false
            callUpdate.hasVideo = false

            self.callKitProvider?.reportCall(with: uuid, updated: callUpdate)
        }
    }

    /// Reports a new incoming call to CallKit.
    private func reportIncomingCall(from: String, uuid: UUID, completion: ((Error?) -> Void)? = nil) {
        // Ensure provider is ready — it may have been invalidated after the last call
        // ended (in didDeactivateAudioSession). setupCallKit() is idempotent.
        setupCallKit()

        let callHandle = CXHandle(type: .generic, value: from)

        let callUpdate = CXCallUpdate()
        callUpdate.remoteHandle = callHandle
        callUpdate.localizedCallerName = clients[from] ?? from
        callUpdate.supportsDTMF = true
        callUpdate.supportsHolding = false
        callUpdate.supportsGrouping = false
        callUpdate.supportsUngrouping = false
        callUpdate.hasVideo = false

        callKitProvider?.reportNewIncomingCall(with: uuid, update: callUpdate) { [weak self] error in
            if let error = error {
                self?.sendPhoneCallEvents(description: "LOG|reportNewIncomingCall failed: \(error.localizedDescription)")
            } else {
                self?.sendPhoneCallEvents(description: "LOG|Incoming call successfully reported to CallKit")
            }
            completion?(error)
        }
    }

    /// Requests CallKit to end a call.
    private func performEndCallAction(uuid: UUID) {
        let endCallAction = CXEndCallAction(call: uuid)
        let transaction = CXTransaction(action: endCallAction)

        callKitCallController?.request(transaction) { [weak self] error in
            if let error = error {
                self?.sendPhoneCallEvents(description: "LOG|EndCallAction failed: \(error.localizedDescription)")
            }
        }
    }

    /// Resets all state associated with a call after it disconnects.
    private func callDisconnected(uuid: UUID) {
        let callId = activeCalls[uuid]
        sendPhoneCallEvents(description: "LOG|callDisconnected uuid=\(uuid) callId=\(callId ?? "nil")")
        sendPhoneCallEvents(description: "LOG|  before: activeCalls=\(activeCalls.count) callInvites=\(callInvites.count) activeCallUUID=\(String(describing: activeCallUUID))")
        activeCalls.removeValue(forKey: uuid)
        callInvites.removeValue(forKey: uuid)

        if let callId = callId {
            callIdToUUID.removeValue(forKey: callId)
        }

        if activeCallUUID == uuid {
            activeCallUUID = nil
        }

        // Reset all per-call state when no calls remain.
        if activeCalls.isEmpty && callInvites.isEmpty {
            callOutgoing = false
            userInitiatedDisconnect = false
            isMuted = false
            desiredSpeakerState = false
            desiredBluetoothState = false
            userExplicitlyChangedAudioRoute = false
            // Clear outgoing call destination — prevents stale callTo/identity from a
            // completed Vonage call being used if CXStartCallAction fires for a Twilio call.
            callTo = ""
            identity = ""
            callArgs = [:]
            pendingOutgoingUUID = nil
            // Notify Flutter that speaker/bluetooth are off so the next call
            // starts with a clean UI — prevents the brief speaker flash.
            sendPhoneCallEvents(description: "Speaker Off")
            sendPhoneCallEvents(description: "Bluetooth Off")
            sendPhoneCallEvents(description: "LOG|  all per-call state reset (no remaining calls)")

            // No pending invites or active calls — release the background task
            // and cancel the ringing timeout.
            cancelRingingTimeout()
            endInviteBackgroundTask()

            // ── Synchronously release the CXProvider ──────────────────────────
            // iOS broadcasts CXStartCallAction to EVERY registered CXProvider in
            // the process. With Vonage's provider still alive after a call, it
            // receives Twilio's outgoing CXStartCallAction and calls action.fail()
            // — which propagates as CXErrorCodeRequestTransactionErrorCodeUnknownCallProvider
            // (error 2) to Twilio's callKitCallController.request() callback,
            // blocking ALL subsequent Twilio outgoing calls until the timing window
            // closes (pattern: Twilio✓ → Vonage✓ → Twilio✗).
            //
            // Tearing down here — not in didDeactivateAudioSession — eliminates
            // the async timing window. VGVoiceClient.disableAudio is called
            // explicitly so the WebRTC stack releases audio before we drop the
            // provider (didDeactivateAudioSession won't fire after invalidation).
            VGVoiceClient.disableAudio(AVAudioSession.sharedInstance())
            // CRITICAL: invalidate() must NOT be called synchronously from inside
            // a CXProviderDelegate callback (e.g. performEndCallAction). iOS does
            // not support calling invalidate() while the provider's delegate queue
            // is mid-dispatch — doing so corrupts the internal CKCallManager /
            // TUCallCenter registry, causing the next Twilio CXStartCallAction to
            // be accepted but performStartCallAction to never fire.
            //
            // Fix: capture the provider/controller, nil the instance vars
            // immediately (blocks future callbacks), remove the delegate
            // synchronously (safe inside a callback), then defer invalidate() to
            // the next runloop cycle via DispatchQueue.main.async so it runs only
            // after the current performEndCallAction stack fully unwinds.
            let providerToInvalidate = callKitProvider
            callKitProvider = nil
            callKitCallController = nil
            providerToInvalidate?.setDelegate(nil, queue: nil)
            isIntentionalProviderReset = true
            // Notify Twilio (same process) that our CXProvider was deregistered from
            // TUCallCenter. Twilio observes this to set needsProviderReset=true so it
            // allocates a fresh CXProvider on the next outgoing call — avoiding the
            // CXErrorCodeRequestTransactionErrorUnknownCallProvider (error 2) that occurs
            // when Twilio tries to reuse an orphaned provider.
            NotificationCenter.default.post(
                name: VonageVoicePlugin.cxProviderInvalidatedNotification,
                object: nil
            )
            sendPhoneCallEvents(description: "LOG|callDisconnected: CXProvider delegate nil'd — deferring invalidate()")
            DispatchQueue.main.async { [weak self] in
                providerToInvalidate?.invalidate()
                self?.isIntentionalProviderReset = false
                // Deregister the Vonage SDK's internal CallKit binding AFTER the provider
                // is fully invalidated. When isUsingCallKit remains true after invalidation,
                // the SDK stays connected to iOS's TUCallCenter internally — receiving
                // Twilio's CXStartCallAction and never responding (fulfill/fail), which
                // permanently stalls the transaction and blocks all subsequent Twilio calls.
                // Setting false here severs that binding cleanly.
                VGVoiceClient.isUsingCallKit = false
                self?.sendPhoneCallEvents(description: "LOG|callDisconnected: CXProvider invalidated, VGVoiceClient.isUsingCallKit=false")
            }
        }
        sendPhoneCallEvents(description: "LOG|  after: activeCalls=\(activeCalls.count) callInvites=\(callInvites.count) activeCallUUID=\(String(describing: activeCallUUID))")
    }
}


// ═══════════════════════════════════════════════════════════════════════
// MARK: - CXProviderDelegate (CallKit Callbacks)
// ═══════════════════════════════════════════════════════════════════════
//
//  These methods are called by iOS whenever the user interacts with
//  the native call UI (answer, decline, hold, mute, etc.).
//

extension VonageVoicePlugin: CXProviderDelegate {

    /// Called when CallKit resets (e.g. all calls dropped by the system).
    public func providerDidReset(_ provider: CXProvider) {
        sendPhoneCallEvents(description: "LOG|providerDidReset (intentional=\(isIntentionalProviderReset))")

        if isIntentionalProviderReset {
            // Triggered by our own callDisconnected() teardown — state was already
            // cleaned up there. The Vonage voiceClient session is still valid;
            // resetting isSessionReady here would cause a spurious session restore
            // on the next Vonage call.
            isIntentionalProviderReset = false
            return
        }

        // iOS-initiated reset (e.g. process handoff, all calls dropped by system).
        activeCalls.removeAll()
        callInvites.removeAll()
        callIdToUUID.removeAll()
        activeCallUUID = nil
        isSessionReady = false
        pendingOutgoingUUID = nil
        cleanupPushState()
    }

    public func providerDidBegin(_ provider: CXProvider) {
        sendPhoneCallEvents(description: "LOG|providerDidBegin")
    }

    // ─── Audio Session Lifecycle ─────────────────────────────────────

    /// CallKit activated the audio session — hook Vonage into it.
    public func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        sendPhoneCallEvents(description: "LOG|provider:didActivateAudioSession category=\(audioSession.category.rawValue) mode=\(audioSession.mode.rawValue)")
        sendPhoneCallEvents(description: "LOG|  currentRoute outputs=\(audioSession.currentRoute.outputs.map { "\($0.portType.rawValue)" })")
        sendPhoneCallEvents(description: "LOG|  desiredSpeakerState=\(desiredSpeakerState) desiredBluetoothState=\(desiredBluetoothState)")

        // CRITICAL: Without this call, the call connects but no audio flows.
        // This hooks WebRTC into the CallKit-managed audio session.
        // CRITICAL: Hook WebRTC into CallKit's audio session FIRST.
        // Do NOT reconfigure the audio session (setCategory/setActive) after this —
        // CallKit already provides a correctly configured session and changing it
        // can disrupt the WebRTC media pipeline.
        VGVoiceClient.enableAudio(audioSession)
        sendPhoneCallEvents(description: "LOG|VGVoiceClient.enableAudio called")

        // Apply the user's preferred audio route.
        // Priority: user-toggled speaker > Bluetooth > earpiece (default).
        if desiredSpeakerState {
            applySpeakerSetting(toSpeaker: true)
        } else if isBluetoothAvailable() {
            desiredBluetoothState = true
            toggleBluetoothAudio(bluetoothOn: true)
        } else {
            // Explicitly route to earpiece — without this, iOS may default
            // to the loudspeaker under .voiceChat mode on some devices.
            do {
                try audioSession.overrideOutputAudioPort(.none)
                try setPreferredInput(portType: .builtInMic)
                // Confirm earpiece is active so Flutter UI doesn't briefly show speaker
                sendPhoneCallEvents(description: "Speaker Off")
            } catch {
                sendPhoneCallEvents(description: "LOG|Earpiece fallback failed: \(error.localizedDescription)")
            }
        }

        sendPhoneCallEvents(description: "LOG|didActivateAudioSession complete, route=\(audioSession.currentRoute.outputs.map { $0.portType.rawValue })")
    }

    /// CallKit deactivated the audio session — belt-and-suspenders fallback.
    /// The primary teardown now happens synchronously in callDisconnected().
    /// This method is only reached if the provider was NOT yet invalidated there
    /// (e.g. a mid-session audio deactivation for an edge-case path).
    public func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        sendPhoneCallEvents(description: "LOG|provider:didDeactivateAudioSession")
        // disableAudio is safe to call twice; second call is a no-op.
        VGVoiceClient.disableAudio(audioSession)
        logFullAudioSessionState(label: "post-didDeactivateAudioSession")

        // Fallback: release provider if callDisconnected didn't already do it.
        if activeCalls.isEmpty && callInvites.isEmpty, callKitProvider != nil {
            sendPhoneCallEvents(description: "LOG|didDeactivateAudioSession: fallback CXProvider release")
            isIntentionalProviderReset = true
            callKitProvider?.invalidate()
            callKitProvider = nil
            callKitCallController = nil
        }
    }

    /// An action timed out. We do NOT fulfil it here — the actual async
    /// callback will fulfil or fail the action when it completes.
    public func provider(_ provider: CXProvider, timedOutPerforming action: CXAction) {
        sendPhoneCallEvents(description: "LOG|provider:timedOutPerformingAction \(type(of: action))")
    }

    // ─── Start Call (Outgoing) ───────────────────────────────────────

    public func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        // iOS delivers CXStartCallAction to ALL registered CXProvider delegates in the
        // process — including Twilio's CXProvider firing into this Vonage handler.
        // Guard: only handle UUIDs that THIS plugin created in performStartCallAction.
        guard action.callUUID == pendingOutgoingUUID else {
            sendPhoneCallEvents(description: "LOG|provider:performStartCallAction — ignoring UUID \(action.callUUID), not initiated by VonageVoicePlugin (expected \(String(describing: pendingOutgoingUUID)))")

            // UUID not owned by Vonage — MUST call action.fail() here.
            //
            // iOS dispatches CXStartCallAction to ALL registered CXProvider delegates
            // in the process (including this Vonage handler for Twilio's UUIDs).
            // iOS requires EVERY registered provider to respond (fulfill OR fail)
            // before it delivers the result to the originating CXCallController's
            // request() completion handler. If any provider returns without
            // responding, the transaction is permanently stuck — the completion
            // callback never fires, Twilio's performStartCallAction never gets
            // dispatched, and the call silently never starts.
            //
            // Calling action.fail() here does NOT cause error 2 (unknownCallProvider)
            // in Twilio's callKitCallController.request() callback. That error only
            // fires when NO provider fulfills the action. Since Twilio's own provider
            // will call action.fulfill(), the transaction succeeds — Vonage's fail()
            // is simply iOS's mechanism to acknowledge "I don't own this call."
            //
            // Historical note: the original action.fail() → error 2 regression was
            // caused by the CXProvider corruption bug (invalidate() called inside a
            // CXProviderDelegate callback), which prevented Twilio's provider from
            // ever receiving or fulfilling the action. That bug is now fixed by the
            // deferred invalidate() in callDisconnected(). With the corruption
            // removed, Vonage fail() + Twilio fulfill() = transaction succeeds.
            sendPhoneCallEvents(description: "LOG|  non-Vonage UUID — calling action.fail() (activeCalls=\(activeCalls.count) invites=\(callInvites.count) pendingUUID=\(String(describing: pendingOutgoingUUID)))")
            action.fail()
            return
        }
        pendingOutgoingUUID = nil

        let from = identity.isEmpty ? (callArgs["from"] as? String ?? "") : identity
        let to = callTo.isEmpty ? (callArgs["to"] as? String ?? "") : callTo

        sendPhoneCallEvents(description: "LOG|provider:performStartCallAction uuid=\(action.callUUID)")

        provider.reportOutgoingCall(with: action.callUUID, startedConnectingAt: Date())
        sendPhoneCallEvents(description: "LOG|reportOutgoingCall startedConnectingAt sent")

        // Build call context
        var context: [String: String] = [:]
        context["to"] = to
        context["To"] = to        // ✅ use local snapshot, not callTo
        if !from.isEmpty { context["from"] = from }
        if !from.isEmpty { context["From"] = from }  // ✅ use local snapshot, not identity

        // Add any extra custom params
        for (key, value) in callArgs {
            if key == "from" || key == "to" || key == "CallerName" { continue }
            context[key] = "\(value)"
        }

        sendPhoneCallEvents(description: "LOG|serverCall context (\(context.count) keys): \(context)")
        for (key, value) in context {
            sendPhoneCallEvents(description: "LOG|  context[\(key)] = '\(value)' (len=\(value.count))")
        }

        // ✅ from and to already declared above — use them directly
        sendPhoneCallEvents(description: "Ringing|\(from)|\(to)|Outgoing")
        sendPhoneCallEvents(description: "LOG|Calling voiceClient.serverCall() now...")

        voiceClient.serverCall(context) { [weak self] error, callId in
            guard let self = self else { return }
            if let error = error {
                self.sendPhoneCallEvents(description: "LOG|serverCall FAILED: \(error.localizedDescription)")
                self.sendPhoneCallEvents(description: "LOG|serverCall error type: \(type(of: error)) — \(error)")
                provider.reportCall(with: action.callUUID, endedAt: Date(), reason: .failed)
                self.sendPhoneCallEvents(description: "Call Ended")
                action.fail()
                return
            }

            guard let callId = callId else {
                self.sendPhoneCallEvents(description: "LOG|serverCall returned nil callId (no error, but no callId)")
                action.fail()
                return
            }

            self.sendPhoneCallEvents(description: "LOG|serverCall success, callId=\(callId)")
            self.activeCalls[action.callUUID] = callId
            self.callIdToUUID[callId] = action.callUUID
            self.activeCallUUID = action.callUUID
            self.userExplicitlyChangedAudioRoute = false

            provider.reportOutgoingCall(with: action.callUUID, connectedAt: Date())
            self.sendPhoneCallEvents(description: "LOG|reportOutgoingCall connectedAt sent")
            // ✅ from/to are captured from the outer scope — correct values guaranteed
            self.sendPhoneCallEvents(description: "Connected|\(from)|\(to)|Outgoing")
            self.sendPhoneCallEvents(description: "LOG|activeCalls=\(self.activeCalls.count) activeCallUUID=\(String(describing: self.activeCallUUID))")

            action.fulfill()
            self.sendPhoneCallEvents(description: "LOG|CXStartCallAction fulfilled")
        }
    }

    // ─── Answer Call (Incoming) ──────────────────────────────────────

    public func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        sendPhoneCallEvents(description: "LOG|provider:performAnswerCallAction uuid=\(action.callUUID)")

        guard let invite = callInvites[action.callUUID] else {
            // UUID not owned by Vonage — fail immediately so iOS resolves the action.
            // Each CXProvider receives its own action dispatch; failing here does NOT
            // prevent Twilio's provider from fulfilling the same UUID independently.
            sendPhoneCallEvents(description: "LOG|provider:performAnswerCallAction — ignoring UUID \(action.callUUID), not a Vonage call")
            action.fail()
            return
        }

        // KILLED STATE: The callId isn't available yet (session still restoring).
        // Queue the action — it will be fulfilled in didReceiveInviteForCall.
        if invite.callId.isEmpty {
            sendPhoneCallEvents(description: "LOG|Deferring answer — waiting for callId (killed state)")
            pendingAnswerAction = action
            return
        }

        // Normal path — answer immediately.
        isAnsweringInProgress = true
        voiceClient.answer(invite.callId) { [weak self] error in
            guard let self = self else { return }
            self.isAnsweringInProgress = false
            if let error = error {
                self.sendPhoneCallEvents(description: "LOG|answer failed: \(error.localizedDescription)")
                action.fail()
                return
            }

            self.sendPhoneCallEvents(description: "LOG|Call answered successfully callId=\(invite.callId)")

            // Move from invites to active calls.
            self.activeCalls[action.callUUID] = invite.callId
            self.callIdToUUID[invite.callId] = action.callUUID
            self.activeCallUUID = action.callUUID
            self.callInvites.removeValue(forKey: action.callUUID)
            self.userExplicitlyChangedAudioRoute = false

            let from = invite.from
            let to = invite.to
            self.sendPhoneCallEvents(description: "Answer|\(from)|\(to)|Incoming")
            self.sendPhoneCallEvents(description: "Connected|\(from)|\(to)|Incoming")

            self.callKitCompletionCallback?(true)
            action.fulfill()
        }
    }

    // ─── End Call ────────────────────────────────────────────────────

    public func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        sendPhoneCallEvents(description: "LOG|provider:performEndCallAction uuid=\(action.callUUID)")

        // Guard: only handle UUIDs this plugin owns.
        // Each CXProvider receives its own action dispatch — failing here does NOT
        // prevent Twilio's provider from handling the same UUID independently.
        guard callInvites[action.callUUID] != nil || activeCalls[action.callUUID] != nil else {
            sendPhoneCallEvents(description: "LOG|provider:performEndCallAction — ignoring UUID \(action.callUUID), not a Vonage call")
            action.fail()
            return
        }

        if let invite = callInvites[action.callUUID] {
            // Pending invite — reject it.
            if invite.callId.isEmpty {
                // KILLED STATE: callId not available yet — defer.
                sendPhoneCallEvents(description: "LOG|Deferring decline — waiting for callId (killed state)")
                pendingEndAction = action
                return
            }

            sendPhoneCallEvents(description: "LOG|Rejecting invite id=\(invite.callId)")
            voiceClient.reject(invite.callId) { [weak self] error in
                if let error = error {
                    self?.sendPhoneCallEvents(description: "LOG|reject failed: \(error.localizedDescription)")
                }
            }
            callInvites.removeValue(forKey: action.callUUID)
            callIdToUUID.removeValue(forKey: invite.callId)
            callDisconnected(uuid: action.callUUID)
            sendPhoneCallEvents(description: "Declined")

        } else if let callId = activeCalls[action.callUUID] {
            // Active call — hang up.
            sendPhoneCallEvents(description: "LOG|Hanging up active call callId=\(callId)")
            voiceClient.hangup(callId) { [weak self] error in
                if let error = error {
                    self?.sendPhoneCallEvents(description: "LOG|hangup failed: \(error.localizedDescription)")
                }
            }
            // CRITICAL: Report the call as ended to CXCallObserver BEFORE invalidating
            // the provider. CXCallObserver tracks all active calls system-wide. Without
            // this report, CXCallObserver keeps hasEnded=false for this UUID until
            // invalidate() fires (deferred to next runloop). Twilio's
            // callKitCallController.request(CXStartCallAction) can fire in that window —
            // iOS sees the Vonage call still "active" in CXCallObserver and silently
            // drops the transaction (completion never fires, performStartCallAction
            // never fires). Reporting ended here updates CXCallObserver synchronously
            // so it shows 0 active calls by the time Twilio submits its transaction.
            provider.reportCall(with: action.callUUID, endedAt: Date(), reason: .remoteEnded)
            sendPhoneCallEvents(description: "LOG|provider:performEndCallAction: reported call ended to CXCallObserver")
            callDisconnected(uuid: action.callUUID)
            // CRITICAL: Defer "Call Ended" to run AFTER Vonage's CXProvider has been fully
            // invalidated. callDisconnected() defers invalidate() via DispatchQueue.main.async.
            // Since both blocks are queued on the main queue in FIFO order, this block runs
            // AFTER invalidate() + VGVoiceClient.isUsingCallKit=false complete.
            //
            // Without this deferral, "Call Ended" arrives at Flutter synchronously, Flutter
            // immediately initiates a Twilio call, and Twilio's CXStartCallAction fires while
            // Vonage's CXProvider is still registered (with delegate=nil, pending invalidation).
            // iOS sees a registered provider that cannot respond → error 2
            // (CXErrorCodeRequestTransactionErrorUnknownCallProvider) and rejects the transaction.
            //
            // With this deferral: invalidate() runs first (Vonage's provider fully deregistered),
            // THEN Flutter receives "Call Ended" and makes the Twilio call → success.
            DispatchQueue.main.async { [weak self] in
                self?.sendPhoneCallEvents(description: "LOG|provider:performEndCallAction: sending deferred Call Ended (after invalidate)")
                self?.sendPhoneCallEvents(description: "Call Ended")
            }

        } else {
            callDisconnected(uuid: action.callUUID)
        }

        action.fulfill()
    }

    // ─── Mute (from native CallKit UI) ─────────────────────────────

    public func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        sendPhoneCallEvents(description: "LOG|provider:performSetMutedAction uuid=\(action.callUUID) isMuted=\(action.isMuted)")

        if let callId = activeCalls[action.callUUID] {
            if action.isMuted {
                voiceClient.mute(callId) { _ in }
                isMuted = true
            } else {
                voiceClient.unmute(callId) { _ in }
                isMuted = false
            }
            sendPhoneCallEvents(description: action.isMuted ? "Mute" : "Unmute")
            action.fulfill()
        } else {
            // UUID not owned by Vonage — fail so iOS resolves the action.
            // Each CXProvider receives its own action dispatch; failing here does NOT
            // affect Twilio's handling of the same UUID.
            sendPhoneCallEvents(description: "LOG|provider:performSetMutedAction — ignoring UUID \(action.callUUID), not a Vonage call")
            action.fail()
        }
    }
}


// ═══════════════════════════════════════════════════════════════════════
// MARK: - PKPushRegistryDelegate (VoIP Pushes)
// ═══════════════════════════════════════════════════════════════════════
//
//  PushKit delivers VoIP pushes even when the app is killed.
//  Apple REQUIRES that every VoIP push results in a reportNewIncomingCall
//  call — otherwise the app gets terminated permanently.
//
//  Three scenarios:
//    1. FOREGROUND/BACKGROUND — Session exists → process push normally.
//    2. KILLED STATE — No session → report to CallKit immediately,
//       then restore session from stored JWT in the background.
//    3. LOGGED OUT — No session, no JWT → report dummy call and end it.
//

extension VonageVoicePlugin: PKPushRegistryDelegate {

    /// Called when PushKit gives us a new VoIP token.
    public func pushRegistry(_ registry: PKPushRegistry, didUpdate pushCredentials: PKPushCredentials, for type: PKPushType) {
        NSLog("[VonageVoice-Push] didUpdatePushCredentials type=%@", String(describing: type))
        sendPhoneCallEvents(description: "LOG|pushRegistry:didUpdatePushCredentials")

        guard type == .voIP else { return }

        let token = pushCredentials.token
        deviceToken = token

        let hexToken = token.map { String(format: "%02x", $0) }.joined()
        NSLog("[VonageVoice-Push] VoIP token (%d bytes): %@", token.count, hexToken)
        sendPhoneCallEvents(description: "LOG|VoIP push token received (\(token.count) bytes) hex=\(hexToken)")

        if accessToken != nil {
            registerPushToken(token)
        }
    }

    /// Called when the VoIP push token is invalidated.
    public func pushRegistry(_ registry: PKPushRegistry, didInvalidatePushTokenFor type: PKPushType) {
        sendPhoneCallEvents(description: "LOG|pushRegistry:didInvalidatePushTokenForType")

        guard type == .voIP else { return }

        if let deviceId = deviceId {
            voiceClient.unregisterDeviceTokens(byDeviceId: deviceId) { [weak self] error in
                if let error = error {
                    self?.sendPhoneCallEvents(description: "LOG|unregisterDeviceTokens failed: \(error.localizedDescription)")
                }
            }
        }
    }

    // ─── Incoming VoIP Push (iOS 13+) ────────────────────────────────

    public func pushRegistry(_ registry: PKPushRegistry,
                             didReceiveIncomingPushWith payload: PKPushPayload,
                             for type: PKPushType,
                             completion: @escaping () -> Void) {
        NSLog("[VonageVoice-Push] *** didReceiveIncomingPush *** type=%@", String(describing: type))
        NSLog("[VonageVoice-Push] Push payload keys: %@",
              payload.dictionaryPayload.keys.map { String(describing: $0) }.joined(separator: ", "))
        sendPhoneCallEvents(description: "LOG|pushRegistry:didReceiveIncomingPush")

        guard type == .voIP else { completion(); return }

        // Apple kills the app if a VoIP push doesn't result in a reportNewIncomingCall.
        let pushType = VGBaseClient.vonagePushType(payload.dictionaryPayload)
        NSLog("[VonageVoice-Push] Vonage push type: %@", String(describing: pushType))

        // ── Not a Vonage call push ────────────────────────────────────
        // Must still report a dummy call to satisfy Apple's requirement.
        // BUT: also forward to the SDK — Vonage may send a cancel push
        // (e.g. call answered elsewhere) that the SDK needs to process.
        // This is critical when the device is locked: the WebSocket is
        // frozen so the cancel can only arrive via a second VoIP push.
        //
        // IMPORTANT: Do NOT call setupCallKit() for non-incomingCall pushes.
        // Cancel/hangup-confirmation pushes arrive after a call has ended; calling
        // setupCallKit() here would re-create Vonage's CXProvider with no real call
        // in progress. iOS then dispatches Twilio's CXStartCallAction to Vonage's
        // provider; Vonage calls action.fail(), blocking every subsequent Twilio call
        // (pattern: Twilio✓ → Vonage✓ → Twilio✗).
        //
        // reportDummyCallAndEnd handles provider creation internally:
        //  • If callKitProvider != nil (real Vonage call active): uses the existing provider.
        //  • If callKitProvider == nil (no real call): creates a LOCAL, non-stored provider
        //    that is never referenced by self.callKitProvider — so self.callKitProvider
        //    stays nil throughout, eliminating any timing window.
        guard pushType == .incomingCall else {
            if !callInvites.isEmpty {
                sendPhoneCallEvents(description: "LOG|Non-incomingCall push while invite pending — forwarding to SDK")
                voiceClient.processCallInvitePushData(payload.dictionaryPayload)
            }
            sendPhoneCallEvents(description: "LOG|Non-incomingCall push — reportDummyCallAndEnd (callKitProvider=\(callKitProvider != nil))")
            reportDummyCallAndEnd(completion: completion)
            return
        }

        // ── KILLED STATE: No session → restore from stored JWT ───────
        // Guard: pendingPushUUID == nil prevents re-entering handleKilledStatePush
        // for a second push that arrives while the first push's createSession is still
        // in-flight. Without it, the second push would overwrite pendingPushUUID and
        // pendingPushCompletion, breaking the first push's coordination (Apple kills
        // the app if completion() is never called) and firing a duplicate createSession.
        // Note: accessToken is set synchronously inside handleKilledStatePush before
        // createSession, so the LOGGED OUT guard below naturally catches any subsequent
        // push once the first handleKilledStatePush has started.
        if !isSessionReady, pendingPushUUID == nil, let storedJwt = getStoredJwt() {
            // Real call: create the CXProvider before handing off to handleKilledStatePush.
            setupCallKit()
            handleKilledStatePush(payload: payload, storedJwt: storedJwt, completion: completion)
            return
        }

        // ── LOGGED OUT: No session, no JWT ───────────────────────────
        // IMPORTANT: Do NOT call setupCallKit() here.
        // setupCallKit() creates a persistent CXProvider and sets VGVoiceClient.isUsingCallKit=true.
        // In the logged-out state we cannot process this call — calling setupCallKit() would:
        //   1. Fire providerDidBegin (activating the provider for no real call)
        //   2. Make reportDummyCallAndEnd use the "existing provider" path instead of
        //      a local temp provider, causing the "Unknown" ringing call to appear
        //      prominently via the persistent system-registered provider.
        //   3. Leave VGVoiceClient.isUsingCallKit=true after the dummy call ends
        //      (reportDummyCallAndEnd does not reset it), which can interfere with
        //      subsequent Twilio calls.
        // Using the temp-provider path in reportDummyCallAndEnd is correct here:
        // it satisfies Apple's reportNewIncomingCall mandate without any persistent state.
        if accessToken == nil {
            sendPhoneCallEvents(description: "LOG|No session and no stored JWT — cannot process push")
            reportDummyCallAndEnd(completion: completion)
            return
        }

        // ── FOREGROUND / BACKGROUND: Session exists ──────────────────
        // Real call: create the CXProvider before processing the invite.
        // processCallInvitePushData triggers didReceiveInviteForCall synchronously,
        // which calls reportNewIncomingCall before we reach completion().
        setupCallKit()
        voiceClient.processCallInvitePushData(payload.dictionaryPayload)
        completion()
    }

    /// iOS < 13 fallback (no completion handler).
    public func pushRegistry(_ registry: PKPushRegistry,
                             didReceiveIncomingPushWith payload: PKPushPayload,
                             for type: PKPushType) {
        sendPhoneCallEvents(description: "LOG|pushRegistry:didReceiveIncomingPush (legacy)")

        guard type == .voIP else { return }
        let pushType = VGBaseClient.vonagePushType(payload.dictionaryPayload)
        guard pushType == .incomingCall else { return }

        if accessToken == nil, let storedJwt = getStoredJwt() {
            accessToken = storedJwt
            voiceClient.createSession(storedJwt) { [weak self] error, _ in
                guard let self = self, error == nil else { return }
                self.isSessionReady = true
                self.voiceClient.processCallInvitePushData(payload.dictionaryPayload)
            }
        } else if accessToken != nil {
            voiceClient.processCallInvitePushData(payload.dictionaryPayload)
        }
    }

    // ─── Killed-State Push Handler ───────────────────────────────────

    /// Handles a VoIP push when the app was killed.
    /// Reports to CallKit IMMEDIATELY, then restores the session in the background.
    private func handleKilledStatePush(payload: PKPushPayload,
                                       storedJwt: String,
                                       completion: @escaping () -> Void) {
        sendPhoneCallEvents(description: "LOG|KILLED STATE: Restoring session from stored JWT")

        let uuid = UUID()
        pendingPushUUID = uuid
        pendingPushCompletion = completion

        // Create a placeholder invite (callId filled by didReceiveInviteForCall later).
        callInvites[uuid] = CallInviteInfo(callId: "", from: "", to: identity)

        // Report to CallKit immediately so Apple doesn't kill us.
        reportIncomingCall(from: defaultCaller, uuid: uuid)

        // Start the background task NOW — before the async session restore.
        // Without this, iOS suspends the app after PushKit completion and
        // the WebSocket dies before .answeredElsewhere can arrive.
        beginInviteBackgroundTask()

        // Safety timeout: end the call if didReceiveInviteForCall never fires.
        pushCompletionTimer = Timer.scheduledTimer(withTimeInterval: 10.0, repeats: false) { [weak self] _ in
            self?.endPreReportedCall(reason: "Push completion timeout — ending pre-reported call")
        }

        // Restore the session asynchronously.
        accessToken = storedJwt
        voiceClient.createSession(storedJwt) { [weak self] error, sessionId in
            guard let self = self else { return }
            if let error = error {
                self.sendPhoneCallEvents(description: "LOG|Session restore failed: \(error.localizedDescription)")
                self.endPreReportedCall(reason: "Session restore failed — ending pre-reported call")
                return
            }
            self.isSessionReady = true
            self.sendPhoneCallEvents(description: "LOG|Session restored successfully, processing push")
            // Re-register the VoIP push token now that the session is live.
            // handleTokens() may have been called while createSession was still
            // in flight (pendingPushUUID != nil + isSessionReady == false), so
            // it skipped token registration. Do it here to ensure Vonage always
            // has a current device registration — mirrors the isSessionReady path
            // in handleTokens().
            if let token = self.deviceToken {
                self.registerPushToken(token)
            }
            self.voiceClient.processCallInvitePushData(payload.dictionaryPayload)
            // completion() is called from didReceiveInviteForCall via pendingPushCompletion.
        }
    }

    /// Reports a dummy incoming call and immediately ends it.
    /// Required by Apple: every VoIP push MUST result in reportNewIncomingCall.
    ///
    /// When a real Vonage call is active (`callKitProvider != nil`), uses the existing
    /// persistent provider. Otherwise, creates a LOCAL, non-stored `CXProvider` that
    /// is NEVER assigned to `self.callKitProvider`. This ensures there is zero timing
    /// window where a lingering Vonage provider can intercept Twilio's CXStartCallAction.
    private func reportDummyCallAndEnd(completion: @escaping () -> Void) {
        let uuid = UUID()
        let callUpdate = CXCallUpdate()
        callUpdate.remoteHandle = CXHandle(type: .generic, value: "Unknown")
        callUpdate.localizedCallerName = "Unknown"

        // Use .answeredElsewhere instead of .failed as the end reason.
        // .failed records the call in the system recents as a "failed" entry, which
        // iOS may present as a missed-call notification. .answeredElsewhere tells iOS
        // the call was handled on another device/context — it does not generate a
        // missed-call notification and does not appear prominently in recents.
        let endReason = CXCallEndedReason.answeredElsewhere

        if let existingProvider = callKitProvider {
            // Real Vonage call is active — use the persistent provider.
            sendPhoneCallEvents(description: "LOG|reportDummyCallAndEnd: using existing provider")
            existingProvider.reportNewIncomingCall(with: uuid, update: callUpdate) { [weak self] _ in
                existingProvider.reportCall(with: uuid, endedAt: Date(), reason: endReason)
                if let self = self, self.activeCalls.isEmpty && self.callInvites.isEmpty {
                    self.sendPhoneCallEvents(description: "LOG|reportDummyCallAndEnd: releasing CXProvider (no active calls)")
                    self.isIntentionalProviderReset = true
                    self.callKitProvider?.invalidate()
                    self.callKitProvider = nil
                    self.callKitCallController = nil
                }
                completion()
            }
        } else {
            // No real Vonage call — use a LOCAL temporary provider.
            // self.callKitProvider stays nil throughout, so iOS will not route any
            // other plugin's CXStartCallAction (e.g. Twilio's) to this provider.
            sendPhoneCallEvents(description: "LOG|reportDummyCallAndEnd: using temp local provider (callKitProvider is nil)")
            let config = CXProviderConfiguration(localizedName: VonageVoicePlugin.appName)
            config.maximumCallGroups = 1
            config.maximumCallsPerCallGroup = 1
            config.supportedHandleTypes = [.phoneNumber, .generic]
            config.supportsVideo = false
            // tempProvider is captured by the closure and released after the report.
            // No setDelegate — we only report, we don't need to handle CXActions.
            let tempProvider = CXProvider(configuration: config)
            tempProvider.reportNewIncomingCall(with: uuid, update: callUpdate) { _ in
                tempProvider.reportCall(with: uuid, endedAt: Date(), reason: endReason)
                tempProvider.invalidate()
                completion()
            }
        }
    }
}


// ═══════════════════════════════════════════════════════════════════════
// MARK: - VGVoiceClientDelegate (Vonage SDK Callbacks)
// ═══════════════════════════════════════════════════════════════════════
//
//  These methods are called by the Vonage SDK when:
//    • A new call invite arrives (didReceiveInviteForCall)
//    • An invite is cancelled by the caller (didReceiveInviteCancelForCall)
//    • The remote party hangs up (didReceiveHangupForCall)
//    • Media state changes (reconnecting, mute, errors)
//    • The session token expires
//

extension VonageVoicePlugin: VGVoiceClientDelegate {

    // ─── New Call Invite ─────────────────────────────────────────────

    public func voiceClient(_ client: VGVoiceClient,
                            didReceiveInviteForCall callId: String,
                            from caller: String,
                            with type: VGVoiceChannelType) {
        NSLog("[VonageVoice-Push] *** didReceiveInviteForCall *** callId=%@ from=%@", callId, caller)
        sendPhoneCallEvents(description: "LOG|didReceiveInviteForCall callId=\(callId), from=\(caller)")

        // Deduplicate: WebSocket and push processing can fire for the same call.
        if callIdToUUID[callId] != nil {
            sendPhoneCallEvents(description: "LOG|Duplicate invite for callId=\(callId), ignoring")
            return
        }

        // ── KILLED STATE: Associate callId with the pre-reported UUID ──
        if let pushUUID = pendingPushUUID {
            handleKilledStateInvite(callId: callId, caller: caller, pushUUID: pushUUID)
            return
        }

        // ── ACTIVE CALL GUARD (single-call only) ──────────────────────────
        // Mirrors Android's TVConnectionService: if a call is already active,
        // auto-reject the new invite silently so the active call is not
        // interrupted. The second call arrives via the live WebSocket (not
        // PushKit) when the app is foreground, so no reportNewIncomingCall
        // is required by Apple here.
        //
        // IMPORTANT: Do NOT emit "Missed Call" here. "Missed Call" maps to
        // CallEvent.missedCall in the Dart BLoC which sets isCallEnded=true
        // and dismisses the active call screen for the FIRST (still-live) call.
        // Mirrors Twilio's pattern: suppress "Missed Call" when other active
        // calls remain (SwiftTwilioVoicePlugin.swift callInviteCancelled guard).
        if !activeCalls.isEmpty || activeCallUUID != nil {
            NSLog("[VonageVoice] Second invite callId=%@ while active call exists — auto-rejecting (suppressing Missed Call event)", callId)
            sendPhoneCallEvents(description: "LOG|Auto-rejecting second call callId=\(callId) — active call in progress, Missed Call suppressed to protect active call screen")
            voiceClient.reject(callId) { [weak self] error in
                if let error = error {
                    self?.sendPhoneCallEvents(description: "LOG|Auto-reject second call failed: \(error.localizedDescription)")
                }
            }
            // Do NOT send "Missed Call" — first call is still active and
            // "Missed Call" would set isCallEnded=true on the BLoC, dismissing
            // the active call screen for the first call.
            return
        }

        // ── NORMAL PATH (foreground / background) ──
        let uuid = UUID()
        let callerDisplay = clients[caller] ?? caller
        let invite = CallInviteInfo(callId: callId, from: caller, to: identity)
        callInvites[uuid] = invite
        callIdToUUID[callId] = uuid

        sendPhoneCallEvents(description: "Incoming|\(caller)|\(identity)|Incoming")
        reportIncomingCall(from: callerDisplay, uuid: uuid)

        // Keep the app alive while the invite is pending so the WebSocket
        // can deliver a cancel event (answered elsewhere / caller hung up)
        // even when the device is locked and iOS would otherwise suspend us.
        beginInviteBackgroundTask()
        startRingingTimeout()
    }

    /// Handles didReceiveInviteForCall when the app was launched from killed state.
    /// The call was already pre-reported to CallKit — now we update it with real data
    /// and fulfil any queued answer/decline actions.
    private func handleKilledStateInvite(callId: String, caller: String, pushUUID: UUID) {
        pushCompletionTimer?.invalidate()
        pushCompletionTimer = nil

        // Keep the app alive while the invite is pending (locked-device fix).
        beginInviteBackgroundTask()
        startRingingTimeout()

        let callerDisplay = clients[caller] ?? caller

        // Update the placeholder invite with real data.
        let invite = CallInviteInfo(callId: callId, from: caller, to: identity)
        callInvites[pushUUID] = invite
        callIdToUUID[callId] = pushUUID

        // Update CallKit with the real caller info.
        let callUpdate = CXCallUpdate()
        callUpdate.localizedCallerName = callerDisplay
        callUpdate.remoteHandle = CXHandle(type: .generic, value: callerDisplay)
        callKitProvider?.reportCall(with: pushUUID, updated: callUpdate)

        sendPhoneCallEvents(description: "Incoming|\(caller)|\(identity)|Incoming")

        // Fulfil any queued answer action (user tapped Answer before callId was available).
        if let answerAction = pendingAnswerAction {
            pendingAnswerAction = nil
            isAnsweringInProgress = true
            voiceClient.answer(callId) { [weak self] error in
                guard let self = self else { return }
                self.isAnsweringInProgress = false
                if let error = error {
                    self.sendPhoneCallEvents(description: "LOG|Deferred answer failed: \(error.localizedDescription)")
                    answerAction.fail()
                    return
                }
                self.activeCalls[pushUUID] = callId
                self.activeCallUUID = pushUUID
                self.callInvites.removeValue(forKey: pushUUID)
                self.userExplicitlyChangedAudioRoute = false
                self.sendPhoneCallEvents(description: "Answer|\(caller)|\(self.identity)|Incoming")
                self.sendPhoneCallEvents(description: "Connected|\(caller)|\(self.identity)|Incoming")
                answerAction.fulfill()
            }
        }

        // Fulfil any queued decline action (user tapped Decline before callId was available).
        if let endAction = pendingEndAction {
            pendingEndAction = nil
            voiceClient.reject(callId) { [weak self] error in
                if let error = error {
                    self?.sendPhoneCallEvents(description: "LOG|Deferred reject failed: \(error.localizedDescription)")
                }
            }
            callInvites.removeValue(forKey: pushUUID)
            callIdToUUID.removeValue(forKey: callId)
            callDisconnected(uuid: pushUUID)
            sendPhoneCallEvents(description: "Declined")
            endAction.fulfill()
        }

        // Call the deferred PushKit completion handler.
        pendingPushCompletion?()
        pendingPushCompletion = nil
        pendingPushUUID = nil
    }

    // ─── Call Invite Cancelled (caller hung up before we answered) ───

    public func voiceClient(_ client: VGVoiceClient,
                            didReceiveInviteCancelForCall callId: String,
                            with reason: VGVoiceInviteCancelReason) {
        NSLog("[VonageVoice-Push] *** didReceiveInviteCancelForCall *** callId=%@ reason=%@",
              callId, String(describing: reason))
        sendPhoneCallEvents(description: "LOG|didReceiveInviteCancelForCall callId=\(callId), reason=\(reason)")

        // ── KILLED STATE: cancel arrived before didReceiveInviteForCall ──
        if callIdToUUID[callId] == nil, let pushUUID = pendingPushUUID {
            pushCompletionTimer?.invalidate()
            pushCompletionTimer = nil
            pendingAnswerAction?.fail()
            pendingEndAction?.fulfill()
            callKitProvider?.reportCall(with: pushUUID, endedAt: Date(), reason: .remoteEnded)
            callDisconnected(uuid: pushUUID)
            sendPhoneCallEvents(description: "Missed Call")
            pendingPushCompletion?()
            cleanupPushState()
            return
        }

        // ── NORMAL PATH ──
        guard let uuid = callIdToUUID[callId] else {
            // Cancel arrived before the invite was delivered (e.g. caller hung up
            // very quickly while app was in foreground). No UUID exists, so there's
            // IMPORTANT: Only emit "Missed Call" when no active call exists.
            // If an active call is in progress (e.g. this cancel is for the 2nd
            // call we auto-rejected in didReceiveInviteForCall), emitting "Missed
            // Call" would set isCallEnded=true in the BLoC and dismiss the first
            // call's active screen. Mirrors the activeCalls.isEmpty guard used in
            // all other "Missed Call" emission sites.
            //
            // Also suppress during session teardown (unregister/user-switch): the SDK
            // fires cancel callbacks as cleanup when deleteSession() runs. At that point
            // isSessionReady is already false, and any "Missed Call" emission would
            // create a phantom "Unknown" missed-call record in the app's call log.
            if activeCalls.isEmpty {
                guard isSessionReady else {
                    sendPhoneCallEvents(description: "LOG|Cancel received with no matching invite during teardown (isSessionReady=false) — suppressed to prevent phantom missed call")
                    return
                }
                sendPhoneCallEvents(description: "LOG|Cancel received with no matching invite — emitting Missed Call")
                sendPhoneCallEvents(description: "Missed Call")
            } else {
                sendPhoneCallEvents(description: "LOG|Cancel received with no matching invite but active call exists — suppressing Missed Call to protect active call screen")
            }
            return
        }

        // GUARD: If the call was already answered (or is being answered right
        // now), do NOT process the cancel — the SDK fires cancel as cleanup
        // after the invite was consumed by answer. Ending an active/answering
        // call here would cause the "auto-hangup after answer" bug.
        if activeCalls[uuid] != nil || isAnsweringInProgress {
            sendPhoneCallEvents(description: "LOG|Ignoring invite cancel for answered/answering call callId=\(callId)")
            return
        }

        callInvites.removeValue(forKey: uuid)
        callIdToUUID.removeValue(forKey: callId)

        // Use reportCall(endedAt:reason:) — NOT performEndCallAction.
        // performEndCallAction goes through CXCallController (user-initiated end)
        // which can race with reportNewIncomingCall. reportCall is the correct
        // API for server-initiated cancellation.
        callKitProvider?.reportCall(with: uuid, endedAt: Date(), reason: .remoteEnded)
        callDisconnected(uuid: uuid)

        if activeCalls.isEmpty {
            sendPhoneCallEvents(description: "Missed Call")
        }
    }

    // ─── Remote Hangup ───────────────────────────────────────────────

    public func voiceClient(_ client: VGVoiceClient,
                            didReceiveHangupForCall callId: String,
                            withQuality callQuality: VGRTCQuality,
                            reason: VGHangupReason) {
        let reasonName: String
        switch reason {
        case .localHangup:   reasonName = "localHangup(0)"
        case .remoteReject:  reasonName = "remoteReject(1)"
        case .remoteHangup:  reasonName = "remoteHangup(2)"
        case .mediaTimeout:  reasonName = "mediaTimeout(3)"
        @unknown default:    reasonName = "unknown(\(reason.rawValue))"
        }
        sendPhoneCallEvents(description: "LOG|didReceiveHangupForCall callId=\(callId)")
        sendPhoneCallEvents(description: "LOG|  reason=\(reasonName)")
        sendPhoneCallEvents(description: "LOG|  callQuality: \(callQuality)")
        sendPhoneCallEvents(description: "LOG|  state: userInitiatedDisconnect=\(userInitiatedDisconnect) activeCalls=\(activeCalls.count) callOutgoing=\(callOutgoing)")
        sendPhoneCallEvents(description: "LOG|  uuid lookup: \(callIdToUUID[callId]?.uuidString ?? "NOT FOUND")")

        if let uuid = callIdToUUID[callId] {
            if !userInitiatedDisconnect {
                callKitProvider?.reportCall(with: uuid, endedAt: Date(), reason: .remoteEnded)
                sendPhoneCallEvents(description: "LOG|  reported remoteEnded to CallKit")
            } else {
                sendPhoneCallEvents(description: "LOG|  userInitiatedDisconnect=true, skipping CallKit report")
            }

            // If this was a pending invite (answered on another device),
            // treat it like a cancel — clean up invite and emit Missed Call.
            let wasInvite = callInvites[uuid] != nil
            if wasInvite {
                callInvites.removeValue(forKey: uuid)
                callIdToUUID.removeValue(forKey: callId)
            }
            callDisconnected(uuid: uuid)

            if activeCalls.isEmpty {
                sendPhoneCallEvents(description: wasInvite ? "Missed Call" : "Call Ended")
            }
        } else {
            sendPhoneCallEvents(description: "LOG|  WARNING: No UUID found for callId=\(callId) — orphan hangup")
        }
    }

    // ─── Media State Changes ─────────────────────────────────────────

    public func voiceClient(_ client: VGVoiceClient, didReceiveMediaReconnectingForCall callId: String) {
        sendPhoneCallEvents(description: "LOG|didReceiveMediaReconnecting callId=\(callId)")
        sendPhoneCallEvents(description: "Reconnecting")
    }

    public func voiceClient(_ client: VGVoiceClient, didReceiveMediaReconnectionForCall callId: String) {
        sendPhoneCallEvents(description: "LOG|didReceiveMediaReconnection callId=\(callId)")
        sendPhoneCallEvents(description: "Reconnected")
    }

    public func voiceClient(_ client: VGVoiceClient, didReceiveMediaErrorForCall callId: String, error: VGError) {
        sendPhoneCallEvents(description: "LOG|didReceiveMediaError callId=\(callId), error=\(error.localizedDescription), code=\(error.code)")
    }

    public func voiceClient(_ client: VGVoiceClient,
                            didReceiveMuteForCall callId: String,
                            withLegId legId: String,
                            andStatus isMuted: DarwinBoolean) {
        let muted = isMuted.boolValue
        sendPhoneCallEvents(description: "LOG|didReceiveMute callId=\(callId), isMuted=\(muted)")
        self.isMuted = muted
        sendPhoneCallEvents(description: muted ? "Mute" : "Unmute")
    }

    // ─── Session Error ───────────────────────────────────────────────

    public func client(_ client: VGBaseClient, didReceiveSessionErrorWith reason: VGSessionErrorReason) {
        sendPhoneCallEvents(description: "LOG|didReceiveSessionError reason=\(reason)")
        if reason == .tokenExpired || reason == VGSessionErrorReason.tokenExpired {
            sendPhoneCallEvents(description: "DEVICETOKEN|expired")
        }
    }
}


// ═══════════════════════════════════════════════════════════════════════
// MARK: - Helper Types
// ═══════════════════════════════════════════════════════════════════════

/// Holds the data for a pending call invite.
struct CallInviteInfo {
    let callId: String
    let from: String
    let to: String
}

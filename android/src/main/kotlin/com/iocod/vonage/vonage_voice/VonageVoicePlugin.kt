package com.iocod.vonage.vonage_voice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.graphics.drawable.Icon
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.iocod.vonage.vonage_voice.constants.Constants
import com.iocod.vonage.vonage_voice.constants.FlutterErrorCodes
import com.iocod.vonage.vonage_voice.fcm.VonageFirebaseMessagingService
import com.iocod.vonage.vonage_voice.IncomingCallActivity
import com.iocod.vonage.vonage_voice.receivers.TVBroadcastReceiver
import com.iocod.vonage.vonage_voice.service.TVConnectionService
import com.iocod.vonage.vonage_voice.service.VonageClientHolder
import com.iocod.vonage.vonage_voice.storage.Storage
import com.iocod.vonage.vonage_voice.storage.StorageImpl
import com.iocod.vonage.vonage_voice.types.CallDirection
import com.iocod.vonage.vonage_voice.types.VNMethodChannels
import com.iocod.vonage.vonage_voice.types.VNNativeCallEvents
import com.vonage.voice.api.VoiceClient
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * VonageVoicePlugin — main Flutter plugin entry point.
 *
 * Implements:
 *   [FlutterPlugin]        — Flutter engine attach / detach lifecycle
 *   [MethodCallHandler]    — handles all 36 MethodChannel commands from Dart
 *   [EventChannel.StreamHandler] — streams call/audio/permission events to Dart
 *   [ActivityAware]        — access to Activity for permission requests
 *   [PluginRegistry.RequestPermissionsResultListener] — handles permission results
 *
 * Channel names:
 *   MethodChannel → "vonage_voice/messages"
 *   EventChannel  → "vonage_voice/events"
 *
 * All events to Flutter are pipe-delimited strings:
 *   "Ringing|+14155551234|+14158765432|Outgoing"
 *   "PERMISSION|Microphone|true"
 */
class VonageVoicePlugin :
    FlutterPlugin,
    MethodCallHandler,
    EventChannel.StreamHandler,
    ActivityAware,
    PluginRegistry.RequestPermissionsResultListener,
    PluginRegistry.ActivityResultListener {

    // ── Flutter channels ──────────────────────────────────────────────────

    private var methodChannel: MethodChannel? = null
    private var eventChannel: EventChannel? = null
    private var eventSink: EventChannel.EventSink? = null

    // ── Android context ───────────────────────────────────────────────────

    private var context: Context? = null
    private var activity: Activity? = null

    // ── Core components ───────────────────────────────────────────────────

    private var voiceClient: VoiceClient? = null
    private var storage: Storage? = null
    private var broadcastReceiver: TVBroadcastReceiver? = null

    // ── Call state tracking ───────────────────────────────────────────────

    /** callId of the current active or pending call. Null when no call is active. */
    private var activeCallId: String? = null

    /** Local mute state — Vonage SDK has no getMuteState() so we track it here. */
    private var isMuted: Boolean = false

    /** Dedup guard for BROADCAST_CALL_INVITE — prevents duplicate Incoming events. */
    private var lastBroadcastCallId: String? = null
    private var lastBroadcastTimeMs: Long = 0L

    /** Dedup guard for setCallInviteListener — prevents double-fire when SDK accumulates listeners. */
    private var lastInviteCallId: String? = null
    private var lastInviteTimeMs: Long = 0L

    // ── Permission request tracking ───────────────────────────────────────

    private val permissionResultHandlers = HashMap<Int, (Boolean) -> Unit>()
    private var btEnableResult: Result? = null

    /** Pending re-emit runnables — cancelled in onCancel() to prevent stale emissions. */
    private val pendingReEmitRunnables = mutableListOf<Runnable>()

    /**
     * Critical call-state events queued while [eventSink] is null.
     * Flushed in [onListen] the moment Flutter re-subscribes.
     * Mirrors the Twilio plugin's [pendingEvents] pattern.
     * Thread-safe: access is synchronized on this list.
     */
    private val pendingCallEvents = mutableListOf<String>()

    companion object {
        private const val REQUEST_CODE_MIC         = 1001
        private const val REQUEST_CODE_PHONE_STATE = 1002
        private const val REQUEST_CODE_CALL_PHONE  = 1003
        private const val REQUEST_CODE_PHONE_NUMBERS = 1004
        private const val REQUEST_CODE_MANAGE_CALLS  = 1005
        private const val REQUEST_CODE_BLUETOOTH     = 1006
        private const val REQUEST_CODE_NOTIFICATIONS  = 1007
        private const val REQUEST_CODE_BT_ENABLE     = 2001
    }

    // ── Application foreground/background observer ────────────────────────
    // ProcessLifecycleOwner fires onStart when ANY activity becomes visible
    // and onStop when ALL activities are in the background (with a ~700ms delay
    // to account for config changes / activity transitions).
    private val appLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            VonageClientHolder.isFlutterInForeground = true
            android.util.Log.d("VonagePlugin", "App moved to FOREGROUND")
        }

        override fun onStop(owner: LifecycleOwner) {
            VonageClientHolder.isFlutterInForeground = false
            android.util.Log.d("VonagePlugin", "App moved to BACKGROUND")
        }
    }

    // ── FlutterPlugin — engine lifecycle ──────────────────────────────────

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        storage = StorageImpl(binding.applicationContext)

        // ── Idempotent cleanup: remove any leftover from a previous attach ──
        // Handles hot restart and engine re-attach without a preceding detach,
        // which causes duplicate lifecycle callbacks ("App moved to FOREGROUND"
        // logged twice per transition) and duplicate broadcast handling.
        try {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(appLifecycleObserver)
        } catch (_: Exception) {}
        broadcastReceiver?.let {
            try { it.unregister(binding.applicationContext) } catch (_: Exception) {}
        }

        // Register MethodChannel
        methodChannel = MethodChannel(binding.binaryMessenger, "vonage_voice/messages")
        methodChannel!!.setMethodCallHandler(this)

        // Register EventChannel
        eventChannel = EventChannel(binding.binaryMessenger, "vonage_voice/events")
        eventChannel!!.setStreamHandler(this)

        // Register LocalBroadcast receiver
        broadcastReceiver = TVBroadcastReceiver(object : TVBroadcastReceiver.BroadcastListener {
            override fun onBroadcastReceived(intent: Intent) {
                handleBroadcastIntent(intent)
            }
        })
        broadcastReceiver!!.register(binding.applicationContext)

        // Reset reEmit flag on each engine attach so cold-starts
        // (app killed → FCM → user taps notification → engine starts)
        // will re-emit the pending call state to Flutter.
        pendingCallReEmitted = null
        synchronized(pendingCallEvents) { pendingCallEvents.clear() }

        // Track app foreground/background state so TVConnectionService
        // knows whether to show the native IncomingCallActivity (background)
        // or let Flutter handle the incoming call UI (foreground).
        // ProcessLifecycleOwner is the most reliable way to detect this —
        // it fires onStart/onStop for the entire Application, not per-Activity.
        ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleObserver)
        VonageClientHolder.isFlutterEngineAttached = true
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        // Mark Flutter engine as detached so TVConnectionService uses
        // the native IncomingCallActivity for incoming calls.
        VonageClientHolder.isFlutterEngineAttached = false
        VonageClientHolder.isFlutterInForeground = false
        ProcessLifecycleOwner.get().lifecycle.removeObserver(appLifecycleObserver)

        methodChannel?.setMethodCallHandler(null)
        methodChannel = null

        eventChannel?.setStreamHandler(null)
        eventChannel = null

        broadcastReceiver?.unregister(binding.applicationContext)
        broadcastReceiver = null

        // Clear VoiceClient from holder and release session
        voiceClient?.deleteSession { error ->
            if (error != null) logEvent("deleteSession error: ${error.message}")
        }
        VonageClientHolder.voiceClient = null
        VonageClientHolder.isCallAnsweredNatively = false
        VonageClientHolder.isAnsweringInProgress = false
        voiceClient = null

        context = null
    }

    // ── ActivityAware — activity lifecycle ────────────────────────────────

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addRequestPermissionsResultListener(this)
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addRequestPermissionsResultListener(this)
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    // ── EventChannel.StreamHandler ────────────────────────────────────────

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        eventSink = events
        VonageClientHolder.isEventSinkAttached = (events != null)
        android.util.Log.d("VonagePlugin", "onListen — EventSink attached, scheduling reEmitPendingCallState")

        // Flush any call-state events that arrived while the EventSink was null.
        // Mirrors the Twilio plugin's pendingEvents flush-on-onListen pattern.
        if (events != null) {
            val toFlush: List<String>
            synchronized(pendingCallEvents) {
                toFlush = pendingCallEvents.toList()
                pendingCallEvents.clear()
            }
            if (toFlush.isNotEmpty()) {
                android.util.Log.d("VonagePlugin", "onListen: flushing ${toFlush.size} queued event(s) to Flutter")

                // Check if the queue contains a Connected event — if so, the call
                // was already answered natively and we should skip the Incoming event.
                val hasConnectedEvent = toFlush.any { it.startsWith("Connected") }
                // Check if the queue contains a CallEnded event — if so, the call
                // has already ended and we should skip both Incoming and Connected.
                val hasEndedEvent = toFlush.any {
                    it == VNNativeCallEvents.EVENT_CALL_ENDED || it == VNNativeCallEvents.EVENT_MISSED_CALL
                }

                for (queued in toFlush) {
                    // Drop stale Incoming events if:
                    // 1. No pending invite AND no active connection (call completely gone), OR
                    // 2. A Connected event is also in the queue (call already answered — no
                    //    point showing the ringing screen before switching to active call), OR
                    // 3. A CallEnded event is also in the queue (call already ended).
                    if (queued.startsWith("Incoming")) {
                        if (TVConnectionService.pendingInvites.isEmpty() &&
                            TVConnectionService.activeConnections.isEmpty()) {
                            android.util.Log.d("VonagePlugin",
                                "onListen: dropping stale Incoming event — no pending invite or active connection")
                            continue
                        }
                        if (hasConnectedEvent) {
                            android.util.Log.d("VonagePlugin",
                                "onListen: dropping Incoming event — Connected event already queued")
                            continue
                        }
                        if (hasEndedEvent) {
                            android.util.Log.d("VonagePlugin",
                                "onListen: dropping Incoming event — CallEnded event already queued")
                            continue
                        }
                    }
                    // Drop Connected events if the call has already ended
                    if (queued.startsWith("Connected") && hasEndedEvent) {
                        android.util.Log.d("VonagePlugin",
                            "onListen: dropping Connected event — CallEnded event already queued")
                        continue
                    }
                    android.util.Log.d("VonagePlugin", "onListen: flushed queued event: $queued")
                    events.success(queued)
                }
            }
        }

        // Cancel any leftover runnables from a previous subscription
        cancelPendingReEmitRunnables()

        // Retry with increasing delays to handle slow cold-starts.
        // The first attempt fires quickly for the foreground case;
        // later attempts catch slower background-launch scenarios
        // (e.g. answer callback hasn't completed yet at 100ms).
        val delays = longArrayOf(100, 500, 1500, 3000)
        val handler = Handler(Looper.getMainLooper())
        for (delay in delays) {
            val runnable = Runnable {
                if (eventSink != null) {
                    reEmitPendingCallState()
                }
            }
            pendingReEmitRunnables.add(runnable)
            handler.postDelayed(runnable, delay)
        }
    }

    override fun onCancel(arguments: Any?) {
        cancelPendingReEmitRunnables()
        eventSink = null
        VonageClientHolder.isEventSinkAttached = false
    }

    /** Cancel all pending re-emit runnables to prevent stale emissions. */
    private fun cancelPendingReEmitRunnables() {
        val handler = Handler(Looper.getMainLooper())
        for (runnable in pendingReEmitRunnables) {
            handler.removeCallbacks(runnable)
        }
        pendingReEmitRunnables.clear()
    }

    /**
     * Re-emits the current call state to Flutter when the EventChannel
     * listener is (re-)attached. Handles two cases:
     *
     * 1. Pending incoming invite — app was backgrounded when FCM push
     *    arrived and the original "Incoming|..." event was lost.
     * 2. Active connected call — user answered from the notification
     *    while the app was backgrounded and the "Connected|..." event
     *    was never delivered to Flutter.
     *
     * This method is called multiple times with increasing delays.
     * [pendingCallReEmitted] tracks what was last emitted so we
     * can upgrade from "incoming" to "connected" if the answer
     * callback completes between retries.
     */
    private var pendingCallReEmitted: String? = null

    private fun reEmitPendingCallState() {
        // Case 2 first: already-answered call — show active call screen
        // Check this BEFORE pending invites because handleAnswer() moves
        // the call from pendingInvites → activeConnections when answer succeeds.
        val activeEntry = TVConnectionService.activeConnections.entries.firstOrNull()
        if (activeEntry != null) {
            // Only emit if we haven't already emitted "connected" for this call
            if (pendingCallReEmitted == "connected_${activeEntry.key}") return
            val conn = activeEntry.value
            activeCallId = activeEntry.key
            pendingCallReEmitted = "connected_${activeEntry.key}"
            android.util.Log.d("VonagePlugin", "reEmit: Connected -- callId=${activeEntry.key}")
            emitEvent(
                VNNativeCallEvents.callStateEvent(
                    VNNativeCallEvents.EVENT_CONNECTED,
                    conn.from, conn.to,
                    CallDirection.INCOMING
                )
            )
            return
        }

        // Case 1: pending incoming invite — show ringing screen
        val pendingEntry = TVConnectionService.pendingInvites.entries.firstOrNull()
        if (pendingEntry != null) {
            // Don't re-emit incoming if we already emitted it AND there's no
            // active connection yet (answer still in progress — let the next retry handle it)
            if (pendingCallReEmitted == "incoming_${pendingEntry.key}") return
            val invite = pendingEntry.value
            val from = resolveCallerName(invite.from)
            activeCallId = pendingEntry.key
            pendingCallReEmitted = "incoming_${pendingEntry.key}"
            android.util.Log.d("VonagePlugin", "reEmit: Incoming -- callId=${pendingEntry.key}")
            emitEvent(VNNativeCallEvents.incomingCallEvent(from, invite.to))
            return
        }

        // Case 0: SharedPreferences cross-process fallback (Twilio pattern).
        // Fires when the OS killed the process between FCM delivery and the
        // user tapping the notification — pendingInvites is empty in the new
        // process, but SharedPreferences survived across the process boundary.
        // We re-emit Incoming so Flutter shows the ringing screen, then call
        // tryReprocessPendingFcm() to rebuild the Vonage SDK invite so
        // that answer() will actually work.
        val ctx = context ?: return
        try {
            val prefs = ctx.getSharedPreferences(
                TVConnectionService.PREFS_PENDING_CALL, Context.MODE_PRIVATE)
            val savedCallId   = prefs.getString(TVConnectionService.KEY_PENDING_CALL_ID, null)
            val savedFrom     = prefs.getString(TVConnectionService.KEY_PENDING_CALL_FROM, null)
            val savedTo       = prefs.getString(TVConnectionService.KEY_PENDING_CALL_TO, "") ?: ""
            val savedTimestamp = prefs.getLong(TVConnectionService.KEY_PENDING_CALL_TIMESTAMP, 0)

            if (!savedCallId.isNullOrEmpty() && !savedFrom.isNullOrEmpty()) {
                val elapsed = System.currentTimeMillis() - savedTimestamp
                if (elapsed < TVConnectionService.PENDING_CALL_TTL_MS) {
                    if (pendingCallReEmitted == "incoming_$savedCallId") return
                    pendingCallReEmitted = "incoming_$savedCallId"
                    activeCallId = savedCallId
                    val displayFrom = resolveCallerName(savedFrom)
                    android.util.Log.d("VonagePlugin",
                        "reEmit: Incoming from SharedPreferences fallback — callId=$savedCallId, from=$displayFrom (age=${elapsed}ms)")
                    emitEvent(VNNativeCallEvents.incomingCallEvent(displayFrom, savedTo))
                    // Re-process the raw FCM push so the Vonage SDK registers
                    // the invite and client.answer(callId) will succeed.
                    tryReprocessPendingFcm(retriesLeft = 5)
                } else {
                    android.util.Log.d("VonagePlugin",
                        "reEmit: SharedPreferences entry expired (age=${elapsed}ms) — clearing")
                    TVConnectionService.clearPendingCallData(ctx)
                    VonageFirebaseMessagingService.clearPendingFcmData(ctx)
                }
            } else {
                // If the call was already answered natively (killed-app lock-screen
                // answer), the FCM data is stale — clear it and skip re-processing.
                // Without this guard, tryReprocessPendingFcm() would re-create the
                // SDK invite and cause a phantom re-ring after the call has ended.
                val answeredPrefs = ctx.getSharedPreferences(
                    TVConnectionService.PREFS_ANSWERED_CALL, Context.MODE_PRIVATE)
                val answeredCallId = answeredPrefs.getString(
                    TVConnectionService.KEY_ANSWERED_CALL_ID, null)
                if (!answeredCallId.isNullOrEmpty()) {
                    android.util.Log.d("VonagePlugin",
                        "reEmit: Call already answered natively (callId=$answeredCallId) — clearing stale FCM data")
                    VonageFirebaseMessagingService.clearPendingFcmData(ctx)
                    TVConnectionService.clearAnsweredCallData(ctx)
                    return
                }

                // vonage_pending_call is empty — this is the killed-process scenario where
                // FCM arrived but processPushCallInvite never completed (so handleIncomingCall()
                // never wrote to vonage_pending_call). Check vonage_pending_fcm directly for
                // raw FCM data and trigger session restore + re-processing.
                val fcmPrefs = ctx.getSharedPreferences(
                    VonageFirebaseMessagingService.PREFS_PENDING_FCM, Context.MODE_PRIVATE)
                val nexmoRaw = fcmPrefs.getString(VonageFirebaseMessagingService.KEY_NEXMO_RAW, null)
                val fcmTimestamp = fcmPrefs.getLong(VonageFirebaseMessagingService.KEY_FCM_TIMESTAMP, 0)
                if (!nexmoRaw.isNullOrEmpty()) {
                    val fcmAge = System.currentTimeMillis() - fcmTimestamp
                    if (fcmAge < VonageFirebaseMessagingService.PENDING_FCM_TTL_MS) {
                        android.util.Log.d("VonagePlugin",
                            "reEmit: vonage_pending_call empty but FCM data exists (age=${fcmAge}ms) — triggering tryReprocessPendingFcm")
                        tryReprocessPendingFcm(retriesLeft = 10)
                    } else {
                        android.util.Log.d("VonagePlugin",
                            "reEmit: FCM data expired (age=${fcmAge}ms) — clearing")
                        VonageFirebaseMessagingService.clearPendingFcmData(ctx)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("VonagePlugin", "reEmitPendingCallState: SharedPreferences check failed: ${e.message}")
        }
    }

    // ── MethodChannel dispatcher ──────────────────────────────────────────

    /**
     * Main dispatch method — resolves the method name to a [VNMethodChannels]
     * enum entry and routes to the correct handler.
     */
    override fun onMethodCall(call: MethodCall, result: Result) {
        when (VNMethodChannels.fromMethodName(call.method)) {

            // ── Session / registration ────────────────────────────────────
            VNMethodChannels.TOKENS -> handleTokens(call, result)
            VNMethodChannels.UNREGISTER -> handleUnregister(call, result)
            VNMethodChannels.REFRESH_SESSION -> handleRefreshSession(call, result)

            // ── Core call controls ────────────────────────────────────────
            VNMethodChannels.MAKE_CALL -> handleMakeCall(call, result)
            VNMethodChannels.HANG_UP -> handleHangUp(result)
            VNMethodChannels.ANSWER -> handleAnswer(result)
            VNMethodChannels.SEND_DIGITS -> handleSendDigits(call, result)

            // ── Audio routing ─────────────────────────────────────────────
            VNMethodChannels.TOGGLE_SPEAKER -> handleToggleSpeaker(call, result)
            VNMethodChannels.IS_ON_SPEAKER -> handleIsOnSpeaker(result)
            VNMethodChannels.TOGGLE_BLUETOOTH -> handleToggleBluetooth(call, result)
            VNMethodChannels.IS_BLUETOOTH_ON -> handleIsBluetoothOn(result)
            VNMethodChannels.IS_BLUETOOTH_AVAILABLE -> handleIsBluetoothAvailable(result)
            VNMethodChannels.IS_BLUETOOTH_ENABLED -> handleIsBluetoothEnabled(result)
            VNMethodChannels.SHOW_BLUETOOTH_ENABLE_PROMPT -> handleShowBluetoothEnablePrompt(result)
            VNMethodChannels.OPEN_BLUETOOTH_SETTINGS -> handleOpenBluetoothSettings(result)
            VNMethodChannels.GET_AUDIO_DEVICES -> handleGetAudioDevices(result)
            VNMethodChannels.SELECT_AUDIO_DEVICE -> handleSelectAudioDevice(call, result)

            // ── Mute ──────────────────────────────────────────────────────
            VNMethodChannels.TOGGLE_MUTE -> handleToggleMute(call, result)
            VNMethodChannels.IS_MUTED -> result.success(isMuted)

            // ── Call state queries ────────────────────────────────────────
            VNMethodChannels.IS_ON_CALL -> result.success(TVConnectionService.hasActiveCall())
            VNMethodChannels.CALL_SID -> result.success(activeCallId)
            VNMethodChannels.GET_DEVICE_ID -> {
                val id = context?.let { VonageClientHolder.getStoredDeviceId(it) }
                result.success(id)
            }

            // ── Terminated-state recovery (mirrors Twilio's GetActiveCallOnResumeFromTerminatedState) ───
            // Dart bloc calls this on startup to trigger a re-emit of the current call state.
            // Resets pendingCallReEmitted so reEmitPendingCallState() always fires fresh.
            VNMethodChannels.GET_ACTIVE_CALL_ON_RESUME -> {
                android.util.Log.d("VonagePlugin", "getActiveCallOnResumeFromTerminatedState — triggering reEmitPendingCallState")
                pendingCallReEmitted = null
                reEmitPendingCallState()
                result.success(TVConnectionService.hasActiveCall())
            }

            // ── Caller identity registry ──────────────────────────────────
            VNMethodChannels.REGISTER_CLIENT -> handleRegisterClient(call, result)
            VNMethodChannels.UNREGISTER_CLIENT -> handleUnregisterClient(call, result)
            VNMethodChannels.DEFAULT_CALLER -> handleDefaultCaller(call, result)

            // ── Telecom / PhoneAccount ────────────────────────────────────
            VNMethodChannels.HAS_REGISTERED_PHONE_ACCOUNT -> handleHasRegisteredPhoneAccount(result)
            VNMethodChannels.REGISTER_PHONE_ACCOUNT -> handleRegisterPhoneAccount(result)
            VNMethodChannels.IS_PHONE_ACCOUNT_ENABLED -> handleIsPhoneAccountEnabled(result)
            VNMethodChannels.OPEN_PHONE_ACCOUNT_SETTINGS -> handleOpenPhoneAccountSettings(result)

            // ── Permissions ───────────────────────────────────────────────
            VNMethodChannels.HAS_MIC_PERMISSION ->
                result.success(hasPermission(Manifest.permission.RECORD_AUDIO))
            VNMethodChannels.REQUEST_MIC_PERMISSION ->
                requestPermission(Manifest.permission.RECORD_AUDIO,
                    VNNativeCallEvents.PERMISSION_MICROPHONE,
                    REQUEST_CODE_MIC, result)

            VNMethodChannels.HAS_READ_PHONE_STATE_PERMISSION ->
                result.success(hasPermission(Manifest.permission.READ_PHONE_STATE))
            VNMethodChannels.REQUEST_READ_PHONE_STATE_PERMISSION ->
                requestPermission(Manifest.permission.READ_PHONE_STATE,
                    VNNativeCallEvents.PERMISSION_READ_PHONE_STATE,
                    REQUEST_CODE_PHONE_STATE, result)

            VNMethodChannels.HAS_CALL_PHONE_PERMISSION ->
                result.success(hasPermission(Manifest.permission.CALL_PHONE))
            VNMethodChannels.REQUEST_CALL_PHONE_PERMISSION ->
                requestPermission(Manifest.permission.CALL_PHONE,
                    VNNativeCallEvents.PERMISSION_CALL_PHONE,
                    REQUEST_CODE_CALL_PHONE, result)

            VNMethodChannels.HAS_READ_PHONE_NUMBERS_PERMISSION ->
                result.success(hasPermission(Manifest.permission.READ_PHONE_NUMBERS))
            VNMethodChannels.REQUEST_READ_PHONE_NUMBERS_PERMISSION ->
                requestPermission(Manifest.permission.READ_PHONE_NUMBERS,
                    VNNativeCallEvents.PERMISSION_READ_PHONE_NUMBERS,
                    REQUEST_CODE_PHONE_NUMBERS, result)

            VNMethodChannels.HAS_MANAGE_OWN_CALLS_PERMISSION ->
                result.success(hasPermission(Manifest.permission.MANAGE_OWN_CALLS))
            VNMethodChannels.REQUEST_MANAGE_OWN_CALLS_PERMISSION ->
                requestPermission(Manifest.permission.MANAGE_OWN_CALLS,
                    VNNativeCallEvents.PERMISSION_MANAGE_CALLS,
                    REQUEST_CODE_MANAGE_CALLS, result)

            // ── Notification / behaviour settings ─────────────────────────
            VNMethodChannels.SHOW_NOTIFICATIONS -> {
                val show = call.argument<Boolean>(Constants.PARAM_SHOW) ?: true
                storage?.setShowNotifications(show)
                result.success(true)
            }
            VNMethodChannels.REJECT_CALL_ON_NO_PERMISSIONS -> {
                val shouldReject = call.argument<Boolean>(Constants.PARAM_SHOULD_REJECT) ?: false
                storage?.setRejectCallOnNoPermissions(shouldReject)
                result.success(true)
            }
            VNMethodChannels.IS_REJECTING_CALL_ON_NO_PERMISSIONS ->
                result.success(storage?.shouldRejectCallOnNoPermissions() ?: false)

            // ── Bluetooth permission (API 31+) ──────────────────────────
            VNMethodChannels.HAS_BLUETOOTH_PERMISSION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    result.success(hasPermission(Manifest.permission.BLUETOOTH_CONNECT))
                } else {
                    result.success(true) // not needed pre-API 31
                }
            }
            VNMethodChannels.REQUEST_BLUETOOTH_PERMISSION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    requestPermission(Manifest.permission.BLUETOOTH_CONNECT,
                        VNNativeCallEvents.PERMISSION_BLUETOOTH_CONNECT,
                        REQUEST_CODE_BLUETOOTH, result)
                } else {
                    result.success(true)
                }
            }
            VNMethodChannels.BACKGROUND_CALL_UI -> result.success(true)
            VNMethodChannels.UPDATE_CALL_KIT_ICON -> result.success(true)

            // ── Push processing (Dart-side FCM forwarding) ────────────
            VNMethodChannels.PROCESS_PUSH -> handleProcessPush(call, result)

            // ── Battery / power optimization ──────────────────────────────
            VNMethodChannels.IS_BATTERY_OPTIMIZED -> handleIsBatteryOptimized(result)
            VNMethodChannels.REQUEST_BATTERY_OPTIMIZATION_EXEMPTION -> handleRequestBatteryOptimizationExemption(result)

            // ── Full-screen intent permission (API 34+) ──────────────────
            VNMethodChannels.CAN_USE_FULL_SCREEN_INTENT -> handleCanUseFullScreenIntent(result)
            VNMethodChannels.OPEN_FULL_SCREEN_INTENT_SETTINGS -> handleOpenFullScreenIntentSettings(result)

            // ── Overlay / "Display over other apps" permission ────────────
            VNMethodChannels.CAN_DRAW_OVERLAYS -> handleCanDrawOverlays(result)
            VNMethodChannels.OPEN_OVERLAY_SETTINGS -> handleOpenOverlaySettings(result)

            // ── Notification permission (API 33+) ──────────────────────
            VNMethodChannels.HAS_NOTIFICATION_PERMISSION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    result.success(hasPermission(Manifest.permission.POST_NOTIFICATIONS))
                } else {
                    result.success(true) // not needed pre-API 33
                }
            }
            VNMethodChannels.REQUEST_NOTIFICATION_PERMISSION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermission(Manifest.permission.POST_NOTIFICATIONS,
                        VNNativeCallEvents.PERMISSION_NOTIFICATIONS,
                        REQUEST_CODE_NOTIFICATIONS, result)
                } else {
                    result.success(true)
                }
            }

            // ── Unknown method ────────────────────────────────────────────
            null -> result.notImplemented()
        }
    }

    // ── Session handlers ──────────────────────────────────────────────────

    /**
     * tokens() — initialise VoiceClient with JWT and optional FCM token.
     *
     * Flutter args:
     *   jwt         : String  — Vonage JWT (required, comes from your backend)
     *   deviceToken : String? — FCM token (optional, needed for incoming calls)
     */
    private fun handleTokens(call: MethodCall, result: Result) {
        val jwt = call.argument<String>(Constants.PARAM_JWT)
        val fcmToken = call.argument<String>(Constants.PARAM_FCM_TOKEN)
        android.util.Log.i("VonagePlugin", "━━ handleTokens START ━━")
        android.util.Log.i("VonagePlugin", "  jwt=${if (!jwt.isNullOrEmpty()) "${jwt.take(20)}... (${jwt.length} chars)" else "NULL/EMPTY"}")
        android.util.Log.i("VonagePlugin", "  fcmToken=${if (!fcmToken.isNullOrEmpty()) "${fcmToken.take(20)}... (${fcmToken.length} chars)" else "NULL/EMPTY"}")
        if (jwt.isNullOrEmpty()) {
            result.error(
                FlutterErrorCodes.INVALID_PARAMS,
                "jwt is required",
                null
            )
            return
        }

        val ctx = context ?: run {
            result.error(FlutterErrorCodes.UNAVAILABLE_ERROR, "Context not available", null)
            return
        }

        // Create VoiceClient if not already initialised.
        // Reuse the client created by VonageFirebaseMessagingService if the
        // app was killed and restarted via an incoming call push — this
        // preserves the call invite data on that client instance.
        if (voiceClient == null) {
            val existingClient = VonageClientHolder.voiceClient
            if (existingClient != null) {
                voiceClient = existingClient
            } else {
                voiceClient = VoiceClient(ctx)
                VonageClientHolder.voiceClient = voiceClient
            }

            // Register all SDK event listeners once on creation
            registerVoiceClientListeners()
        }

        // If the FCM handler already restored the session (app was killed →
        // incoming call push → VonageFirebaseMessagingService.createSession()),
        // DO NOT call createSession() again — it would invalidate the pending
        // call invite and cause "No invite found" when answering.
        // HOWEVER: if no calls are in progress, the old session's JWT may be
        // expired. In that case, re-create the session with the fresh JWT.
        if (VonageClientHolder.isSessionReady) {
            val hasActiveCalls = TVConnectionService.activeConnections.isNotEmpty()
                    || TVConnectionService.pendingInvites.isNotEmpty()

            if (hasActiveCalls) {
                android.util.Log.d("VonagePlugin", "Session already active (restored by FCM) with active calls -- skipping createSession")
                VonageClientHolder.storeJwt(ctx, jwt)
                val fcmToken = call.argument<String>(Constants.PARAM_FCM_TOKEN)
                if (!fcmToken.isNullOrEmpty()) {
                    registerFcmTokenWithCleanup(fcmToken)
                }
                result.success(true)
                return
            }

            // No active calls — re-create session with fresh JWT to avoid invalid-token errors
            android.util.Log.d("VonagePlugin", "Session was restored by FCM but no active calls -- re-creating with fresh JWT")
            VonageClientHolder.isSessionReady = false
            // Fall through to createSession() below
        }

        // Create session with the JWT from Flutter — never hardcoded
        voiceClient!!.createSession(jwt) { error, sessionId ->
            if (error != null) {
                android.util.Log.e("VonagePlugin", "createSession failed: ${error.message}")
                result.error(
                    FlutterErrorCodes.SESSION_ERROR,
                    "createSession failed: ${error.message}",
                    null
                )
                return@createSession
            }
            android.util.Log.i("VonagePlugin", "Session created: $sessionId")

            // Persist JWT so VonageFirebaseMessagingService can restore
            // the session when the app process was killed by the OS.
            VonageClientHolder.storeJwt(ctx, jwt)
            VonageClientHolder.isSessionReady = true

            // Register FCM push token if provided
            val fcmToken = call.argument<String>(Constants.PARAM_FCM_TOKEN)
            if (!fcmToken.isNullOrEmpty()) {
                android.util.Log.i("VonagePlugin", "Session ready — now registering FCM token with Vonage...")
                registerFcmTokenWithCleanup(fcmToken)
            } else {
                android.util.Log.w("VonagePlugin", "⚠ No FCM token provided — Vonage will NOT send incoming call pushes!")
            }

            // ── Cross-process invite recovery ─────────────────────────────
            // If the OS killed the process while a background call was ringing
            // (MIUI/aggressive OEMs), the Vonage SDK lost the invite. Now that
            // the session is ready, re-process the stored FCM nexmo payload so
            // client.answer() works when the user taps Answer on the ringing screen.
            tryReprocessPendingFcm(retriesLeft = 3)

            result.success(true)
        }
    }

    /**
     * Registers an FCM push token with Vonage, first unregistering any
     * previously stored device to avoid hitting the max-device-limit.
     * If registration still fails with max-device-limit, retries once.
     */
    private fun registerFcmTokenWithCleanup(fcmToken: String) {
        val client = voiceClient ?: run {
            android.util.Log.e("VonagePlugin", "registerFcmTokenWithCleanup: voiceClient is null — cannot register")
            return
        }
        val ctx = context ?: run {
            android.util.Log.e("VonagePlugin", "registerFcmTokenWithCleanup: context is null — cannot register")
            return
        }

        android.util.Log.i("VonagePlugin", "registerFcmTokenWithCleanup: fcmToken=${fcmToken.take(20)}...")

        // Unregister old stored deviceId first (from a prior install/session)
        val oldDeviceId = VonageClientHolder.getStoredDeviceId(ctx)
        if (!oldDeviceId.isNullOrEmpty()) {
            android.util.Log.i("VonagePlugin", "Unregistering old deviceId before re-registering: $oldDeviceId")
            logEvent("Unregistering old deviceId before re-registering: $oldDeviceId")
            client.unregisterDevicePushToken(oldDeviceId) { error ->
                if (error != null) {
                    android.util.Log.w("VonagePlugin", "Old device unregister failed (non-fatal): ${error.message}")
                    logEvent("Old device unregister failed (non-fatal): ${error.message}")
                }
                doRegisterFcmToken(fcmToken, isRetry = false)
            }
        } else {
            android.util.Log.d("VonagePlugin", "No old deviceId stored — registering directly")
            doRegisterFcmToken(fcmToken, isRetry = false)
        }
    }

    private fun doRegisterFcmToken(fcmToken: String, isRetry: Boolean) {
        val client = voiceClient ?: run {
            android.util.Log.e("VonagePlugin", "doRegisterFcmToken: voiceClient is null")
            return
        }
        val ctx = context ?: run {
            android.util.Log.e("VonagePlugin", "doRegisterFcmToken: context is null")
            return
        }

        android.util.Log.i("VonagePlugin", "━━ Registering FCM push token with Vonage (retry=$isRetry) ━━")
        logEvent("Registering FCM push token (retry=$isRetry)")
        client.registerDevicePushToken(fcmToken) { tokenError, deviceId ->
            if (tokenError != null) {
                android.util.Log.e("VonagePlugin", "✗ FCM token registration FAILED: ${tokenError.message}")
                logEvent("FCM token registration failed: ${tokenError.message}")

                // Handle max-device-limit: unregister stored device and retry once
                if (!isRetry && (tokenError.message?.contains("max-device-limit") == true)) {
                    android.util.Log.w("VonagePlugin", "Max device limit reached — attempting unregister and retry")
                    logEvent("Max device limit reached — attempting unregister and retry")
                    val storedId = VonageClientHolder.getStoredDeviceId(ctx)
                    if (!storedId.isNullOrEmpty()) {
                        client.unregisterDevicePushToken(storedId) { unregError ->
                            if (unregError != null) {
                                android.util.Log.w("VonagePlugin", "Unregister for retry failed: ${unregError.message}")
                                logEvent("Unregister for retry failed: ${unregError.message}")
                            }
                            doRegisterFcmToken(fcmToken, isRetry = true)
                        }
                    } else {
                        android.util.Log.e("VonagePlugin", "No stored deviceId — cannot auto-recover from max-device-limit")
                        logEvent("No stored deviceId — cannot auto-recover from max-device-limit. Use Vonage REST API to clear old devices.")
                    }
                }
            } else if (deviceId != null) {
                VonageClientHolder.storeDeviceId(ctx, deviceId)
                android.util.Log.i("VonagePlugin", "✓ FCM push token registered with Vonage. deviceId=$deviceId")
                logEvent("FCM push token registered. deviceId=$deviceId")
            } else {
                android.util.Log.w("VonagePlugin", "✗ FCM registration returned null error AND null deviceId")
            }
        }
    }

    /**
     * processVonagePush() — forward an FCM push payload from Dart.
     *
     * When Flutter's firebase_messaging plugin intercepts the FCM message
     * before VonageFirebaseMessagingService, the Dart side can call this
     * method to feed the push data to VoiceClient.processPushCallInvite().
     *
     * Flutter args:
     *   data : Map<String, String> — the RemoteMessage.data map
     */
    private fun handleProcessPush(call: MethodCall, result: Result) {
        @Suppress("UNCHECKED_CAST")
        val data = call.argument<Map<String, String>>("data")
        if (data.isNullOrEmpty()) {
            result.success(null)
            return
        }

        // Vonage pushes always contain a "nexmo" key.
        if (!data.containsKey("nexmo")) {
            android.util.Log.d("VonagePlugin", "processVonagePush: no 'nexmo' key — not a Vonage push")
            result.success(null)
            return
        }

        // Guard: if VonageFirebaseMessagingService already started processing
        // this push within the last 5 seconds, skip to avoid double-processing
        // which can corrupt the Vonage SDK's invite state.
        val timeSinceNativePush = System.currentTimeMillis() - VonageClientHolder.lastNativePushTimestamp
        if (timeSinceNativePush < 5000) {
            android.util.Log.d("VonagePlugin", "processVonagePush: native FCM service processed ${timeSinceNativePush}ms ago — skipping")
            result.success(null)
            return
        }

        // The Vonage SDK's internal extractJsonFromPushData expects the data
        // in Kotlin Map.toString() format: {nexmo={"type":...}}
        // It does substringAfter("nexmo=").dropLast(1) to extract the JSON.
        val dataString = data.toString()
        android.util.Log.d("VonagePlugin", "processVonagePush data for SDK (first 200 chars): ${dataString.take(200)}")

        // Extract caller display name from the push data and store it
        // so the setCallInviteListener callback can use it.
        val nexmoRaw = data["nexmo"]
        if (!nexmoRaw.isNullOrEmpty()) {
            val callerDisplay = extractCallerDisplay(nexmoRaw)
            if (!callerDisplay.isNullOrEmpty()) {
                VonageClientHolder.pendingCallerDisplay = callerDisplay
            }
        }

        val client = voiceClient ?: VonageClientHolder.voiceClient
        if (client == null) {
            // VoiceClient not available — the native VonageFirebaseMessagingService
            // handles push processing independently, so this is not fatal.
            android.util.Log.w("VonagePlugin", "processVonagePush: VoiceClient is null — native FCM service handles it")
            result.success(null)
            return
        }

        try {
            val callId = client.processPushCallInvite(dataString)
            android.util.Log.d("VonagePlugin", "processVonagePush callId=$callId")
            // Deduplicate: if VonageFirebaseMessagingService already processed this push, skip.
            if (!callId.isNullOrEmpty() && !VonageClientHolder.markPushProcessed(callId)) {
                android.util.Log.d("VonagePlugin", "processVonagePush: callId=$callId already processed — skipping")
            }
            // Null callId is normal — SDK fires setCallInviteListener asynchronously.
            result.success(callId)
        } catch (e: Exception) {
            android.util.Log.e("VonagePlugin", "processVonagePush error: ${e.message}")
            result.success(null)
        }
    }

    /**
     * unregister() — unregister FCM push token and delete the Vonage session.
     *
     * Flutter args:
     *   deviceToken : String? — deviceId returned by registerDevicePushToken
     */
    private fun handleUnregister(call: MethodCall, result: Result) {
        val client = voiceClient ?: run {
            result.error(FlutterErrorCodes.CLIENT_NOT_INITIALISED, "Call tokens() first", null)
            return
        }

        // Use the stored Vonage device ID (returned by registerDevicePushToken),
        // not the Dart-side accessToken which is incorrectly passed as "deviceToken".
        val ctx = context
        val storedDeviceId = ctx?.let { VonageClientHolder.getStoredDeviceId(it) }
        if (!storedDeviceId.isNullOrEmpty()) {
            client.unregisterDevicePushToken(storedDeviceId) { error ->
                if (error != null) logEvent("unregisterDevicePushToken failed: ${error.message}")
            }
        }

        client.deleteSession { error ->
            if (error != null) {
                result.error(
                    FlutterErrorCodes.SESSION_ERROR,
                    "deleteSession failed: ${error.message}",
                    null
                )
                return@deleteSession
            }
            voiceClient = null
            VonageClientHolder.voiceClient = null
            VonageClientHolder.isSessionReady = false
            context?.let {
                VonageClientHolder.clearStoredJwt(it)
                VonageClientHolder.clearStoredDeviceId(it)
            }
            result.success(true)
        }
    }

    /**
     * refreshSession() — refresh an expiring JWT without destroying the session.
     *
     * Flutter args:
     *   jwt : String — new JWT from your backend
     */
    private fun handleRefreshSession(call: MethodCall, result: Result) {
        val jwt = call.argument<String>(Constants.PARAM_JWT)
        if (jwt.isNullOrEmpty()) {
            result.error(FlutterErrorCodes.INVALID_PARAMS, "jwt is required", null)
            return
        }

        val client = voiceClient ?: run {
            result.error(FlutterErrorCodes.CLIENT_NOT_INITIALISED, "Call tokens() first", null)
            return
        }

        client.refreshSession(jwt) { error ->
            if (error != null) {
                result.error(
                    FlutterErrorCodes.SESSION_REFRESH_ERROR,
                    "refreshSession failed: ${error.message}",
                    null
                )
                return@refreshSession
            }
            // Update stored JWT so background session restore uses the fresh token
            context?.let { VonageClientHolder.storeJwt(it, jwt) }
            result.success(true)
        }
    }

    // ── Call control handlers ─────────────────────────────────────────────

    /**
     * makeCall() — place an outbound call via Vonage serverCall().
     *
     * Flutter args:
     *   to   : String            — destination number or user identity
     *   from : String?           — caller identity (optional)
     *   ...  : any extra keys    — passed as custom params to serverCall()
     */
    private fun handleMakeCall(call: MethodCall, result: Result) {
        val to = call.argument<String>(Constants.PARAM_TO)
        if (to.isNullOrEmpty()) {
            result.error(FlutterErrorCodes.INVALID_PARAMS, "to is required", null)
            return
        }

        val from = call.argument<String>(Constants.PARAM_FROM) ?: ""

        val ctx = context ?: run {
            result.error(FlutterErrorCodes.UNAVAILABLE_ERROR, "Context not available", null)
            return
        }

        // Build intent for TVConnectionService — all params from Flutter
        val intent = Intent(ctx, TVConnectionService::class.java).apply {
            action = Constants.ACTION_PLACE_OUTGOING_CALL
            putExtra(Constants.EXTRA_CALL_TO, to)
            putExtra(Constants.EXTRA_CALL_FROM, from)

            // Pass any custom params the Flutter side added
            @Suppress("UNCHECKED_CAST")
            val allArgs = call.arguments as? HashMap<String, String>
            allArgs?.let { putExtra(Constants.EXTRA_OUTGOING_PARAMS, it) }
        }

        ctx.startService(intent)
        result.success(true)
    }

    /**
     * hangUp() — disconnect the active call.
     */
    private fun handleHangUp(result: Result) {
        val callId = activeCallId ?: TVConnectionService.getActiveCallId()
        if (callId.isNullOrEmpty()) {
            result.error(FlutterErrorCodes.NO_ACTIVE_CALL, "No active call to hang up", null)
            return
        }

        val ctx = context ?: run {
            result.error(FlutterErrorCodes.UNAVAILABLE_ERROR, "Context not available", null)
            return
        }

        val intent = Intent(ctx, TVConnectionService::class.java).apply {
            action = Constants.ACTION_HANGUP
            putExtra(Constants.EXTRA_CALL_ID, callId)
        }
        ctx.startService(intent)
        result.success(true)
    }

    /**
     * answer() — answer a pending incoming call invite.
     */
    private fun handleAnswer(result: Result) {
        val callId = activeCallId
        if (callId.isNullOrEmpty()) {
            result.error(FlutterErrorCodes.NO_ACTIVE_CALL, "No pending call invite to answer", null)
            return
        }

        val ctx = context ?: run {
            result.error(FlutterErrorCodes.UNAVAILABLE_ERROR, "Context not available", null)
            return
        }

        val intent = Intent(ctx, TVConnectionService::class.java).apply {
            action = Constants.ACTION_ANSWER
            putExtra(Constants.EXTRA_CALL_ID, callId)
        }
        ctx.startService(intent)
        result.success(true)
    }

    /**
     * sendDigits() — send DTMF tones on the active call.
     *
     * Flutter args:
     *   digits : String — digit string e.g. "1234" or "*#"
     */
    private fun handleSendDigits(call: MethodCall, result: Result) {
        val digits = call.argument<String>(Constants.PARAM_DIGITS)
        if (digits.isNullOrEmpty()) {
            result.error(FlutterErrorCodes.INVALID_PARAMS, "digits is required", null)
            return
        }

        val callId = activeCallId ?: TVConnectionService.getActiveCallId()
        if (callId.isNullOrEmpty()) {
            result.error(FlutterErrorCodes.NO_ACTIVE_CALL, "No active call", null)
            return
        }

        val ctx = context ?: run {
            result.error(FlutterErrorCodes.UNAVAILABLE_ERROR, "Context not available", null)
            return
        }

        val intent = Intent(ctx, TVConnectionService::class.java).apply {
            action = Constants.ACTION_SEND_DIGITS
            putExtra(Constants.EXTRA_CALL_ID, callId)
            putExtra(Constants.EXTRA_DIGITS, digits)
        }
        ctx.startService(intent)
        result.success(true)
    }

    // ── Audio routing handlers ────────────────────────────────────────────

    /**
     * toggleSpeaker() — route audio to / from speakerphone.
     *
     * Flutter args:
     *   speakerIsOn : Boolean
     */
    private fun handleToggleSpeaker(call: MethodCall, result: Result) {
        val speakerOn = call.argument<Boolean>(Constants.PARAM_SPEAKER_IS_ON) ?: false

        val callId = activeCallId ?: TVConnectionService.getActiveCallId()
        if (callId.isNullOrEmpty()) {
            result.error(FlutterErrorCodes.NO_ACTIVE_CALL, "No active call", null)
            return
        }

        val ctx = context ?: run {
            result.error(FlutterErrorCodes.UNAVAILABLE_ERROR, "Context not available", null)
            return
        }

        val intent = Intent(ctx, TVConnectionService::class.java).apply {
            action = Constants.ACTION_TOGGLE_SPEAKER
            putExtra(Constants.EXTRA_CALL_ID, callId)
            putExtra(Constants.EXTRA_SPEAKER_STATE, speakerOn)
        }
        ctx.startService(intent)
        result.success(true)
    }

    /**
     * isOnSpeaker() — returns true if audio is routed to the speaker.
     * Uses communicationDevice check on API 31+ for accuracy.
     */
    private fun handleIsOnSpeaker(result: Result) {
        val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager == null) {
            result.success(false)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = audioManager.communicationDevice
            val isSpeaker = device != null &&
                device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            result.success(isSpeaker || audioManager.isSpeakerphoneOn)
        } else {
            result.success(audioManager.isSpeakerphoneOn)
        }
    }

    /**
     * toggleBluetooth() — route audio to / from Bluetooth headset.
     *
     * Flutter args:
     *   bluetoothOn : Boolean
     */
    private fun handleToggleBluetooth(call: MethodCall, result: Result) {
        val bluetoothOn = call.argument<Boolean>(Constants.PARAM_BLUETOOTH_ON) ?: false

        val callId = activeCallId ?: TVConnectionService.getActiveCallId()
        if (callId.isNullOrEmpty()) {
            result.error(FlutterErrorCodes.NO_ACTIVE_CALL, "No active call", null)
            return
        }

        val ctx = context ?: run {
            result.error(FlutterErrorCodes.UNAVAILABLE_ERROR, "Context not available", null)
            return
        }

        val intent = Intent(ctx, TVConnectionService::class.java).apply {
            action = Constants.ACTION_TOGGLE_BLUETOOTH
            putExtra(Constants.EXTRA_CALL_ID, callId)
            putExtra(Constants.EXTRA_BLUETOOTH_STATE, bluetoothOn)
        }
        ctx.startService(intent)
        result.success(true)
    }

    /**
     * isBluetoothEnabled() — returns true if the device's Bluetooth adapter is on.
     */
    private fun handleIsBluetoothEnabled(result: Result) {
        val btManager = context?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter
        result.success(adapter?.isEnabled ?: false)
    }

    /**
     * showBluetoothEnablePrompt() — shows the native "Turn on Bluetooth?" dialog.
     * Returns true if the user enabled BT, false if they declined.
     */
    private fun handleShowBluetoothEnablePrompt(result: Result) {
        val act = activity
        if (act == null) {
            result.error(FlutterErrorCodes.UNAVAILABLE_ERROR, "No activity available", null)
            return
        }
        val btManager = act.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter
        if (adapter == null) {
            result.success(false)
            return
        }
        if (adapter.isEnabled) {
            result.success(true)
            return
        }
        btEnableResult = result
        @Suppress("DEPRECATION")
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        act.startActivityForResult(enableBtIntent, REQUEST_CODE_BT_ENABLE)
    }

    /**
     * openBluetoothSettings() — opens the system Bluetooth settings screen
     * so the user can pair/connect a device.
     */
    private fun handleOpenBluetoothSettings(result: Result) {
        val ctx = context ?: run {
            result.error(FlutterErrorCodes.UNAVAILABLE_ERROR, "Context not available", null)
            return
        }
        val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
        result.success(true)
    }

    /**
     * isBluetoothAvailable() — returns true if a Bluetooth audio device
     * is connected and available for routing.
     */
    private fun handleIsBluetoothAvailable(result: Result) {
        val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager == null) {
            result.success(false)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val hasBt = devices.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
            result.success(hasBt)
        } else {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val hasBt = devices.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
            result.success(hasBt)
        }
    }

    /**
     * isBluetoothOn() — returns true if audio is routed via Bluetooth.
     * Uses communicationDevice on API 31+ for accurate detection.
     */
    private fun handleIsBluetoothOn(result: Result) {
        val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager == null) {
            result.success(false)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = audioManager.communicationDevice
            val isBt = device != null && (
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            )
            result.success(isBt)
        } else {
            result.success(audioManager.isBluetoothScoOn)
        }
    }

    // ── Audio Device Management ───────────────────────────────────────────

    /**
     * getAudioDevices() — returns all available audio output devices.
     *
     * Each device is returned as a Map with keys:
     *   id       — platform device ID (AudioDeviceInfo.id as String)
     *   type     — "earpiece", "speaker", "bluetooth", "wiredHeadset", "unknown"
     *   name     — human-readable product name
     *   isActive — true if this device is the current audio output
     *
     * On API 31+: uses availableCommunicationDevices + communicationDevice.
     * On pre-31: uses getDevices(GET_DEVICES_OUTPUTS) + isSpeakerphoneOn/isBluetoothScoOn.
     */
    private fun handleGetAudioDevices(result: Result) {
        val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager == null) {
            result.success(emptyList<Map<String, Any>>())
            return
        }

        val devices = mutableListOf<Map<String, Any>>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val commDevices = audioManager.availableCommunicationDevices
            val activeDevice = audioManager.communicationDevice

            for (device in commDevices) {
                val type = mapDeviceType(device.type) ?: continue
                devices.add(mapOf(
                    "id" to device.id.toString(),
                    "type" to type,
                    "name" to (device.productName?.toString()?.ifEmpty { null }
                        ?: getDefaultDeviceName(type)),
                    "isActive" to (activeDevice != null && activeDevice.id == device.id)
                ))
            }
        } else {
            // Pre-API 31: enumerate output devices
            val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val activeSpeaker = audioManager.isSpeakerphoneOn
            val activeBt = audioManager.isBluetoothScoOn
            val seen = mutableSetOf<String>()

            for (device in outputDevices) {
                val type = mapDeviceType(device.type) ?: continue
                val id = device.id.toString()
                if (!seen.add("$type:$id")) continue

                val isActive = when (type) {
                    "speaker" -> activeSpeaker
                    "bluetooth" -> activeBt
                    "earpiece" -> !activeSpeaker && !activeBt
                    else -> false
                }

                devices.add(mapOf(
                    "id" to id,
                    "type" to type,
                    "name" to (device.productName?.toString()?.ifEmpty { null }
                        ?: getDefaultDeviceName(type)),
                    "isActive" to isActive
                ))
            }
        }

        // Ensure earpiece and speaker are always in the list
        if (devices.none { it["type"] == "earpiece" }) {
            val isActive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val d = audioManager.communicationDevice
                d != null && d.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            } else {
                !audioManager.isSpeakerphoneOn && !audioManager.isBluetoothScoOn
            }
            devices.add(0, mapOf(
                "id" to "earpiece",
                "type" to "earpiece",
                "name" to "Earpiece",
                "isActive" to isActive
            ))
        }

        if (devices.none { it["type"] == "speaker" }) {
            val isActive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val d = audioManager.communicationDevice
                d != null && d.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            } else {
                audioManager.isSpeakerphoneOn
            }
            devices.add(mapOf(
                "id" to "speaker",
                "type" to "speaker",
                "name" to "Speaker",
                "isActive" to isActive
            ))
        }

        result.success(devices)
    }

    /**
     * selectAudioDevice() — routes audio to a specific device by ID.
     *
     * Flutter args:
     *   deviceId : String — platform device ID from getAudioDevices()
     *
     * On API 31+: uses setCommunicationDevice() for precise routing.
     * On pre-31: falls back to setSpeakerphoneOn / startBluetoothSco
     * based on the target device type.
     */
    private fun handleSelectAudioDevice(call: MethodCall, result: Result) {
        val deviceId = call.argument<String>(Constants.PARAM_DEVICE_ID)
        if (deviceId.isNullOrEmpty()) {
            result.error(FlutterErrorCodes.INVALID_PARAMS, "deviceId is required", null)
            return
        }

        val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager == null) {
            result.error(FlutterErrorCodes.UNAVAILABLE_ERROR, "AudioManager not available", null)
            return
        }

        // Handle synthetic IDs for built-in devices
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val commDevices = audioManager.availableCommunicationDevices

            // Try numeric ID first
            val numericId = deviceId.toIntOrNull()
            val target = if (numericId != null) {
                commDevices.firstOrNull { it.id == numericId }
            } else {
                // Handle synthetic IDs: "earpiece", "speaker"
                when (deviceId) {
                    "earpiece" -> commDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    }
                    "speaker" -> commDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    else -> null
                }
            }

            if (target != null) {
                audioManager.isSpeakerphoneOn = false
                val success = audioManager.setCommunicationDevice(target)

                // Update connection state so events stay in sync
                val conn = TVConnectionService.getActiveConnection()
                if (conn != null) {
                    val isBt = target.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                               target.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                    val isSpk = target.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    conn.isSpeakerOn = isSpk
                    conn.isBluetoothOn = isBt
                    if (isSpk) audioManager.isSpeakerphoneOn = true
                }

                result.success(success)
            } else {
                result.error(FlutterErrorCodes.INVALID_PARAMS, "Device not found: $deviceId", null)
            }
        } else {
            // Pre-API 31 — use legacy audio routing
            val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val numericId = deviceId.toIntOrNull()
            val targetDevice = if (numericId != null) {
                outputDevices.firstOrNull { it.id == numericId }
            } else null

            val targetType = targetDevice?.type?.let { mapDeviceType(it) } ?: deviceId

            when (targetType) {
                "earpiece" -> {
                    audioManager.isSpeakerphoneOn = false
                    if (audioManager.isBluetoothScoOn) {
                        audioManager.stopBluetoothSco()
                        audioManager.isBluetoothScoOn = false
                    }
                }
                "speaker" -> {
                    if (audioManager.isBluetoothScoOn) {
                        audioManager.stopBluetoothSco()
                        audioManager.isBluetoothScoOn = false
                    }
                    audioManager.isSpeakerphoneOn = true
                }
                "bluetooth" -> {
                    audioManager.isSpeakerphoneOn = false
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                }
                else -> {
                    result.error(FlutterErrorCodes.INVALID_PARAMS, "Unknown device type: $targetType", null)
                    return
                }
            }

            // Update connection state
            val conn = TVConnectionService.getActiveConnection()
            if (conn != null) {
                conn.isSpeakerOn = (targetType == "speaker")
                conn.isBluetoothOn = (targetType == "bluetooth")
            }

            result.success(true)
        }
    }

    /**
     * Maps an Android AudioDeviceInfo.type constant to our device type string.
     * Returns null for device types we don't expose to Flutter.
     */
    private fun mapDeviceType(type: Int): String? {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth"
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET -> "wiredHeadset"
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                    "bluetooth"
                } else {
                    null
                }
            }
        }
    }

    /**
     * Returns a default human-readable name for a device type.
     */
    private fun getDefaultDeviceName(type: String): String {
        return when (type) {
            "earpiece" -> "Earpiece"
            "speaker" -> "Speaker"
            "bluetooth" -> "Bluetooth"
            "wiredHeadset" -> "Wired Headset"
            else -> "Unknown"
        }
    }

    // ── Mute handler ──────────────────────────────────────────────────────

    /**
     * toggleMute() — mute or unmute the microphone.
     *
     * Flutter args:
     *   muted : Boolean — true to mute, false to unmute
     */
    private fun handleToggleMute(call: MethodCall, result: Result) {
        val muted = call.argument<Boolean>(Constants.PARAM_MUTED) ?: false

        val callId = activeCallId ?: TVConnectionService.getActiveCallId()
        if (callId.isNullOrEmpty()) {
            result.error(FlutterErrorCodes.NO_ACTIVE_CALL, "No active call", null)
            return
        }

        val ctx = context ?: run {
            result.error(FlutterErrorCodes.UNAVAILABLE_ERROR, "Context not available", null)
            return
        }

        val intent = Intent(ctx, TVConnectionService::class.java).apply {
            action = Constants.ACTION_TOGGLE_MUTE
            putExtra(Constants.EXTRA_CALL_ID, callId)
            putExtra(Constants.EXTRA_MUTE_STATE, muted)
        }
        ctx.startService(intent)

        // Update local state immediately so isMuted() returns correct value
        isMuted = muted
        result.success(true)
    }

    // ── Caller registry handlers ──────────────────────────────────────────

    private fun handleRegisterClient(call: MethodCall, result: Result) {
        val id = call.argument<String>(Constants.PARAM_CLIENT_ID)
        val name = call.argument<String>(Constants.PARAM_CLIENT_NAME)
        if (id.isNullOrEmpty() || name.isNullOrEmpty()) {
            result.error(FlutterErrorCodes.INVALID_PARAMS, "id and name are required", null)
            return
        }
        storage?.addRegisteredClient(id, name)
        result.success(true)
    }

    private fun handleUnregisterClient(call: MethodCall, result: Result) {
        val id = call.argument<String>(Constants.PARAM_CLIENT_ID)
        if (id.isNullOrEmpty()) {
            result.error(FlutterErrorCodes.INVALID_PARAMS, "id is required", null)
            return
        }
        storage?.removeRegisteredClient(id)
        result.success(true)
    }

    private fun handleDefaultCaller(call: MethodCall, result: Result) {
        val name = call.argument<String>(Constants.PARAM_DEFAULT_CALLER)
        if (name.isNullOrEmpty()) {
            result.error(FlutterErrorCodes.INVALID_PARAMS, "defaultCaller is required", null)
            return
        }
        storage?.setDefaultCaller(name)
        result.success(true)
    }

    // ── Telecom / PhoneAccount handlers ───────────────────────────────────

    private fun handleHasRegisteredPhoneAccount(result: Result) {
        val ctx = context ?: run {
            result.success(false)
            return
        }
        val telecomManager = ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        if (telecomManager == null) {
            result.success(false)
            return
        }
        val componentName = android.content.ComponentName(
            ctx, TVConnectionService::class.java
        )
        val handle = PhoneAccountHandle(componentName, "VonageVoiceAccount")
        try {
            val account = telecomManager.getPhoneAccount(handle)
            result.success(account != null)
        } catch (e: SecurityException) {
            android.util.Log.w("VonageVoice", "hasRegisteredPhoneAccount: ${e.message}")
            result.success(false)
        }
    }

    private fun handleRegisterPhoneAccount(result: Result) {
        val ctx = context ?: run {
            result.success(false)
            return
        }
        val telecomManager = ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        if (telecomManager == null) {
            result.success(false)
            return
        }
        try {
            val componentName = android.content.ComponentName(ctx, TVConnectionService::class.java)
            val handle = PhoneAccountHandle(componentName, "VonageVoiceAccount")
            val label = ctx.applicationInfo.loadLabel(ctx.packageManager).toString()
            val phoneAccount = PhoneAccount.builder(handle, label)
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .setShortDescription("Provides calling services for $label")
                .setIcon(Icon.createWithResource(ctx, ctx.applicationInfo.icon))
                .addSupportedUriScheme(PhoneAccount.SCHEME_TEL)
                .build()
            telecomManager.registerPhoneAccount(phoneAccount)
            android.util.Log.d("VonageVoice", "registerPhoneAccount: registered successfully")
            result.success(true)
        } catch (e: Exception) {
            android.util.Log.e("VonageVoice", "registerPhoneAccount: failed — ${e.message}", e)
            result.error("PHONE_ACCOUNT_ERROR", e.message, null)
        }
    }

    private fun handleIsPhoneAccountEnabled(result: Result) {
        val ctx = context ?: run {
            result.success(false)
            return
        }
        val telecomManager = ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        if (telecomManager == null) {
            result.success(false)
            return
        }
        val componentName = android.content.ComponentName(
            ctx, TVConnectionService::class.java
        )
        val handle = PhoneAccountHandle(componentName, "VonageVoiceAccount")
        try {
            val account = telecomManager.getPhoneAccount(handle)
            // SELF_MANAGED accounts are always considered enabled once registered.
            result.success(account != null)
        } catch (e: SecurityException) {
            android.util.Log.w("VonageVoice", "isPhoneAccountEnabled: ${e.message}")
            result.success(false)
        }
    }

    private fun handleOpenPhoneAccountSettings(result: Result) {
        val intent = Intent(android.telecom.TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context?.startActivity(intent)
        result.success(true)
    }

    // ── Permission helpers ────────────────────────────────────────────────

    /**
     * Check if a manifest permission is currently granted.
     */
    private fun hasPermission(permission: String): Boolean {
        val ctx = context ?: return false
        return ContextCompat.checkSelfPermission(ctx, permission) ==
                PackageManager.PERMISSION_GRANTED
    }

    /**
     * Request a runtime permission, emit a PERMISSION event to Flutter
     * with the result, and call result.success(granted).
     */
    private fun requestPermission(
        manifestPermission: String,
        permissionLabel: String,
        requestCode: Int,
        result: Result
    ) {
        val act = activity ?: run {
            result.error(
                FlutterErrorCodes.UNAVAILABLE_ERROR,
                "Activity not available for permission request",
                null
            )
            return
        }

        if (hasPermission(manifestPermission)) {
            emitEvent(VNNativeCallEvents.permissionEvent(permissionLabel, true))
            result.success(true)
            return
        }

        // Store result handler — called in onRequestPermissionsResult
        permissionResultHandlers[requestCode] = { granted ->
            emitEvent(VNNativeCallEvents.permissionEvent(permissionLabel, granted))
            result.success(granted)
        }

        ActivityCompat.requestPermissions(act, arrayOf(manifestPermission), requestCode)
    }

    /**
     * Receives the permission request result from the system dialog.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ): Boolean {
        val handler = permissionResultHandlers.remove(requestCode) ?: return false
        val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
        handler(granted)
        return true
    }

    /**
     * Receives the Bluetooth enable prompt result.
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == REQUEST_CODE_BT_ENABLE) {
            val enabled = resultCode == Activity.RESULT_OK
            btEnableResult?.success(enabled)
            btEnableResult = null
            return true
        }
        return false
    }

    // ── Battery optimization handlers ────────────────────────────────────

    /**
     * Returns true if the app is NOT exempt from battery optimization.
     * On Vivo, Xiaomi, OPPO, etc. this means the OS may kill the app and
     * prevent FCM data messages from waking it for incoming calls.
     */
    private fun handleIsBatteryOptimized(result: Result) {
        val ctx = context ?: run {
            result.success(true) // assume optimized if no context
            return
        }
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        if (pm == null) {
            result.success(false)
            return
        }
        // isIgnoringBatteryOptimizations returns TRUE if the app is exempt.
        // We invert: "is battery optimized" = NOT exempt = can be killed.
        result.success(!pm.isIgnoringBatteryOptimizations(ctx.packageName))
    }

    /**
     * Opens the system dialog to request battery optimization exemption.
     * This uses ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS which shows a
     * system dialog (not a scary permission prompt) asking the user to allow
     * the app to run in the background.
     *
     * On OEMs like Vivo this is ESSENTIAL — without it the app process is
     * killed aggressively and incoming call pushes are never delivered.
     */
    private fun handleRequestBatteryOptimizationExemption(result: Result) {
        val ctx = context ?: run {
            result.error(FlutterErrorCodes.UNAVAILABLE_ERROR, "Context not available", null)
            return
        }
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        if (pm != null && pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
            // Already exempt
            result.success(true)
            return
        }
        try {
            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:${ctx.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            result.success(true)
        } catch (e: Exception) {
            android.util.Log.e("VonagePlugin", "Failed to open battery optimization settings: ${e.message}")
            result.error(FlutterErrorCodes.UNAVAILABLE_ERROR, "Cannot open battery settings: ${e.message}", null)
        }
    }

    // ── Full-screen intent permission handlers ────────────────────────────

    /**
     * Returns true if the app can use full-screen intents.
     * On API 34+, USE_FULL_SCREEN_INTENT is a special permission that must be
     * granted manually for non-default-dialer apps. Without it, the incoming
     * call notification's fullScreenIntent is silently ignored — no heads-up
     * notification appears on the lock screen.
     */
    private fun handleCanUseFullScreenIntent(result: Result) {
        if (Build.VERSION.SDK_INT < 34) {
            // Pre-API 34: USE_FULL_SCREEN_INTENT is auto-granted
            result.success(true)
            return
        }
        val ctx = context ?: run {
            result.success(false)
            return
        }
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        result.success(nm?.canUseFullScreenIntent() ?: false)
    }

    /**
     * Opens system settings where the user can grant USE_FULL_SCREEN_INTENT.
     * Only meaningful on API 34+.
     */
    private fun handleOpenFullScreenIntentSettings(result: Result) {
        if (Build.VERSION.SDK_INT < 34) {
            result.success(true) // not needed
            return
        }
        val ctx = context ?: run {
            result.error(FlutterErrorCodes.UNAVAILABLE_ERROR, "Context not available", null)
            return
        }
        try {
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                data = android.net.Uri.parse("package:${ctx.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            result.success(true)
        } catch (e: Exception) {
            android.util.Log.e("VonagePlugin", "Failed to open full-screen intent settings: ${e.message}")
            result.error(FlutterErrorCodes.UNAVAILABLE_ERROR, "Cannot open settings: ${e.message}", null)
        }
    }

    // ── Overlay / "Display over other apps" permission handlers ──────────

    /**
     * Returns true if SYSTEM_ALERT_WINDOW is granted.
     * On API < 23 this is auto-granted at install time.
     * On Samsung and many OEMs, this must be explicitly enabled by the user
     * for startActivity() from a foreground service to show the incoming
     * call screen reliably — this is what WhatsApp and Botim use.
     */
    private fun handleCanDrawOverlays(result: Result) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            result.success(true)
            return
        }
        val ctx = context ?: run {
            result.success(false)
            return
        }
        result.success(android.provider.Settings.canDrawOverlays(ctx))
    }

    /**
     * Opens system settings where the user can grant SYSTEM_ALERT_WINDOW
     * ("Display over other apps" / "Appear on top").
     */
    private fun handleOpenOverlaySettings(result: Result) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            result.success(true)
            return
        }
        val ctx = context ?: run {
            result.error(FlutterErrorCodes.UNAVAILABLE_ERROR, "Context not available", null)
            return
        }
        try {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${ctx.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            result.success(true)
        } catch (e: Exception) {
            android.util.Log.e("VonagePlugin", "Failed to open overlay settings: ${e.message}")
            result.error(FlutterErrorCodes.UNAVAILABLE_ERROR, "Cannot open settings: ${e.message}", null)
        }
    }

    // ── LocalBroadcast → EventChannel routing ────────────────────────────

    /**
     * Routes LocalBroadcast intents from TVConnectionService and
     * VonageFirebaseMessagingService into pipe-delimited EventChannel strings.
     *
     * This is the central event routing method — every call/audio/permission
     * state change passes through here on its way to Flutter.
     */
    private fun handleBroadcastIntent(intent: Intent) {
        when (intent.action) {

            // ── Incoming call invite ──────────────────────────────────────
            Constants.BROADCAST_CALL_INVITE -> {
                val callId = intent.getStringExtra(Constants.EXTRA_CALL_ID) ?: return
                val from = resolveCallerName(intent.getStringExtra(Constants.EXTRA_CALL_FROM) ?: "")
                val to = intent.getStringExtra(Constants.EXTRA_CALL_TO) ?: ""
                // Dedup: ignore if same callId seen within 5 seconds (prevents double-emit
                // when BROADCAST fires from both FCM service and ConnectionService paths).
                val now = System.currentTimeMillis()
                if (callId == lastBroadcastCallId && (now - lastBroadcastTimeMs) < 5_000L) {
                    android.util.Log.d("VonagePlugin",
                        "BROADCAST_CALL_INVITE duplicate suppressed — callId=$callId")
                    return
                }
                lastBroadcastCallId = callId
                lastBroadcastTimeMs = now
                activeCallId = callId
                val event = VNNativeCallEvents.incomingCallEvent(from, to)
                android.util.Log.d("VonagePlugin", "BROADCAST_CALL_INVITE -- callId: $callId, from: $from, to: $to")
                emitEvent(event)
            }

            // ── Invite cancelled by remote party ──────────────────────────
            Constants.BROADCAST_CALL_INVITE_CANCELLED -> {
                if (activeCallId == null) return  // guard against duplicate cancel broadcasts
                activeCallId = null
                pendingCallReEmitted = null
                emitEvent(VNNativeCallEvents.EVENT_MISSED_CALL)
            }

            // ── Call ringing (outbound) ───────────────────────────────────
            Constants.BROADCAST_CALL_RINGING -> {
                val callId = intent.getStringExtra(Constants.EXTRA_CALL_ID) ?: return
                val from = resolveCallerName(intent.getStringExtra(Constants.EXTRA_CALL_FROM) ?: "")
                val to = intent.getStringExtra(Constants.EXTRA_CALL_TO) ?: ""
                activeCallId = callId
                emitEvent(
                    VNNativeCallEvents.callStateEvent(
                        VNNativeCallEvents.EVENT_RINGING,
                        from, to,
                        CallDirection.OUTGOING
                    )
                )
            }

            // ── Call connected ────────────────────────────────────────────
            Constants.BROADCAST_CALL_CONNECTED -> {
                val callId = intent.getStringExtra(Constants.EXTRA_CALL_ID) ?: return
                val from = resolveCallerName(intent.getStringExtra(Constants.EXTRA_CALL_FROM) ?: "")
                val to = intent.getStringExtra(Constants.EXTRA_CALL_TO) ?: ""
                val direction = intent.getStringExtra(Constants.EXTRA_CALL_DIRECTION)
                    ?.let { CallDirection.valueOf(it) } ?: CallDirection.INCOMING
                activeCallId = callId
                emitEvent(
                    VNNativeCallEvents.callStateEvent(
                        VNNativeCallEvents.EVENT_CONNECTED,
                        from, to,
                        direction
                    )
                )
            }

            // ── Call ended ────────────────────────────────────────────────
            Constants.BROADCAST_CALL_ENDED -> {
                android.util.Log.i("VonagePlugin",
                    "[HANGUP][BROADCAST] CALL_ENDED broadcast received" +
                    " — activeCallId=$activeCallId isMuted=$isMuted" +
                    " (will emit EVENT_CALL_ENDED to Flutter unless guard blocks)")
                // Guard against duplicate broadcasts (hangup + SDK listener)
                if (activeCallId == null && !isMuted) {
                    android.util.Log.w("VonagePlugin",
                        "[HANGUP][BROADCAST] ⚠ guard triggered: activeCallId is null — skipping duplicate")
                    return
                }

                // If invite listeners were skipped during registerVoiceClientListeners()
                // because the call was answered natively, register them now so the
                // next incoming call is handled properly.
                val needsInviteListenerRegistration = VonageClientHolder.isCallAnsweredNatively

                activeCallId = null
                isMuted = false
                pendingCallReEmitted = null
                // Clear native-answer flags so future incoming calls work normally
                VonageClientHolder.isCallAnsweredNatively = false
                VonageClientHolder.isAnsweringInProgress = false

                // Reset audio state events so Flutter UI doesn't carry stale
                // mute/speaker/bluetooth state into the next call
                emitEvent(VNNativeCallEvents.EVENT_UNMUTE)
                emitEvent(VNNativeCallEvents.EVENT_SPEAKER_OFF)
                emitEvent(VNNativeCallEvents.EVENT_BT_OFF)
                android.util.Log.i("VonagePlugin",
                    "[HANGUP][BROADCAST] ✓ emitting EVENT_CALL_ENDED to Flutter")
                emitEvent(VNNativeCallEvents.EVENT_CALL_ENDED)

                if (needsInviteListenerRegistration) {
                    // Re-register all voice client listeners (not just invite listeners).
                    // Using registerVoiceClientListeners() ensures setCallInviteListener
                    // replaces any existing listener rather than potentially accumulating
                    // a second one (which would cause INCOMING CALL to fire twice).
                    registerVoiceClientListeners()
                }
            }

            // ── Reconnecting ──────────────────────────────────────────────
            Constants.BROADCAST_CALL_RECONNECTING ->
                emitEvent(VNNativeCallEvents.EVENT_RECONNECTING)

            Constants.BROADCAST_CALL_RECONNECTED ->
                emitEvent(VNNativeCallEvents.EVENT_RECONNECTED)

            // ── Call error ────────────────────────────────────────────────
            Constants.BROADCAST_CALL_FAILED -> {
                val error = intent.getStringExtra("error") ?: "Unknown error"
                emitEvent(VNNativeCallEvents.callErrorEvent(error))
            }

            // ── Mute state ────────────────────────────────────────────────
            Constants.BROADCAST_MUTE_STATE -> {
                val muted = intent.getBooleanExtra("state", false)
                isMuted = muted
                emitEvent(
                    if (muted) VNNativeCallEvents.EVENT_MUTE
                    else VNNativeCallEvents.EVENT_UNMUTE
                )
            }

            // ── Speaker state ─────────────────────────────────────────────
            Constants.BROADCAST_SPEAKER_STATE -> {
                val speakerOn = intent.getBooleanExtra("state", false)
                emitEvent(
                    if (speakerOn) VNNativeCallEvents.EVENT_SPEAKER_ON
                    else VNNativeCallEvents.EVENT_SPEAKER_OFF
                )
            }

            // ── Bluetooth state ───────────────────────────────────────────
            Constants.BROADCAST_BLUETOOTH_STATE -> {
                val btOn = intent.getBooleanExtra("state", false)
                emitEvent(
                    if (btOn) VNNativeCallEvents.EVENT_BT_ON
                    else VNNativeCallEvents.EVENT_BT_OFF
                )
            }

            // ── System-initiated disconnect (BT headset button, car kit) ──
            Constants.BROADCAST_SYSTEM_DISCONNECT -> {
                val callId = intent.getStringExtra(Constants.EXTRA_CALL_ID) ?: return
                val client = voiceClient
                if (client != null) {
                    client.hangup(callId) { error ->
                        if (error != null) logEvent("system disconnect hangup failed: ${error.message}")
                    }
                }
                // Clean up connection from service
                TVConnectionService.activeConnections.remove(callId)
                activeCallId = null
                isMuted = false
                VonageClientHolder.isCallAnsweredNatively = false
                VonageClientHolder.isAnsweringInProgress = false

                // Reset audio state events so Flutter UI doesn't carry stale
                // mute/speaker/bluetooth state into the next call
                emitEvent(VNNativeCallEvents.EVENT_UNMUTE)
                emitEvent(VNNativeCallEvents.EVENT_SPEAKER_OFF)
                emitEvent(VNNativeCallEvents.EVENT_BT_OFF)
                emitEvent(VNNativeCallEvents.EVENT_CALL_ENDED)
            }

            // ── New FCM token ─────────────────────────────────────────────
            Constants.BROADCAST_NEW_FCM_TOKEN -> {
                val token = intent.getStringExtra(Constants.EXTRA_FCM_DATA) ?: return
                registerFcmTokenWithCleanup(token)
            }
        }
    }

    // ── Vonage SDK listeners ──────────────────────────────────────────────

    /**
     * Register all Vonage VoiceClient callback listeners.
     * Called once when the VoiceClient is first created in handleTokens().
     *
     * These listeners fire on SDK events (incoming invite, hangup, reconnect)
     * and route them into LocalBroadcasts so handleBroadcastIntent() can
     * forward them to Flutter.
     */
    private fun registerVoiceClientListeners() {
        android.util.Log.d("VonagePlugin", "registerVoiceClientListeners called")
        val client = voiceClient ?: run {
            android.util.Log.w("VonagePlugin", "voiceClient is null in registerVoiceClientListeners")
            return
        }
        val ctx = context ?: return

        // ── Guard: skip invite listener when a call was already answered natively ──
        // When the app was killed and the user tapped Answer on the notification,
        // TVConnectionService.doAnswer() already answered the call via the VoiceClient
        // created by VonageFirebaseMessagingService. Re-registering setCallInviteListener
        // at this point can interfere with the SDK's internal state for the active call,
        // causing an unexpected hangup.
        // NOTE: The CANCEL listener is intentionally NOT guarded — it must remain active
        // even while answering, to handle the race where iOS answers concurrently and the
        // cancel arrives during Android's answer window.
        val skipInviteListeners = VonageClientHolder.isCallAnsweredNatively
                || VonageClientHolder.isAnsweringInProgress

        if (skipInviteListeners) {
            android.util.Log.d("VonagePlugin",
                "Skipping setCallInviteListener — call already answered/answering natively")
        } else {
            // ── Incoming call invite ──────────────────────────────────────────
            client.setCallInviteListener { callId, from, channelType ->
                // Dedup: guard against the SDK firing this callback twice for the
                // same call (can happen if setCallInviteListener accumulates rather
                // than replaces when called multiple times during a session).
                val nowInvite = System.currentTimeMillis()
                if (callId == lastInviteCallId && (nowInvite - lastInviteTimeMs) < 5_000L) {
                    android.util.Log.d("VonagePlugin", "setCallInviteListener duplicate suppressed — callId=$callId")
                    return@setCallInviteListener
                }
                lastInviteCallId = callId
                lastInviteTimeMs = nowInvite
                // Prefer the display name extracted from the push payload
                // (set by handleProcessPush or VonageFirebaseMessagingService).
                val displayFrom = VonageClientHolder.pendingCallerDisplay ?: from
                VonageClientHolder.pendingCallerDisplay = null
                android.util.Log.d("VonagePlugin", "INCOMING CALL -- callId: $callId, from: $from, display: $displayFrom")
                val intent = Intent(ctx, TVConnectionService::class.java).apply {
                    action = Constants.ACTION_INCOMING_CALL
                    putExtra(Constants.EXTRA_CALL_ID, callId)
                    putExtra(Constants.EXTRA_CALL_FROM, displayFrom)
                    putExtra(Constants.EXTRA_CALL_TO, "")
                }
                // Must use startForegroundService — the SDK callback fires
                // asynchronously and on Android 10+ the FCM background
                // execution window may have expired by this point.
                ContextCompat.startForegroundService(ctx, intent)
            }
        }

        // ── Incoming invite cancelled by remote (ALWAYS registered) ───────────
        // This listener is registered outside the skipInviteListeners guard so it
        // remains active even when isCallAnsweredNatively=true or isAnsweringInProgress=true.
        // Reason: the cancel event can arrive while Android is mid-answer (iOS answered
        // concurrently), and it must still trigger teardown to dismiss the notification.
        client.setCallInviteCancelListener { callId, reason ->
            android.util.Log.d("VonagePlugin",
                "setCallInviteCancelListener: callId=$callId, reason=$reason")
            val intent = Intent(ctx, TVConnectionService::class.java).apply {
                action = Constants.ACTION_CANCEL_CALL_INVITE
                putExtra(Constants.EXTRA_CALL_ID, callId)
            }
            // Service is already in foreground from the invite, so
            // startService is fine here. But use startForegroundService
            // as a safety net in case the invite was never delivered.
            ContextCompat.startForegroundService(ctx, intent)
        }

        // ── Call hung up ──────────────────────────────────────────────────
        client.setOnCallHangupListener { callId, quality, reason ->
            // ★ PATH = WEBSOCKET (SDK listener fired while Flutter plugin is attached)
            val hasConn   = TVConnectionService.activeConnections.containsKey(callId)
            val hasInvite = TVConnectionService.pendingInvites.containsKey(callId)
            android.util.Log.i("VonagePlugin",
                "[HANGUP][WEBSOCKET] ▶ hangup via active-session / WebSocket path" +
                " callId=$callId reason=$reason" +
                " hasConnection=$hasConn hasPendingInvite=$hasInvite")

            // Guard against duplicate CALL_ENDED — handleHangup() in
            // TVConnectionService already removes the connection and
            // broadcasts CALL_ENDED when the caller initiates hangup.
            val connection = TVConnectionService.activeConnections[callId]
            val pendingInvite = TVConnectionService.pendingInvites[callId]

            if (connection != null) {
                android.util.Log.i("VonagePlugin",
                    "[HANGUP][WEBSOCKET] ✓ active connection found — disconnecting + broadcasting CALL_ENDED")
                connection.disconnect()
                TVConnectionService.activeConnections.remove(callId)

                val broadcastIntent = Intent(Constants.BROADCAST_CALL_ENDED).apply {
                    putExtra(Constants.EXTRA_CALL_ID, callId)
                }
                LocalBroadcastManager.getInstance(ctx).sendBroadcast(broadcastIntent)
            } else if (pendingInvite != null) {
                // "Answered elsewhere" — SDK fires hangup instead of invite-cancel.
                // Delegate to TVConnectionService via ACTION_CANCEL_CALL_INVITE so the
                // full teardown runs: ringtone, wake lock, notification, pending-call
                // prefs, invite timeout, and the exception-guarded cancel() with fallback
                // broadcast. This mirrors how setCallInviteCancelListener is handled.
                android.util.Log.i("VonagePlugin",
                    "[HANGUP][WEBSOCKET] ✓ pending invite found (answered elsewhere) — delegating to service for full teardown")
                val cancelIntent = Intent(ctx, TVConnectionService::class.java).apply {
                    action = Constants.ACTION_CANCEL_CALL_INVITE
                    putExtra(Constants.EXTRA_CALL_ID, callId)
                }
                ContextCompat.startForegroundService(ctx, cancelIntent)
            } else {
                // Fallback: connection was already removed by a concurrent cleanup path
                // (race between local handleHangup() and SDK callback). Still broadcast
                // CALL_ENDED so Flutter is guaranteed to exit the call screen.
                android.util.Log.w("VonagePlugin",
                    "[HANGUP][WEBSOCKET] ⚠ no connection/invite found for callId=$callId" +
                    " — emitting fallback CALL_ENDED (race/double-cleanup)")
                val fallbackBroadcast = Intent(Constants.BROADCAST_CALL_ENDED).apply {
                    putExtra(Constants.EXTRA_CALL_ID, callId)
                }
                LocalBroadcastManager.getInstance(ctx).sendBroadcast(fallbackBroadcast)
            }

            // Clear pending answered call data so MainActivity.onResume() does
            // NOT navigate to a dead-call screen when user unlocks after remote
            // hangup on lock screen.
            IncomingCallActivity.pendingAnsweredCallData = null

            // Reset "answered natively" flag so the next call's invite listener
            // is properly registered in registerVoiceClientListeners().
            VonageClientHolder.isCallAnsweredNatively = false
            VonageClientHolder.isAnsweringInProgress = false

            // Tell TVConnectionService to clean up notification & stop foreground
            // when no calls remain. This handles the remote hangup case where
            // handleHangup() was never called locally.
            if (TVConnectionService.activeConnections.isEmpty() &&
                TVConnectionService.pendingInvites.isEmpty()) {
                // Direct notification cancellation — belt-and-suspenders.
                // On Samsung One UI + lock screen, startForegroundService()
                // can occasionally be deferred. Cancel immediately here so the
                // lock screen tile doesn't persist.
                try {
                    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
                            as android.app.NotificationManager
                    nm.cancel(Constants.NOTIFICATION_ID)
                } catch (e: Exception) {
                    android.util.Log.w("VonagePlugin",
                        "Direct notification cancel failed: ${e.message}")
                }

                try {
                    val cleanupIntent = Intent(ctx, TVConnectionService::class.java).apply {
                        action = Constants.ACTION_CLEANUP
                    }
                    ContextCompat.startForegroundService(ctx, cleanupIntent)
                } catch (e: Exception) {
                    android.util.Log.e("VonagePlugin", "Failed to send cleanup intent: ${e.message}")
                }
            }
        }

        // ── Media reconnecting ────────────────────────────────────────────
        client.setOnCallMediaReconnectingListener { callId ->
            val broadcastIntent = Intent(Constants.BROADCAST_CALL_RECONNECTING).apply {
                putExtra(Constants.EXTRA_CALL_ID, callId)
            }
            LocalBroadcastManager.getInstance(ctx).sendBroadcast(broadcastIntent)
        }

        // ── Media reconnected ─────────────────────────────────────────────
        client.setOnCallMediaReconnectionListener { callId ->
            val broadcastIntent = Intent(Constants.BROADCAST_CALL_RECONNECTED).apply {
                putExtra(Constants.EXTRA_CALL_ID, callId)
            }
            LocalBroadcastManager.getInstance(ctx).sendBroadcast(broadcastIntent)
        }

        // ── Media error ───────────────────────────────────────────────────
        client.setOnCallMediaErrorListener { callId, error ->
            val broadcastIntent = Intent(Constants.BROADCAST_CALL_FAILED).apply {
                putExtra(Constants.EXTRA_CALL_ID, callId)
                putExtra("error", error.message ?: "Media error")
            }
            LocalBroadcastManager.getInstance(ctx).sendBroadcast(broadcastIntent)
        }

        // ── Mute state confirmed by SDK ───────────────────────────────────
        client.setOnMutedListener { callId, legId, muted ->
            val broadcastIntent = Intent(Constants.BROADCAST_MUTE_STATE).apply {
                putExtra(Constants.EXTRA_CALL_ID, callId)
                putExtra("state", muted)
            }
            LocalBroadcastManager.getInstance(ctx).sendBroadcast(broadcastIntent)
        }

        // ── Session error ─────────────────────────────────────────────────
        client.setSessionErrorListener { reason ->
            emitEvent(VNNativeCallEvents.callErrorEvent("Session error: $reason"))
        }
    }

    /**
     * Register (or re-register) only the invite-related listeners on VoiceClient.
     * Called after a natively-answered call ends so that the next incoming call
     * is properly handled via the plugin path.
     */
    private fun registerInviteListeners() {
        val client = voiceClient ?: return
        val ctx = context ?: return

        android.util.Log.d("VonagePlugin", "registerInviteListeners — re-registering invite listeners after native call ended")

        client.setCallInviteListener { callId, from, channelType ->
            val nowInvite = System.currentTimeMillis()
            if (callId == lastInviteCallId && (nowInvite - lastInviteTimeMs) < 5_000L) {
                android.util.Log.d("VonagePlugin", "setCallInviteListener duplicate suppressed — callId=$callId")
                return@setCallInviteListener
            }
            lastInviteCallId = callId
            lastInviteTimeMs = nowInvite
            val displayFrom = VonageClientHolder.pendingCallerDisplay ?: from
            VonageClientHolder.pendingCallerDisplay = null
            android.util.Log.d("VonagePlugin", "INCOMING CALL -- callId: $callId, from: $from, display: $displayFrom")
            val intent = Intent(ctx, TVConnectionService::class.java).apply {
                action = Constants.ACTION_INCOMING_CALL
                putExtra(Constants.EXTRA_CALL_ID, callId)
                putExtra(Constants.EXTRA_CALL_FROM, displayFrom)
                putExtra(Constants.EXTRA_CALL_TO, "")
            }
            ContextCompat.startForegroundService(ctx, intent)
        }

        client.setCallInviteCancelListener { callId, reason ->
            android.util.Log.d("VonagePlugin",
                "registerInviteListeners/setCallInviteCancelListener: callId=$callId, reason=$reason")
            val intent = Intent(ctx, TVConnectionService::class.java).apply {
                action = Constants.ACTION_CANCEL_CALL_INVITE
                putExtra(Constants.EXTRA_CALL_ID, callId)
            }
            ContextCompat.startForegroundService(ctx, intent)
        }
    }

    // ── Utility helpers ───────────────────────────────────────────────────

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Re-process a previously persisted FCM nexmo push payload through the
     * Vonage SDK to rebuild the call invite in the new process.
     *
     * This is the cross-process recovery step that mirrors Twilio's SharedPreferences
     * pattern. Without it, emitting "Incoming" to Flutter shows the ringing screen
     * but client.answer(callId) would fail because the Vonage SDK has no record of
     * the invite in the new process — the raw nexmo data is needed to restore it.
     *
     * Called from [reEmitPendingCallState] Case 0 (SharedPreferences fallback)
     * and from the session-ready callback in [handleTokens] so the invite is
     * always re-processed before the user can tap Answer.
     */
    private fun tryReprocessPendingFcm(retriesLeft: Int = 5) {
        val ctx = context ?: return
        try {
            val fcmPrefs = ctx.getSharedPreferences(
                VonageFirebaseMessagingService.PREFS_PENDING_FCM, Context.MODE_PRIVATE)
            val nexmoRaw  = fcmPrefs.getString(VonageFirebaseMessagingService.KEY_NEXMO_RAW, null)
            val timestamp = fcmPrefs.getLong(VonageFirebaseMessagingService.KEY_FCM_TIMESTAMP, 0)

            if (nexmoRaw.isNullOrEmpty()) {
                android.util.Log.d("VonagePlugin", "tryReprocessPendingFcm: no stored FCM data — nothing to re-process")
                return
            }

            val elapsed = System.currentTimeMillis() - timestamp
            if (elapsed >= VonageFirebaseMessagingService.PENDING_FCM_TTL_MS) {
                android.util.Log.d("VonagePlugin",
                    "tryReprocessPendingFcm: FCM data expired (age=${elapsed}ms) — clearing")
                VonageFirebaseMessagingService.clearPendingFcmData(ctx)
                TVConnectionService.clearPendingCallData(ctx)
                return
            }

            val client = VonageClientHolder.voiceClient
            if (client == null || !VonageClientHolder.isSessionReady) {
                if (retriesLeft > 0) {
                    android.util.Log.d("VonagePlugin",
                        "tryReprocessPendingFcm: client/session not ready — retrying in 1s (retriesLeft=$retriesLeft)")
                    mainHandler.postDelayed({ tryReprocessPendingFcm(retriesLeft - 1) }, 1000)
                } else {
                    android.util.Log.w("VonagePlugin",
                        "tryReprocessPendingFcm: gave up waiting for client/session after retries")
                }
                return
            }

            // Check if invite already exists in pendingInvites — skip re-processing
            if (TVConnectionService.pendingInvites.isNotEmpty()) {
                android.util.Log.d("VonagePlugin",
                    "tryReprocessPendingFcm: pendingInvites already populated — skipping re-process")
                VonageFirebaseMessagingService.clearPendingFcmData(ctx)
                return
            }

            // Reconstruct the dataString in the format expected by the Vonage SDK:
            // It calls extractJsonFromPushData which does substringAfter("nexmo=").dropLast(1).
            // The stored nexmoRaw is the raw JSON value. We must wrap it: {nexmo=<json>}
            val dataString = "{nexmo=$nexmoRaw}"
            android.util.Log.d("VonagePlugin",
                "tryReprocessPendingFcm: re-calling processPushCallInvite (age=${elapsed}ms)")

            val callId = client.processPushCallInvite(dataString)
            android.util.Log.d("VonagePlugin",
                "tryReprocessPendingFcm: processPushCallInvite returned callId=$callId")

            if (!callId.isNullOrEmpty()) {
                // Update activeCallId so answer() uses the correct callId
                activeCallId = callId
                android.util.Log.d("VonagePlugin",
                    "tryReprocessPendingFcm: ✓ invite re-processed, activeCallId=$callId")
                // Clear FCM prefs — successfully re-processed
                VonageFirebaseMessagingService.clearPendingFcmData(ctx)
                // Schedule fresh re-emit runnables so Flutter is notified even if
                // the EventSink was null during the MPIN→main navigation gap.
                // Reset dedup guard so reEmitPendingCallState() fires unconditionally.
                pendingCallReEmitted = null
                val handler = Handler(Looper.getMainLooper())
                for (delay in longArrayOf(200, 700, 1800, 3500)) {
                    val r = Runnable { if (eventSink != null) reEmitPendingCallState() }
                    pendingReEmitRunnables.add(r)
                    handler.postDelayed(r, delay)
                }
                android.util.Log.d("VonagePlugin",
                    "tryReprocessPendingFcm: scheduled 4 re-emit runnables (200/700/1800/3500ms)")
            } else {
                android.util.Log.w("VonagePlugin",
                    "tryReprocessPendingFcm: processPushCallInvite returned null callId")
            }
        } catch (e: Exception) {
            android.util.Log.e("VonagePlugin", "tryReprocessPendingFcm: exception: ${e.message}", e)
        }
    }

    /**
     * Emit a pipe-delimited string event to Flutter via the EventChannel.
     * Must be called on the main thread — Flutter channels are not thread-safe.
     */
    /**
     * Returns true if [event] is a critical call-state event that must
     * be queued when [eventSink] is null, so Flutter receives it on the
     * next subscription. Audio-only events (mute, speaker, bluetooth) are
     * not queued — they are stale by the time Flutter re-subscribes.
     */
    private fun isCriticalCallEvent(event: String): Boolean =
        event.startsWith("Incoming") ||
        event.startsWith("Ringing") ||
        event.startsWith("Connected") ||
        event == VNNativeCallEvents.EVENT_CALL_ENDED ||
        event == VNNativeCallEvents.EVENT_MISSED_CALL ||
        event.startsWith("Declined")

    private fun emitEvent(event: String) {
        mainHandler.post {
            val sink = eventSink
            if (sink == null) {
                if (isCriticalCallEvent(event)) {
                    // Queue the event — Flutter will receive it when it re-subscribes.
                    // Mirrors the Twilio plugin's pendingEvents pattern.
                    synchronized(pendingCallEvents) { pendingCallEvents.add(event) }
                    android.util.Log.d("VonagePlugin", "emitEvent: queued (sink null): $event")
                } else {
                    android.util.Log.w("VonagePlugin", "emitEvent: eventSink is NULL -- event lost: $event")
                }
            } else {
                android.util.Log.d("VonagePlugin", "emitEvent: $event")
                sink.success(event)
            }
        }
    }

    /**
     * Log a non-fatal diagnostic event to Flutter.
     * Useful for debugging session and push registration issues.
     */
    private fun logEvent(message: String) {
        emitEvent(VNNativeCallEvents.callErrorEvent(message))
    }

    /**
     * Resolve a caller ID to a display name using the registered client registry.
     * Falls back to the stored defaultCaller name if no mapping exists.
     */
    private fun resolveCallerName(callerId: String): String {
        if (callerId.isEmpty()) return storage?.getDefaultCaller()
            ?: Constants.DEFAULT_UNKNOWN_CALLER
        // Prefer registered client name, then fall back to the raw callerId
        // (phone number / user identity) rather than the default caller name.
        return storage?.getRegisteredClient(callerId)
            ?: callerId
    }

    // ── Push-data helpers ───────────────────────────────────────────────

    /**
     * Extract the caller display name from the Vonage push payload.
     *
     * Priority:
     *   1. push_info.from_user.display_name  (app-to-app calls)
     *   2. body.channel.from.number          (PSTN calls)
     */
    private fun extractCallerDisplay(nexmoRaw: String): String? {
        return try {
            val json = org.json.JSONObject(nexmoRaw)
            val fromUser = json.optJSONObject("push_info")
                ?.optJSONObject("from_user")
            val displayName = fromUser?.optString("display_name", null)
            if (!displayName.isNullOrEmpty()) return displayName

            json.optJSONObject("body")
                ?.optJSONObject("channel")
                ?.optJSONObject("from")
                ?.optString("number", null)
        } catch (_: Exception) {
            null
        }
    }
}
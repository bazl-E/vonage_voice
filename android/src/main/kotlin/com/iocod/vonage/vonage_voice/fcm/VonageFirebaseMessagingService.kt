package com.iocod.vonage.vonage_voice.fcm

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.telecom.TelecomManager
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.iocod.vonage.vonage_voice.IncomingCallActivity
import com.iocod.vonage.vonage_voice.constants.Constants
import com.iocod.vonage.vonage_voice.service.TVConnectionService
import com.iocod.vonage.vonage_voice.service.VonageClientHolder
import com.vonage.voice.api.VoiceClient
import org.json.JSONObject

/**
 * VonageFirebaseMessagingService — FCM to Vonage call invite bridge.
 *
 * Extends [FirebaseMessagingService] to intercept FCM push messages
 * delivered by the Vonage backend when an incoming call arrives.
 *
 * This service runs even when the app is killed or backgrounded,
 * which is why incoming calls can wake the app up.
 *
 * Flow:
 *   Vonage backend
 *     → FCM push message (data payload)
 *       → onMessageReceived()
 *         → VoiceClient.processPushCallInvite()  [validates it is a Vonage call]
 *           → setCallInviteListener fires         [if valid call invite]
 *             → startService(ACTION_INCOMING_CALL) → TVConnectionService
 *               → LocalBroadcast → VonageVoicePlugin → Flutter "Incoming|..." event
 *
 * FCM token lifecycle:
 *   onNewToken() fires when FCM rotates the device token.
 *   We broadcast it via LocalBroadcastManager so VonageVoicePlugin
 *   can re-register it with Vonage via client.registerDevicePushToken().
 *
 * NOTE: Firebase must be configured in your app before this service works:
 *   1. Add google-services.json to android/app/
 *   2. Add Firebase dependencies to build.gradle
 *   3. Connect Vonage app to Firebase in the Vonage dashboard
 *   Until then, this service is registered but will simply receive no messages.
 */
class VonageFirebaseMessagingService : FirebaseMessagingService() {

    private lateinit var broadcastManager: LocalBroadcastManager

    /**
     * Ensure broadcastManager is initialised. When this service is
     * instantiated directly by FCM, onCreate() runs and sets the field.
     * However, when EasifyFirebaseMessagingService delegates via
     * reflection, onCreate() is NEVER called — so we lazily init here
     * to avoid UninitializedPropertyAccessException on error paths.
     */
    private fun ensureBroadcastManager() {
        if (!::broadcastManager.isInitialized) {
            broadcastManager = LocalBroadcastManager.getInstance(applicationContext)
        }
    }

    override fun onCreate() {
        super.onCreate()
        broadcastManager = LocalBroadcastManager.getInstance(this)
        android.util.Log.d("VonageFCM", "VonageFirebaseMessagingService created")
    }

    // ── FCM token lifecycle ───────────────────────────────────────────────

    /**
     * Called by FCM when the device push token is created or rotated.
     *
     * We broadcast the new token so VonageVoicePlugin can call
     * client.registerDevicePushToken(token) to keep Vonage in sync.
     *
     * The plugin stores the returned deviceId so it can later call
     * client.unregisterDevicePushToken(deviceId) on logout.
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        ensureBroadcastManager()

        val intent = Intent(Constants.BROADCAST_NEW_FCM_TOKEN).apply {
            putExtra(Constants.EXTRA_FCM_DATA, token)
        }
        broadcastManager.sendBroadcast(intent)
    }

    // ── Incoming message handling ─────────────────────────────────────────

    /**
     * Called when an FCM data message is received.
     *
     * We first check if it is a Vonage push message using
     * [VoiceClient.getPushNotificationType]. If it is an incoming call,
     * we process it via [VoiceClient.processPushCallInvite] which triggers
     * the callInviteListener registered in VonageVoicePlugin.
     *
     * Non-Vonage messages are ignored so other FCM channels in your app
     * are not affected.
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        ensureBroadcastManager()
        android.util.Log.i("VonageFCM", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        android.util.Log.i("VonageFCM", "[FCM-1] onMessageReceived — keys=${remoteMessage.data.keys}")

        val data = remoteMessage.data
        if (data.isEmpty()) {
            android.util.Log.w("VonageFCM", "[FCM-1] Empty data payload — ignoring")
            return
        }

        // ── Active call detection ─────────────────────────────────────────
        // If the user is already on ANY call (cellular, Twilio VoIP,
        // WhatsApp, or any other ConnectionService-based call), skip
        // the VoIP invite entirely. It will appear as "missed" on Vonage.
        if (isDeviceInSystemCall()) {
            android.util.Log.w("VonageFCM", "[FCM-1] Device is in an active call — skipping VoIP invite")
            return
        }

        // Vonage pushes always contain a "nexmo" key — check this first so
        // non-Vonage FCM messages (Twilio, app notifications, etc.) are
        // discarded immediately without touching any Vonage-specific state.
        val nexmoRaw = data["nexmo"]
        if (nexmoRaw.isNullOrEmpty()) {
            android.util.Log.d("VonageFCM", "[FCM-1] No 'nexmo' key — not a Vonage push, ignored")
            return
        }
        android.util.Log.i("VonageFCM", "[FCM-2] ✓ Vonage push detected")

        // ── Active Vonage call guard ──────────────────────────────────────
        // Vonage is single-call only (no hold/swap/conference). If there is
        // already an active or pending Vonage call, ignore a 2nd *invite*
        // entirely — it will appear as "missed" on the Vonage side.
        //
        // CRITICAL: Cancel/hangup pushes MUST bypass this guard.
        // When A cancels before B answers, Vonage sends a cancel FCM push.
        // At that moment pendingInvites.isNotEmpty() is true (B is ringing),
        // so the old blind guard would discard the cancel push — leaving B
        // ringing indefinitely when the WebSocket is also down.
        // Invite pushes carry "type":"member:invited"; cancel/hangup pushes
        // carry a different type and must reach processPushCallInvite() so
        // the SDK fires setCallInviteCancelListener and stops the ringing.
        val isInvitePush = try {
            JSONObject(nexmoRaw).optString("type", "").contains("invited", ignoreCase = true)
        } catch (e: Exception) { false }  // assume NOT an invite on parse failure — cancel/hangup
        // pushes must always reach processPushCallInvite(); blocking them on bad JSON is worse
        // than letting a potentially malformed invite through (duplicate guard catches it later).

        TVConnectionService.clearStaleInvitesIfAny(applicationContext)

        if (isInvitePush && (TVConnectionService.hasActiveCall() || TVConnectionService.pendingInvites.isNotEmpty())) {
            android.util.Log.w("VonageFCM", "[FCM-1] Active Vonage call exists — skipping 2nd VoIP invite")
            return
        }

        // Record timestamp so the Dart fallback path (processVonagePush)
        // can detect that a native FCM service is already processing this push
        // and skip to avoid double-processing.
        VonageClientHolder.lastNativePushTimestamp = System.currentTimeMillis()

        // ── Acquire WakeLock IMMEDIATELY ──────────────────────────────────
        // The SDK processes the push asynchronously. Without a WakeLock the
        // CPU may go back to sleep before the callback fires — especially
        // on OEMs like Vivo that aggressively suspend background processes.
        val wakeLock = acquireProcessingWakeLock()

        // The Vonage SDK's internal extractJsonFromPushData expects the data
        // in Kotlin Map.toString() format: {nexmo={"type":...}}
        // It does substringAfter("nexmo=").dropLast(1) to extract the JSON.
        // So we pass remoteMessage.data.toString() directly — NOT JSON.
        val dataString = data.toString()
        android.util.Log.d("VonageFCM", "[FCM-2] Data string (first 200): ${dataString.take(200)}")

        // Extract caller display info from the push payload.
        val callerDisplay = extractCallerDisplay(nexmoRaw)
        android.util.Log.i("VonageFCM", "[FCM-2] Caller display: $callerDisplay")

        // Store the display name so both FCM-service and plugin paths can use it.
        if (!callerDisplay.isNullOrEmpty()) {
            VonageClientHolder.pendingCallerDisplay = callerDisplay
        }

        // ── Persist FCM nexmo data to SharedPreferences ───────────────────
        // Twilio mirrors this pattern: store push data BEFORE processing so the
        // plugin can re-process the invite if the OS kills the process between
        // FCM delivery and the user tapping Answer (MIUI, vivo, aggressive OEMs).
        // commit() (synchronous) guarantees the write survives a sudden kill.
        try {
            val fcmPrefs = applicationContext.getSharedPreferences(
                PREFS_PENDING_FCM, android.content.Context.MODE_PRIVATE)
            fcmPrefs.edit()
                .putString(KEY_NEXMO_RAW, nexmoRaw)
                .putString(KEY_CALLER_DISPLAY, callerDisplay ?: "")
                .putLong(KEY_FCM_TIMESTAMP, System.currentTimeMillis())
                .commit()
            android.util.Log.d("VonageFCM", "[FCM-2] ✓ Persisted nexmo FCM data to SharedPreferences")
        } catch (e: Exception) {
            android.util.Log.w("VonageFCM", "[FCM-2] Failed to persist FCM data: ${e.message}")
        }

        var client = VonageClientHolder.voiceClient
        // Treat "client exists but session not ready" the same as "no client" —
        // this handles the boot+unlock race condition where BootCompletedReceiver
        // created the VoiceClient but its async createSession hasn't completed yet.
        val clientWasNull = client == null || !VonageClientHolder.isSessionReady
        android.util.Log.i("VonageFCM", "[FCM-4] Client state: voiceClient=${if (client != null) "set" else "null"}, isSessionReady=${VonageClientHolder.isSessionReady}, clientWasNull=$clientWasNull")

        // ── START PLACEHOLDER ONLY FOR THE ASYNC RECOVERY PATH ───────────
        // Twilio can start its service with the real invite immediately because
        // the FCM callback already has a materialized CallInvite object.
        // Vonage only knows the real callId after processPushCallInvite()
        // finishes. We therefore keep the placeholder only when the client/session
        // is not ready yet (killed app, reboot, boot-race). In the already-alive
        // path we skip the placeholder entirely and wait for the real incoming
        // call intent so the user does not see a transient placeholder UI.
        if (clientWasNull) {
            try {
                val placeholderIntent = Intent(applicationContext, TVConnectionService::class.java).apply {
                    action = Constants.ACTION_INCOMING_CALL
                    // No EXTRA_CALL_ID — handleIncomingCall will skip creating a
                    // pendingInvite, but ensureForeground() already showed the notification.
                    putExtra(Constants.EXTRA_CALL_FROM, callerDisplay ?: "Incoming Call")
                    putExtra(Constants.EXTRA_CALL_TO, "")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    android.util.Log.i("VonageFCM", "[FCM-3] Starting placeholder foreground service (client/session not ready)")
                    applicationContext.startForegroundService(placeholderIntent)
                } else {
                    applicationContext.startService(placeholderIntent)
                }
                android.util.Log.i("VonageFCM", "[FCM-3] ✓ Placeholder foreground service started")
            } catch (e: Exception) {
                android.util.Log.e("VonageFCM", "[FCM-3] ✗ Failed to start placeholder foreground service: ${e.message}", e)
            }
        } else {
            android.util.Log.i("VonageFCM", "[FCM-3] Skipping placeholder — client/session already ready, waiting for real callId")
        }

        if (client == null) {
            android.util.Log.i("VonageFCM", "[FCM-4] Creating new VoiceClient (boot/killed path)")
            client = VoiceClient(applicationContext)
            VonageClientHolder.voiceClient = client
            VonageClientHolder.isSessionReady = false
        } else if (!VonageClientHolder.isSessionReady) {
            android.util.Log.w("VonageFCM", "[FCM-4] VoiceClient exists but session not ready (boot race) — will restore session")
        } else {
            android.util.Log.i("VonageFCM", "[FCM-4] VoiceClient alive and session ready — foreground/background path")
        }

        // Guard to prevent both the listener AND the direct processPushCallInvite
        // path from sending duplicate incoming call intents.
        val inviteHandledDirectly = java.util.concurrent.atomic.AtomicBoolean(false)

        // Only register the listener when we just created the client (app was killed).
        // When the plugin is alive it has already set its own listener in
        // registerVoiceClientListeners() — overwriting it here would break
        // the foreground incoming call path.
        if (clientWasNull) {
            client.setCallInviteListener { callId, from, channelType ->
                android.util.Log.i("VonageFCM", "[FCM-6] ✓ setCallInviteListener FIRED — callId=$callId, from=$from, channel=$channelType")

                // If we already handled the invite via processPushCallInvite's
                // returned callId, skip the listener to avoid duplicates.
                if (!inviteHandledDirectly.compareAndSet(false, true)) {
                    android.util.Log.d("VonageFCM", "Invite already handled directly -- skipping listener")
                    releaseProcessingWakeLock(wakeLock)
                    return@setCallInviteListener
                }

                // Prefer the display name extracted from the push payload.
                val displayFrom = VonageClientHolder.pendingCallerDisplay ?: from
                VonageClientHolder.pendingCallerDisplay = null

                // Send the REAL intent with callId — this updates the
                // placeholder notification with Answer/Decline buttons
                // and creates the pendingInvite in TVConnectionService.
                val intent = Intent(applicationContext, TVConnectionService::class.java).apply {
                    action = Constants.ACTION_INCOMING_CALL
                    putExtra(Constants.EXTRA_CALL_ID, callId)
                    putExtra(Constants.EXTRA_CALL_FROM, displayFrom)
                    putExtra(Constants.EXTRA_CALL_TO, "")
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        android.util.Log.d("VonageFCM", "Updating foreground service with real callId")
                        applicationContext.startForegroundService(intent)
                    } else {
                        applicationContext.startService(intent)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("VonageFCM", "Error updating service with callId: ${e.message}", e)
                } finally {
                    releaseProcessingWakeLock(wakeLock)
                }
            }

            // ── Cancel listener for killed-app state ─────────────────────
            // Without this, if the caller cancels or another device answers
            // before the user opens the app, the notification persists.
            client.setCallInviteCancelListener { callId, reason ->
                android.util.Log.d("VonageFCM", "setCallInviteCancelListener FIRED -- callId: $callId, reason: $reason")
                val cancelIntent = Intent(applicationContext, TVConnectionService::class.java).apply {
                    action = Constants.ACTION_CANCEL_CALL_INVITE
                    putExtra(Constants.EXTRA_CALL_ID, callId)
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        applicationContext.startForegroundService(cancelIntent)
                    } else {
                        applicationContext.startService(cancelIntent)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("VonageFCM", "Error sending cancel invite intent: ${e.message}", e)
                }
            }

            // ── Hangup listener for killed-app state ─────────────────────
            // CRITICAL: Without this, if the remote party hangs up while the
            // device is locked and Flutter hasn't started yet, the notification
            // tile persists because VonageVoicePlugin.registerVoiceClientListeners()
            // (the only other place that sets this listener) hasn't been called.
            //
            // This mirrors the pattern used by Twilio's ConnectionService where
            // disconnect callbacks naturally fire regardless of Flutter's state.
            // Since Vonage doesn't integrate with Android Telecom for call lifecycle,
            // we must register the SDK hangup listener at the FCM level.
            client.setOnCallHangupListener { callId, quality, reason ->
                // ★ PATH = FCM PUSH (WebSocket was down — app was killed or in background
                //   with no active plugin session; Vonage delivered hangup via push)
                val hasConn   = TVConnectionService.activeConnections.containsKey(callId)
                val hasInvite = TVConnectionService.pendingInvites.containsKey(callId)
                android.util.Log.i("VonageFCM",
                    "[HANGUP][FCM] ▶ hangup via FCM push path (WebSocket down / app killed)" +
                    " callId=$callId reason=$reason" +
                    " hasConnection=$hasConn hasPendingInvite=$hasInvite")

                // Remove the connection from the active map
                val connection = TVConnectionService.activeConnections[callId]
                val pendingInvite = TVConnectionService.pendingInvites[callId]

                if (connection != null) {
                    android.util.Log.i("VonageFCM",
                        "[HANGUP][FCM] ✓ active connection found — disconnecting + broadcasting CALL_ENDED")
                    connection.disconnect()
                    TVConnectionService.activeConnections.remove(callId)

                    // Broadcast CALL_ENDED so any alive activity/receiver can react
                    val broadcastIntent = Intent(Constants.BROADCAST_CALL_ENDED).apply {
                        putExtra(Constants.EXTRA_CALL_ID, callId)
                    }
                    LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(broadcastIntent)
                } else if (pendingInvite != null) {
                    // "Answered elsewhere" — SDK fires hangup instead of invite-cancel.
                    // Delegate to TVConnectionService via ACTION_CANCEL_CALL_INVITE so the
                    // full teardown runs: ringtone, wake lock, notification, pending-call
                    // prefs, and the exception-guarded cancel() with fallback broadcast.
                    android.util.Log.i("VonageFCM",
                        "[HANGUP][FCM] ✓ pending invite found (answered elsewhere) — delegating to service for full teardown")
                    val cancelIntent = Intent(applicationContext, TVConnectionService::class.java).apply {
                        action = Constants.ACTION_CANCEL_CALL_INVITE
                        putExtra(Constants.EXTRA_CALL_ID, callId)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        applicationContext.startForegroundService(cancelIntent)
                    } else {
                        applicationContext.startService(cancelIntent)
                    }
                } else {
                    // Fallback: connection was already removed by a concurrent cleanup path.
                    // Still broadcast CALL_ENDED so any alive Flutter engine / activity can
                    // react and dismiss the call screen — prevents a stuck UI after remote
                    // hangup when the FCM path and plugin path race.
                    android.util.Log.w("VonageFCM",
                        "[HANGUP][FCM] ⚠ no connection/invite for callId=$callId" +
                        " — emitting fallback CALL_ENDED (race/double-cleanup)")
                    val fallbackBroadcast = Intent(Constants.BROADCAST_CALL_ENDED).apply {
                        putExtra(Constants.EXTRA_CALL_ID, callId)
                    }
                    LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(fallbackBroadcast)
                }

                // Clear stale pending data so unlock doesn't navigate to a dead call
                IncomingCallActivity.pendingAnsweredCallData = null
                VonageClientHolder.isCallAnsweredNatively = false
                VonageClientHolder.isAnsweringInProgress = false

                // Clean up notification + stop foreground service
                if (TVConnectionService.activeConnections.isEmpty() &&
                    TVConnectionService.pendingInvites.isEmpty()) {
                    // Direct notification cancel — belt-and-suspenders for Samsung
                    // lock screen where startForegroundService can be deferred
                    try {
                        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                                as android.app.NotificationManager
                        nm.cancel(Constants.NOTIFICATION_ID)
                    } catch (e: Exception) {
                        android.util.Log.w("VonageFCM",
                            "[FCM-HANGUP] Direct notification cancel failed: ${e.message}")
                    }

                    try {
                        val cleanupIntent = Intent(applicationContext, TVConnectionService::class.java).apply {
                            action = Constants.ACTION_CLEANUP
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            applicationContext.startForegroundService(cleanupIntent)
                        } else {
                            applicationContext.startService(cleanupIntent)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("VonageFCM",
                            "[FCM-HANGUP] Failed to send cleanup intent: ${e.message}", e)
                    }
                }
            }

            // ── Restore session from stored JWT ──────────────────────────
            // When the app was killed, the VoiceClient has no session.
            // Without a session, client.answer() will fail when the user
            // taps Answer. We restore the session FIRST, then process
            // the push — the SDK only registers the invite internally
            // when a valid session exists.
            val storedJwt = VonageClientHolder.getStoredJwt(applicationContext)
            if (!storedJwt.isNullOrEmpty()) {
                android.util.Log.i("VonageFCM", "[FCM-5] Stored JWT found (${storedJwt.length} chars) — restoring session...")
                restoreSessionWithRetry(client, storedJwt, dataString, callerDisplay, clientWasNull, inviteHandledDirectly, wakeLock, retriesLeft = 1)
            } else {
                android.util.Log.e("VonageFCM", "[FCM-5] ✗ No stored JWT — cannot restore session after reboot. User must login first!")
                // No JWT — try processing anyway (will show notification but answer will fail)
                processInviteAndNotify(client, dataString, callerDisplay, clientWasNull, inviteHandledDirectly, wakeLock)
            }
        } else {
            // Client was already alive (app in foreground/background) —
            // process the push inline. If Flutter IS running, its plugin
            // listener handles the invite. If Flutter is NOT running (process
            // alive from a previous FCM but Flutter engine never started), we
            // send the real intent to TVConnectionService directly — otherwise
            // nothing rings and the call appears stuck in "Processing".
            android.util.Log.i("VonageFCM", "[FCM-5] Client alive path — calling processPushCallInvite directly")
            try {
                val callId = client.processPushCallInvite(dataString)
                android.util.Log.i("VonageFCM", "[FCM-5] processPushCallInvite returned: $callId")
                if (!callId.isNullOrEmpty()) {
                    val isNew = VonageClientHolder.markPushProcessed(callId)
                    if (!isNew) {
                        android.util.Log.d("VonageFCM", "[FCM-5] callId=$callId already processed by plugin — skipping")
                    } else if (!VonageClientHolder.isFlutterInForeground) {
                        // Flutter is NOT running — plugin's setCallInviteListener won't fire.
                        // Send the real intent directly so TVConnectionService can ring.
                        val displayFrom = VonageClientHolder.pendingCallerDisplay
                            ?: callerDisplay
                            ?: Constants.DEFAULT_UNKNOWN_CALLER
                        VonageClientHolder.pendingCallerDisplay = null
                        android.util.Log.i("VonageFCM", "[FCM-7] Flutter NOT in foreground — sending real incoming call intent: callId=$callId, from=$displayFrom")
                        val realIntent = Intent(applicationContext, TVConnectionService::class.java).apply {
                            action = Constants.ACTION_INCOMING_CALL
                            putExtra(Constants.EXTRA_CALL_ID, callId)
                            putExtra(Constants.EXTRA_CALL_FROM, displayFrom)
                            putExtra(Constants.EXTRA_CALL_TO, "")
                        }
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                applicationContext.startForegroundService(realIntent)
                            } else {
                                applicationContext.startService(realIntent)
                            }
                            android.util.Log.i("VonageFCM", "[FCM-7] ✓ Real incoming call intent sent (client-alive, Flutter background)")
                        } catch (se: Exception) {
                            android.util.Log.e("VonageFCM", "[FCM-7] Failed to start real intent: ${se.message}", se)
                        }
                    } else {
                        android.util.Log.d("VonageFCM", "[FCM-5] Flutter in foreground — plugin listener will handle callId=$callId")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("VonageFCM", "[FCM-5] processPushCallInvite threw: ${e.message}", e)
            }

            // ── Background WebSocket revival (doze-mode guard) ────────────────
            // When the app is backgrounded, Android doze mode silently kills the
            // TCP connection that backs the Vonage WebSocket while the VoiceClient
            // object stays alive in memory (clientWasNull=false, isSessionReady=true).
            // processPushCallInvite() above succeeds using the stale session, but
            // the server-side cancel (caller hangs up) can NEVER arrive over the
            // dead socket.  Calling createSession() here re-establishes a live
            // WebSocket while the phone is still ringing.  If the caller hangs up
            // before the new session is ready, the 60 s fallback timer in
            // TVConnectionService covers the gap.  Fire-and-forget — we never
            // block the FCM callback on this result.
            val storedJwt = VonageClientHolder.getStoredJwt(applicationContext)
            if (storedJwt != null) {
                android.util.Log.i("VonageFCM", "[FCM-5] Kicking background WebSocket revival (doze-mode guard)...")
                // client is VoiceClient? (var), so !! is safe here — we are inside the
                // clientWasNull=false branch, meaning client was non-null and session was
                // ready when this FCM arrived.
                client!!.createSession(storedJwt) { error, sessionId ->
                    if (error != null) {
                        android.util.Log.w("VonageFCM", "[FCM-5] WebSocket revival failed: ${error.message} — 60s fallback active")
                    } else {
                        android.util.Log.i("VonageFCM", "[FCM-5] ✓ WebSocket revived — sessionId=$sessionId. Cancel will now arrive normally")
                        VonageClientHolder.isSessionReady = true
                    }
                }
            } else {
                android.util.Log.w("VonageFCM", "[FCM-5] No stored JWT — cannot revive WebSocket. 60s fallback active")
            }

            releaseProcessingWakeLock(wakeLock)
        }
    }

    /**
     * Process the push invite and send the incoming call intent to TVConnectionService.
     *
     * Called AFTER createSession() completes (or fails) when the app was killed.
     * This ensures the SDK has an active session when it processes the push,
     * so the invite is properly registered and client.answer(callId) will work.
     */
    /**
     * Attempt to restore a Vonage session from a stored JWT.
     *
     * After a device reboot, the network interface may not be fully up
     * when the first FCM push arrives. If [createSession] fails (network
     * error or JWT expiry), we retry once after a 2-second delay before
     * falling back to processing the push without a session (notification
     * will still appear but answering will fail without a valid session).
     */
    private fun restoreSessionWithRetry(
        client: VoiceClient,
        jwt: String,
        dataString: String,
        callerDisplay: String?,
        clientWasNull: Boolean,
        inviteHandledDirectly: java.util.concurrent.atomic.AtomicBoolean,
        wakeLock: PowerManager.WakeLock?,
        retriesLeft: Int
    ) {
        // ── Diagnostic: network + doze state before createSession ────────
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val isNetworkAvailable: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } else {
            @Suppress("DEPRECATION")
            cm?.activeNetworkInfo?.isConnectedOrConnecting == true
        }
        val pm2 = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isDoze = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pm2.isDeviceIdleMode else false
        val isScreenOn = pm2.isInteractive
        android.util.Log.i("VonageFCM",
            "[FCM-5a] Pre-createSession: thread=${Thread.currentThread().name}, " +
            "networkAvailable=$isNetworkAvailable, dozeMode=$isDoze, screenOn=$isScreenOn, " +
            "jwtLength=${jwt.length}, retriesLeft=$retriesLeft")

        // ── Timeout watchdog ─────────────────────────────────────────────
        // If createSession callback never fires (network blocked, doze, SDK hang),
        // log a warning after 20 seconds to capture the exact failure mode.
        val callbackFired = java.util.concurrent.atomic.AtomicBoolean(false)
        val watchdogHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val watchdog = Runnable {
            if (!callbackFired.get()) {
                val pm3 = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                val dozeNow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pm3.isDeviceIdleMode else false
                android.util.Log.e("VonageFCM",
                    "[FCM-5a] ⚠️ WATCHDOG: createSession callback did NOT fire after 20s! " +
                    "networkWas=$isNetworkAvailable, dozeWas=$isDoze, dozeNow=$dozeNow, " +
                    "isSessionReady=${VonageClientHolder.isSessionReady}, inviteHandled=${inviteHandledDirectly.get()}")
            }
        }
        watchdogHandler.postDelayed(watchdog, 20_000L)

        android.util.Log.i("VonageFCM", "[FCM-5a] Calling createSession (retriesLeft=$retriesLeft)...")
        client.createSession(jwt) { error, sessionId ->
            callbackFired.set(true)
            watchdogHandler.removeCallbacks(watchdog)
            android.util.Log.i("VonageFCM",
                "[FCM-5a] createSession callback fired on thread=${Thread.currentThread().name}")
            if (error != null) {
                android.util.Log.e("VonageFCM", "[FCM-5a] ✗ createSession failed (retriesLeft=$retriesLeft): ${error.message}")
                if (retriesLeft > 0) {
                    // Network may not be ready immediately after boot — retry after 2s
                    android.util.Log.i("VonageFCM", "[FCM-5a] Retrying createSession in 2000ms...")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        restoreSessionWithRetry(client, jwt, dataString, callerDisplay, clientWasNull, inviteHandledDirectly, wakeLock, retriesLeft - 1)
                    }, 2000L)
                } else {
                    android.util.Log.e("VonageFCM", "[FCM-5a] ✗ createSession exhausted retries — processing push WITHOUT valid session (answer may fail)")
                    processInviteAndNotify(client, dataString, callerDisplay, clientWasNull, inviteHandledDirectly, wakeLock)
                }
            } else {
                android.util.Log.i("VonageFCM", "[FCM-5a] ✓ createSession succeeded: sessionId=$sessionId")
                VonageClientHolder.isSessionReady = true
                processInviteAndNotify(client, dataString, callerDisplay, clientWasNull, inviteHandledDirectly, wakeLock)
            }
        }
        android.util.Log.d("VonageFCM", "[FCM-5a] createSession registered (async) — waiting for callback...")
    }

    private fun processInviteAndNotify(
        client: VoiceClient,
        dataString: String,
        callerDisplay: String?,
        clientWasNull: Boolean,
        inviteHandledDirectly: java.util.concurrent.atomic.AtomicBoolean,
        wakeLock: PowerManager.WakeLock?
    ) {
        android.util.Log.i("VonageFCM", "[FCM-6] Calling processPushCallInvite... " +
            "clientWasNull=$clientWasNull, inviteHandled=${inviteHandledDirectly.get()}, " +
            "isSessionReady=${VonageClientHolder.isSessionReady}, " +
            "isActivityAlive=${IncomingCallActivity.isActivityAlive}")
        try {
            val callId = client.processPushCallInvite(dataString)
            android.util.Log.i("VonageFCM", "[FCM-6] processPushCallInvite returned: callId=$callId")
            if (!callId.isNullOrEmpty()) {
                android.util.Log.d("VonageFCM", "[FCM-6] Incoming call processed with callId: $callId")

                if (clientWasNull && inviteHandledDirectly.compareAndSet(false, true)) {
                    val displayFrom = VonageClientHolder.pendingCallerDisplay ?: callerDisplay ?: "Unknown"
                    VonageClientHolder.pendingCallerDisplay = null

                    android.util.Log.i("VonageFCM", "[FCM-7] Sending real incoming call intent: callId=$callId, from=$displayFrom")
                    val realIntent = Intent(applicationContext, TVConnectionService::class.java).apply {
                        action = Constants.ACTION_INCOMING_CALL
                        putExtra(Constants.EXTRA_CALL_ID, callId)
                        putExtra(Constants.EXTRA_CALL_FROM, displayFrom)
                        putExtra(Constants.EXTRA_CALL_TO, "")
                    }
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            applicationContext.startForegroundService(realIntent)
                        } else {
                            applicationContext.startService(realIntent)
                        }
                        android.util.Log.i("VonageFCM", "[FCM-7] ✓ Real incoming call intent sent — handleIncomingCall will add to Telecom + notify + ring")
                        android.util.Log.i("VonageFCM", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    } catch (e: Exception) {
                        android.util.Log.e("VonageFCM", "[FCM-7] ✗ Error sending real incoming call intent: ${e.message}", e)
                    }
                } else {
                    android.util.Log.d("VonageFCM", "[FCM-6] inviteHandledDirectly already set — listener path handled it")
                }
            } else {
                android.util.Log.i("VonageFCM", "[FCM-6] processPushCallInvite returned null — waiting for setCallInviteListener to fire")
            }
        } catch (e: Exception) {
            android.util.Log.e("VonageFCM", "[FCM-6] ✗ processPushCallInvite threw: ${e.message}", e)
        }
        releaseProcessingWakeLock(wakeLock)
    }

    // ── Push payload helpers ────────────────────────────────────────────────

    /**
     * Build data string in Kotlin Map.toString() format from an FCM data map.
     *
     * The Vonage SDK's internal `extractJsonFromPushData` expects:
     *   `{nexmo={"type":"member:invited",...}}`
     * It does `substringAfter("nexmo=").dropLast(1)` to extract the JSON.
     *
     * When called from the native `onMessageReceived`, we can use
     * `remoteMessage.data.toString()` directly. This helper exists for
     * the Dart fallback path where the data arrives as a re-constructed map.
     */
    @Suppress("unused")
    private fun buildNestedJson(data: Map<String, String>): String {
        return data.toString()
    }

    /**
     * Extract the caller display name from the Vonage push payload.
     *
     * Priority:
     *   1. push_info.from_user.display_name  (app-to-app calls)
     *   2. body.channel.from.number          (PSTN calls)
     */
    private fun extractCallerDisplay(nexmoRaw: String): String? {
        return try {
            val json = JSONObject(nexmoRaw)
            // App-to-app: push_info.from_user.display_name
            val fromUser = json.optJSONObject("push_info")
                ?.optJSONObject("from_user")
            val displayName = fromUser?.optString("display_name", null)
            if (!displayName.isNullOrEmpty()) return displayName

            // PSTN: body.channel.from.number
            json.optJSONObject("body")
                ?.optJSONObject("channel")
                ?.optJSONObject("from")
                ?.optString("number", null)
        } catch (_: Exception) {
            null
        }
    }

    // ── Broadcast helpers ─────────────────────────────────────────────────

    private fun broadcastError(message: String) {
        val intent = Intent(Constants.BROADCAST_CALL_FAILED).apply {
            putExtra("error", message)
        }
        broadcastManager.sendBroadcast(intent)
    }

    // ── WakeLock helpers ──────────────────────────────────────────────────

    /**
     * Acquire a partial WakeLock to keep the CPU alive while the Vonage SDK
     * processes the push asynchronously. Without this, aggressive OEMs like
     * Vivo may put the CPU back to sleep before setCallInviteListener fires.
     *
     * The lock auto-releases after 30 seconds as a safety net.
     */
    private fun acquireProcessingWakeLock(): PowerManager.WakeLock? {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "VonageVoice:FCMProcessing"
            ).apply {
                acquire(30_000L) // 30s timeout safety net
            }
            android.util.Log.d("VonageFCM", "[WAKELOCK] ✓ PARTIAL_WAKE_LOCK acquired (30s timeout)")
            lock
        } catch (e: Exception) {
            android.util.Log.e("VonageFCM", "[WAKELOCK] ✗ Failed to acquire WakeLock: ${e.message}")
            null
        }
    }

    private fun releaseProcessingWakeLock(wakeLock: PowerManager.WakeLock?) {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock.release()
            }
        } catch (e: Exception) {
            android.util.Log.e("VonageFCM", "Failed to release WakeLock: ${e.message}")
        }
    }

    // ── Active call detection ─────────────────────────────────────────────

    /**
     * Returns true if the device is currently in ANY active call — cellular,
     * Twilio VoIP, or any other VoIP app registered via ConnectionService.
     *
     * Uses [TelecomManager.isInCall] which detects both managed (cellular)
     * and self-managed (VoIP) calls. This ensures an incoming Vonage invite
     * is silently skipped when the user is already on a Twilio call, a phone
     * call, WhatsApp call, or any other active call.
     */
    private fun isDeviceInSystemCall(): Boolean {
        return try {
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                ?: return false
            @Suppress("DEPRECATION")
            val inCall = telecomManager.isInCall
            android.util.Log.d("VonageFCM", "isDeviceInSystemCall: isInCall=$inCall")
            inCall
        } catch (e: SecurityException) {
            // READ_PHONE_STATE permission may not be granted — assume no call
            android.util.Log.w("VonageFCM", "isDeviceInSystemCall: SecurityException — ${e.message}")
            false
        } catch (e: Exception) {
            android.util.Log.w("VonageFCM", "isDeviceInSystemCall: ${e.message}")
            false
        }
    }

    companion object {
        /** SharedPreferences file that stores the raw nexmo FCM payload for cross-process recovery. */
        const val PREFS_PENDING_FCM   = "vonage_pending_fcm"
        const val KEY_NEXMO_RAW       = "nexmo_raw"
        const val KEY_CALLER_DISPLAY  = "caller_display"
        const val KEY_FCM_TIMESTAMP   = "fcm_timestamp"
        /** Maximum age (ms) for a stored FCM payload to be considered valid for re-processing. */
        const val PENDING_FCM_TTL_MS  = 120_000L

        /** Clear the persisted FCM data — called after the invite is successfully re-processed. */
        fun clearPendingFcmData(context: android.content.Context) {
            try {
                context.getSharedPreferences(PREFS_PENDING_FCM, android.content.Context.MODE_PRIVATE)
                    .edit().clear().commit()
                android.util.Log.d("VonageFCM", "clearPendingFcmData: SharedPreferences cleared")
            } catch (e: Exception) {
                android.util.Log.w("VonageFCM", "clearPendingFcmData failed: ${e.message}")
            }
        }
    }
}
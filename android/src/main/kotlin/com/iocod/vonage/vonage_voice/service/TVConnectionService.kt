package com.iocod.vonage.vonage_voice.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.iocod.vonage.vonage_voice.call.TVCallConnection
import com.iocod.vonage.vonage_voice.call.TVCallInviteConnection
import com.iocod.vonage.vonage_voice.constants.Constants
import com.iocod.vonage.vonage_voice.IncomingCallActivity
import com.iocod.vonage.vonage_voice.AnswerCallTrampolineActivity
import com.iocod.vonage.vonage_voice.fcm.VonageFirebaseMessagingService
import com.vonage.voice.api.VoiceClient
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.RadialGradient
import android.graphics.drawable.Icon
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.core.app.Person
import com.iocod.vonage.vonage_voice.R

/**
 * TVConnectionService — TelecomVoice ConnectionService.
 *
 * Extends Android's [ConnectionService] to integrate Vonage voice calls
 * with the Android Telecom framework. This enables:
 *   - Native system call UI (incoming call screen, in-call screen)
 *   - Bluetooth headset support
 *   - Car kit / wearable integration
 *   - Proper audio focus management
 *
 * Communication flow:
 *
 *   VonageVoicePlugin → startService(Intent) → onStartCommand()
 *        ↓
 *   TVConnectionService handles call actions (answer, hangup, mute etc.)
 *        ↓
 *   Vonage VoiceClient SDK calls
 *        ↓
 *   LocalBroadcast events → TVBroadcastReceiver → VonageVoicePlugin → Flutter
 *
 * Active connections are tracked in [activeConnections] map keyed by callId.
 * Invite connections are tracked in [pendingInvites] map keyed by callId.
 */
class TVConnectionService : ConnectionService() {

    private lateinit var broadcastManager: LocalBroadcastManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var scoReceiver: BroadcastReceiver? = null
    // Receives ACTION_SCREEN_ON / ACTION_USER_PRESENT to re-apply audio mode
    // on OPPO ColorOS where AUDIOFOCUS_GAIN doesn't reliably fire after screen-on.
    private var screenOnReceiver: BroadcastReceiver? = null
    private var incomingCallWakeLock: PowerManager.WakeLock? = null

    // Guards against firing fullScreenIntent multiple times for the same call.
    // Samsung A15 / Android 16 can crash or show heads-up instead of full-screen
    // when nm.notify() fires the same fullScreenIntent PendingIntent repeatedly.
    private var incomingNotificationPosted = false

    // ── Audio focus management ────────────────────────────────────────────
    // Samsung devices (A15, S23, etc.) emit a spurious AUDIOFOCUS_LOSS_TRANSIENT
    // ~3 seconds after granting focus. This is caused by their SystemUI reclaiming
    // focus for "call notification" sounds. We ignore any transient loss within
    // AUDIO_FOCUS_SETTLE_MS of the original request.
    private var hasAudioFocus = false
    private var wasAutoMutedByFocusLoss = false
    private var lastAudioFocusRequestTime = 0L
    private val restoreAudioFocusHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingAudioFocusRestore: Runnable? = null

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        val elapsed = SystemClock.elapsedRealtime() - lastAudioFocusRequestTime
        android.util.Log.d("TVConnectionService",
            "[AUDIO-FOCUS] focusChange=$focusChange, elapsed=${elapsed}ms, hasAudioFocus=$hasAudioFocus")

        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                // Cancel any pending restore — we already have focus
                cancelPendingAudioFocusRestore()
                // If we previously auto-muted, unmute now
                if (wasAutoMutedByFocusLoss) {
                    wasAutoMutedByFocusLoss = false
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    audioManager.isMicrophoneMute = false
                    val conn = activeConnections.values.firstOrNull()
                    if (conn?.isMuted == false) {
                        android.util.Log.d("TVConnectionService",
                            "[AUDIO-FOCUS] Restoring unmuted state after focus gain")
                    }
                }
                // Re-apply audio mode in case OEM reset it during screen lock/unlock.
                // MIUI / Redmi / Poco / Vivo: AUDIOFOCUS_GAIN fires on screen unlock.
                // Without this, MODE_IN_COMMUNICATION stays reset → bidirectional silence.
                if (activeConnections.isNotEmpty()) {
                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val modeBefore = am.mode
                    // Preserve mic mute state before changing audio mode —
                    // some OEMs reset isMicrophoneMute when AudioManager.mode is set.
                    val muteBeforeRecovery = am.isMicrophoneMute
                    // Preserve speaker state before changing audio mode.
                    val speakerBeforeRecovery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        am.communicationDevice?.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    } else {
                        @Suppress("DEPRECATION")
                        am.isSpeakerphoneOn
                    }
                    am.mode = AudioManager.MODE_IN_COMMUNICATION
                    // Restore mic mute to what it was before mode change
                    am.isMicrophoneMute = muteBeforeRecovery
                    // Restore speaker routing to what it was before mode change
                    if (speakerBeforeRecovery) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val spkDev = am.availableCommunicationDevices
                                .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                            if (spkDev != null) am.setCommunicationDevice(spkDev)
                            else {
                                @Suppress("DEPRECATION")
                                am.isSpeakerphoneOn = true
                            }
                        } else {
                            @Suppress("DEPRECATION")
                            am.isSpeakerphoneOn = true
                        }
                    }
                    val modeAfter = am.mode
                    android.util.Log.d("TVConnectionService",
                        "[AUDIO-FOCUS] GAIN — re-applied MODE_IN_COMMUNICATION: " +
                        "before=$modeBefore after=$modeAfter stuck=${modeAfter == AudioManager.MODE_IN_COMMUNICATION} | " +
                        "micMute=${am.isMicrophoneMute} speaker=$speakerBeforeRecovery")
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Samsung 3-second guard: ignore transient loss that arrives
                // within AUDIO_FOCUS_SETTLE_MS of our request — it's spurious.
                if (elapsed < AUDIO_FOCUS_SETTLE_MS) {
                    android.util.Log.d("TVConnectionService",
                        "[AUDIO-FOCUS] Ignoring spurious transient loss (${elapsed}ms < ${AUDIO_FOCUS_SETTLE_MS}ms)")
                    return@OnAudioFocusChangeListener
                }
                // Real transient loss (e.g., cellular call, navigation instruction).
                // Auto-mute the mic to prevent the other party hearing the interruption.
                android.util.Log.d("TVConnectionService",
                    "[AUDIO-FOCUS] Real transient loss — auto-muting mic")
                wasAutoMutedByFocusLoss = true
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.isMicrophoneMute = true
                // Schedule a re-request after 1 second to recover focus
                scheduleAudioFocusRestore()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Permanent loss — another app took focus (e.g., music player).
                // We should still try to hold onto our call audio.
                android.util.Log.w("TVConnectionService",
                    "[AUDIO-FOCUS] Permanent loss — scheduling restore")
                hasAudioFocus = false
                scheduleAudioFocusRestore()
            }
        }
    }

    companion object {
        private const val AUDIO_FOCUS_SETTLE_MS = 3000L
        private const val AUDIO_FOCUS_RESTORE_DELAY_MS = 1000L

        /**
         * Maximum time a pending invite can sit in memory before being
         * considered stale and auto-expired. This is critical because
         * the FCM active-call guard blocks new calls when pendingInvites
         * is non-empty — a stuck invite would permanently block all
         * future incoming calls until app restart.
         *
         * Set to 60 seconds to match Twilio's PENDING_CALL_TIMEOUT_MS.
         */
        private const val PENDING_INVITE_TIMEOUT_MS = 60_000L

        // ── SharedPreferences keys (matching Twilio's cross-process pattern) ──
        const val PREFS_PENDING_CALL       = "vonage_pending_call"
        const val KEY_PENDING_CALL_ID      = "pending_call_id"
        const val KEY_PENDING_CALL_FROM    = "pending_call_from"
        const val KEY_PENDING_CALL_TO      = "pending_call_to"
        const val KEY_PENDING_CALL_TIMESTAMP = "pending_call_timestamp"
        /** Maximum age (ms) for a persisted call entry to be considered valid. */
        const val PENDING_CALL_TTL_MS      = 120_000L

        /**
         * Map of currently active call connections keyed by callId.
         * Accessed by VonageVoicePlugin to query call state.
         */
        val activeConnections: HashMap<String, TVCallConnection> = HashMap()

        /**
         * Map of pending incoming call invite connections keyed by callId.
         * Moved to activeConnections once the call is answered.
         */
        val pendingInvites: HashMap<String, TVCallInviteConnection> = HashMap()

        /**
         * Timestamps for when each pending invite was created.
         * Used by [expireStaleInvites] to auto-clean stuck invites.
         */
        private val pendingInviteTimestamps: HashMap<String, Long> = HashMap()

        /**
         * Returns true if there is at least one active call connection.
         */
        fun hasActiveCall(): Boolean = activeConnections.isNotEmpty()

        /**
         * Returns the callId of the first active connection, or null.
         */
        fun getActiveCallId(): String? = activeConnections.keys.firstOrNull()

        /**
         * Returns the first active TVCallConnection, or null.
         * Used by VonageVoicePlugin for direct state updates (e.g. selectAudioDevice).
         */
        fun getActiveConnection(): TVCallConnection? = activeConnections.values.firstOrNull()

        /** Clear the persisted pending call entry — called after answer/hangup/cleanup. */
        fun clearPendingCallData(context: android.content.Context) {
            try {
                context.getSharedPreferences(PREFS_PENDING_CALL, android.content.Context.MODE_PRIVATE)
                    .edit().clear().commit()
                android.util.Log.d("TVConnectionService", "clearPendingCallData: SharedPreferences cleared")
            } catch (e: Exception) {
                android.util.Log.w("TVConnectionService", "clearPendingCallData failed: ${e.message}")
            }
        }

        // ── Answered call persistence (survives across process death) ─────────
        // Mirrors the PREFS_PENDING_CALL pattern: written with commit() when a
        // call is answered natively (doAnswer() success callback), read by
        // MainActivity.onResume() and reEmitPendingCallState() so Flutter can
        // navigate directly to the active call screen even if the process was
        // killed between the lock-screen answer and the user unlocking.
        const val PREFS_ANSWERED_CALL           = "vonage_answered_call"
        const val KEY_ANSWERED_CALL_ID          = "answered_call_id"
        const val KEY_ANSWERED_CALL_FROM        = "answered_call_from"
        const val KEY_ANSWERED_CALL_TO          = "answered_call_to"
        const val KEY_ANSWERED_CALL_TIMESTAMP   = "answered_call_timestamp"
        /** Maximum age for the answered-call entry to be considered valid (5 min). */
        const val ANSWERED_CALL_TTL_MS          = 300_000L

        /**
         * Persist answered call metadata to SharedPreferences.
         * Called synchronously (commit) from doAnswer() success so the data
         * survives process death on aggressive OEMs (MIUI, Samsung).
         */
        fun persistAnsweredCallData(
            context: android.content.Context,
            callId: String,
            from: String?,
            to: String?
        ) {
            try {
                context.getSharedPreferences(PREFS_ANSWERED_CALL, android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_ANSWERED_CALL_ID, callId)
                    .putString(KEY_ANSWERED_CALL_FROM, from ?: "")
                    .putString(KEY_ANSWERED_CALL_TO, to ?: "")
                    .putLong(KEY_ANSWERED_CALL_TIMESTAMP, System.currentTimeMillis())
                    .commit()
                android.util.Log.d("TVConnectionService", "✓ persistAnsweredCallData: callId=$callId, from=$from")
            } catch (e: Exception) {
                android.util.Log.w("TVConnectionService", "persistAnsweredCallData failed: ${e.message}")
            }
        }

        /** Clear the answered call entry — called on hangup/cleanup/call-end. */
        fun clearAnsweredCallData(context: android.content.Context) {
            try {
                context.getSharedPreferences(PREFS_ANSWERED_CALL, android.content.Context.MODE_PRIVATE)
                    .edit().clear().commit()
                android.util.Log.d("TVConnectionService", "clearAnsweredCallData: SharedPreferences cleared")
            } catch (e: Exception) {
                android.util.Log.w("TVConnectionService", "clearAnsweredCallData failed: ${e.message}")
            }
        }

        // ── Cross-plugin active-call flag ─────────────────────────────────────
        // Written to a shared SharedPreferences file so EasifyFirebaseMessagingService
        // (the FCM dispatcher) can detect any active call at the FCM-message level
        // and silently suppress incoming calls from either provider.
        // Uses synchronous commit() for cross-process safety.
        const val CROSS_PLUGIN_PREFS    = "easify_active_call_state"
        const val KEY_CALL_ACTIVE       = "active_call_in_progress"
        const val KEY_CALL_ACTIVE_TS    = "active_call_timestamp"

        fun setCallActive(context: android.content.Context, active: Boolean) {
            try {
                val editor = context.getSharedPreferences(
                    CROSS_PLUGIN_PREFS, android.content.Context.MODE_PRIVATE
                ).edit()
                if (active) {
                    editor.putBoolean(KEY_CALL_ACTIVE, true)
                        .putLong(KEY_CALL_ACTIVE_TS, System.currentTimeMillis())
                } else {
                    editor.remove(KEY_CALL_ACTIVE)
                        .remove(KEY_CALL_ACTIVE_TS)
                }
                editor.commit()
                android.util.Log.d("TVConnectionService", "setCallActive: active=$active")
            } catch (e: Exception) {
                android.util.Log.w("TVConnectionService", "setCallActive failed: ${e.message}")
            }
        }

        /**
         * Expire any pending invites older than [PENDING_INVITE_TIMEOUT_MS].
         * Called from VonageFirebaseMessagingService before the active-call guard
         * so a stuck invite never permanently blocks future incoming calls.
         *
         * Two-phase teardown:
         * 1. Remove stale entries from maps immediately so the guard check that
         *    follows this call sees clean state right away.
         * 2. Send ACTION_CANCEL_CALL_INVITE for each stale ID so the service
         *    also tears down ringtone, wake lock, notification, pending-call
         *    prefs, and the foreground service — the same path as a normal
         *    cancel/hangup. handleCancelCallInvite() handles a null invite
         *    gracefully (invite?.cancel()) so removing from the map first is safe.
         */
        fun clearStaleInvitesIfAny(context: android.content.Context) {
            val now = System.currentTimeMillis()
            val staleIds = pendingInviteTimestamps.filter { (_, timestamp) ->
                now - timestamp > PENDING_INVITE_TIMEOUT_MS
            }.keys.toList()
            for (staleId in staleIds) {
                android.util.Log.w("TVConnectionService",
                    "[FCM-GUARD] Clearing stale invite before guard: callId=$staleId")
                // Phase 1 — remove from maps so the active-call guard passes immediately
                pendingInvites.remove(staleId)
                pendingInviteTimestamps.remove(staleId)
                // Phase 2 — route through service for full teardown (ringtone, wake lock,
                // notification, SharedPreferences, foreground service stop)
                try {
                    val cancelIntent = android.content.Intent(
                        context, TVConnectionService::class.java
                    ).apply {
                        action = Constants.ACTION_CANCEL_CALL_INVITE
                        putExtra(Constants.EXTRA_CALL_ID, staleId)
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(cancelIntent)
                    } else {
                        context.startService(cancelIntent)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("TVConnectionService",
                        "[FCM-GUARD] Failed to send cancel intent for stale invite $staleId: ${e.message}")
                }
            }
        }
    }

    /**
     * Auto-expire pending invites that have been sitting for longer than
     * [PENDING_INVITE_TIMEOUT_MS]. This prevents a stuck invite (caused by
     * a lost server cancel event or network drop) from permanently blocking
     * all future incoming calls via the FCM active-call guard.
     */
    private fun expireStaleInvites() {
        val now = System.currentTimeMillis()
        val staleIds = pendingInviteTimestamps.filter { (_, timestamp) ->
            now - timestamp > PENDING_INVITE_TIMEOUT_MS
        }.keys.toList()

        for (staleId in staleIds) {
            android.util.Log.w("TVConnectionService",
                "Expiring stale pending invite: callId=$staleId (age=${now - (pendingInviteTimestamps[staleId] ?: 0)}ms)")
            pendingInvites[staleId]?.cancel()
            pendingInvites.remove(staleId)
            pendingInviteTimestamps.remove(staleId)
        }

        if (staleIds.isNotEmpty() && activeConnections.isEmpty() && pendingInvites.isEmpty()) {
            stopServiceRinging()
            releaseIncomingCallWakeLock()
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager
            notificationManager.cancel(Constants.NOTIFICATION_ID)
            stopForeground(true)
            stopSelf()
        }
    }

    // ── Per-invite auto-cancel timer ──────────────────────────────────────
    // Fires handleCancelCallInvite() if the SDK cancel/hangup event never arrives
    // (caused by a WebSocket drop between processPushCallInvite and the remote
    // answering elsewhere — the reconnect window can be 100ms–2s, during which
    // the "answered elsewhere" event is permanently lost by the SDK).
    // The timer is cancelled immediately if answer/hangup/cancel arrives normally,
    // so it only fires in the stuck-invite failure case.
    private val inviteTimeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val inviteTimeoutRunnables = HashMap<String, Runnable>()

    // ── Service-level ringtone & vibration ────────────────────────────────
    // Ringing is managed by the service (not IncomingCallActivity) so it
    // works reliably in background and locked states where the activity
    // may fail to launch due to BAL restrictions.
    private var serviceRingtone: Ringtone? = null
    private var serviceVibrator: Vibrator? = null
    private var isServiceRinging = false

    // ── Service lifecycle ─────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        broadcastManager = LocalBroadcastManager.getInstance(this)

        android.util.Log.i("TVConnectionService",
            "[LIFECYCLE] onCreate: pid=${android.os.Process.myPid()}, " +
            "pendingInvites=${pendingInvites.size}, activeConns=${activeConnections.size}")

        // Install a catch-all handler to log crashes that would otherwise
        // appear as silent "binderDied" events in system_server Telecom logs.
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("TVConnectionService",
                "[FATAL] Uncaught exception on thread ${thread.name} (pid=${android.os.Process.myPid()})",
                throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // Clean up the old notification channel that had sound/vibration.
        // Must happen here (before any startForeground call) because
        // Android throws SecurityException if you delete a channel while
        // a foreground service is using it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.deleteNotificationChannel(Constants.INCOMING_CALL_CHANNEL_ID_OLD)
        }
    }

    // NOTE: ConnectionService.onBind() is final — cannot be overridden.
    // Telecom binding is tracked via onCreateIncomingConnection logs instead.

    /**
     * Entry point for all actions sent from VonageVoicePlugin via
     * startService() or startForegroundService(Intent).
     *
     * When started via startForegroundService(), Android requires
     * startForeground() to be called within 5 seconds. We ensure
     * this by immediately promoting to foreground for any action
     * that arrives while the service is not yet in foreground.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: "(null)"
        val callId = intent?.getStringExtra(Constants.EXTRA_CALL_ID)
        val callerFrom = intent?.getStringExtra(Constants.EXTRA_CALL_FROM)
        android.util.Log.i("TVConnectionService",
            "[INTENT] onStartCommand: action=$action, callId=$callId, from=$callerFrom, " +
            "pendingInvites=${pendingInvites.size}, activeConns=${activeConnections.size}, " +
            "pid=${android.os.Process.myPid()}")
        // Ensure we satisfy the startForeground contract for every
        // startForegroundService() call. If the service is already in
        // foreground (from handleIncomingCall), this is a no-op update.
        ensureForeground(intent?.action, callerFrom, callId)
        intent?.let { handleIntent(it) }
        return START_NOT_STICKY
    }

    /**
     * Ensures the service is promoted to foreground.
     * Called early in onStartCommand to prevent the 5-second ANR
     * when the service is started via startForegroundService().
     *
     * [intentAction] is used to determine the notification type BEFORE
     * the intent is fully processed. This ensures the very first
     * startForeground() call uses the HIGH importance incoming call
     * channel when appropriate, so the notification is always visible
     * on devices that "sticky" the first channel importance.
     */
    private fun ensureForeground(intentAction: String? = null, callerFrom: String? = null, callId: String? = null) {
        createNotificationChannel()
        createIncomingCallNotificationChannel()

        // ACTION_ANSWER is treated differently: we are transitioning from incoming
        // to active, so we must NOT rebuild the incoming call notification.
        // On OPPO ColorOS (and other OEMs), calling startForeground() with a
        // CallStyle.forIncomingCall() notification while processing ACTION_ANSWER
        // re-triggers the system call overlay right before doAnswer() updates it,
        // causing the "Incoming Call" overlay to persist even after Connected.
        val isAnswerAction = intentAction == Constants.ACTION_ANSWER
        val isIncomingAction = intentAction == Constants.ACTION_INCOMING_CALL
                || intentAction == Constants.ACTION_ANSWER

        val notification = if (!isAnswerAction && pendingInvites.isNotEmpty()) {
            val entry = pendingInvites.entries.first()
            buildIncomingCallNotification(entry.key, entry.value.from)
        } else if (!isAnswerAction && isIncomingAction && !callId.isNullOrEmpty()) {
            // Real incoming call with known callId — build full notification
            // with the real callId in fullScreenIntent. This prevents the
            // activity from launching in placeholder mode on locked screens
            // (fullScreenIntent fires immediately, before handleIncomingCall runs).
            android.util.Log.d("TVConnectionService",
                "[ensureForeground] Using real notification: callId=$callId, from=$callerFrom")
            buildIncomingCallNotification(callId, callerFrom ?: "Incoming Call")
        } else if (!isAnswerAction && isIncomingAction) {
            // No callId yet (placeholder path from killed-app FCM) — use
            // placeholder notification WITH fullScreenIntent so the incoming
            // call screen appears immediately on lock screen.
            buildPlaceholderIncomingNotification(callerFrom ?: "Incoming Call")
        } else if (activeConnections.isNotEmpty()) {
            buildActiveCallNotification()
        } else {
            buildActiveCallNotification()
        }

        // For ACTION_ANSWER: use both MICROPHONE and PHONE_CALL — matches
        // startCallForegroundService() and satisfies Vivo/OPPO AudioRecord requirement.
        // For incoming calls: PHONE_CALL only (RECORD_AUDIO not yet needed).
        // For active calls: MICROPHONE only.
        val serviceType = if (isAnswerAction) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        } else if (isIncomingAction || pendingInvites.isNotEmpty()) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        } else {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(Constants.NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(Constants.NOTIFICATION_ID, notification)
        }

        // For incoming calls, also post via NotificationManager.notify() —
        // startForeground() alone doesn't reliably fire fullScreenIntent on
        // Samsung A15 / Android 16.  This must happen BEFORE the wake lock
        // turns the screen on, so the system still considers the device
        // "locked + screen off" and launches the fullScreenIntent as a
        // full-screen activity (not a heads-up notification).
        // ONLY fire once — Samsung crashes or shows heads-up (not full-screen)
        // when the same fullScreenIntent PendingIntent fires multiple times.
        // Skip for ACTION_ANSWER — the answer path must not re-post the incoming
        // call notification (it would re-trigger the OPPO/ColorOS system overlay).
        if (!isAnswerAction && (isIncomingAction || pendingInvites.isNotEmpty()) && !incomingNotificationPosted) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(Constants.NOTIFICATION_ID, notification)
            incomingNotificationPosted = true
            android.util.Log.d("TVConnectionService",
                "[ensureForeground] ✓ nm.notify() fired fullScreenIntent (pid=${android.os.Process.myPid()})")
        }

        // Acquire FULL_WAKE_LOCK for incoming calls to physically turn the screen on.
        // Without this, the fullScreenIntent may not fire on some devices.
        if (!isAnswerAction && isIncomingAction && incomingCallWakeLock == null) {
            acquireIncomingCallWakeLock()
        }
    }

    // override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    // ── Intent dispatcher ─────────────────────────────────────────────────

    /**
     * Routes incoming intents to the correct handler based on action string.
     * All actions are defined in [Constants].
     */
    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            Constants.ACTION_INCOMING_CALL -> handleIncomingCall(intent)
            Constants.ACTION_CANCEL_CALL_INVITE -> handleCancelCallInvite(intent)
            Constants.ACTION_PLACE_OUTGOING_CALL -> handleOutgoingCall(intent)
            Constants.ACTION_ANSWER -> handleAnswer(intent)
            Constants.ACTION_HANGUP -> handleHangup(intent)
            Constants.ACTION_SEND_DIGITS -> handleSendDigits(intent)
            Constants.ACTION_TOGGLE_SPEAKER -> handleToggleSpeaker(intent)
            Constants.ACTION_TOGGLE_BLUETOOTH -> handleToggleBluetooth(intent)
            Constants.ACTION_TOGGLE_MUTE -> handleToggleMute(intent)
            Constants.ACTION_CLEANUP -> handleCleanup()
        }
    }

    // ── Incoming call handling ────────────────────────────────────────────

    /**
     * Called when FCM push delivers an incoming call invite.
     * Creates a [TVCallInviteConnection] and notifies the Telecom system
     * so it can show the native incoming call UI.
     *
     * Intent extras required:
     *   EXTRA_CALL_ID   — Vonage callId string
     *   EXTRA_CALL_FROM — caller display name or number
     *   EXTRA_CALL_TO   — callee (this device's identity)
     */
    private fun handleIncomingCall(intent: Intent) {
        val callId = intent.getStringExtra(Constants.EXTRA_CALL_ID)
        val from = intent.getStringExtra(Constants.EXTRA_CALL_FROM)
                ?: Constants.DEFAULT_UNKNOWN_CALLER
        val to = intent.getStringExtra(Constants.EXTRA_CALL_TO) ?: ""

        // Placeholder intent (no callId) — FCM sends this immediately while
        // createSession() is still running. Start ringing now so the user
        // hears audio ~2 seconds earlier than waiting for the real intent.
        if (callId.isNullOrEmpty()) {
            android.util.Log.d("TVConnectionService", "[INCOMING-PLACEHOLDER] No callId yet — starting ringtone early (from=$from)")
            startServiceRinging()
            return
        }

        android.util.Log.i("TVConnectionService", "[INCOMING-REAL] callId=$callId, from=$from, " +
            "isFlutterForeground=${VonageClientHolder.isFlutterInForeground}, " +
            "isFlutterAttached=${VonageClientHolder.isFlutterEngineAttached}, " +
            "isSessionReady=${VonageClientHolder.isSessionReady}")

        // ── Expire stale pending invites ───────────────────────────────────
        // If a previous invite got stuck (server cancel lost, network drop),
        // auto-clean it so it doesn't permanently block new calls.
        expireStaleInvites()

        // Skip if we already have a pending invite for this callId (duplicate FCM processing)
        if (pendingInvites.containsKey(callId)) {
            android.util.Log.d("TVConnectionService", "Duplicate incoming call for callId=$callId — skipping")
            return
        }

        // ── Active call guard (single-call only) ──────────────────────────
        // Safety net: if an active call already exists, ignore the 2nd invite.
        // The primary guard is in VonageFirebaseMessagingService, but this
        // protects against race conditions where the FCM check passed but
        // a call was answered between then and now.
        if (activeConnections.isNotEmpty()) {
            android.util.Log.d("TVConnectionService", "Active call exists — ignoring 2nd invite callId=$callId")
            return
        }

        val connection = TVCallInviteConnection(this, callId, from, to)
        pendingInvites[callId] = connection
        pendingInviteTimestamps[callId] = System.currentTimeMillis()

        // ── Schedule self-expiry timer ─────────────────────────────────────
        // If neither the Vonage SDK WebSocket nor an FCM cancel push delivers
        // the remote-hangup signal (e.g. WebSocket killed by doze mode + FCM
        // not delivered on aggressive OEMs), the phone would ring indefinitely.
        // This timer guarantees teardown after PENDING_INVITE_TIMEOUT_MS (60 s)
        // as a last-resort fallback.  Cancelled immediately by handleAnswer(),
        // handleHangup(), and handleCancelCallInvite() on the normal paths.
        scheduleInviteTimeout(callId)

        // ── Persist call metadata to SharedPreferences ────────────────────
        // Mirrors Twilio's pending_incoming_call_sid pattern: write with commit()
        // (synchronous) so the data survives if the OS kills the process between
        // FCM delivery and the user tapping Answer on MIUI/aggressive OEMs.
        // The plugin's reEmitPendingCallState() reads this as a cross-process fallback.
        try {
            getSharedPreferences(PREFS_PENDING_CALL, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PENDING_CALL_ID, callId)
                .putString(KEY_PENDING_CALL_FROM, from)
                .putString(KEY_PENDING_CALL_TO, to)
                .putLong(KEY_PENDING_CALL_TIMESTAMP, System.currentTimeMillis())
                .commit()
            android.util.Log.d("TVConnectionService", "✓ Persisted pending call to SharedPreferences: callId=$callId")
        } catch (e: Exception) {
            android.util.Log.w("TVConnectionService", "Failed to persist pending call: ${e.message}")
        }

        // ── Broadcast REAL_CALL_READY so placeholder activity upgrades ────
        val upgradeIntent = Intent(Constants.BROADCAST_REAL_CALL_READY).apply {
            putExtra(Constants.EXTRA_CALL_ID, callId)
            putExtra(Constants.EXTRA_CALL_FROM, from)
        }
        broadcastManager.sendBroadcast(upgradeIntent)
        android.util.Log.d("TVConnectionService", "[INCOMING-REAL] Sent BROADCAST_REAL_CALL_READY callId=$callId")

        // ── Notification is already posted by ensureForeground() ────────
        // ensureForeground() runs in onStartCommand BEFORE handleIntent(),
        // so the notification with fullScreenIntent is already active.
        // On Samsung A15 / Android 16, the fullScreenIntent is the ONLY
        // reliable way to show an activity over the lock screen.
        // DO NOT post the notification again here — triple-notify causes
        // Samsung to show heads-up instead of full-screen, and may contribute
        // to process crashes from rapid PendingIntent re-fires.

        // ── Now notify Telecom ────────────────────────────────────────────
        // addNewIncomingCall() integrates us with the Android call system
        // (audio routing, Bluetooth, call log, etc). onShowIncomingCallUi()
        // is the secondary UI path — the notification's fullScreenIntent above
        // is primary for lock screen display.
        val flutterCanHandleUi = VonageClientHolder.isFlutterInForeground
                && VonageClientHolder.isEventSinkAttached
        if (!flutterCanHandleUi) {
            android.util.Log.d("TVConnectionService",
                "[INCOMING-REAL] Showing native UI — isFlutterForeground=${VonageClientHolder.isFlutterInForeground}, isEventSinkAttached=${VonageClientHolder.isEventSinkAttached}")
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (telecomManager != null) {
                val componentName = android.content.ComponentName(this, TVConnectionService::class.java)
                val handle = PhoneAccountHandle(componentName, "VonageVoiceAccount")
                try {
                    val existing = telecomManager.getPhoneAccount(handle)
                    if (existing == null || existing.capabilities != android.telecom.PhoneAccount.CAPABILITY_SELF_MANAGED) {
                        telecomManager.registerPhoneAccount(
                            android.telecom.PhoneAccount.builder(handle, "Vonage Voice")
                                .setCapabilities(android.telecom.PhoneAccount.CAPABILITY_SELF_MANAGED)
                                .build()
                        )
                        android.util.Log.d("TVConnectionService", "[INCOMING-REAL] PhoneAccount registered")
                    }
                } catch (e: Exception) {
                    android.util.Log.w("TVConnectionService", "[INCOMING-REAL] ensurePhoneAccount: ${e.message}")
                }

                try {
                    val account = telecomManager.getPhoneAccount(handle)
                    val isEnabled = account?.isEnabled ?: false
                    android.util.Log.d("TVConnectionService",
                        "[INCOMING-REAL] PhoneAccount state: enabled=$isEnabled, " +
                        "capabilities=${account?.capabilities}")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val permitted = telecomManager.isIncomingCallPermitted(handle)
                        android.util.Log.d("TVConnectionService",
                            "[INCOMING-REAL] isIncomingCallPermitted=$permitted")
                        if (!permitted) {
                            android.util.Log.w("TVConnectionService",
                                "[INCOMING-REAL] ⚠ Telecom will REJECT this call — falling back to notification-only")
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("TVConnectionService",
                        "[INCOMING-REAL] PhoneAccount check failed: ${e.message}")
                }

                val extras = android.os.Bundle().apply {
                    putString(Constants.EXTRA_CALL_ID, callId)
                    putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                }
                try {
                    telecomManager.addNewIncomingCall(handle, extras)
                    android.util.Log.d("TVConnectionService", "[INCOMING-REAL] ✓ addNewIncomingCall() succeeded callId=$callId")
                } catch (e: Exception) {
                    android.util.Log.e("TVConnectionService", "[INCOMING-REAL] ✗ addNewIncomingCall() failed: ${e.message} — using notification fallback")
                }
            } else {
                android.util.Log.w("TVConnectionService", "[INCOMING-REAL] TelecomManager not available — notification-only path")
            }
        } else {
            android.util.Log.d("TVConnectionService", "[INCOMING-REAL] Flutter in foreground with active EventSink — skipping native UI")
        }

        // Acquire wake lock to ensure the screen turns on for incoming call
        if (incomingCallWakeLock == null) {
            acquireIncomingCallWakeLock()
        }

        // ── Start ringing AFTER Telecom has accepted the call ─────────────
        startServiceRinging()

        android.util.Log.i("TVConnectionService",
            "[INCOMING-REAL] ✓ handleIncomingCall complete: callId=$callId, pendingInvites=${pendingInvites.size}")

        val broadcast = Intent(Constants.BROADCAST_CALL_INVITE).apply {
            putExtra(Constants.EXTRA_CALL_ID, callId)
            putExtra(Constants.EXTRA_CALL_FROM, from)
            putExtra(Constants.EXTRA_CALL_TO, to)
        }
        broadcastManager.sendBroadcast(broadcast)
    }

    /**
     * Called when the remote party cancels the invite before it is answered.
     * Cleans up the pending invite connection and notifies Flutter.
     *
     * Intent extras required:
     *   EXTRA_CALL_ID — callId of the invite to cancel
     */
    private fun handleCancelCallInvite(intent: Intent) {
        val callId = intent.getStringExtra(Constants.EXTRA_CALL_ID) ?: return

        // Cancel the per-invite timeout so it doesn't double-fire
        cancelInviteTimeout(callId)

        // Stop ringing — call is being cancelled
        stopServiceRinging()

        // Release the incoming call wake lock — call is being cancelled
        releaseIncomingCallWakeLock()

        // Clear the persisted pending call data — caller cancelled the invite
        clearPendingCallData(this)
        incomingNotificationPosted = false

        // cancel() internally broadcasts BROADCAST_CALL_INVITE_CANCELLED
        // Wrap in try-catch: Connection.setDisconnected() can throw
        // UnsupportedOperationException on double-cancel (rare race between
        // setCallInviteCancelListener and setOnCallHangupListener firing together).
        // Without this guard, the exception would skip pendingInvites.remove(),
        // leaving the map non-empty and blocking all future calls.
        val invite = pendingInvites[callId]
        try {
            invite?.cancel()
        } catch (e: Exception) {
            android.util.Log.e("TVConnectionService",
                "handleCancelCallInvite: cancel() threw for callId=$callId: ${e.message}")
            // cancel() failed before it could broadcast INVITE_CANCELLED.
            // Broadcast manually so Flutter still gets the missed-call event.
            if (invite != null) {
                val fallback = Intent(Constants.BROADCAST_CALL_INVITE_CANCELLED).apply {
                    putExtra(Constants.EXTRA_CALL_ID, callId)
                }
                broadcastManager.sendBroadcast(fallback)
            }
        }
        pendingInvites.remove(callId)
        pendingInviteTimestamps.remove(callId)

        if (activeConnections.isEmpty() && pendingInvites.isEmpty()) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager
            notificationManager.cancel(Constants.NOTIFICATION_ID)
            stopForeground(true)
            stopSelf()
        }
    }

    // ── Outgoing call handling ────────────────────────────────────────────

    /**
     * Called from VonageVoicePlugin.makeCall() to place an outbound call.
     * Creates a [TVCallConnection] and starts the Vonage SDK serverCall().
     *
     * Intent extras required:
     *   EXTRA_JWT             — Vonage JWT (passed from Flutter, never hardcoded)
     *   EXTRA_CALL_TO         — destination number / user
     *   EXTRA_CALL_FROM       — caller identity
     *   EXTRA_OUTGOING_PARAMS — optional HashMap<String,String> custom params
     */
    private fun handleOutgoingCall(intent: Intent) {
        // All call params come from Flutter via Intent extras — nothing hardcoded
        val callTo = intent.getStringExtra(Constants.EXTRA_CALL_TO) ?: return
        val callFrom = intent.getStringExtra(Constants.EXTRA_CALL_FROM) ?: ""

        @Suppress("UNCHECKED_CAST")
        val customParams = intent.getSerializableExtra(Constants.EXTRA_OUTGOING_PARAMS)
                as? HashMap<String, String> ?: HashMap()

        // Build context map for Vonage serverCall()
        // Backend expects capitalized keys: "To" and "From"
        val callContext = HashMap<String, String>().apply {
            put("To", callTo)
            if (callFrom.isNotEmpty()) put("From", callFrom)
            putAll(customParams)
        }

        // Get VoiceClient instance held by the plugin
        val client = VonageClientHolder.voiceClient ?: run {
            broadcastError("VoiceClient not initialised — call tokens() first")
            return
        }

        // Place the call via Vonage SDK
        client.serverCall(callContext) { error, callId ->
            if (error != null) {
                broadcastError("serverCall failed: ${error.message}")
                return@serverCall
            }

            if (callId.isNullOrEmpty()) {
                broadcastError("serverCall returned null callId")
                return@serverCall
            }

            // Create Telecom connection for this outgoing call
            val connection = TVCallConnection(this, callId, callFrom, callTo)
            activeConnections[callId] = connection
            // SDK LIMITATION: serverCall() provides no callee-answered callback.
            // setCallActive() is called here at call-placement ack time, which is
            // earlier than the true callee-answered moment. This is the only
            // available transition point in the Vonage SDK's public API surface.
            // setDialing() is intentionally omitted for the same reason.
            connection.setCallActive()
            setCallActive(this, true)
            requestAudioFocus()
            // Oppo ColorOS (and Realme) reset AudioManager.mode to MODE_NORMAL
            // when the screen locks, even during an active outgoing call.
            // The screen receiver re-applies MODE_IN_COMMUNICATION on screen-off/on
            // so the WebRTC mic capture path is never silenced.
            registerScreenOnReceiver("OUTGOING")

            startCallForegroundService()

            // Broadcast ringing event to Flutter
            val broadcast = Intent(Constants.BROADCAST_CALL_RINGING).apply {
                putExtra(Constants.EXTRA_CALL_ID, callId)
                putExtra(Constants.EXTRA_CALL_FROM, callFrom)
                putExtra(Constants.EXTRA_CALL_TO, callTo)
            }
            broadcastManager.sendBroadcast(broadcast)

            // Broadcast connected event to Flutter (outgoing direction)
            val connectedBroadcast = Intent(Constants.BROADCAST_CALL_CONNECTED).apply {
                putExtra(Constants.EXTRA_CALL_ID, callId)
                putExtra(Constants.EXTRA_CALL_FROM, callFrom)
                putExtra(Constants.EXTRA_CALL_TO, callTo)
                putExtra(Constants.EXTRA_CALL_DIRECTION, "OUTGOING")
            }
            broadcastManager.sendBroadcast(connectedBroadcast)
            android.util.Log.i("TVConnectionService",
                "[OUTGOING] CALL_CONNECTED broadcast sent: callId=$callId")
        }
    }

    // ── Answer ────────────────────────────────────────────────────────────

    /**
     * Answer a pending incoming call invite.
     * Moves the connection from pendingInvites → activeConnections.
     *
     * Intent extras required:
     *   EXTRA_CALL_ID — callId of the invite to answer
     */

     private fun requestAudioFocus(preserveMute: Boolean = false, preserveSpeaker: Boolean = false) {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                lastAudioFocusRequestTime = SystemClock.elapsedRealtime()
                cancelPendingAudioFocusRestore()
                val modeBefore = audioManager.mode
                val muteBefore = audioManager.isMicrophoneMute
                android.util.Log.d("TVConnectionService",
                    "[REQ-AUDIO-FOCUS] called | modeBefore=$modeBefore | micMute=$muteBefore | " +
                    "sdkInt=${Build.VERSION.SDK_INT} | activeConns=${activeConnections.size}")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .setAcceptsDelayedFocusGain(true)
                        .setOnAudioFocusChangeListener(audioFocusListener)
                        .build()
                    audioFocusRequest = focusRequest
                    val result = audioManager.requestAudioFocus(focusRequest)
                    hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                    android.util.Log.d("TVConnectionService",
                        "[AUDIO-FOCUS] requestAudioFocus: result=$result, hasAudioFocus=$hasAudioFocus")
                } else {
                    @Suppress("DEPRECATION")
                    val result = audioManager.requestAudioFocus(
                        audioFocusListener,
                        AudioManager.STREAM_VOICE_CALL,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                    )
                    hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                }

                // Set audio mode to voice call — critical for microphone to work
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                // preserveSpeaker: when recovering from screen lock, preserve the user's
                // intentional speaker state. Only reset speaker for new calls (default false).
                if (!preserveSpeaker) {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = false
                }
                // preserveMute is read by callers BEFORE any OS-level resets can occur.
                // We use it here rather than re-reading am.isMicrophoneMute (which may
                // have already been reset by the OS between the caller's read and now).
                if (!preserveMute) {
                    audioManager.isMicrophoneMute = false
                }
                val modeAfter = audioManager.mode
                android.util.Log.d("TVConnectionService",
                    "[REQ-AUDIO-FOCUS] mode set: before=$modeBefore after=$modeAfter " +
                    "stuck=${modeAfter == AudioManager.MODE_IN_COMMUNICATION} | " +
                    "micMute=${audioManager.isMicrophoneMute} preserveMute=$preserveMute preserveSpeaker=$preserveSpeaker")

                // Broadcast speaker state: preserve on recovery, off for new calls.
                // Broadcast the caller-supplied mute state (not always false) to preserve
                // the user's intentional mute across audio focus recovery events.
                val speakerBroadcast = Intent(Constants.BROADCAST_SPEAKER_STATE).apply {
                    putExtra("state", preserveSpeaker)
                }
                broadcastManager.sendBroadcast(speakerBroadcast)

                val muteBroadcast = Intent(Constants.BROADCAST_MUTE_STATE).apply {
                    putExtra("state", preserveMute)
                }
                broadcastManager.sendBroadcast(muteBroadcast)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val devices = audioManager.availableCommunicationDevices
                    if (preserveSpeaker) {
                        // Recovery path: route back to speaker
                        val speakerDevice = devices.firstOrNull {
                            it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                        }
                        if (speakerDevice != null) {
                            audioManager.setCommunicationDevice(speakerDevice)
                        } else {
                            @Suppress("DEPRECATION")
                            audioManager.isSpeakerphoneOn = true
                        }
                    } else {
                        // New call or non-speaker recovery: prefer BT if connected, otherwise earpiece
                        val bluetoothDevice = devices.firstOrNull {
                            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
                        }
                        val targetDevice = bluetoothDevice
                            ?: devices.firstOrNull {
                                it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                            }
                        targetDevice?.let { audioManager.setCommunicationDevice(it) }

                        // Broadcast BT state so Flutter UI updates immediately
                        if (bluetoothDevice != null) {
                            val btBroadcast = Intent(Constants.BROADCAST_BLUETOOTH_STATE).apply {
                                putExtra("state", true)
                            }
                            broadcastManager.sendBroadcast(btBroadcast)
                        }
                    }
                } else {
                    if (preserveSpeaker) {
                        // Recovery path: re-apply speaker (pre-API 31)
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = true
                    } else {
                        // Pre-API 31 — only route to BT if a device is actually connected
                        val btOutputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                        val hasBtDevice = btOutputs.any {
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        }
                        if (hasBtDevice && audioManager.isBluetoothScoAvailableOffCall) {
                            audioManager.startBluetoothSco()
                            audioManager.isBluetoothScoOn = true
                            // Actual BT connected state is confirmed via SCO state listener
                        }
                    }
                }

                registerBluetoothMonitor()
    }

    private fun scheduleAudioFocusRestore() {
        cancelPendingAudioFocusRestore()
        pendingAudioFocusRestore = Runnable {
            if (activeConnections.isNotEmpty()) {
                android.util.Log.d("TVConnectionService",
                    "[AUDIO-FOCUS] Restoring audio focus after transient loss")
                // Preserve mute and speaker state across transient audio focus loss/gain cycle.
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val muteState = am.isMicrophoneMute ||
                    (activeConnections.values.firstOrNull()?.isMuted == true)
                val hwSpeaker = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    am.communicationDevice?.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                } else {
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn
                }
                val speakerState = hwSpeaker || (activeConnections.values.firstOrNull()?.isSpeakerOn == true)
                requestAudioFocus(preserveMute = muteState, preserveSpeaker = speakerState)
            }
        }
        restoreAudioFocusHandler.postDelayed(pendingAudioFocusRestore!!, AUDIO_FOCUS_RESTORE_DELAY_MS)
    }

    private fun cancelPendingAudioFocusRestore() {
        pendingAudioFocusRestore?.let { restoreAudioFocusHandler.removeCallbacks(it) }
        pendingAudioFocusRestore = null
    }

    private fun releaseAudioFocus() {
        unregisterBluetoothMonitor()
        unregisterScreenOnReceiver()
        cancelPendingAudioFocusRestore()
        hasAudioFocus = false
        wasAutoMutedByFocusLoss = false
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusListener)
        }

        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
        audioManager.isMicrophoneMute = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            // Stop Bluetooth SCO if it was started
            if (audioManager.isBluetoothScoOn) {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            }
        }
    }


    // ── Bluetooth device monitoring ──────────────────────────────────────

    /**
     * Registers an AudioDeviceCallback to detect Bluetooth device
     * connections and disconnections during an active call.
     *
     * - When a BT device connects: auto-route audio to it (like native phone app)
     * - When a BT device disconnects: fall back to earpiece and notify Flutter
     */
    private fun registerBluetoothMonitor() {
        if (audioDeviceCallback != null) return
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                if (activeConnections.isEmpty()) return

                val btAdded = addedDevices.any { isBluetoothAudioDevice(it.type) }
                if (!btAdded) return

                // Check if already on BT — skip if so (deduplicate with onCallAudioStateChanged)
                val conn = activeConnections.values.firstOrNull()
                if (conn?.isBluetoothOn == true) return

                // Auto-route to BT like the native phone app
                audioManager.isSpeakerphoneOn = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val devices = audioManager.availableCommunicationDevices
                    val btDevice = devices.firstOrNull { isBluetoothAudioDevice(it.type) }
                    btDevice?.let { audioManager.setCommunicationDevice(it) }
                } else {
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                }

                // Update connection state
                conn?.let {
                    it.isBluetoothOn = true
                    if (it.isSpeakerOn) {
                        it.isSpeakerOn = false
                    }
                }

                val broadcast = Intent(Constants.BROADCAST_BLUETOOTH_STATE).apply {
                    putExtra("state", true)
                }
                broadcastManager.sendBroadcast(broadcast)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                if (activeConnections.isEmpty()) return

                val btRemoved = removedDevices.any { isBluetoothAudioDevice(it.type) }
                if (!btRemoved) return

                // Check if already off BT — skip if so (deduplicate)
                val conn = activeConnections.values.firstOrNull()
                if (conn?.isBluetoothOn == false) return

                // Fall back to earpiece
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val devices = audioManager.availableCommunicationDevices
                    val earpiece = devices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    }
                    earpiece?.let { audioManager.setCommunicationDevice(it) }
                } else {
                    audioManager.stopBluetoothSco()
                    audioManager.isBluetoothScoOn = false
                }

                // Update connection state
                conn?.let { it.isBluetoothOn = false }

                val broadcast = Intent(Constants.BROADCAST_BLUETOOTH_STATE).apply {
                    putExtra("state", false)
                }
                broadcastManager.sendBroadcast(broadcast)
            }
        }

        audioDeviceCallback = callback
        audioManager.registerAudioDeviceCallback(callback, null)

        // Pre-API 31: listen for SCO audio state changes to confirm BT link
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && scoReceiver == null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return
                    val state = intent.getIntExtra(
                        AudioManager.EXTRA_SCO_AUDIO_STATE,
                        AudioManager.SCO_AUDIO_STATE_DISCONNECTED
                    )
                    val conn = activeConnections.values.firstOrNull() ?: return
                    when (state) {
                        AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                            if (!conn.isBluetoothOn) {
                                conn.isBluetoothOn = true
                                val bc = Intent(Constants.BROADCAST_BLUETOOTH_STATE).apply {
                                    putExtra("state", true)
                                }
                                broadcastManager.sendBroadcast(bc)
                            }
                        }
                        AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                            if (conn.isBluetoothOn) {
                                conn.isBluetoothOn = false
                                val bc = Intent(Constants.BROADCAST_BLUETOOTH_STATE).apply {
                                    putExtra("state", false)
                                }
                                broadcastManager.sendBroadcast(bc)
                            }
                        }
                    }
                }
            }
            scoReceiver = receiver
            val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            registerReceiver(receiver, filter)
        }
    }

    private fun unregisterBluetoothMonitor() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioDeviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        audioDeviceCallback = null

        scoReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        scoReceiver = null
    }

    // ── OEM lock-screen audio fix ─────────────────────────────────────────

    /**
     * Registers a broadcast receiver for ACTION_SCREEN_ON and ACTION_USER_PRESENT.
     *
     * OPPO ColorOS (and some Realme devices) do not reliably fire AUDIOFOCUS_GAIN
     * after the screen turns on during an active call. Without this, MODE_IN_COMMUNICATION
     * stays reset (OEM set it to MODE_NORMAL while screen was off), causing:
     *   - Local can't hear remote (earpiece not in voice mode)
     *   - Remote can't hear local (WebRTC ADM mic capture path inactive)
     *
     * ACTION_USER_PRESENT fires after PIN/pattern unlock — the safest re-apply point.
     * Guard: no-op when no active connections (e.g., between calls).
     */
    private fun registerScreenOnReceiver(direction: String = "UNKNOWN") {
        if (screenOnReceiver != null) {
            android.util.Log.d("TVConnectionService",
                "[SCREEN-RECV] already registered — skipping (direction=$direction)")
            return
        }
        android.util.Log.d("TVConnectionService",
            "[SCREEN-RECV] registering for SCREEN_OFF/ON/USER_PRESENT (direction=$direction)")
        screenOnReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (activeConnections.isEmpty()) {
                    android.util.Log.d("TVConnectionService",
                        "[SCREEN-RECV] ${intent.action} — no active connections, ignoring")
                    return
                }
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val modeBefore = am.mode
                val muteBefore = am.isMicrophoneMute
                val hasFocusBefore = hasAudioFocus
                android.util.Log.d("TVConnectionService",
                    "[SCREEN-RECV] action=${intent.action} | " +
                    "modeBefore=$modeBefore (expected=${AudioManager.MODE_IN_COMMUNICATION}) | " +
                    "micMute=$muteBefore | hasAudioFocus=$hasFocusBefore | " +
                    "activeConnections=${activeConnections.size} | " +
                    "sdkInt=${Build.VERSION.SDK_INT}")
                // Read mic mute BEFORE requestAudioFocus — am.isMicrophoneMute is the
                // hardware ground truth. conn.isMuted may not yet be set if the Vonage
                // SDK callback hasn't fired (race: SDK sets hardware mute synchronously
                // but TVCallConnection.setMuted runs in an async callback).
                val muteBeforeRecovery = am.isMicrophoneMute
                // Read speaker state BEFORE requestAudioFocus (same race-condition logic).
                // On API 31+, check communication device type for ground truth.
                val speakerBeforeRecovery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    am.communicationDevice?.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                } else {
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn
                }
                wasAutoMutedByFocusLoss = false
                requestAudioFocus(preserveMute = muteBeforeRecovery, preserveSpeaker = speakerBeforeRecovery)
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                // Re-apply hardware mute if needed — the OS may have reset
                // am.isMicrophoneMute during the audio focus request on API 36+.
                if (muteBeforeRecovery) {
                    am.isMicrophoneMute = true
                }
                // Re-apply speaker if needed — some OEMs reset communication device
                // during requestAudioFocus even when we explicitly set it inside.
                if (speakerBeforeRecovery) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val spkDev = am.availableCommunicationDevices
                            .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        if (spkDev != null) am.setCommunicationDevice(spkDev)
                        else {
                            @Suppress("DEPRECATION")
                            am.isSpeakerphoneOn = true
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        am.isSpeakerphoneOn = true
                    }
                }
                // Re-read to verify the mode actually stuck (some OEMs silently reject setMode)
                val modeAfter = am.mode
                val muteAfter = am.isMicrophoneMute
                android.util.Log.d("TVConnectionService",
                    "[SCREEN-RECV] after fix: mode=$modeAfter (stuck=${modeAfter == AudioManager.MODE_IN_COMMUNICATION}) | " +
                    "micMute=$muteAfter | speaker=$speakerBeforeRecovery | hasAudioFocus=$hasAudioFocus")
                if (modeAfter != AudioManager.MODE_IN_COMMUNICATION) {
                    android.util.Log.e("TVConnectionService",
                        "[SCREEN-RECV] *** MODE DID NOT STICK — OEM rejected setMode! " +
                        "mode is still $modeAfter on sdk=${Build.VERSION.SDK_INT} ***")
                }
            }
        }
        registerReceiver(screenOnReceiver, IntentFilter().apply {
            // ACTION_SCREEN_OFF: ColorOS/FuntouchOS resets MODE_IN_COMMUNICATION at
            // screen-off time. Re-applying immediately prevents any gap in mic capture.
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
    }

    private fun unregisterScreenOnReceiver() {
        screenOnReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        screenOnReceiver = null
    }

    private fun isBluetoothAudioDevice(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
               (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                type == AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    // ── Per-invite timeout helpers ────────────────────────────────────────

    /**
     * Schedule an auto-cancel for [callId] after [PENDING_INVITE_TIMEOUT_MS].
     * The timer is cancelled immediately when the call is answered, rejected,
     * or cancelled normally — it only fires in the stuck-invite failure case
     * (WebSocket drop during SDK reconnect, etc.).
     */
    private fun scheduleInviteTimeout(callId: String) {
        val runnable = Runnable {
            if (pendingInvites.containsKey(callId)) {
                android.util.Log.w("TVConnectionService",
                    "[INVITE-TIMEOUT] Auto-cancelling stuck invite after ${PENDING_INVITE_TIMEOUT_MS}ms: callId=$callId")
                handleCancelCallInvite(Intent().apply {
                    putExtra(Constants.EXTRA_CALL_ID, callId)
                })
            }
            inviteTimeoutRunnables.remove(callId)
        }
        inviteTimeoutRunnables[callId] = runnable
        inviteTimeoutHandler.postDelayed(runnable, PENDING_INVITE_TIMEOUT_MS)
        android.util.Log.d("TVConnectionService",
            "[INVITE-TIMEOUT] Scheduled auto-cancel in ${PENDING_INVITE_TIMEOUT_MS}ms for callId=$callId")
    }

    /**
     * Cancel the pending auto-cancel timer for [callId].
     * Called from handleAnswer(), handleHangup(), and handleCancelCallInvite()
     * so the timer never double-fires when the call ends normally.
     */
    private fun cancelInviteTimeout(callId: String) {
        inviteTimeoutRunnables.remove(callId)?.let {
            inviteTimeoutHandler.removeCallbacks(it)
            android.util.Log.d("TVConnectionService",
                "[INVITE-TIMEOUT] Cancelled timer for callId=$callId")
        }
    }

    private fun handleAnswer(intent: Intent) {
        val callId = intent.getStringExtra(Constants.EXTRA_CALL_ID) ?: return

        // Cancel the per-invite timeout — the call is being answered normally
        cancelInviteTimeout(callId)

        // Idempotency guard — prevents double-answering when both
        // IncomingCallActivity and Connection.onAnswer() race to answer.
        if (VonageClientHolder.isAnsweringInProgress) {
            android.util.Log.d("TVConnectionService",
                "handleAnswer: Already answering in progress — skipping duplicate for callId=$callId")
            return
        }
        if (VonageClientHolder.isCallAnsweredNatively) {
            android.util.Log.d("TVConnectionService",
                "handleAnswer: Call already answered natively — skipping duplicate for callId=$callId")
            return
        }

        // Stop ringing — call is being answered
        stopServiceRinging()

        // Release the incoming call wake lock — call is being answered
        releaseIncomingCallWakeLock()

        // Clear the persisted pending call data — call is being answered
        clearPendingCallData(this)
        VonageFirebaseMessagingService.clearPendingFcmData(this)

        val client = VonageClientHolder.voiceClient ?: run {
            broadcastError("VoiceClient not initialised")
            return
        }

        doAnswer(client, callId, retriesLeft = 2)
    }

    /**
     * Attempt to answer the call. If it fails (e.g. session not yet
     * restored after app-kill), retry up to [retriesLeft] times with
     * a 1.5-second delay. This covers the race between
     * VonageFirebaseMessagingService.createSession() and the user
     * tapping Answer very quickly.
     */
    private fun doAnswer(client: VoiceClient, callId: String, retriesLeft: Int) {
        VonageClientHolder.isAnsweringInProgress = true
        client.answer(callId) { error ->
            if (error != null) {
                android.util.Log.e("TVConnectionService",
                    "answer failed (retries=$retriesLeft): ${error.message}")
                if (retriesLeft > 0) {
                    android.util.Log.d("TVConnectionService",
                        "Retrying answer in 1500ms...")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        doAnswer(client, callId, retriesLeft - 1)
                    }, 1500)
                } else {
                    VonageClientHolder.isAnsweringInProgress = false
                    broadcastError("answer failed: ${error.message}")

                    // Clean up stale invite — the call is dead on the server
                    val inviteConn = pendingInvites.remove(callId)
                    pendingInviteTimestamps.remove(callId)
                    inviteConn?.setDisconnected(android.telecom.DisconnectCause(
                        android.telecom.DisconnectCause.ERROR, "Answer failed"))
                    inviteConn?.destroy()

                    // Dismiss IncomingCallActivity (it listens for INVITE_CANCELLED)
                    val cancelBroadcast = Intent(Constants.BROADCAST_CALL_INVITE_CANCELLED).apply {
                        putExtra(Constants.EXTRA_CALL_ID, callId)
                    }
                    broadcastManager.sendBroadcast(cancelBroadcast)

                    // Cancel notification and stop service
                    handleCleanup()
                }
                return@answer
            }

            // Mark that the call was answered via the native path so that
            // registerVoiceClientListeners() (called when Flutter starts)
            // does not overwrite the setCallInviteListener and interfere
            // with this already-answered call.
            VonageClientHolder.isAnsweringInProgress = false
            VonageClientHolder.isCallAnsweredNatively = true

            // Promote invite connection to active connection
            val invite = pendingInvites.remove(callId)
            pendingInviteTimestamps.remove(callId)

            // Persist answered call to SharedPreferences so Flutter can restore
            // the active call screen even if the process is killed between the
            // lock-screen answer and the user unlocking (MIUI/Samsung OEM trims).
            // Mirrors the PREFS_PENDING_CALL pattern used for incoming-call recovery.
            persistAnsweredCallData(this, callId, invite?.from, invite?.to)

            // Transition the invite connection to ACTIVE before disconnecting it.
            // Telecom (and OPPO ColorOS / OEM system call overlays) tracks the
            // connection state machine. Going RINGING → ACTIVE tells the system
            // "the call was answered", whereas RINGING → DISCONNECTED looks like
            // a rejection/cancellation, leaving the OPPO incoming-call overlay
            // visible even after the call is Connected in Flutter.
            // Sequence: RINGING → ACTIVE → DISCONNECTED(LOCAL) → destroy()
            invite?.setActive()

            // Cleanly disconnect the invite connection from the Telecom framework.
            // This was returned from onCreateIncomingConnection() so Telecom tracks it.
            // We must destroy it before the new active connection takes over.
            invite?.setDisconnected(android.telecom.DisconnectCause(
                android.telecom.DisconnectCause.LOCAL, "Promoted to active call"))
            invite?.destroy()

            val connection = TVCallConnection(
                this,
                callId,
                invite?.from ?: "",
                invite?.to ?: ""
            )
            activeConnections[callId] = connection
            connection.setCallActive()
            setCallActive(this, true)
            requestAudioFocus()
            // Upgrade the foreground service type from PHONE_CALL → MICROPHONE|PHONE_CALL.
            // ensureForeground() (called at ACTION_ANSWER) uses PHONE_CALL only.
            // Vivo FuntouchOS and Oppo ColorOS enforce that AudioRecord is suspended
            // during screen lock unless FOREGROUND_SERVICE_TYPE_MICROPHONE is declared.
            // Without this upgrade, the mic silently freezes when the screen turns off.
            startCallForegroundService()
            registerScreenOnReceiver("INCOMING")

            // ── Switch notification from incoming → active call ───────────────────
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager
            notificationManager.notify(Constants.NOTIFICATION_ID, buildActiveCallNotification())

            // Broadcast connected event to Flutter
            val broadcast = Intent(Constants.BROADCAST_CALL_CONNECTED).apply {
                putExtra(Constants.EXTRA_CALL_ID, callId)
                putExtra(Constants.EXTRA_CALL_FROM, invite?.from ?: "")
                putExtra(Constants.EXTRA_CALL_TO, invite?.to ?: "")
                putExtra(Constants.EXTRA_CALL_DIRECTION, "INCOMING")
            }
            broadcastManager.sendBroadcast(broadcast)

            // Broadcast CALL_ANSWERED so IncomingCallActivity (if alive) can
            // dismiss itself. This handles the case where the call is answered
            // externally (BT headset button, notification action) while the
            // full-screen IncomingCallActivity is still showing.
            val answeredBroadcast = Intent(Constants.BROADCAST_CALL_ANSWERED).apply {
                putExtra(Constants.EXTRA_CALL_ID, callId)
            }
            broadcastManager.sendBroadcast(answeredBroadcast)
        }
    }

    // ── Hangup ────────────────────────────────────────────────────────────

    /**
     * Hang up or reject a call.
     * Works for both active calls and pending invites.
     *
     * Intent extras required:
     *   EXTRA_CALL_ID — callId to hang up
     */
    private fun handleHangup(intent: Intent) {
        val callId = intent.getStringExtra(Constants.EXTRA_CALL_ID) ?: return

        // Cancel the per-invite timeout — the call is being declined/ended normally
        cancelInviteTimeout(callId)

        // Stop ringing — call is being declined/ended
        stopServiceRinging()

        // Release the incoming call wake lock — call is being declined/ended
        releaseIncomingCallWakeLock()

        // Clear both the pending invite data and the answered call data
        clearPendingCallData(this)
        clearAnsweredCallData(this)
        setCallActive(this, false)
        VonageFirebaseMessagingService.clearPendingFcmData(this)
        // Reset so the NEXT incoming call's fullScreenIntent fires in ensureForeground().
        // handleCancelCallInvite() and handleCleanup() also reset this; handleHangup()
        // covers the receiver-declined path (onReject / user taps Decline on notification).
        incomingNotificationPosted = false


        val client = VonageClientHolder.voiceClient ?: run {
            broadcastError("VoiceClient not initialised")
            return
        }

        // Check if this is a pending invite (reject) or active call (hangup)
        if (pendingInvites.containsKey(callId)) {
            // Remove from pendingInvites immediately so cleanup runs correctly
            val inviteConnection = pendingInvites.remove(callId)
            pendingInviteTimestamps.remove(callId)

            // Disconnect the invite connection from the Telecom framework
            inviteConnection?.setDisconnected(android.telecom.DisconnectCause(
                android.telecom.DisconnectCause.REJECTED))
            inviteConnection?.destroy()

            // Broadcast CALL_INVITE_CANCELLED so IncomingCallActivity dismisses
            val cancelBroadcast = Intent(Constants.BROADCAST_CALL_INVITE_CANCELLED).apply {
                putExtra(Constants.EXTRA_CALL_ID, callId)
            }
            broadcastManager.sendBroadcast(cancelBroadcast)

            client.reject(callId) { error ->
                if (error != null) {
                    broadcastError("reject failed: ${error.message}")
                    return@reject
                }
                broadcastCallEnded(callId)
            }
        } else {
            client.hangup(callId) { error ->
                if (error != null) {
                    broadcastError("hangup failed: ${error.message}")
                    return@hangup
                }
                broadcastCallEnded(callId)
            }
        }

        // Clean up connection
        activeConnections[callId]?.disconnect()
        activeConnections.remove(callId)

        // Clear the static pendingAnsweredCallData so MainActivity.onResume()
        // does NOT navigate to a dead-call screen after remote hangup on lock screen.
        IncomingCallActivity.pendingAnsweredCallData = null

        // Reset "answered natively" flag so the next call's invite listener works.
        VonageClientHolder.isCallAnsweredNatively = false
        VonageClientHolder.isAnsweringInProgress = false

        // Always cancel the notification and stop the service when no calls remain
        if (activeConnections.isEmpty() && pendingInvites.isEmpty()) {
            releaseAudioFocus()
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager
            notificationManager.cancel(Constants.NOTIFICATION_ID)
            stopForeground(true)
            stopSelf()
        }
    }

    // ── Cleanup (remote hangup / external) ──────────────────────────────

    /**
     * Clean up notification and stop foreground service when no calls remain.
     * Called by VonageVoicePlugin when the SDK reports a remote hangup
     * and the local handleHangup() path was never invoked.
     */
    private fun handleCleanup() {
        stopServiceRinging()
        releaseIncomingCallWakeLock()
        // Clear both the pending invite data and the answered call data
        clearPendingCallData(this)
        clearAnsweredCallData(this)
        setCallActive(this, false)
        VonageFirebaseMessagingService.clearPendingFcmData(this)
        incomingNotificationPosted = false

        // Clear the static pendingAnsweredCallData so that MainActivity.onResume()
        // does NOT navigate to a dead-call screen when the user unlocks after a
        // remote hangup that happened while the device was locked.
        IncomingCallActivity.pendingAnsweredCallData = null

        // Reset "answered natively" flag so the NEXT incoming call's invite
        // listener is properly registered in registerVoiceClientListeners().
        VonageClientHolder.isCallAnsweredNatively = false
        VonageClientHolder.isAnsweringInProgress = false

        if (activeConnections.isEmpty() && pendingInvites.isEmpty()) {
            releaseAudioFocus()
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager
            notificationManager.cancel(Constants.NOTIFICATION_ID)
            stopForeground(true)
            stopSelf()
        }
    }

    // ── DTMF ──────────────────────────────────────────────────────────────

    /**
     * Send DTMF tones on the active call.
     *
     * Intent extras required:
     *   EXTRA_CALL_ID — active callId
     *   EXTRA_DIGITS  — digit string e.g. "1234" or "*#"
     */
    private fun handleSendDigits(intent: Intent) {
        val callId = intent.getStringExtra(Constants.EXTRA_CALL_ID) ?: return
        val digits = intent.getStringExtra(Constants.EXTRA_DIGITS) ?: return

        val client = VonageClientHolder.voiceClient ?: return

        client.sendDTMF(callId, digits) { error ->
            if (error != null) broadcastError("sendDTMF failed: ${error.message}")
        }
    }

    // ── Audio routing ─────────────────────────────────────────────────────

    /**
     * Toggle speakerphone on / off.
     *
     * Intent extras required:
     *   EXTRA_CALL_ID      — active callId
     *   EXTRA_SPEAKER_STATE — Boolean true = speaker on
     */
    private fun handleToggleSpeaker(intent: Intent) {
        val callId = intent.getStringExtra(Constants.EXTRA_CALL_ID) ?: return
        val speakerOn = intent.getBooleanExtra(Constants.EXTRA_SPEAKER_STATE, false)
        activeConnections[callId]?.setSpeaker(speakerOn)
    }

    /**
     * Toggle Bluetooth audio routing on / off.
     *
     * Intent extras required:
     *   EXTRA_CALL_ID        — active callId
     *   EXTRA_BLUETOOTH_STATE — Boolean true = Bluetooth on
     */
    private fun handleToggleBluetooth(intent: Intent) {
        val callId = intent.getStringExtra(Constants.EXTRA_CALL_ID) ?: return
        val bluetoothOn = intent.getBooleanExtra(Constants.EXTRA_BLUETOOTH_STATE, false)
        activeConnections[callId]?.setBluetooth(bluetoothOn)
    }

    /**
     * Toggle microphone mute on / off.
     * Also calls Vonage SDK mute/unmute so the server is aware.
     *
     * Intent extras required:
     *   EXTRA_CALL_ID   — active callId
     *   EXTRA_MUTE_STATE — Boolean true = muted
     */
    private fun handleToggleMute(intent: Intent) {
        val callId = intent.getStringExtra(Constants.EXTRA_CALL_ID) ?: return
        val muted = intent.getBooleanExtra(Constants.EXTRA_MUTE_STATE, false)

        val client = VonageClientHolder.voiceClient ?: return

        if (muted) {
            client.mute(callId) { error ->
                if (error != null) {
                    broadcastError("mute failed: ${error.message}")
                    return@mute
                }
                activeConnections[callId]?.setMuted(true)
            }
        } else {
            client.unmute(callId) { error ->
                if (error != null) {
                    broadcastError("unmute failed: ${error.message}")
                    return@unmute
                }
                activeConnections[callId]?.setMuted(false)
            }
        }
    }

    // ── Telecom ConnectionService overrides ───────────────────────────────

    /**
     * Called by the Telecom framework to create an outgoing connection.
     * Required override — returns a minimal connection so Telecom is satisfied.
     * Actual call setup is handled in handleOutgoingCall().
     */
    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        val callId = getActiveCallId() ?: ""
        return activeConnections[callId] ?: TVCallConnection(this, callId, "", "")
    }

    /**
     * Called by the Telecom framework to create an incoming connection.
     * Triggered by addNewIncomingCall() — returns the pending invite connection.
     *
     * The Telecom framework nests the extras we passed to addNewIncomingCall()
     * inside EXTRA_INCOMING_CALL_EXTRAS, so we check both paths.
     */
    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        try {
            // Telecom nests our extras under EXTRA_INCOMING_CALL_EXTRAS
            val callId = request?.extras
                ?.getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS)
                ?.getString(Constants.EXTRA_CALL_ID)
                // Fallback: check top-level extras (some OEMs flatten the bundle)
                ?: request?.extras?.getString(Constants.EXTRA_CALL_ID)
                ?: ""

            android.util.Log.d("TVConnectionService",
                "[TELECOM] onCreateIncomingConnection: callId=$callId, " +
                "pendingInvites=${pendingInvites.keys}, pid=${android.os.Process.myPid()}")

            val connection = pendingInvites[callId]
            if (connection != null) {
                android.util.Log.d("TVConnectionService",
                    "[TELECOM] ✓ Returning pending invite connection for callId=$callId")
                return connection
            }

            // Fallback: if callId lookup failed, return the first pending invite
            // (there should only be one at a time in our single-call model)
            val fallback = pendingInvites.values.firstOrNull()
            if (fallback != null) {
                android.util.Log.w("TVConnectionService",
                    "onCreateIncomingConnection: callId=$callId not found, using fallback=${fallback.callId}")
                return fallback
            }

            // Last resort: create a new connection. This happens when Telecom
            // restarts the process after a crash — pendingInvites is empty.
            android.util.Log.e("TVConnectionService",
                "onCreateIncomingConnection: no pending invites found for callId=$callId " +
                "(pid=${android.os.Process.myPid()}) — creating bare connection")
            return TVCallInviteConnection(this, callId, "", "")
        } catch (e: Exception) {
            android.util.Log.e("TVConnectionService",
                "[TELECOM] onCreateIncomingConnection CRASHED", e)
            // Return a minimal connection to prevent Telecom from timing out
            val safeCallId = request?.extras
                ?.getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS)
                ?.getString(Constants.EXTRA_CALL_ID) ?: ""
            return TVCallInviteConnection(this, safeCallId, "", "")
        }
    }

    // ── Foreground service ────────────────────────────────────────────────

    /**
     * Start a foreground service with a persistent notification so the
     * call can continue when the app is backgrounded.
     *
     * On API 29+ a FOREGROUND_SERVICE_TYPE_MICROPHONE is required.
     * On API 34+ FOREGROUND_SERVICE_MICROPHONE permission is needed
     * (declared in AndroidManifest.xml).
     */
    private fun startCallForegroundService() {
        createNotificationChannel()

        val notification = buildActiveCallNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Declare both MICROPHONE and PHONE_CALL so Vivo FuntouchOS / Oppo ColorOS
            // cannot suspend AudioRecord during screen lock.
            // PHONE_CALL alone is not enough — mic is frozen on screen-off.
            // MICROPHONE alone works too but PHONE_CALL allows CallKit-style routing.
            val serviceType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            android.util.Log.d("TVConnectionService",
                "[FG-SERVICE] startForeground with MICROPHONE|PHONE_CALL (sdkInt=${Build.VERSION.SDK_INT})")
            startForeground(Constants.NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(Constants.NOTIFICATION_ID, notification)
        }
    }

    /**
     * Create the notification channel for active/ongoing calls.
     * Safe to call multiple times — system ignores duplicate registrations.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.NOTIFICATION_CHANNEL_ID,
                Constants.NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows ongoing Vonage voice call status"
                setSound(null, null)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Create a high-importance notification channel for incoming calls.
     * This channel must use IMPORTANCE_HIGH so that:
     *   - fullScreenIntent fires on lock screen / when app is not visible
     *   - heads-up notification appears when app is in foreground
     * Safe to call multiple times — system ignores duplicate registrations.
     */
    private fun createIncomingCallNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Silent channel — TVConnectionService.startServiceRinging() is the
            // sole source of ringtone and vibration. No sound on the notification
            // channel prevents duplicate/overlapping ringtones.
            val channel = NotificationChannel(
                Constants.INCOMING_CALL_CHANNEL_ID,
                Constants.INCOMING_CALL_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming Vonage voice call alerts"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
                setSound(null, null)
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Build a placeholder notification for the foreground service when we
     * don't yet have a callId (FCM placeholder startup).
     * 
     * CRITICAL: This notification MUST include setFullScreenIntent() so
     * the incoming call screen launches immediately on the lock screen.
     * Without it, the lock screen only shows a small status-bar notification,
     * and many OEMs (Samsung, Xiaomi) won't re-trigger fullScreenIntent
     * when the real notification replaces the placeholder.
     *
     * Answer/Decline buttons are NOT included — the IncomingCallActivity
     * handles the no-callId state by showing the UI with buttons disabled
     * until the real callId arrives via onNewIntent().
     */
    private fun buildPlaceholderIncomingNotification(callerDisplay: String): Notification {
        createIncomingCallNotificationChannel()

        // fullScreenIntent → IncomingCallActivity with placeholder callId
        val fullScreenIntent = IncomingCallActivity.createPlaceholderIntent(applicationContext, callerDisplay)
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            9999, // fixed request code for placeholder
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Decline action — sends ACTION_HANGUP with empty callId.
        // TVConnectionService.handleHangup() will reject the pending invite
        // when the real callId arrives, or stop the service if no invite exists.
        val declineServiceIntent = Intent(applicationContext, TVConnectionService::class.java).apply {
            action = Constants.ACTION_CLEANUP
        }
        val declinePendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                this,
                9998,
                declineServiceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                this,
                9998,
                declineServiceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        // Android 12+: Use native CallStyle for consistent system UI
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val avatarBitmap = createCallerAvatarBitmap(callerDisplay, 64)
            val callerPerson = android.app.Person.Builder()
                .setName(callerDisplay)
                .setIcon(Icon.createWithBitmap(avatarBitmap))
                .setImportant(true)
                .build()

            // Use a dummy answer intent — the real answer goes through
            // IncomingCallActivity once callId is available.
            val dummyAnswerIntent = PendingIntent.getActivity(
                this,
                9997,
                fullScreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val callStyle = Notification.CallStyle.forIncomingCall(
                callerPerson,
                declinePendingIntent,
                dummyAnswerIntent
            )

            return Notification.Builder(this, Constants.INCOMING_CALL_CHANNEL_ID).apply {
                setSmallIcon(android.R.drawable.ic_menu_call)
                setVisibility(Notification.VISIBILITY_PUBLIC)
                setOngoing(true)
                setAutoCancel(false)
                setShowWhen(false)
                setFullScreenIntent(fullScreenPendingIntent, true)
                setContentIntent(fullScreenPendingIntent)
                setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                setCategory(Notification.CATEGORY_CALL)
                setContentTitle(callerDisplay)
                setContentText("Incoming Call")
                setColorized(true)
                setColor(Color.parseColor("#1C1C1E"))
                style = callStyle
            }.build()
        }

        // Pre-Android 12: Simple notification with fullScreenIntent
        return NotificationCompat.Builder(this, Constants.INCOMING_CALL_CHANNEL_ID)
            .setContentTitle(callerDisplay)
            .setContentText("Incoming Call")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentIntent(fullScreenPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_delete,
                    "Decline",
                    declinePendingIntent
                ).build()
            )
            .build()
    }

    /**
     * Build a minimal persistent notification for the foreground service.
     * The app's launch intent is used as the tap action.
     */
    private fun buildIncomingCallNotification(callId: String, from: String): Notification {
        createIncomingCallNotificationChannel()

        // ── fullScreenIntent → IncomingCallActivity (native, works without Flutter) ──
        val fullScreenIntent = IncomingCallActivity.createIntent(applicationContext, callId, from)
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            (callId.hashCode() and 0x7FFFFFFF) % 10000 + 1000,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // ── Answer action → AnswerCallTrampolineActivity (bypasses background start restriction) ──
        val answerTrampolineIntent = Intent(applicationContext, AnswerCallTrampolineActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(AnswerCallTrampolineActivity.EXTRA_CALL_ID, callId)
            putExtra(AnswerCallTrampolineActivity.EXTRA_CALLER_NAME, from)
        }
        val answerPendingIntent = PendingIntent.getActivity(
            this,
            (callId.hashCode() and 0x7FFFFFFF) % 10000 + 2000,
            answerTrampolineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // ── Decline action ────────────────────────────────────────────────────
        val declineServiceIntent = Intent(applicationContext, TVConnectionService::class.java).apply {
            action = Constants.ACTION_HANGUP
            putExtra(Constants.EXTRA_CALL_ID, callId)
        }
        val declinePendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                this,
                (callId.hashCode() and 0x7FFFFFFF) % 10000 + 3000,
                declineServiceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                this,
                (callId.hashCode() and 0x7FFFFFFF) % 10000 + 3000,
                declineServiceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        // Build avatar bitmap for notification
        val avatarBitmap = createCallerAvatarBitmap(from, 64)

        // Android 12+: Use native CallStyle for system-managed UI
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callerPerson = android.app.Person.Builder()
                .setName(from)
                .setIcon(Icon.createWithBitmap(avatarBitmap))
                .setImportant(true)
                .build()

            val callStyle = Notification.CallStyle.forIncomingCall(
                callerPerson,
                declinePendingIntent,
                answerPendingIntent
            )

            return Notification.Builder(this, Constants.INCOMING_CALL_CHANNEL_ID).apply {
                setSmallIcon(android.R.drawable.ic_menu_call)
                setVisibility(Notification.VISIBILITY_PUBLIC)
                setOngoing(true)
                setAutoCancel(false)
                setShowWhen(false)
                setFullScreenIntent(fullScreenPendingIntent, true)
                setContentIntent(fullScreenPendingIntent)
                setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                setCategory(Notification.CATEGORY_CALL)
                setContentTitle(from)
                setContentText("Incoming Call")
                setColorized(true)
                setColor(Color.parseColor("#1C1C1E"))
                style = callStyle
            }.build()
        }

        // Pre-Android 12: Custom RemoteViews
        val callerPerson = Person.Builder()
            .setName(from)
            .setImportant(true)
            .build()

        val customView = RemoteViews(packageName, R.layout.vonage_notification_incoming_call)
        customView.setImageViewBitmap(R.id.notification_avatar, avatarBitmap)
        customView.setTextViewText(R.id.notification_caller_name, from)
        customView.setTextViewText(R.id.notification_caller_number, "Incoming Call")
        customView.setOnClickPendingIntent(R.id.notification_answer_button, answerPendingIntent)
        customView.setOnClickPendingIntent(R.id.notification_decline_button, declinePendingIntent)

        return NotificationCompat.Builder(this, Constants.INCOMING_CALL_CHANNEL_ID)
            .setContentTitle("Incoming Call")
            .setContentText("From: $from")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentIntent(fullScreenPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setCustomContentView(customView)
            .setCustomBigContentView(customView)
            .setCustomHeadsUpContentView(customView)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setOngoing(true)
            .setColorized(true)
            .setColor(Color.parseColor("#1C1C1E"))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addPerson(callerPerson)
            .addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_call,
                    "Answer",
                    answerPendingIntent
                ).build()
            )
            .addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_delete,
                    "Decline",
                    declinePendingIntent
                ).build()
            )
            .build()
    }

    private fun buildActiveCallNotification(): Notification {
        createNotificationChannel()

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // ── Hangup action ─────────────────────────────────────────────────────
        val hangupServiceIntent = Intent(applicationContext, TVConnectionService::class.java).apply {
            action = Constants.ACTION_HANGUP
            putExtra(Constants.EXTRA_CALL_ID, getActiveCallId() ?: "")
        }
        val hangupIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                this,
                3,
                hangupServiceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                this,
                3,
                hangupServiceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        // Get caller name from active connection
        val activeCallId = getActiveCallId() ?: ""
        val callerName = activeConnections[activeCallId]?.from ?: "Unknown"
        val avatarBitmap = createCallerAvatarBitmap(callerName, 64)
        val callStartTime = System.currentTimeMillis() // TODO: track actual call start time

        // Android 12+: Use native CallStyle for system-managed UI with Chronometer
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val caller = android.app.Person.Builder()
                .setName(callerName)
                .setIcon(Icon.createWithBitmap(avatarBitmap))
                .setImportant(true)
                .build()

            return Notification.Builder(this, Constants.NOTIFICATION_CHANNEL_ID).apply {
                setOngoing(true)
                setSmallIcon(android.R.drawable.ic_menu_call)
                setContentIntent(contentIntent)
                setCategory(Notification.CATEGORY_CALL)
                setVisibility(Notification.VISIBILITY_PUBLIC)
                setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                setColorized(true)
                setColor(Color.parseColor("#1C1C1E"))
                style = Notification.CallStyle.forOngoingCall(caller, hangupIntent)
                setUsesChronometer(true)
                setWhen(callStartTime)
            }.build()
        }

        // Pre-Android 12: Custom RemoteViews with Chronometer
        val elapsedBase = SystemClock.elapsedRealtime()

        // Collapsed view
        val collapsedView = RemoteViews(packageName, R.layout.vonage_notification_ongoing_call)
        collapsedView.setImageViewBitmap(R.id.notification_avatar, avatarBitmap)
        collapsedView.setTextViewText(R.id.notification_caller_name, callerName)
        collapsedView.setChronometer(R.id.notification_chronometer, elapsedBase, null, true)
        collapsedView.setOnClickPendingIntent(R.id.notification_btn_hangup, hangupIntent)

        // Expanded view
        val expandedView = RemoteViews(packageName, R.layout.vonage_notification_ongoing_call_expanded)
        expandedView.setImageViewBitmap(R.id.notification_avatar, avatarBitmap)
        expandedView.setTextViewText(R.id.notification_caller_name, callerName)
        expandedView.setChronometer(R.id.notification_chronometer, elapsedBase, null, true)
        expandedView.setOnClickPendingIntent(R.id.notification_btn_hangup, hangupIntent)

        return NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(callerName)
            .setContentText("Ongoing call")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentIntent(contentIntent)
            .setCustomContentView(collapsedView)
            .setCustomBigContentView(expandedView)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setOngoing(true)
            .setSilent(true)
            .setColorized(true)
            .setColor(Color.parseColor("#1C1C1E"))
            .setUsesChronometer(true)
            .setWhen(callStartTime)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_delete,
                    "End Call",
                    hangupIntent
                ).build()
            )
            .build()
    }

    /**
     * Creates a circular avatar bitmap with gradient background and caller initials.
     * Matches the Twilio Easify metallic 3D look for notifications and CallStyle.
     */
    private fun createCallerAvatarBitmap(callerName: String, sizeDp: Int): Bitmap {
        val density = resources.displayMetrics.density
        val sizePx = (sizeDp * density).toInt()
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = sizePx / 2f
        val cy = sizePx / 2f
        val radius = sizePx / 2f

        // Outer ring: subtle gray gradient border
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, sizePx.toFloat(), sizePx.toFloat(),
                Color.parseColor("#5A5A5A"),
                Color.parseColor("#3A3A3A"),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, radius, ringPaint)

        // Inner fill: dark radial gradient for metallic/3D look
        val innerRadius = radius - (2 * density)
        val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx * 0.8f, cy * 0.7f, innerRadius * 1.2f,
                Color.parseColor("#4A4A4A"),
                Color.parseColor("#1A1A1A"),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, innerRadius, innerPaint)

        // Initials text
        val initials = getAvatarInitials(callerName)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = sizePx * 0.35f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(initials, cx, textY, textPaint)

        return bitmap
    }

    private fun getAvatarInitials(name: String): String {
        if (name.isEmpty()) return "?"
        if (name.startsWith("+") || name.all { it.isDigit() || it == ' ' || it == '-' || it == '(' || it == ')' }) {
            return "#"
        }
        val parts = name.trim().split("\\s+".toRegex())
        return when {
            parts.size >= 2 -> "${parts[0].first().uppercaseChar()}${parts[1].first().uppercaseChar()}"
            parts.isNotEmpty() -> parts[0].first().uppercaseChar().toString()
            else -> "?"
        }
    }

    // ── Incoming call wake lock ──────────────────────────────────────────

    /**
     * Acquire a FULL_WAKE_LOCK to physically turn the screen on for incoming calls.
     * This ensures the fullScreenIntent fires and IncomingCallActivity is visible
     * even when the device is locked and screen is off.
     */
    @Suppress("DEPRECATION")
    private fun acquireIncomingCallWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            incomingCallWakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
                "VonageVoice:IncomingCallScreenWakeLock"
            ).apply {
                acquire(60_000L) // 60 seconds for incoming call
            }
            android.util.Log.d("TVConnectionService", "Incoming call FULL_WAKE_LOCK acquired")
        } catch (e: Exception) {
            android.util.Log.e("TVConnectionService", "Failed to acquire incoming call wake lock: ${e.message}")
        }
    }

    private fun releaseIncomingCallWakeLock() {
        try {
            incomingCallWakeLock?.let { if (it.isHeld) it.release() }
            incomingCallWakeLock = null
        } catch (e: Exception) {
            android.util.Log.e("TVConnectionService", "Failed to release incoming call wake lock: ${e.message}")
        }
    }

    // ── Service-level ringtone & vibration ────────────────────────────────

    /**
     * Start playing the default ringtone and vibrating.
     * Called from [handleIncomingCall] so ringing starts immediately
     * regardless of whether IncomingCallActivity manages to launch.
     */
    private var ringtoneAudioFocusRequest: AudioFocusRequest? = null

    private fun startServiceRinging() {
        if (isServiceRinging) return
        isServiceRinging = true

        // ── Request audio focus for ringtone ──────────────────────────────
        // Without audio focus, other apps (music, podcasts, navigation) may
        // play over or duck the ringtone, making it inaudible to the user.
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.mode = AudioManager.MODE_RINGTONE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusAttrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                ringtoneAudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(focusAttrs)
                    .setAcceptsDelayedFocusGain(false)
                    .build()
                audioManager.requestAudioFocus(ringtoneAudioFocusRequest!!)
                android.util.Log.d("TVConnectionService", "Ringtone audio focus requested")
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_RING,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("TVConnectionService", "Error requesting ringtone audio focus: ${e.message}")
        }

        // Play default ringtone
        try {
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            serviceRingtone = RingtoneManager.getRingtone(applicationContext, ringtoneUri)
            serviceRingtone?.let { ring ->
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                ring.audioAttributes = audioAttributes
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ring.isLooping = true
                }
                ring.play()
                android.util.Log.d("TVConnectionService", "Service ringtone started")
            }
        } catch (e: Exception) {
            android.util.Log.e("TVConnectionService", "Error starting service ringtone: ${e.message}")
        }

        // Start vibration
        try {
            serviceVibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            serviceVibrator?.let { vib ->
                if (vib.hasVibrator()) {
                    val pattern = longArrayOf(0, 1000, 1000) // 1s on, 1s off
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vib.vibrate(VibrationEffect.createWaveform(pattern, 0))
                    } else {
                        @Suppress("DEPRECATION")
                        vib.vibrate(pattern, 0)
                    }
                    android.util.Log.d("TVConnectionService", "Service vibration started")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("TVConnectionService", "Error starting service vibration: ${e.message}")
        }
    }

    /**
     * Stop ringtone and vibration. Safe to call multiple times.
     */
    private fun stopServiceRinging() {
        if (!isServiceRinging) return
        isServiceRinging = false

        try {
            serviceRingtone?.let { if (it.isPlaying) it.stop() }
            serviceRingtone = null
        } catch (e: Exception) {
            android.util.Log.e("TVConnectionService", "Error stopping service ringtone: ${e.message}")
        }

        try {
            serviceVibrator?.cancel()
            serviceVibrator = null
        } catch (e: Exception) {
            android.util.Log.e("TVConnectionService", "Error stopping service vibration: ${e.message}")
        }

        // ── Release ringtone audio focus ───────────────────────────────────
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ringtoneAudioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                ringtoneAudioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
            audioManager.mode = AudioManager.MODE_NORMAL
            android.util.Log.d("TVConnectionService", "Ringtone audio focus released")
        } catch (e: Exception) {
            android.util.Log.e("TVConnectionService", "Error releasing ringtone audio focus: ${e.message}")
        }
    }

    // ── Broadcast helpers ─────────────────────────────────────────────────

    private fun broadcastCallEnded(callId: String) {
        // Clear answered call data — the call is fully over
        clearAnsweredCallData(this)
        setCallActive(this, false)
        val intent = Intent(Constants.BROADCAST_CALL_ENDED).apply {
            putExtra(Constants.EXTRA_CALL_ID, callId)
        }
        broadcastManager.sendBroadcast(intent)
    }

    private fun broadcastError(message: String) {
        val intent = Intent(Constants.BROADCAST_CALL_FAILED).apply {
            putExtra("error", message)
        }
        broadcastManager.sendBroadcast(intent)
    }
}

/**
 * VonageClientHolder — singleton holder for the VoiceClient instance.
 *
 * The VoiceClient is created and owned by VonageVoicePlugin but needs
 * to be accessible from TVConnectionService without passing it through
 * Intent extras (it is not Parcelable).
 *
 * Set by VonageVoicePlugin.onAttachedToEngine() and cleared on detach.
 */
object VonageClientHolder {
    var voiceClient: VoiceClient? = null
    /** Caller display name extracted from the last FCM push payload. */
    var pendingCallerDisplay: String? = null

    /** True once createSession() has succeeded on the current VoiceClient. */
    @Volatile
    var isSessionReady: Boolean = false

    /**
     * True when the Flutter engine is attached to VonageVoicePlugin.
     * Set in VonageVoicePlugin.onAttachedToEngine/onDetachedFromEngine.
     */
    @Volatile
    var isFlutterEngineAttached: Boolean = false

    /**
     * True when the Flutter app Activity is in the foreground (resumed/started).
     * Tracked via ProcessLifecycleOwner in VonageVoicePlugin.
     *
     * Key distinction from [isFlutterEngineAttached]:
     *   - Engine attached + foreground → Flutter MAY handle UI (check isEventSinkAttached)
     *   - Engine attached + background → Native screen needed (Flutter can't show UI)
     *   - Engine detached → Native screen needed (app process killed/restarted)
     */
    @Volatile
    var isFlutterInForeground: Boolean = false

    /**
     * True when the Dart side is actively subscribed to the Vonage EventChannel
     * (i.e., onListen() has been called and onCancel() has NOT been called).
     *
     * This is the authoritative signal that Flutter can actually receive call events.
     * [isFlutterInForeground] alone is NOT sufficient — the Flutter Activity can be
     * in the foreground while the EventChannel subscription is null (e.g. the user
     * navigated to a screen that disposed the widget subscribing to the stream).
     *
     * Used in handleIncomingCall() to decide whether to show the native
     * IncomingCallActivity: if Flutter is foreground BUT sink is detached, we
     * MUST show the native screen or the call is completely invisible.
     */
    @Volatile
    var isEventSinkAttached: Boolean = false

    /**
     * Timestamp (millis) of the last FCM push processed by the native
     * VonageFirebaseMessagingService. The Dart fallback path
     * (handleProcessPush) checks this to avoid double-processing.
     */
    @Volatile
    var lastNativePushTimestamp: Long = 0L

    /**
     * True when a call was answered via the native path (notification Answer button
     * or IncomingCallActivity) BEFORE Flutter's engine had attached its listeners.
     * Prevents registerVoiceClientListeners() from re-registering setCallInviteListener
     * which can interfere with the already-answered call on the Vonage SDK.
     * Cleared when the call ends.
     */
    @Volatile
    var isCallAnsweredNatively: Boolean = false

    /**
     * True while doAnswer() is in progress (including retries).
     * Prevents registerVoiceClientListeners() from overwriting SDK listeners
     * mid-answer which could reset the invite state.
     */
    @Volatile
    var isAnsweringInProgress: Boolean = false

    /**
     * Set of callIds already processed by processPushCallInvite().
     * Prevents duplicate processing when both Dart FCM forwarding
     * and VonageFirebaseMessagingService handle the same push.
     */
    private val processedPushCallIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Returns true if the callId was NOT already processed (first caller wins). */
    fun markPushProcessed(callId: String): Boolean {
        val added = processedPushCallIds.add(callId)
        // Auto-clean old entries to prevent unbounded growth
        if (processedPushCallIds.size > 20) {
            val iterator = processedPushCallIds.iterator()
            if (iterator.hasNext()) { iterator.next(); iterator.remove() }
        }
        return added
    }

    private const val PREFS_NAME = "vonage_voice_prefs"
    private const val KEY_JWT = "vonage_jwt"
    private const val KEY_DEVICE_ID = "vonage_device_id"

    /** Persist the JWT so VonageFirebaseMessagingService can restore the session
     *  when the app process was killed. */
    fun storeJwt(context: android.content.Context, jwt: String) {
        context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_JWT, jwt)
            .apply()
    }

    /** Read the stored JWT (may be null if user never logged in). */
    fun getStoredJwt(context: android.content.Context): String? {
        return context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .getString(KEY_JWT, null)
    }

    /** Clear the stored JWT on logout / unregister. */
    fun clearStoredJwt(context: android.content.Context) {
        context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_JWT)
            .apply()
    }

    /** Persist the Vonage device ID so we can unregister it on next install/session. */
    fun storeDeviceId(context: android.content.Context, deviceId: String) {
        context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DEVICE_ID, deviceId)
            .apply()
    }

    /** Read the stored Vonage device ID (may be null). */
    fun getStoredDeviceId(context: android.content.Context): String? {
        return context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .getString(KEY_DEVICE_ID, null)
    }

    /** Clear the stored device ID on unregister. */
    fun clearStoredDeviceId(context: android.content.Context) {
        context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_DEVICE_ID)
            .apply()
    }
}
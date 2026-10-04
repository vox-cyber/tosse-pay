// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Tosse Pay

package com.tossepay.app.managers

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.tossepay.app.R
import com.tossepay.app.TossePayApplication
import com.tossepay.app.constants.AppConstants
import com.tossepay.app.constants.PermissionConstants
import com.tossepay.app.payment.Upi123CallStringBuilder
import com.tossepay.app.payment.messageFor
import com.tossepay.app.telephony.CallStateCoordinator
import com.tossepay.app.telephony.DeviceCallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

enum class CallType {
    USSD, VOICE, UPI123, MANUAL_TRANSFER
}

class CallManager(private val context: Context) {

    companion object {
        private const val TAG = "CallManager"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    private val coordinator: CallStateCoordinator by lazy {
        TossePayApplication.from(context)?.callStateCoordinator ?: CallStateCoordinator(context.applicationContext)
    }
    private var callTrackingJob: Job? = null

    private var originalCallVolume: Int = 0
    private var previousAudioMode: Int = AudioManager.MODE_NORMAL
    private var isAudioMuted = false

    // Call state tracking - thread-safe with proper synchronization
    private var _isCallInProgress = mutableStateOf(false)

    @Volatile
    private var currentCallType: CallType? = null
    private var onCallEndedCallback: ((CallType) -> Unit)? = null
    private var onUssdSessionComplete: (() -> Unit)? = null

    // Synchronization objects for thread safety
    private val audioLock = Any()
    private val callStateLock = Any()
    private val callbackLock = Any()

    private val isUSSDDialPending = AtomicBoolean(false)

    /**
     * Thread-safe callback management
     */
    private fun setCallEndedCallback(callback: ((CallType) -> Unit)?) {
        synchronized(callbackLock) {
            onCallEndedCallback = callback
        }
    }

    private fun setUssdSessionCompleteCallback(callback: (() -> Unit)?) {
        synchronized(callbackLock) {
            onUssdSessionComplete = callback
        }
    }

    private fun getCallEndedCallback(): ((CallType) -> Unit)? {
        synchronized(callbackLock) {
            return onCallEndedCallback
        }
    }

    private fun getUssdSessionCompleteCallback(): (() -> Unit)? {
        synchronized(callbackLock) {
            return onUssdSessionComplete
        }
    }

    private fun clearCallbacks() {
        synchronized(callbackLock) {
            onCallEndedCallback = null
            onUssdSessionComplete = null
        }
    }

    /**
     * Initiates a call - simplified without cycle tracking
     */
    fun initiateCall(
        context: Context,
        phoneNumber: String,
        callType: CallType,
        onCallEnded: (CallType) -> Unit,
        onUssdComplete: (() -> Unit)? = null
    ) {
        // Check permissions using centralized constants
        if (ContextCompat.checkSelfPermission(context, PermissionConstants.CRITICAL_PERMISSIONS[0]) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "CALL_PHONE permission not granted")
            return
        }

        // Handle USSD calls using ACTION_CALL (simplified)
        if (callType == CallType.USSD) {
            handleUSSDCall(phoneNumber, onUssdComplete ?: { onCallEnded(CallType.USSD) })
            return
        }

        // Set up call tracking with thread safety
        synchronized(callStateLock) {
            currentCallType = callType
            setCallEndedCallback(onCallEnded)
            _isCallInProgress.value = true
        }

        coordinator.acquire(TAG)
        callTrackingJob?.cancel()
        callTrackingJob = CoroutineScope(Dispatchers.Main.immediate).launch {
            var hasBeenActive = false
            coordinator.callState.collect { state ->
                Log.d(TAG, "Observed callState: $state (hasBeenActive=$hasBeenActive)")
                when (state) {
                    is DeviceCallState.OffHook, is DeviceCallState.Ringing -> {
                        hasBeenActive = true
                    }
                    is DeviceCallState.Idle -> {
                        if (hasBeenActive) {
                            Log.d(TAG, "Call finished: active -> idle")
                            handleCallEnded()
                        }
                    }
                }
            }
        }

        val cleanNumber = phoneNumber.trim().removePrefix("tel:")
        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$cleanNumber")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        try {
            context.startActivity(intent)
            Log.d(TAG, "Call initiated: $callType to $cleanNumber")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate call", e)
            handleCallEnded()
        }
    }

    /**
     * Handle USSD calls using ACTION_CALL
     */
    private fun handleUSSDCall(ussdCode: String, onUssdComplete: (() -> Unit)?) {
        // Prevent double-dial at the call-manager level (atomic check-and-set)
        if (!isUSSDDialPending.compareAndSet(false, true)) {
            Log.w(TAG, "USSD dial already pending, ignoring duplicate")
            return
        }

        // Set up USSD callback
        setUssdSessionCompleteCallback(onUssdComplete)
        _isCallInProgress.value = true
        currentCallType = CallType.USSD

        Log.d(TAG, "Starting USSD call: $ussdCode")

        coordinator.acquire("${TAG}_USSD")
        callTrackingJob?.cancel()
        callTrackingJob = CoroutineScope(Dispatchers.Main.immediate).launch {
            var hasBeenActive = false
            coordinator.callState.collect { state ->
                when (state) {
                    is DeviceCallState.OffHook, is DeviceCallState.Ringing -> {
                        hasBeenActive = true
                    }
                    is DeviceCallState.Idle -> {
                        if (hasBeenActive) {
                            Log.d(TAG, "USSD call active -> idle")
                            isUSSDDialPending.set(false)
                            handleCallEnded()
                        }
                    }
                }
            }
        }

        val cleanCode = ussdCode.trim().removePrefix("tel:")
        val encodedUssdCode = Uri.encode(cleanCode)
        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$encodedUssdCode")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        try {
            context.startActivity(intent)
            Log.d(TAG, "USSD call initiated: $cleanCode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate USSD call", e)
            isUSSDDialPending.set(false)
            handleCallEnded()
        }
    }

    private fun handleCallEnded() {
        callTrackingJob?.cancel()
        callTrackingJob = null
        try {
            coordinator.release(TAG)
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing coordinator: ${e.message}")
        }
        val type: CallType?
        val endedCallback: ((CallType) -> Unit)?
        val ussdCallback: (() -> Unit)?
        synchronized(callStateLock) {
            type = currentCallType
            endedCallback = getCallEndedCallback()
            ussdCallback = getUssdSessionCompleteCallback()
            _isCallInProgress.value = false
            currentCallType = null
            clearCallbacks()
        }
        type?.let { endedCallback?.invoke(it) }
        ussdCallback?.invoke()
    }

    /**
     * Ends current call
     */
    fun endCall() {
        handleCallEnded()
    }

    /**
     * Checks if call is in progress
     */
    fun isCallInProgress(): Boolean = _isCallInProgress.value

    /**
     * Constructs the UPI123 call string in the format: tel:<serviceNumber>,,1,<phoneNumber>,,<amount>,,1
     * Validation and construction live in the pure, unit-tested Upi123CallStringBuilder.
     */
    fun constructUPI123CallString(phoneNumber: String, amount: String): String {
        return when (
            val result = Upi123CallStringBuilder.build(
                AppConstants.DEFAULT_UPI_SERVICE_NUMBER,
                phoneNumber,
                amount
            )
        ) {
            is Upi123CallStringBuilder.Result.Valid -> result.callString
            is Upi123CallStringBuilder.Result.Invalid ->
                throw IllegalArgumentException(result.reason.name)
        }
    }

    /**
     * Initiates a UPI123 call with the given phone number and amount
     */
    fun initiateUPI123Call(phoneNumber: String, amount: String): Boolean {
        return try {
            val result = Upi123CallStringBuilder.build(
                AppConstants.DEFAULT_UPI_SERVICE_NUMBER,
                phoneNumber.orEmpty(),
                amount.orEmpty()
            )
            val callString = when (result) {
                is Upi123CallStringBuilder.Result.Invalid -> {
                    Log.e(TAG, "UPI123 call rejected: ${result.reason.name}")
                    Toast.makeText(
                        context,
                        result.reason.messageFor(context),
                        Toast.LENGTH_SHORT
                    ).show()
                    return false
                }
                is Upi123CallStringBuilder.Result.Valid -> result.callString
            }

            val hasCallPermission = ContextCompat.checkSelfPermission(
                context, PermissionConstants.CRITICAL_PERMISSIONS[0]
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasCallPermission) {
                Log.e(TAG, "CALL_PHONE permission not granted")
                Toast.makeText(
                    context,
                    "Phone call permission is required for manual payments. Please grant permission in Settings.",
                    Toast.LENGTH_LONG
                ).show()
                return false
            }

            // Call-state tracking and outcome interpretation are owned by
            // CallStateCoordinator / PaymentSessionManager — no per-call
            // PhoneStateListener is registered here.
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse(callString)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Log.d(TAG, "UPI123 call started")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception while initiating call", e)
            Toast.makeText(context, R.string.error_call_permission_denied, Toast.LENGTH_SHORT).show()
            false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error while initiating UPI123 call", e)
            Toast.makeText(context, R.string.error_call_failed, Toast.LENGTH_SHORT).show()
            false
        }
    }

    /**
     * Mutes all audio streams for seamless call experience
     * @return true if successful, false otherwise
     */
    fun muteCallAudio(): Boolean {
        synchronized(audioLock) {
            return try {
                Log.d(TAG, "Attempting to mute call audio")

                // Store original audio settings with error checking
                originalCallVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                previousAudioMode = audioManager.mode

                // Set audio mode for call with validation
                audioManager.mode = AudioManager.MODE_IN_CALL
                if (audioManager.mode != AudioManager.MODE_IN_CALL) {
                    Log.w(TAG, "Failed to set audio mode to MODE_IN_CALL")
                    return false
                }

                // Mute microphone and speaker
                audioManager.isMicrophoneMute = true
                audioManager.isSpeakerphoneOn = false

                // Mute ONLY the voice-call stream. Ring/alarm/notification streams
                // must never be touched — silencing a user's alarm clock during a
                // 35-second payment call is real-world harm.
                try {
                    audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, 0, 0)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to mute voice-call stream", e)
                }

                isAudioMuted = true
                Log.d(TAG, "Call audio muted")
                true
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception while muting audio", e)
                Toast.makeText(context, R.string.error_audio_permission, Toast.LENGTH_SHORT).show()
                false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to mute call audio", e)
                Toast.makeText(context, R.string.error_audio_lower_failed, Toast.LENGTH_SHORT).show()
                false
            }
        }
    }

    /**
     * Restores all audio streams to original settings
     * @return true if successful, false otherwise
     */
    fun unmuteCallAudio(): Boolean {
        synchronized(audioLock) {
            return try {
                Log.d(TAG, "Restoring call audio")

                // Restore audio mode with validation
                audioManager.mode = previousAudioMode
                if (audioManager.mode != previousAudioMode) {
                    Log.w(TAG, "Failed to restore audio mode to $previousAudioMode")
                }

                // Unmute microphone
                audioManager.isMicrophoneMute = false

                // Restore the voice-call volume (the only stream mute touches)
                try {
                    audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, originalCallVolume, 0)
                    val currentCallVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                    if (currentCallVolume != originalCallVolume) {
                        Log.w(
                            TAG,
                            "Failed to restore call volume, expected: $originalCallVolume, actual: $currentCallVolume"
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restore call volume", e)
                }

                isAudioMuted = false
                Log.d(TAG, "Call audio restored successfully")
                true
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception while restoring audio", e)
                Toast.makeText(context, R.string.error_audio_permission, Toast.LENGTH_SHORT).show()
                false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore call audio", e)
                Toast.makeText(context, R.string.error_audio_restore_failed, Toast.LENGTH_SHORT).show()
                false
            }
        }
    }

    /**
     * Sets call volume to minimum (1) for IVR calls
     * @return true if successful, false otherwise
     */
    fun setCallVolumeToMinimum(): Boolean {
        synchronized(audioLock) {
            return try {
                Log.d(TAG, "Setting call volume to minimum (1)")

                // Store original call volume if not already stored
                if (originalCallVolume == 0) {
                    originalCallVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                    Log.d(TAG, "Stored original call volume: $originalCallVolume")
                }

                // Set audio mode for call
                audioManager.mode = AudioManager.MODE_IN_CALL

                // Set call volume to 1 (minimum audible level)
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, 1, AudioManager.FLAG_SHOW_UI)
                val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)

                Log.d(TAG, "Call volume set to: $currentVolume (target was 1)")

                if (currentVolume <= 1) {
                    Log.d(TAG, "Call volume set to minimum successfully")
                    true
                } else {
                    Log.w(TAG, "Failed to set call volume to 1, current volume: $currentVolume")
                    false
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception while setting call volume", e)
                false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set call volume to minimum", e)
                false
            }
        }
    }

    /**
     * Restores call volume to original level
     * @return true if successful, false otherwise
     */
    fun restoreCallVolume(): Boolean {
        synchronized(audioLock) {
            return try {
                Log.d(TAG, "Restoring call volume to original level: $originalCallVolume")

                if (originalCallVolume > 0) {
                    // Restore audio mode
                    audioManager.mode = previousAudioMode

                    // Restore call volume
                    audioManager.setStreamVolume(
                        AudioManager.STREAM_VOICE_CALL,
                        originalCallVolume,
                        AudioManager.FLAG_SHOW_UI
                    )
                    val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)

                    Log.d(TAG, "Call volume restored to: $currentVolume (expected: $originalCallVolume)")

                    if (currentVolume == originalCallVolume) {
                        Log.d(TAG, "Call volume restored successfully")
                        true
                    } else {
                        Log.w(
                            TAG,
                            "Failed to restore call volume, expected: $originalCallVolume, actual: $currentVolume"
                        )
                        false
                    }
                } else {
                    Log.w(TAG, "No original call volume stored, cannot restore")
                    false
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception while restoring call volume", e)
                false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore call volume", e)
                false
            }
        }
    }

    /**
     * Shows the call overlay for UPI123 protection using the new service
     */
    fun showCallOverlay(phoneNumber: String, amount: String) {
        try {
            // Use the new CallOverlayService instead of activity
            com.tossepay.app.services.CallOverlayService.showOverlay(context, phoneNumber, amount)
            Log.d(TAG, "Call overlay shown via service")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show call overlay", e)
        }
    }

    /**
     * Hides the call overlay using the new service
     */
    fun hideCallOverlay() {
        try {
            // Use the new CallOverlayService instead of activity
            com.tossepay.app.services.CallOverlayService.hideOverlay(context)
            Log.d(TAG, "Call overlay hidden via service")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide call overlay", e)
        }
    }

    /**
     * Terminates the active call via TelecomManager.endCall().
     *
     * Requires the ANSWER_PHONE_CALLS runtime permission; without it this
     * honestly returns false so the caller can tell the user to hang up
     * manually. The previous KEYCODE_ENDCALL / ITelephony-reflection
     * fallbacks never worked on Android 10+ and were removed.
     */
    fun terminateCall(): Boolean {
        return try {
            if (getCallState() == TelephonyManager.CALL_STATE_IDLE) {
                Log.w(TAG, "No call in progress, cannot terminate")
                return false
            }
            val hasPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ANSWER_PHONE_CALLS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                Log.w(TAG, "ANSWER_PHONE_CALLS not granted - cannot end call programmatically")
                return false
            }
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager

            @Suppress("DEPRECATION")
            val terminated = telecomManager.endCall()
            Log.d(TAG, "TelecomManager.endCall() -> $terminated")
            if (terminated) {
                synchronized(callStateLock) {
                    _isCallInProgress.value = false
                    currentCallType = null
                }
            }
            terminated
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception while terminating call", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to terminate call", e)
            false
        }
    }

    /**
     * Cleanup method to release all resources and prevent memory leaks
     */
    fun cleanup() {
        synchronized(audioLock) {
            try {
                // Restore audio if it was muted
                if (isAudioMuted) {
                    unmuteCallAudio()
                } else {
                    Log.d(TAG, "Audio was not muted, no restoration needed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during audio cleanup", e)
            }
        }

        handleCallEnded()
        Log.d(TAG, "CallManager cleanup completed")
    }

    /**
     * Checks if audio is currently muted
     */
    fun isAudioMuted(): Boolean = isAudioMuted

    /**
     * Gets the current call state
     */
    fun getCallState(): Int = telephonyManager.callState
}

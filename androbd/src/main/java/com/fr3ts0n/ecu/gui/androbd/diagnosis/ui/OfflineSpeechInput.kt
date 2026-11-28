/* (C) 2026 Bengkel Scan contributors. GPL-2.0-or-later. */
package com.fr3ts0n.ecu.gui.androbd.diagnosis.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.fr3ts0n.ecu.gui.androbd.R
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.SpeechErrorPolicy
import java.util.Locale

/**
 * One user-initiated utterance at a time; never creates a network-capable recognizer.
 * The owner requests permission, calls cancel in onStop, and destroy in onDestroy.
 * No audio, partial results, confidence scores, or transcript are retained here.
 */
@MainThread
class OfflineSpeechInput(
    private val activity: AppCompatActivity,
    private val listener: Listener,
) {
    interface Listener {
        /** Active includes capability checking and waiting for a final result. */
        fun onSpeechStatus(messageRes: Int, active: Boolean)
        fun onTranscript(text: String)
    }

    private enum class State { IDLE, CHECKING, LISTENING, FINISHING, DESTROYED }

    private var state = State.IDLE
    private var generation = 0L
    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var deadline: Runnable? = null

    init {
        requireMainThread()
    }

    /** Service availability only; it does not assert that Indonesian is installed. */
    fun isAvailable(): Boolean {
        requireMainThread()
        if (state == State.DESTROYED || Build.VERSION.SDK_INT < 31) return false
        return try {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)
        } catch (_: RuntimeException) {
            false
        }
    }

    fun start() {
        requireMainThread()
        // A second tap must not replace an in-flight callback listener or open another microphone.
        if (state != State.IDLE) return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            listener.onSpeechStatus(R.string.speech_permission_required, false)
            return
        }
        if (Build.VERSION.SDK_INT < 31 || !isAvailable()) {
            listener.onSpeechStatus(R.string.speech_unavailable, false)
            return
        }

        val token = ++generation
        state = State.CHECKING
        val current = try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(activity)
        } catch (_: SecurityException) {
            finish(token, R.string.speech_permission_required)
            return
        } catch (_: UnsupportedOperationException) {
            finish(token, R.string.speech_unavailable)
            return
        } catch (_: RuntimeException) {
            finish(token, R.string.speech_client_error)
            return
        }
        recognizer = current
        deadline = Runnable { if (isCurrent(token)) finish(token, R.string.speech_session_timeout) }.also {
            handler.postDelayed(it, activity.resources.getInteger(R.integer.speech_session_timeout_ms).toLong())
        }
        if (!platformCall(token) { current.setRecognitionListener(callbacks(token)) }) return
        val intent = recognitionIntent()
        if (Build.VERSION.SDK_INT >= 33) {
            listener.onSpeechStatus(R.string.speech_checking_language, true)
            if (isCurrent(token) && state == State.CHECKING) checkSupport(token, current, intent)
        } else {
            beginListening(token, current, intent, supportChecked = false)
        }
    }

    /** Stop capturing but still accept the final result; cancel discards it instead. */
    fun stop() {
        requireMainThread()
        when (state) {
            State.CHECKING -> cancel()
            State.LISTENING -> {
                val token = generation
                val current = recognizer ?: return
                state = State.FINISHING
                listener.onSpeechStatus(R.string.speech_processing, true)
                if (isCurrent(token)) platformCall(token) { current.stopListening() }
            }
            else -> Unit
        }
    }

    /** Invalidate before touching the platform: cancellation errors/results must stay silent. */
    fun cancel() {
        requireMainThread()
        if (state == State.DESTROYED) return
        val wasActive = state != State.IDLE
        releaseSession()
        if (wasActive) listener.onSpeechStatus(R.string.speech_cancelled, false)
    }

    fun destroy() {
        requireMainThread()
        if (state == State.DESTROYED) return
        releaseSession()
        state = State.DESTROYED
    }

    @RequiresApi(33)
    private fun checkSupport(token: Long, current: SpeechRecognizer, intent: Intent) {
        try {
            current.checkRecognitionSupport(intent, activity.mainExecutor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        if (!isCurrent(token) || state != State.CHECKING) return
                        if (support.installedOnDeviceLanguages.any(::isRequestedLanguage)) {
                            beginListening(token, current, intent, supportChecked = true)
                        } else {
                            val needsInstallation = support.supportedOnDeviceLanguages.any(::isRequestedLanguage)
                                || support.pendingOnDeviceLanguages.any(::isRequestedLanguage)
                            finish(token, if (needsInstallation) R.string.speech_language_unavailable
                                else R.string.speech_language_unsupported)
                        }
                    }

                    override fun onError(error: Int) {
                        if (!isCurrent(token) || state != State.CHECKING) return
                        if (SpeechErrorPolicy.canAttemptWithoutSupport(error)) {
                            beginListening(token, current, intent, supportChecked = false)
                        } else {
                            finish(token, errorMessage(error))
                        }
                    }
                })
        } catch (_: UnsupportedOperationException) {
            // The service cannot check support; this remains the same on-device-only instance.
            if (isCurrent(token) && state == State.CHECKING) {
                beginListening(token, current, intent, supportChecked = false)
            }
        } catch (_: SecurityException) {
            finish(token, R.string.speech_permission_required)
        } catch (_: RuntimeException) {
            finish(token, R.string.speech_client_error)
        }
    }

    private fun beginListening(
        token: Long,
        current: SpeechRecognizer,
        intent: Intent,
        supportChecked: Boolean,
    ) {
        if (!isCurrent(token) || state != State.CHECKING) return
        // Permission can change while the asynchronous capability request is pending.
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            finish(token, R.string.speech_permission_required)
            return
        }
        state = State.LISTENING
        listener.onSpeechStatus(if (supportChecked) R.string.speech_starting
            else R.string.speech_starting_unverified, true)
        // Listener code may synchronously cancel or destroy the owner.
        if (isCurrent(token) && state == State.LISTENING) {
            platformCall(token) { current.startListening(intent) }
        }
    }

    private fun callbacks(token: Long): RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (isCurrent(token) && state == State.LISTENING) {
                listener.onSpeechStatus(R.string.speech_listening, true)
            }
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (isCurrent(token) && state == State.LISTENING) {
                state = State.FINISHING
                listener.onSpeechStatus(R.string.speech_processing, true)
            }
        }

        override fun onError(error: Int) {
            if (isCurrent(token)) finish(token, errorMessage(error))
        }

        override fun onResults(results: Bundle?) {
            if (!isCurrent(token) || state == State.CHECKING) return
            val transcript = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull { !it.isNullOrBlank() }?.trim()
            if (transcript.isNullOrBlank()) {
                finish(token, R.string.speech_no_match)
                return
            }
            releaseSession()
            val completedGeneration = generation
            listener.onSpeechStatus(R.string.speech_transcript_ready, false)
            if (state == State.IDLE && generation == completedGeneration) {
                listener.onTranscript(transcript)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun recognitionIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
    }

    private fun isRequestedLanguage(tag: String): Boolean =
        Locale.forLanguageTag(tag).toLanguageTag().equals(LANGUAGE, ignoreCase = true)

    private fun isCurrent(token: Long): Boolean =
        generation == token && state != State.IDLE && state != State.DESTROYED

    /** Only platform calls are caught; application listener failures are not hidden. */
    private inline fun platformCall(token: Long, action: () -> Unit): Boolean {
        return try {
            action()
            true
        } catch (_: SecurityException) {
            finish(token, R.string.speech_permission_required)
            false
        } catch (_: RuntimeException) {
            finish(token, R.string.speech_client_error)
            false
        }
    }

    private fun finish(token: Long, messageRes: Int) {
        if (!isCurrent(token)) return
        releaseSession()
        listener.onSpeechStatus(messageRes, false)
    }

    private fun releaseSession() {
        deadline?.let(handler::removeCallbacks)
        deadline = null
        ++generation
        state = State.IDLE
        val previous = recognizer
        recognizer = null
        // A disconnected service may throw during either call. Still attempt destroy, never retry.
        try {
            previous?.cancel()
        } catch (_: RuntimeException) {
            // The session is already invalidated; no cancellation error reaches the screen.
        } finally {
            try {
                previous?.destroy()
            } catch (_: RuntimeException) {
                // Do not retain an unusable recognizer or let teardown crash lifecycle cleanup.
            }
        }
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "OfflineSpeechInput requires the main thread" }
    }

    private fun errorMessage(code: Int): Int = when (SpeechErrorPolicy.fromPlatformError(code)) {
        SpeechErrorPolicy.Failure.NETWORK_TIMEOUT -> R.string.speech_network_timeout
        SpeechErrorPolicy.Failure.NETWORK -> R.string.speech_network_error
        SpeechErrorPolicy.Failure.AUDIO -> R.string.speech_audio_error
        SpeechErrorPolicy.Failure.SERVER -> R.string.speech_server_error
        SpeechErrorPolicy.Failure.CLIENT -> R.string.speech_client_error
        SpeechErrorPolicy.Failure.NO_SPEECH -> R.string.speech_no_speech
        SpeechErrorPolicy.Failure.NO_MATCH -> R.string.speech_no_match
        SpeechErrorPolicy.Failure.BUSY -> R.string.speech_busy
        SpeechErrorPolicy.Failure.PERMISSION -> R.string.speech_permission_required
        SpeechErrorPolicy.Failure.TOO_MANY_REQUESTS -> R.string.speech_too_many_requests
        SpeechErrorPolicy.Failure.DISCONNECTED -> R.string.speech_disconnected
        SpeechErrorPolicy.Failure.LANGUAGE_NOT_SUPPORTED -> R.string.speech_language_unsupported
        SpeechErrorPolicy.Failure.LANGUAGE_NOT_INSTALLED -> R.string.speech_language_unavailable
        SpeechErrorPolicy.Failure.SUPPORT_CHECK_UNAVAILABLE,
        SpeechErrorPolicy.Failure.DOWNLOAD_EVENTS_UNAVAILABLE -> R.string.speech_support_unavailable
        SpeechErrorPolicy.Failure.UNKNOWN -> R.string.speech_unknown_error
    }

    private companion object {
        const val LANGUAGE = "id-ID"
    }
}

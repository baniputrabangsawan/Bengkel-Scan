/* (C) 2026 Bengkel Scan contributors. GPL-2.0-or-later. */
package com.fr3ts0n.ecu.gui.androbd.diagnosis.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioRouting
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.AudioQuality
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.PcmFormat
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Null means unobservable, not disabled. These states concern session effects, not all OEM DSP. */
data class EffectState(
    val name: String,
    val available: Boolean?,
    val before: Boolean?,
    val after: Boolean?,
    val disableStatus: Int?,
    val note: String?,
)

/**
 * actualSource is AudioRecord's configured client source, including any explicit fallback.
 * hardwareSource is the active capture-path source, observable only on API 29+.
 * sampleRate describes returned PCM; hardwareSampleRate may differ due to platform resampling.
 * routeName is a device-type label, not a potentially identifying Bluetooth/product name.
 */
data class CaptureDiagnostics(
    val requestedSource: Int,
    val actualSource: Int,
    val sourceName: String,
    val fallbackReason: String?,
    val sampleRate: Int,
    val channels: Int,
    val encoding: String,
    val sessionId: Int,
    val routedDeviceId: Int?,
    val routedDeviceType: Int?,
    val routeName: String?,
    val hardwareSampleRate: Int?,
    val clientSilenced: Boolean?,
    val effects: List<EffectState>,
    val hardwareSource: Int? = null,
)

data class PcmCapture(
    val samples: ShortArray,
    val diagnostics: CaptureDiagnostics,
    val startedAtMs: Long,
)

/**
 * User-initiated, foreground-only transient capture. The owner handles consent and permission UI.
 * Only one instance may capture at a time in this process. capture accepts 1..15 seconds.
 * onLevel receives dBFS and monotonic wall elapsed milliseconds on Dispatchers.IO, at most 10 Hz
 * (plus one final update). It must be fast and must marshal any view changes to the main thread.
 * stop requests an early result; coroutine cancellation discards the result. Neither does recorder
 * I/O on the caller's thread. Failures throw IOException; permission denial remains SecurityException.
 *
 * Observations cannot prove that vendor processing is absent. Unknown diagnostics stay nullable.
 * Routing notifications after audio begins conservatively discard the clip, including round trips
 * whose intermediate device is no longer visible when polled. No microphone input is ever logged.
 */
class PcmAudioRecorder(context: Context) {
    private val appContext = context.applicationContext
    private val activeRequest = AtomicReference<StopRequest?>(null)

    suspend fun capture(seconds: Int, onLevel: (Double, Long) -> Unit): PcmCapture {
        require(seconds in 1..MAX_SECONDS) { "audio.invalid_duration" }
        val request = StopRequest()
        if (!activeRequest.compareAndSet(null, request)) throw IOException("audio.capture_in_progress")
        if (!processCaptureActive.compareAndSet(false, true)) {
            activeRequest.compareAndSet(request, null)
            throw IOException("audio.capture_in_progress")
        }
        try {
            return withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    throw SecurityException("audio.permission_required")
                }
                val manager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    ?: throw IOException("audio.manager_unavailable")
                val selection = selectSource(manager)
                // One maximum-sized PCM allocation; reads write directly into the result buffer.
                val samples = ShortArray(seconds * PcmFormat.SAMPLE_RATE)
                val session = openWithFallback(manager, selection, request)
                var failure: Throwable? = null
                try {
                    val observation = Observation()
                    observe(session, manager, observation, 0)
                    installMonitors(session)
                    readCapture(session, manager, observation, request, samples, seconds, onLevel, selection)
                } catch (error: Throwable) {
                    failure = error
                    throw error
                } finally {
                    val cleanupFailure = closeSession(session)
                    if (cleanupFailure != null) {
                        if (failure != null) failure.addSuppressed(cleanupFailure) else throw cleanupFailure
                    }
                }
            }
        } finally {
            // Clear the instance before reopening the process gate: stop cannot target a later clip.
            activeRequest.compareAndSet(request, null)
            processCaptureActive.set(false)
        }
    }

    fun stop() {
        activeRequest.get()?.stopped?.set(true)
    }

    private suspend fun readCapture(
        session: Session,
        manager: AudioManager,
        observation: Observation,
        request: StopRequest,
        samples: ShortArray,
        seconds: Int,
        onLevel: (Double, Long) -> Unit,
        selection: SourceSelection,
    ): PcmCapture {
        var count = 0
        var levelStart = 0
        var lastLevelAt = session.startedElapsedMs
        var lastDataAt = session.startedElapsedMs
        var lastObservationAt = session.startedElapsedMs
        val deadline = session.startedElapsedMs + seconds * 1_000L + WALL_GRACE_MS
        while (count < samples.size) {
            currentCoroutineContext().ensureActive()
            if (request.stopped.get()) break
            val now = SystemClock.elapsedRealtime()
            if (now >= deadline) throw IOException("audio.capture_timeout")
            if (now - lastDataAt >= NO_DATA_TIMEOUT_MS) throw IOException("audio.no_data_timeout")
            if (session.silenced.get()) throw IOException("audio.client_silenced")
            if (session.routeEvent.get() || now - lastObservationAt >= LEVEL_INTERVAL_MS) {
                observe(session, manager, observation, count)
                for (effect in session.effects) effect.observe()
                lastObservationAt = now
            }
            val requested = minOf(READ_SAMPLES, samples.size - count)
            val read = try {
                session.record.read(samples, count, requested, AudioRecord.READ_NON_BLOCKING)
            } catch (error: RuntimeException) {
                throw IOException("audio.read_failed:${error.javaClass.simpleName}", error)
            }
            if (read < 0) throw IOException("audio.read_failed:$read")
            if (read > requested) throw IOException("audio.invalid_read_count")
            if (read > 0) {
                count += read
                lastDataAt = SystemClock.elapsedRealtime()
            }
            val elapsedAt = SystemClock.elapsedRealtime()
            if (elapsedAt - lastLevelAt >= LEVEL_INTERVAL_MS && count > levelStart) {
                onLevel(AudioQuality.rmsDbfs(samples, levelStart, count - levelStart), elapsedAt - session.startedElapsedMs)
                levelStart = count
                lastLevelAt = elapsedAt
            }
            // Even an OEM returning repeated short/zero reads cannot turn this into a busy spin.
            if (count < samples.size && !request.stopped.get()) delay(READ_POLL_MS)
        }
        currentCoroutineContext().ensureActive()
        observe(session, manager, observation, count)
        val effects = session.effects.map { it.snapshot() }
        if (count > levelStart) {
            onLevel(AudioQuality.rmsDbfs(samples, levelStart, count - levelStart),
                SystemClock.elapsedRealtime() - session.startedElapsedMs)
        }
        val diagnostics = CaptureDiagnostics(
            requestedSource = selection.requested,
            actualSource = session.clientSource,
            sourceName = sourceName(session.clientSource),
            fallbackReason = session.fallbackReason,
            sampleRate = PcmFormat.SAMPLE_RATE,
            channels = PcmFormat.CHANNELS,
            encoding = "PCM_16BIT",
            sessionId = session.sessionId,
            routedDeviceId = observation.deviceId,
            routedDeviceType = observation.deviceType,
            routeName = observation.routeName,
            hardwareSampleRate = observation.hardwareRate,
            clientSilenced = observation.clientSilenced,
            effects = effects,
            hardwareSource = observation.hardwareSource,
        )
        logDiagnostics(diagnostics)
        // Early stop trims once, never per read. Full-length captures return the original array.
        return PcmCapture(if (count == samples.size) samples else samples.copyOf(count), diagnostics, session.startedAtMs)
    }

    private fun selectSource(manager: AudioManager): SourceSelection {
        if (Build.VERSION.SDK_INT < 24) {
            return SourceSelection(MediaRecorder.AudioSource.VOICE_RECOGNITION, "unprocessed_requires_api_24")
        }
        val supported = try {
            manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        } catch (error: RuntimeException) {
            return SourceSelection(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                "unprocessed_property_unknown:${error.javaClass.simpleName}")
        }
        return if (supported) SourceSelection(MediaRecorder.AudioSource.UNPROCESSED, null)
        else SourceSelection(MediaRecorder.AudioSource.VOICE_RECOGNITION, "unprocessed_not_advertised")
    }

    private suspend fun openWithFallback(
        manager: AudioManager,
        selection: SourceSelection,
        request: StopRequest,
    ): Session {
        try {
            return openSession(manager, selection.requested, selection.reason, request)
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            if (request.stopped.get() || selection.requested != MediaRecorder.AudioSource.UNPROCESSED) throw error
            val reason = "unprocessed_init_or_start_failed:${error.message}"
            // Messages here are our own codes/class names, never raw platform exception messages.
            Log.w(TAG, "source fallback UNPROCESSED -> VOICE_RECOGNITION reason=$reason")
            try {
                return openSession(manager, MediaRecorder.AudioSource.VOICE_RECOGNITION, reason, request)
            } catch (fallbackError: IOException) {
                fallbackError.addSuppressed(error)
                throw fallbackError
            }
        }
    }

    private suspend fun openSession(
        manager: AudioManager,
        source: Int,
        fallbackReason: String?,
        request: StopRequest,
    ): Session {
        currentCoroutineContext().ensureActive()
        if (request.stopped.get()) throw IOException("audio.stopped_before_start")
        var session: Session? = null
        try {
            val minimumBytes = AudioRecord.getMinBufferSize(PcmFormat.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimumBytes <= 0) throw IOException("audio.unsupported_pcm_format:$minimumBytes")
            val record = AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(PcmFormat.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build())
                .setBufferSizeInBytes(maxOf(minimumBytes, READ_SAMPLES * PcmFormat.BYTES_PER_SAMPLE * 4))
                .build()
            // Establish ownership immediately so every subsequent exception releases the recorder.
            val owned = Session(record, fallbackReason)
            session = owned
            if (record.state != AudioRecord.STATE_INITIALIZED) throw IOException("audio.not_initialized")
            if (record.sampleRate != PcmFormat.SAMPLE_RATE || record.channelCount != PcmFormat.CHANNELS ||
                record.audioFormat != AudioFormat.ENCODING_PCM_16BIT) throw IOException("audio.format_mismatch")
            owned.clientSource = record.audioSource
            if (owned.clientSource != source) throw IOException("audio.client_source_mismatch")
            owned.sessionId = try {
                record.audioSessionId
            } catch (error: RuntimeException) {
                Log.w(TAG, "source session_id_unknown:${error.javaClass.simpleName}")
                -1
            }
            preferBuiltInMicrophone(manager, record)
            attachEffects(owned)
            currentCoroutineContext().ensureActive()
            if (request.stopped.get()) throw IOException("audio.stopped_before_start")
            owned.startAttempted = true
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IOException("audio.start_failed")
            owned.startedAtMs = System.currentTimeMillis()
            owned.startedElapsedMs = SystemClock.elapsedRealtime()
            // Some devices change default effect state when the session starts. Disable again once.
            for (effect in owned.effects) effect.disableAfterStart()
            return owned
        } catch (error: Throwable) {
            val failure = if (error is RuntimeException && error !is SecurityException &&
                error !is kotlinx.coroutines.CancellationException) {
                IOException("audio.init_or_start_failed:${error.javaClass.simpleName}", error)
            } else error
            session?.let { closeSession(it)?.let(failure::addSuppressed) }
            throw failure
        }
    }

    private fun preferBuiltInMicrophone(manager: AudioManager, record: AudioRecord) {
        try {
            val device = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            val accepted = if (device != null) record.setPreferredDevice(device) else false
            Log.i(TAG, "source built_in_mic_preference=$accepted")
        } catch (error: RuntimeException) {
            // Preference is not proof of routing; diagnostics always use the observed device.
            Log.w(TAG, "source built_in_mic_preference_unknown:${error.javaClass.simpleName}")
        }
    }

    private fun attachEffects(session: Session) {
        session.effects.add(SessionEffect("AGC", session.sessionId,
            { AutomaticGainControl.isAvailable() }, { AutomaticGainControl.create(session.sessionId) }))
        session.effects.add(SessionEffect("NS", session.sessionId,
            { NoiseSuppressor.isAvailable() }, { NoiseSuppressor.create(session.sessionId) }))
        session.effects.add(SessionEffect("AEC", session.sessionId,
            { AcousticEchoCanceler.isAvailable() }, { AcousticEchoCanceler.create(session.sessionId) }))
    }

    @Suppress("DEPRECATION")
    private fun installMonitors(session: Session) {
        val handler = Handler(Looper.getMainLooper())
        // Main callbacks only set flags: all recorder queries, reads and cleanup stay on IO.
        try {
            if (Build.VERSION.SDK_INT >= 24) installRoutingMonitor24(session, handler)
            else {
                val listener = AudioRecord.OnRoutingChangedListener { session.routeEvent.set(true) }
                session.removeRoutingMonitor = { session.record.removeOnRoutingChangedListener(listener) }
                session.record.addOnRoutingChangedListener(listener, handler)
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "source routing_callback_unknown:${error.javaClass.simpleName}")
        }
        if (Build.VERSION.SDK_INT >= 29) installSilenceMonitor29(session)
    }

    @RequiresApi(24)
    private fun installRoutingMonitor24(session: Session, handler: Handler) {
        val listener = AudioRouting.OnRoutingChangedListener { session.routeEvent.set(true) }
        session.removeRoutingMonitor = { session.record.removeOnRoutingChangedListener(listener) }
        session.record.addOnRoutingChangedListener(listener, handler)
    }

    @RequiresApi(29)
    private fun installSilenceMonitor29(session: Session) {
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                try {
                    for (config in configs) {
                        if (config.clientAudioSessionId == session.sessionId && config.isClientSilenced) {
                            session.silenced.set(true)
                        }
                    }
                } catch (_: RuntimeException) {
                    // A failed observation must never manufacture a known false state.
                    session.silenceObservationFailed.set(true)
                }
            }
        }
        try {
            session.removeSilenceMonitor = { session.record.unregisterAudioRecordingCallback(callback) }
            session.record.registerAudioRecordingCallback(DIRECT_EXECUTOR, callback)
        } catch (error: RuntimeException) {
            Log.w(TAG, "source silence_callback_unknown:${error.javaClass.simpleName}")
        }
    }

    private fun observe(session: Session, manager: AudioManager, result: Observation, sampleCount: Int) {
        if (session.silenced.get()) throw IOException("audio.client_silenced")
        val routeEvent = session.routeEvent.getAndSet(false)
        val device = try { session.record.routedDevice } catch (_: RuntimeException) { null }
        val deviceType = device?.type
        if (result.deviceType != deviceType) {
            result.routeName = deviceType?.let { "audio_device_type_$it" }
        }
        result.deviceId = device?.id
        result.deviceType = deviceType
        if (result.initialDeviceId != null && result.deviceId == null) {
            throw IOException("audio.route_unavailable")
        }
        if (result.deviceId != null) {
            val baseline = result.initialDeviceId
            if (baseline != null && (baseline != result.deviceId || result.initialDeviceType != result.deviceType)) {
                throw IOException("audio.route_changed")
            }
            if (baseline == null) {
                result.initialDeviceId = result.deviceId
                result.initialDeviceType = result.deviceType
            }
        }
        // Before the first sample, a notification may simply establish the initial active route.
        if (routeEvent && sampleCount > 0) throw IOException("audio.routing_event")
        val config = if (Build.VERSION.SDK_INT >= 29) configuration29(session)
        else if (Build.VERSION.SDK_INT >= 24) configuration24(session, manager) else null
        result.hardwareRate = null
        result.hardwareSource = null
        result.clientSilenced = null
        if (config != null && Build.VERSION.SDK_INT >= 24) {
            val clientFormat = try { config.clientFormat } catch (_: RuntimeException) { null }
            if (clientFormat != null && (clientFormat.sampleRate != PcmFormat.SAMPLE_RATE ||
                clientFormat.channelCount != PcmFormat.CHANNELS ||
                clientFormat.encoding != AudioFormat.ENCODING_PCM_16BIT)) throw IOException("audio.format_changed")
            result.hardwareRate = try { config.format.sampleRate.takeIf { it > 0 } } catch (_: RuntimeException) { null }
            if (Build.VERSION.SDK_INT >= 29) observeConfiguration29(session, config, result)
        }
        if (session.silenced.get()) throw IOException("audio.client_silenced")
        if (session.silenceObservationFailed.get()) result.clientSilenced = null
    }

    @RequiresApi(24)
    private fun configuration24(session: Session, manager: AudioManager): AudioRecordingConfiguration? = try {
        // Examine only our own session; do not retain information about other applications.
        if (session.sessionId > 0) manager.activeRecordingConfigurations
            .firstOrNull { it.clientAudioSessionId == session.sessionId } else null
    } catch (_: RuntimeException) { null }

    @RequiresApi(29)
    private fun configuration29(session: Session): AudioRecordingConfiguration? = try {
        session.record.activeRecordingConfiguration
    } catch (_: RuntimeException) { null }

    @RequiresApi(29)
    private fun observeConfiguration29(session: Session, config: AudioRecordingConfiguration, result: Observation) {
        result.clientSilenced = try { config.isClientSilenced } catch (_: RuntimeException) { null }
        if (result.clientSilenced == true) {
            session.silenced.set(true)
            throw IOException("audio.client_silenced")
        }
        result.hardwareSource = try { config.audioSource } catch (_: RuntimeException) { null }
        val source = result.hardwareSource
        if (source != null) {
            if (result.initialHardwareSource != null && result.initialHardwareSource != source) {
                throw IOException("audio.hardware_source_changed")
            }
            result.initialHardwareSource = source
        }
    }

    /** One stop attempt and one release attempt; cleanup failures cannot mask cancellation/read errors. */
    private fun closeSession(session: Session): IOException? {
        var failure: IOException? = null
        fun cleanup(operation: String, action: () -> Unit) {
            try {
                action()
            } catch (error: RuntimeException) {
                val next = IOException("audio.cleanup_failed:$operation:${error.javaClass.simpleName}", error)
                if (failure == null) failure = next else failure?.addSuppressed(next)
                Log.w(TAG, "source cleanup_failed:$operation:${error.javaClass.simpleName}")
            }
        }
        session.removeRoutingMonitor?.let { cleanup("routing_callback", it) }
        session.removeSilenceMonitor?.let { cleanup("silence_callback", it) }
        if (session.startAttempted) cleanup("stop") { session.record.stop() }
        for (effect in session.effects) cleanup("effect_${effect.name}") { effect.release() }
        cleanup("release") { session.record.release() }
        return failure
    }

    private fun logDiagnostics(diagnostics: CaptureDiagnostics) {
        Log.i(TAG, "source requested=${diagnostics.requestedSource} client=${diagnostics.actualSource}" +
            " hardware=${diagnostics.hardwareSource} clientRate=${diagnostics.sampleRate}" +
            " hardwareRate=${diagnostics.hardwareSampleRate} fallback=${diagnostics.fallbackReason}")
        for (effect in diagnostics.effects) {
            Log.i(TAG, "effect ${effect.name} available=${effect.available} before=${effect.before}" +
                " after=${effect.after} disableStatus=${effect.disableStatus} note=${effect.note}")
        }
    }

    private class SessionEffect(
        val name: String,
        sessionId: Int,
        availability: () -> Boolean,
        create: () -> AudioEffect?,
    ) {
        private val notes = linkedSetOf("session_effect_only;vendor_dsp_unobservable")
        private var effect: AudioEffect? = null
        private var available: Boolean? = null
        private var before: Boolean? = null
        private var after: Boolean? = null
        private var disableStatus: Int? = null

        init {
            available = try { availability() } catch (error: RuntimeException) {
                notes.add("availability_unknown:${error.javaClass.simpleName}")
                null
            }
            if (sessionId <= 0) notes.add("session_id_unavailable")
            else if (available == false) notes.add("not_available;enabled_state_unknown")
            else {
                effect = try { create() } catch (error: RuntimeException) {
                    notes.add("creation_failed:${error.javaClass.simpleName}")
                    null
                }
                if (effect == null) notes.add("instance_unavailable;enabled_state_unknown")
                else {
                    before = enabled("before")
                    disable()
                    observe()
                }
            }
        }

        private fun enabled(stage: String): Boolean? = try {
            effect?.enabled
        } catch (error: RuntimeException) {
            notes.add("${stage}_unknown:${error.javaClass.simpleName}")
            null
        }

        private fun disable() {
            val attached = effect ?: return
            disableStatus = try { attached.setEnabled(false) } catch (error: RuntimeException) {
                notes.add("disable_failed:${error.javaClass.simpleName}")
                null
            }
            if (disableStatus != null && disableStatus != AudioEffect.SUCCESS) notes.add("disable_status_non_success")
        }

        fun disableAfterStart() {
            if (effect == null) return
            val atStart = enabled("start")
            if (atStart == true) notes.add("enabled_at_start")
            if (atStart != false) disable()
            observe()
        }

        fun observe() {
            val attached = effect ?: return
            after = enabled("after")
            if (after == true) notes.add("enabled_observed_after_disable")
            try {
                if (!attached.hasControl()) notes.add("control_not_held")
            } catch (error: RuntimeException) {
                notes.add("control_unknown:${error.javaClass.simpleName}")
            }
        }

        fun snapshot(): EffectState {
            observe()
            return EffectState(name, available, before, after, disableStatus, notes.joinToString(";"))
        }

        fun release() {
            effect?.release()
        }
    }

    private class StopRequest { val stopped = AtomicBoolean(false) }

    private class SourceSelection(val requested: Int, val reason: String?)

    private class Session(val record: AudioRecord, val fallbackReason: String?) {
        var clientSource = -1
        var sessionId = -1
        var startAttempted = false
        var startedAtMs = 0L
        var startedElapsedMs = 0L
        val effects = ArrayList<SessionEffect>(3)
        val routeEvent = AtomicBoolean(false)
        val silenced = AtomicBoolean(false)
        val silenceObservationFailed = AtomicBoolean(false)
        var removeRoutingMonitor: (() -> Unit)? = null
        var removeSilenceMonitor: (() -> Unit)? = null
    }

    private class Observation {
        var initialDeviceId: Int? = null
        var initialDeviceType: Int? = null
        var initialHardwareSource: Int? = null
        var deviceId: Int? = null
        var deviceType: Int? = null
        var routeName: String? = null
        var hardwareRate: Int? = null
        var hardwareSource: Int? = null
        var clientSilenced: Boolean? = null
    }

    private companion object {
        const val TAG = "PcmAudioRecorder"
        const val MAX_SECONDS = 15
        const val READ_SAMPLES = PcmFormat.SAMPLE_RATE / 10
        const val READ_POLL_MS = 10L
        const val LEVEL_INTERVAL_MS = 100L
        const val NO_DATA_TIMEOUT_MS = 2_000L
        const val WALL_GRACE_MS = 3_000L
        val processCaptureActive = AtomicBoolean(false)
        val DIRECT_EXECUTOR = Executor { command -> command.run() }

        fun sourceName(source: Int): String = when (source) {
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            else -> "AUDIO_SOURCE_$source"
        }
    }
}

/* (C) 2026 Bengkel Scan contributors. GPL-2.0-or-later. */
package com.fr3ts0n.ecu.gui.androbd.diagnosis.ui

import android.app.Application
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fr3ts0n.ecu.gui.androbd.BuildConfig
import com.fr3ts0n.ecu.gui.androbd.R
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.AudioQuality
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.QualityFailure
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.QualityResult
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.RecordingConfig
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.CaptureDiagnostics
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.PcmAudioRecorder
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.PcmCapture
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.RecordingConfigLoader
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.RecordingContext
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.RecordingDevice
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.RecordingStore
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.SavedRecording
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Microphone work is foreground-only; rotation/background discards all uncommitted PCM. */
class GuidedRecordingViewModel(application: Application, contextJson: String) : AndroidViewModel(application) {
    enum class Stage { LOADING, READY, COUNTDOWN, REFERENCE, RECORDING, REVIEW, SAVING }
    data class State(
        val stage: Stage = Stage.LOADING,
        val config: RecordingConfig? = null,
        val context: RecordingContext? = null,
        val condition: String = "",
        val safety: Boolean = false,
        val observer: Boolean = false,
        val consent: Boolean = false,
        val vehicleGroup: String = "",
        val referenceReady: Boolean = false,
        val countdown: Int = 0,
        val levelDbfs: Double = -120.0,
        val elapsedMs: Long = 0,
        val quality: QualityResult? = null,
        val diagnostics: CaptureDiagnostics? = null,
        val notice: Int = 0,
        val saved: List<SavedRecording> = emptyList(),
    ) {
        val busy: Boolean get() = stage in setOf(Stage.LOADING, Stage.COUNTDOWN, Stage.REFERENCE, Stage.RECORDING, Stage.SAVING)
        val mayRecord: Boolean get() = config != null && context != null && !context.safetyBlocked &&
            condition in RecordingStore.CONDITIONS && safety && consent && (condition != "driving_braking" || observer)
    }

    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private val recorder = PcmAudioRecorder(application)
    private val store = RecordingStore(File(application.noBackupFilesDir, "guided-recordings"),
        RecordingDevice(Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT, BuildConfig.VERSION_NAME))
    private var work: Job? = null
    private var reference: PcmCapture? = null
    private var capture: PcmCapture? = null
    private var referenceAtElapsedMs = 0L
    private var consentAtMs = 0L

    init {
        work = viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { Triple(RecordingConfigLoader.load(application), RecordingContext.parse(contextJson), store.list()) }
                mutable.update { it.copy(stage = Stage.READY, config = loaded.first, context = loaded.second,
                    saved = loaded.third, notice = if (loaded.second.safetyBlocked) R.string.recording_blocked else 0) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(stage = Stage.READY, notice = R.string.recording_load_failed) }
            }
        }
    }

    fun condition(value: String) {
        if (state.value.busy || value == state.value.condition) return
        discard()
        mutable.update { it.copy(condition = value, safety = false, observer = false, notice = 0) }
    }
    fun safety(value: Boolean) { mutable.update { it.copy(safety = value) }; if (!value) cancel() }
    fun observer(value: Boolean) { mutable.update { it.copy(observer = value) }; if (!value && state.value.condition == "driving_braking") cancel() }
    fun consent(value: Boolean) {
        consentAtMs = if (value) System.currentTimeMillis() else 0
        mutable.update { it.copy(consent = value) }
        if (!value) cancel()
    }
    fun group(value: String) { if (!state.value.busy) mutable.update { it.copy(vehicleGroup = value) } }
    fun permissionResult(granted: Boolean) {
        mutable.update { it.copy(notice = if (granted) R.string.recording_permission_ready else R.string.speech_permission_required) }
    }

    fun startReference() = start(true)
    fun startRecording() = start(false)
    private fun start(isReference: Boolean) {
        val snapshot = state.value
        val config = snapshot.config ?: return
        if (snapshot.busy || !snapshot.mayRecord) {
            mutable.update { it.copy(notice = if (it.context?.safetyBlocked == true) R.string.recording_blocked else R.string.recording_check_required) }
            return
        }
        if (!isReference && (reference == null || expired(config))) {
            reference = null
            mutable.update { it.copy(referenceReady = false, notice = R.string.recording_reference_expired) }
            return
        }
        capture = null
        if (isReference) reference = null
        mutable.update { it.copy(stage = Stage.COUNTDOWN, countdown = config.countdownSeconds, quality = null,
            referenceReady = !isReference, notice = 0, elapsedMs = 0, levelDbfs = -120.0) }
        work = viewModelScope.launch {
            try {
                for (remaining in config.countdownSeconds downTo 1) {
                    mutable.update { it.copy(countdown = remaining) }; delay(1000)
                }
                mutable.update { it.copy(stage = if (isReference) Stage.REFERENCE else Stage.RECORDING, countdown = 0) }
                val result = recorder.capture(if (isReference) config.referenceSeconds else config.recordingSeconds) { dbfs, elapsed ->
                    mutable.update { it.copy(levelDbfs = dbfs, elapsedMs = elapsed) }
                }
                if (isReference) {
                    // Evaluate the independent reference validity, not main-clip duration/SNR rules.
                    val inspected = withContext(Dispatchers.Default) { AudioQuality.evaluate(result.samples, result.samples, config) }
                    if (QualityFailure.REFERENCE_INVALID in inspected.reasons) {
                        reference = null
                        mutable.update { it.copy(stage = Stage.READY, referenceReady = false, diagnostics = result.diagnostics,
                            notice = R.string.recording_reference_bad) }
                    } else {
                        reference = result
                        referenceAtElapsedMs = SystemClock.elapsedRealtime()
                        mutable.update { it.copy(stage = Stage.READY, referenceReady = true, diagnostics = result.diagnostics,
                            notice = R.string.recording_reference_ready) }
                    }
                } else {
                    val background = reference
                    if (background == null || expired(config)) {
                        discard(); mutable.update { it.copy(notice = R.string.recording_reference_expired) }
                    } else if (!RecordingStore.compatible(result.diagnostics, background.diagnostics)) {
                        discard(); mutable.update { it.copy(notice = R.string.recording_source_changed) }
                    } else {
                        val quality = withContext(Dispatchers.Default) { AudioQuality.evaluate(result.samples, background.samples, config) }
                        capture = if (quality.accepted) result else null
                        mutable.update { it.copy(stage = Stage.REVIEW, quality = quality, diagnostics = result.diagnostics,
                            notice = if (quality.accepted) R.string.recording_accepted else R.string.recording_rejected) }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                discard()
                mutable.update { it.copy(notice = if (error is SecurityException) R.string.speech_permission_required else R.string.recording_capture_failed) }
            }
        }
    }

    fun stop() { recorder.stop() }
    fun cancel() {
        if (state.value.stage == Stage.LOADING || state.value.stage == Stage.SAVING) return
        work?.cancel(); recorder.stop(); discard()
        mutable.update { it.copy(notice = R.string.recording_cancelled) }
    }
    fun leave() {
        if (state.value.stage == Stage.SAVING) {
            reference = null; capture = null
        } else if (state.value.stage != Stage.LOADING) cancel()
    }
    private fun discard() {
        reference = null; capture = null
        mutable.update { it.copy(stage = Stage.READY, referenceReady = false, quality = null, diagnostics = null,
            countdown = 0, elapsedMs = 0, levelDbfs = -120.0) }
    }
    private fun expired(config: RecordingConfig) = SystemClock.elapsedRealtime() - referenceAtElapsedMs > config.referenceMaxAgeSeconds * 1000L

    fun save() {
        val snapshot = state.value
        val target = capture ?: return
        val background = reference ?: return
        val context = snapshot.context ?: return
        val config = snapshot.config ?: return
        if (snapshot.busy || !snapshot.mayRecord || snapshot.quality?.accepted != true) return
        if (!snapshot.vehicleGroup.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
            mutable.update { it.copy(notice = R.string.recording_group_required) }; return
        }
        val grantedAtMs = consentAtMs
        mutable.update { it.copy(stage = Stage.SAVING, notice = 0) }
        work = viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    store.save(context, snapshot.condition, target, background, config, grantedAtMs, snapshot.vehicleGroup)
                    store.list()
                }
                discard()
                mutable.update { it.copy(saved = saved, notice = R.string.recording_saved) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(stage = Stage.REVIEW, notice = R.string.recording_save_failed) }
            }
        }
    }

    fun delete(id: String) {
        if (state.value.busy) return
        mutable.update { it.copy(stage = Stage.SAVING) }
        work = viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { store.delete(id); store.list() }
                mutable.update { it.copy(stage = Stage.READY, saved = saved, notice = R.string.recording_deleted) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(stage = Stage.READY, notice = R.string.recording_delete_failed) }
            }
        }
    }
    override fun onCleared() { recorder.stop(); super.onCleared() }
}

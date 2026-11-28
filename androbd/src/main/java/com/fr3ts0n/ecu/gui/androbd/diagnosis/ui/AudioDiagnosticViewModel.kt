/* (C) 2026 Bengkel Scan contributors. GPL-2.0-or-later. */
package com.fr3ts0n.ecu.gui.androbd.diagnosis.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fr3ts0n.ecu.gui.androbd.BuildConfig
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.ExpertKnowledge
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.VehicleContext
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.AudioAnalysis
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.AudioDiagnosticEngine
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.AudioInferenceState
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.AudioModelMetadata
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.ExpertKnowledgeLoader
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Local inference is explicit and foreground-only. No microphone, persistence, training, or upload. */
class AudioDiagnosticViewModel(application: Application) : AndroidViewModel(application) {
    data class State(
        val loading: Boolean = true,
        val metadata: AudioModelMetadata? = null,
        val busy: Boolean = false,
        val completedWindows: Int = 0,
        val totalWindows: Int = 0,
        val result: AudioAnalysis? = null,
        val failed: Boolean = false,
    )
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private val engine = AudioDiagnosticEngine(application)
    private var knowledge: ExpertKnowledge? = null
    private var work: Job? = null

    init {
        work = viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { ExpertKnowledgeLoader.load(application) }
                val metadata = engine.metadata()
                knowledge = loaded
                mutable.value = State(loading = false, metadata = metadata)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.value = State(loading = false)
            }
        }
    }

    fun analyzeTechnicalFixture() {
        if (!BuildConfig.DEBUG || state.value.metadata?.synthetic != true) return
        start { Pair({ getApplication<Application>().assets.open("diagnosis/synthetic_test_model.wav") }, VehicleContext()) }
    }

    private fun start(input: suspend () -> Pair<() -> InputStream, VehicleContext>) {
        val rules = knowledge ?: return
        if (state.value.loading || state.value.busy || state.value.metadata == null) return
        mutable.update { it.copy(busy = true, failed = false, result = null, completedWindows = 0, totalWindows = 0) }
        work = viewModelScope.launch {
            try {
                val (audio, vehicle) = input()
                engine.analyze(audio, rules, vehicle).collect { event ->
                    when (event) {
                        AudioInferenceState.Preparing -> Unit
                        is AudioInferenceState.Running -> mutable.update { it.copy(completedWindows = event.completedWindows, totalWindows = event.totalWindows) }
                        is AudioInferenceState.Complete -> mutable.update { it.copy(busy = false, result = event.result) }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(busy = false, failed = true, result = null) }
            }
        }
    }

    fun cancel() {
        if (!state.value.busy) return
        work?.cancel()
        mutable.update { it.copy(busy = false, result = null, failed = false, completedWindows = 0, totalWindows = 0) }
    }

}

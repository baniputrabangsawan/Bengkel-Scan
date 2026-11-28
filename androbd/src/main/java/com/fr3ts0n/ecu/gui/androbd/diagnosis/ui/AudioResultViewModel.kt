/* (C) 2026 Bengkel Scan contributors. GPL-2.0-or-later. */
package com.fr3ts0n.ecu.gui.androbd.diagnosis.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fr3ts0n.ecu.gui.androbd.BuildConfig
import com.fr3ts0n.ecu.gui.androbd.R
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.*
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.*
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Descriptions are transient; only an explicit feedback action persists a sanitized result snapshot. */
class AudioResultViewModel(application: Application, private val recordingId: String?, private val rejected: Boolean,
    private val safetyBlocked: Boolean = false, private val contextJson: String? = null,
    initialSymptoms: Set<String> = emptySet()) : AndroidViewModel(application) {
    data class State(
        val busy: Boolean = true,
        val result: DiagnosticResult? = null,
        val feedback: RecordingFeedback? = null,
        val config: ResultConfig? = null,
        val canFeedback: Boolean = false,
        val notice: Int = 0,
        val knowledgeRevision: String = "",
        val obdConfig: ObdFusionConfig? = null,
        val obd: ObdEvidence = ObdEvidence(),
        val obdAttached: Boolean = false,
        val obdUsed: Boolean = false,
        val obdNotes: List<String> = emptyList(),
        val obdNowMs: Long = 0,
    )
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private val root = File(application.noBackupFilesDir, "guided-recordings")
    private val recordings = RecordingStore(root, RecordingDevice(Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT, BuildConfig.VERSION_NAME))
    private val feedbacks = RecordingFeedbackStore(root)
    private var knowledge: ExpertKnowledge? = null
    private var parser: SymptomDescriptionParser? = null
    private var analysis: AudioAnalysis? = null
    private var modelMetadata: AudioModelMetadata? = null
    private var modelAvailable = false
    private var work: Job? = null
    private var sourceContext: RecordingContext? = null
    private var selectedObdSession: Long? = null
    private var lastDescription = ""
    private var reviewedSymptoms = initialSymptoms

    init {
        work = viewModelScope.launch {
            try {
                val config = withContext(Dispatchers.IO) { ResultConfigLoader.load(application) }
                val rules = withContext(Dispatchers.IO) { ExpertKnowledgeLoader.load(application) }
                knowledge = rules
                parser = withContext(Dispatchers.IO) { SymptomLexiconLoader.load(application) }
                val obdConfig = withContext(Dispatchers.IO) { ObdConfigLoader.load(application) }
                if (contextJson != null && (recordingId == null || rejected)) {
                    sourceContext = withContext(Dispatchers.Default) { RecordingContext.parse(contextJson) }
                }
                mutable.update { it.copy(config = config, knowledgeRevision = rules.revision, obdConfig = obdConfig, obd = ObdLiveData.state.value) }
                if (!rejected && recordingId != null) {
                    val saved = withContext(Dispatchers.IO) { recordings.loadForAnalysis(recordingId) }
                    sourceContext = saved.context
                    val engine = AudioDiagnosticEngine(application)
                    val metadata = try { engine.metadata() } catch (_: java.io.FileNotFoundException) { null }
                    modelMetadata = metadata
                    modelAvailable = metadata != null
                    if (metadata != null) engine.analyze({ saved.file.inputStream() }, rules,
                        saved.context.profile.context(saved.context.temperature, saved.context.triggers, saved.context.location, emptySet()))
                        .collect { if (it is AudioInferenceState.Complete) analysis = it.result }
                    val feedback = withContext(Dispatchers.IO) { feedbacks.load(recordingId) }
                    mutable.update { it.copy(feedback = feedback, canFeedback = true) }
                }
                calculate("")
                observeObd()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(busy = false, canFeedback = false, notice = R.string.result_load_failed,
                    result = DiagnosticResult(DiagnosticOutcome.ERROR, emptyList(),
                        if (safetyBlocked) Severity.STOP else Severity.INSPECT, safetyBlocked, emptyList(),
                        application.getString(R.string.result_disclaimer))) }
            }
        }
    }

    fun assessDescription(text: String) {
        if (state.value.busy || parser == null || state.value.config == null) return
        lastDescription = text
        reviewedSymptoms = emptySet()
        mutable.update { it.copy(busy = true, notice = 0) }
        work = viewModelScope.launch {
            try { calculate(text) } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(busy = false, notice = R.string.result_description_failed) }
            }
        }
    }

    fun canUseObd(): Boolean = sourceContext?.profile?.fuelSystem != "carburetor" && state.value.obdConfig != null

    fun attachObd(enabled: Boolean) {
        if (state.value.busy || !canUseObd()) return
        val current = ObdLiveData.state.value
        if (enabled && (!current.connected || current.source != "REAL")) return
        selectedObdSession = if (enabled) current.sessionId else null
        mutable.update { it.copy(busy = true, obd = current, obdAttached = enabled) }
        work = viewModelScope.launch {
            try { calculate(lastDescription) } catch (error: Exception) {
                if (error is CancellationException) throw error
                selectedObdSession = null
                mutable.update { it.copy(busy = false, obdAttached = false, notice = R.string.result_description_failed) }
            }
        }
    }

    private fun observeObd() = viewModelScope.launch {
        while (isActive) {
            delay(1000)
            if (state.value.busy) continue
            val latest = ObdLiveData.state.value
            val lost = selectedObdSession != null && (!latest.connected || selectedObdSession != latest.sessionId)
            if (lost) selectedObdSession = null
            val recalculate = selectedObdSession != null || lost
            // Publish session loss and pending re-ranking together, never a falsely settled old result.
            mutable.update { it.copy(obd = latest, obdNowMs = System.currentTimeMillis(),
                obdAttached = selectedObdSession != null, busy = recalculate) }
            if (recalculate) {
                try { calculate(lastDescription, lost) } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    selectedObdSession = null
                    mutable.update { it.copy(busy = false, obdAttached = false, obdUsed = false, notice = R.string.result_description_failed) }
                }
            }
        }
    }

    private suspend fun calculate(text: String, lostObd: Boolean = false) {
        val rules = requireNotNull(knowledge)
        val config = requireNotNull(state.value.config)
        val live = ObdLiveData.state.value
        val evidence = if (selectedObdSession == live.sessionId) live else ObdEvidence()
        val calculated = withContext(Dispatchers.Default) {
            val match = requireNotNull(parser).parse(text)
            val symptoms = match.symptomIds + reviewedSymptoms
            val context = sourceContext
            val vehicle = context?.profile?.context(match.engineTempState ?: context.temperature,
                context.triggers + match.triggerConditions, match.location ?: context.location, match.flags, match.temperaturePattern)
                ?: VehicleContext(flags = match.flags, triggerConditions = match.triggerConditions,
                    engineTempState = match.engineTempState, location = match.location, temperaturePattern = match.temperaturePattern)
            val audio = analysis
            // Synthetic scores never enter either the text or OBD ranking.
            val distribution = if (audio?.synthetic == false && !audio.rejected) audio.probabilities else emptyMap()
            val weight = modelMetadata?.takeUnless { it.synthetic }?.fusionAudioWeightMultiplier ?: 1.0
            val weightedRules = rules.copy(scoring = rules.scoring.copy(audioWeight = rules.scoring.audioWeight * weight))
            val fused = if (selectedObdSession != null) ObdEvidenceFusion.merge(weightedRules, vehicle,
                symptoms, distribution, evidence, requireNotNull(state.value.obdConfig), System.currentTimeMillis())
                else ObdFusionResult(DiagnosisScorer.rank(weightedRules, vehicle, symptoms, distribution), emptyList(), false)
            var triage = fused.triage
            if (context?.profile?.supportsDraftCauseRanking() == false) triage = triage.copy(causes = emptyList(), status = TriageStatus.UNCERTAIN)
            if (safetyBlocked || context?.safetyBlocked == true) triage = triage.copy(severity = Severity.STOP)
            val result = DiagnosticResultPolicy.resolve(ResultInput(triage = triage,
                audioProbabilities = if (audio?.synthetic == false && !audio.rejected) audio.probabilities else emptyMap(),
                reportedSymptoms = symptoms, recordingRejected = rejected && !fused.used,
                synthetic = audio?.synthetic == true, modelAvailable = modelAvailable,
                calibratedCauseProbabilities = null), config)
            val notes = ArrayList(fused.notes)
            if (rejected && fused.used) notes.add(getApplication<Application>().getString(R.string.obd_rejected_audio))
            if (lostObd) notes.add(getApplication<Application>().getString(R.string.obd_excluded))
            Triple(result, notes.toList(), fused.used)
        }
        mutable.update { it.copy(busy = false, result = calculated.first, obdNotes = calculated.second,
            obdUsed = calculated.third, obd = live, obdAttached = selectedObdSession != null, obdNowMs = System.currentTimeMillis()) }
    }

    fun saveFeedback(choice: FeedbackChoice, actualCause: String?, mechanicVerified: Boolean) {
        val snapshot = state.value
        val id = recordingId ?: return
        val config = snapshot.config ?: return
        val result = snapshot.result ?: return
        if (snapshot.busy || !snapshot.canFeedback || result.technicalExample) return
        mutateFeedback {
            feedbacks.save(id, BuildConfig.VERSION_NAME, snapshot.knowledgeRevision, resultSnapshot(result), choice,
                actualCause, mechanicVerified, config.consentTextVersion, System.currentTimeMillis())
        }
    }

    fun consent(granted: Boolean) {
        val id = recordingId ?: return
        val config = state.value.config ?: return
        if (state.value.busy || state.value.feedback == null) return
        mutateFeedback { feedbacks.setExportConsent(id, granted, config.consentTextVersion, System.currentTimeMillis()) }
    }

    private fun mutateFeedback(action: () -> RecordingFeedback) {
        mutable.update { it.copy(busy = true, notice = 0) }
        work = viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { action() }
                mutable.update { it.copy(busy = false, feedback = saved, notice = R.string.result_feedback_saved) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(busy = false, notice = R.string.result_feedback_failed) }
            }
        }
    }

    fun mayExport(): Boolean = state.value.let { !it.busy && it.feedback?.consentGranted == true &&
        it.feedback.consentTextVersion == it.config?.consentTextVersion }

    fun export(uri: Uri) {
        if (!mayExport()) return
        val id = recordingId ?: return
        val version = state.value.config?.consentTextVersion ?: return
        mutable.update { it.copy(busy = true, notice = 0) }
        work = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri, "w")) { "feedback.destination_unavailable" }
                        .use { feedbacks.exportPackage(id, version, it) }
                }
                mutable.update { it.copy(busy = false, notice = R.string.result_exported) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(busy = false, notice = R.string.result_export_failed) }
            }
        }
    }

    private fun resultSnapshot(result: DiagnosticResult): JSONObject = JSONObject()
        .put("outcome", result.outcome.name).put("severity", result.severity.name).put("stop_warning", result.stopWarning)
        .put("knowledge_revision", state.value.knowledgeRevision).put("model_id", analysis?.modelId ?: JSONObject.NULL)
        .put("safety_reasons", JSONArray(result.safetyReasons)).put("disclaimer", result.disclaimer)
        .put("technical_only", analysis?.synthetic == true).put("causes", JSONArray(result.causes.map { cause ->
            JSONObject().put("id", cause.id).put("title", cause.title).put("entry_id", cause.entryId)
                .put("severity", cause.severity.name).put("probability", cause.probability ?: JSONObject.NULL)
                .put("reason", cause.reason).put("recommended_action", cause.recommendedAction)
                .put("verification_tests", JSONArray(cause.verificationTests))
        }))
}

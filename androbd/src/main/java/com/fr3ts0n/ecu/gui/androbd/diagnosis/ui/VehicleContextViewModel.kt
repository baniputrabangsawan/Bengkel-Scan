package com.fr3ts0n.ecu.gui.androbd.diagnosis.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fr3ts0n.ecu.gui.androbd.R
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DescriptionMatch
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisScorer
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.ExpertKnowledge
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.SymptomDescriptionParser
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.TriageResult
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.TriageStatus
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.VehicleProfile
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.ExpertKnowledgeLoader
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.LastVehicleStore
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.SymptomLexiconLoader
import com.fr3ts0n.ecu.gui.androbd.workshop.ui.WorkshopUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Transcript/review/result stay in memory only, including across Activity recreation. */
class VehicleContextViewModel(application: Application, private val serviceId: Long? = null) : AndroidViewModel(application) {
    data class State(
        val loading: Boolean = true,
        val busy: Boolean = false,
        val knowledge: ExpertKnowledge? = null,
        val profile: VehicleProfile = VehicleProfile(),
        val remembered: Boolean = false,
        val recalled: Boolean = false,
        val text: String = "",
        val temperature: String = "",
        val triggers: Set<String> = emptySet(),
        val location: String = "",
        val review: DescriptionMatch? = null,
        val symptoms: Set<String> = emptySet(),
        val flags: Set<String> = emptySet(),
        val observations: Map<String, String> = emptyMap(),
        val result: TriageResult? = null,
        val invalidFields: Set<String> = emptySet(),
        val notice: Int = 0,
    )

    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private val store = LastVehicleStore(application)
    private val storageLock = Mutex()
    private var parser: SymptomDescriptionParser? = null
    private var work: Job? = null
    private var generation = 0
    private var loadingJob: Job? = null

    init { load() }

    fun load() {
        if (loadingJob?.isActive == true || (!mutable.value.loading && mutable.value.knowledge != null)) return
        mutable.update { it.copy(loading = true) }
        loadingJob = viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    ExpertKnowledgeLoader.load(getApplication<Application>()) to
                        SymptomLexiconLoader.load(getApplication<Application>())
                }
                var readFailure = false
                val previous = withContext(Dispatchers.IO) {
                    storageLock.withLock {
                        try { store.read() } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            readFailure = true
                            null
                        }
                    }
                }
                val serviceProfile = withContext(Dispatchers.IO) {
                    serviceId?.let { id ->
                        val repository = WorkshopUi.repository(getApplication<Application>())
                        val service = requireNotNull(repository.findService(id)) { "vehicle.missing_service" }
                        val vehicle = requireNotNull(repository.findVehicle(service.vehicleId)) { "vehicle.missing_vehicle" }
                        VehicleProfile(model = vehicle.model.orEmpty(), year = vehicle.year?.toString().orEmpty(),
                            fuelType = when (vehicle.engineType?.name) { "PETROL" -> "petrol"; "DIESEL" -> "diesel"; else -> "" },
                            mileageKm = (service.odometerKm ?: vehicle.odometerKm)?.toString().orEmpty())
                    }
                }
                parser = loaded.second
                mutable.update { it.copy(loading = false, knowledge = loaded.first,
                    profile = serviceProfile ?: previous ?: VehicleProfile(),
                    remembered = previous != null && serviceProfile == null, recalled = previous != null && serviceProfile == null,
                    notice = when { serviceProfile != null -> R.string.context_service_loaded
                        readFailure -> R.string.context_recall_error
                        else -> 0 }) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(loading = false, notice = R.string.context_load_error) }
            }
        }
    }

    fun updateProfile(profile: VehicleProfile) {
        invalidate()
        mutable.update { it.copy(profile = profile, recalled = false, invalidFields = emptySet(), notice = 0) }
    }

    fun updateText(text: String) {
        if (text == mutable.value.text) return
        invalidate()
        mutable.update { it.copy(text = text, notice = 0) }
    }

    fun updateConditions(temperature: String, triggers: Set<String>, location: String) {
        invalidate()
        mutable.update { it.copy(temperature = temperature, triggers = triggers, location = location, notice = 0) }
    }

    fun remember(enabled: Boolean) {
        if (enabled == mutable.value.remembered) return
        invalidate()
        mutable.update { it.copy(remembered = enabled, notice = 0) }
        if (!enabled) viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { storageLock.withLock { store.clear() } }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(remembered = true, notice = R.string.context_forget_error) }
            }
        }
    }

    fun newVehicle() {
        invalidate()
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { storageLock.withLock { store.clear() } }
                val knowledge = mutable.value.knowledge
                mutable.value = State(loading = false, knowledge = knowledge, notice = R.string.context_forgotten)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(busy = false, notice = R.string.context_forget_error) }
            }
        }
    }

    fun review() {
        val snapshot = mutable.value
        val interpreter = parser ?: return
        val invalid = snapshot.profile.invalidFields()
        if (invalid.isNotEmpty()) {
            mutable.update { it.copy(invalidFields = invalid, notice = R.string.context_invalid_numbers) }
            return
        }
        if (snapshot.text.isBlank()) {
            mutable.update { it.copy(notice = R.string.context_description_required) }
            return
        }
        invalidate()
        val version = generation
        mutable.update { it.copy(busy = true, notice = 0) }
        work = viewModelScope.launch {
            try {
                val interpreted = withContext(Dispatchers.Default) { interpreter.parse(snapshot.text) }
                if (version == generation) mutable.update { it.copy(busy = false, review = interpreted,
                    symptoms = interpreted.symptomIds, flags = interpreted.flags, observations = emptyMap()) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (version == generation) mutable.update { it.copy(busy = false, notice = R.string.context_parse_error) }
            }
        }
    }

    fun selectSymptoms(ids: Set<String>) {
        generation++
        work?.cancel()
        mutable.update { it.copy(symptoms = ids, result = null, busy = false) }
    }
    fun selectFlags(flags: Set<String>) {
        generation++
        work?.cancel()
        mutable.update { it.copy(flags = flags, result = null, busy = false) }
    }

    fun answerObservation(id: String, value: String) {
        generation++
        work?.cancel()
        mutable.update { it.copy(observations = it.observations + (id to value), result = null, busy = false) }
    }

    fun assess() {
        val snapshot = mutable.value
        val knowledge = snapshot.knowledge ?: return
        val review = snapshot.review ?: return
        if (snapshot.symptoms.isEmpty()) {
            mutable.update { it.copy(notice = R.string.context_choose_symptom) }
            return
        }
        val context = snapshot.profile.context(
            snapshot.temperature.ifBlank { review.engineTempState },
            snapshot.triggers + review.triggerConditions,
            snapshot.location.ifBlank { review.location },
            (snapshot.flags - snapshot.observations.filterValues { it == "no" }.keys) +
                snapshot.observations.filterValues { it == "yes" }.keys,
            review.temperaturePattern,
        )
        val version = generation
        mutable.update { it.copy(busy = true, notice = 0) }
        work = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    val ranked = DiagnosisScorer.rank(knowledge, context, snapshot.symptoms)
                    if (snapshot.profile.supportsDraftCauseRanking()) ranked
                    else ranked.copy(causes = emptyList(), status = TriageStatus.UNCERTAIN,
                        message = getApplication<Application>().getString(R.string.context_propulsion_unsupported))
                }
                if (snapshot.remembered) withContext(Dispatchers.IO) {
                    storageLock.withLock {
                        coroutineContext.ensureActive()
                        if (mutable.value.remembered && version == generation) store.save(snapshot.profile)
                    }
                }
                if (version == generation) mutable.update { it.copy(busy = false, result = result) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (version == generation) mutable.update { it.copy(busy = false, notice = R.string.context_assess_error) }
            }
        }
    }

    private fun invalidate() {
        generation++
        work?.cancel()
        mutable.update { it.copy(busy = false, review = null, result = null, symptoms = emptySet(), flags = emptySet(), observations = emptyMap()) }
    }
}

package com.fr3ts0n.ecu.gui.androbd.diagnosis.core

import java.util.Collections

enum class DiagnosticOutcome {
    CONFIDENT, LOW_CONFIDENCE, BACKGROUND_NOISE, RECORDING_REJECTED, CONFLICTING_EVIDENCE,
    EXPERT_ONLY, TECHNICAL_ONLY, MODEL_UNAVAILABLE, ERROR,
}

/** Engineering draft decision thresholds, not clinically validated diagnostic thresholds. */
data class ResultConfig(
    val confidentProbability: Double,
    val noiseProbability: Double,
    val conflictProbability: Double,
    val stopProbability: Double,
    val consentTextVersion: String,
) {
    init {
        require(confidentProbability.isFinite() && confidentProbability in 0.0..1.0) { "result.invalid_confident_probability" }
        require(noiseProbability.isFinite() && noiseProbability in 0.0..1.0) { "result.invalid_noise_probability" }
        require(conflictProbability.isFinite() && conflictProbability in 0.0..1.0) { "result.invalid_conflict_probability" }
        require(stopProbability.isFinite() && stopProbability in 0.0..1.0 && stopProbability <= confidentProbability) {
            "result.invalid_stop_probability"
        }
        require(consentTextVersion.length in 1..64 && consentTextVersion.all {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "-_."
        }) { "result.invalid_consent_text_version" }
    }
}

/** A null probability means unavailable calibration, never a zero-percent likelihood. */
data class ResultCause(
    val id: String,
    val title: String,
    val entryId: String,
    val severity: Severity,
    val verificationTests: List<String>,
    val recommendedAction: String,
    val reason: String,
    val probability: Double? = null,
) {
    init {
        require(probability == null || probability.isFinite() && probability in 0.0..1.0) {
            "result.invalid_cause_probability"
        }
    }
}

data class DiagnosticResult(
    val outcome: DiagnosticOutcome,
    val causes: List<ResultCause>,
    val severity: Severity,
    val stopWarning: Boolean,
    val safetyReasons: List<String>,
    val disclaimer: String,
    val technicalExample: Boolean = false,
)

data class ResultInput(
    val triage: TriageResult? = null,
    val audioProbabilities: Map<String, Double> = emptyMap(),
    val reportedSymptoms: Set<String> = emptySet(),
    val recordingRejected: Boolean = false,
    val synthetic: Boolean = false,
    val modelAvailable: Boolean = true,
    val calibratedCauseProbabilities: Map<String, Double>? = null,
    val technicalExample: Boolean = false,
)

/** No class probability or expert score is converted into a cause probability here. */
object DiagnosticResultPolicy {
    private const val DISCLAIMER = "Hasil ini hanya saran. Keputusan akhir ada pada mekanik."
    // Catalog membership belongs to the knowledge/model loaders, not a second whitelist here.
    private val classIdentifier = Regex("[a-z][a-z0-9_]*")
    private val nonDiagnosticClasses = setOf("normal_idle", "background_noise", "unknown", "hiss")

    fun resolve(input: ResultInput, config: ResultConfig): DiagnosticResult {
        val triage = input.triage
        val explicitStop = triage?.severity == Severity.STOP
        val disclaimer = if (triage?.disclaimer.isNullOrBlank()) DISCLAIMER else "$DISCLAIMER\n${triage?.disclaimer}"
        // Rejection has priority over evidence validation and never exposes rejected candidates.
        if (input.recordingRejected) {
            return DiagnosticResult(DiagnosticOutcome.RECORDING_REJECTED, emptyList(),
                if (explicitStop) Severity.STOP else triage?.severity ?: Severity.INSPECT,
                StopWarningPolicy.shouldWarn(emptyList(), explicitStop, config),
                frozen(triage?.safetyReasons.orEmpty()), disclaimer, input.technicalExample)
        }

        val ranked = triage?.causes.orEmpty()
        input.calibratedCauseProbabilities?.let { probabilities ->
            val candidateIds = ranked.mapTo(HashSet()) { it.cause.id }
            require(probabilities.all { (id, probability) ->
                id in candidateIds && probability.isFinite() && probability in 0.0..1.0
            }) { "result.invalid_calibrated_cause_probabilities" }
        }
        val syntheticProduction = input.synthetic && !input.technicalExample
        val calibration = if (syntheticProduction) null else input.calibratedCauseProbabilities
        val selected = ranked.take(3)
        val causes = selected.map { candidate ->
            ResultCause(candidate.cause.id, candidate.cause.name, candidate.entry.id, candidate.entry.severity,
                candidate.entry.verificationTests, candidate.entry.recommendedAction, candidate.reason,
                calibration?.get(candidate.cause.id))
        }
        // A synthetic model's class outputs are never vehicle evidence, including in example mode.
        val audio = if (input.synthetic || !input.modelAvailable) emptyMap() else input.audioProbabilities
        require(audio.all { (id, probability) ->
            classIdentifier.matches(id) && probability.isFinite() && probability in 0.0..1.0
        }) { "result.invalid_audio_probabilities" }
        var maxKnown = 0.0
        var strongestDiagnostic = -1.0
        for ((id, probability) in audio) {
            maxKnown = maxOf(maxKnown, probability)
            if (id !in nonDiagnosticClasses) strongestDiagnostic = maxOf(strongestDiagnostic, probability)
        }
        val noise = audio["background_noise"]
        val hasReportedDiagnostic = input.reportedSymptoms.any {
            it !in nonDiagnosticClasses && classIdentifier.matches(it)
        }
        // A tied strongest class matching a report is not a contradiction. Map order is irrelevant.
        val conflict = hasReportedDiagnostic && strongestDiagnostic >= config.conflictProbability &&
            audio.none { (id, probability) ->
                id !in nonDiagnosticClasses && probability == strongestDiagnostic && id in input.reportedSymptoms
            }
        val calibratedMaximum = causes.maxOfOrNull { it.probability ?: -1.0 } ?: -1.0
        val unrestrictedTriage = triage != null && triage.status == TriageStatus.RANKED &&
            triage.reviewNotice.isBlank() && selected.all {
                it.entry.diagnostic && it.entry.reviewStatus == "verified" &&
                    it.contributions.none { contribution -> contribution.source == "missing_context" }
            }
        val outcome = when {
            syntheticProduction -> if (causes.isEmpty()) DiagnosticOutcome.TECHNICAL_ONLY else DiagnosticOutcome.EXPERT_ONLY
            !input.modelAvailable -> if (causes.isEmpty()) DiagnosticOutcome.MODEL_UNAVAILABLE else DiagnosticOutcome.EXPERT_ONLY
            noise != null && noise >= config.noiseProbability && noise == maxKnown -> DiagnosticOutcome.BACKGROUND_NOISE
            conflict -> DiagnosticOutcome.CONFLICTING_EVIDENCE
            audio.isNotEmpty() && maxKnown < config.confidentProbability -> DiagnosticOutcome.LOW_CONFIDENCE
            calibration != null && calibratedMaximum >= config.confidentProbability &&
                (unrestrictedTriage || input.technicalExample) -> DiagnosticOutcome.CONFIDENT
            causes.isNotEmpty() -> DiagnosticOutcome.EXPERT_ONLY
            input.synthetic -> DiagnosticOutcome.TECHNICAL_ONLY
            else -> DiagnosticOutcome.LOW_CONFIDENCE
        }
        val stopWarning = StopWarningPolicy.shouldWarn(causes, explicitStop, config)
        val safetyReasons = LinkedHashSet<String>()
        safetyReasons.addAll(triage?.safetyReasons.orEmpty())
        if (stopWarning) {
            for (cause in causes) {
                if (cause.severity == Severity.STOP && (cause.probability == null || cause.probability > config.stopProbability)) {
                    safetyReasons.add(cause.recommendedAction)
                }
            }
        }
        val severity = if (stopWarning) Severity.STOP else
            maxOf(triage?.severity ?: Severity.INSPECT, causes.maxOfOrNull { it.severity } ?: Severity.INSPECT)
        return DiagnosticResult(outcome, Collections.unmodifiableList(causes), severity, stopWarning,
            Collections.unmodifiableList(ArrayList(safetyReasons)), disclaimer, input.technicalExample)
    }

    private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
}

/** Safety is independent of the leading candidate and the result's evidence/availability state. */
object StopWarningPolicy {
    fun shouldWarn(topCauses: List<ResultCause>, explicitStop: Boolean, config: ResultConfig): Boolean {
        if (explicitStop) return true
        for (index in 0 until minOf(3, topCauses.size)) {
            val cause = topCauses[index]
            if (cause.severity == Severity.STOP &&
                (cause.probability == null || cause.probability > config.stopProbability)) return true
        }
        return false
    }
}

package com.fr3ts0n.ecu.gui.androbd.diagnosis.core

/** Versioned expert guidance, separate from persisted question/check snapshots. Scores are not probabilities. */
data class ExpertKnowledge(
    val schemaVersion: Int,
    val revision: String,
    val disclaimer: String,
    val reviewNotice: String,
    val scoring: ScoringConfig,
    val safetyRules: List<SafetyRule>,
    val entries: List<ExpertEntry>,
)

data class ScoringConfig(
    val maxResults: Int,
    val minimumScore: Double,
    val minimumMargin: Double,
    val manualSymptomWeight: Double,
    val audioWeight: Double,
    val audioMinProbability: Double,
    val triggerMatchWeight: Double,
    val locationMatchWeight: Double,
    val baseReason: String,
    val manualReason: String,
    val audioReason: String,
    val triggerReason: String,
    val locationReason: String,
    val missingContextReason: String,
    val uncertainMessage: String,
    val rankedMessage: String,
    val noMatchMessage: String,
)

data class ExpertEntry(
    val id: String,
    val symptomName: String,
    val laymanSoundDescription: String,
    val triggerConditions: Set<String>,
    val location: Set<String>,
    val candidateCauses: List<ExpertCause>,
    val severity: Severity,
    val verificationTests: List<String>,
    val recommendedAction: String,
    val costCategory: String,
    val reviewStatus: String,
    val diagnostic: Boolean,
)

data class ExpertCause(
    val id: String,
    val name: String,
    val baseWeight: Double,
    val applicability: List<ContextPredicate>,
    val contextModifiers: List<ContextModifier>,
)

/** Exactly one value is populated. Predicates in a rule are combined with AND. */
data class ContextPredicate(
    val field: String,
    val operator: String,
    val textValue: String? = null,
    val numberValue: Double? = null,
)

data class ContextModifier(val conditions: List<ContextPredicate>, val delta: Double, val reason: String)
data class SafetyRule(val conditions: List<ContextPredicate>, val severity: Severity, val reason: String)

/** Null/unknown facts never imply petrol, diesel, a particular cylinder count, or a healthy component. */
data class VehicleContext(
    val fuelType: String? = null,
    val cylinderCount: Int? = null,
    val mileageKm: Int? = null,
    val engineTempState: String? = null,
    val fuelSystem: String? = null,
    val powerSteeringType: String? = null,
    val triggerConditions: Set<String> = emptySet(),
    val location: String? = null,
    val flags: Set<String> = emptySet(),
    val temperaturePattern: String? = null,
) {
    init {
        require(cylinderCount == null || cylinderCount > 0) { "triage.invalid_cylinder_count" }
        require(mileageKm == null || mileageKm >= 0) { "triage.invalid_mileage" }
    }
}

enum class Severity(val label: String) {
    MONITOR("Aman dipantau"),
    INSPECT("Segera periksa"),
    STOP("Hentikan berkendara"),
}

enum class TriageStatus { RANKED, UNCERTAIN }

data class ScoreContribution(val source: String, val delta: Double, val reason: String)

data class ExpertRankedCause(
    val entry: ExpertEntry,
    val cause: ExpertCause,
    val score: Double,
    val contributions: List<ScoreContribution>,
) {
    val reason: String get() = contributions.joinToString("\n") { "${it.reason} (${it.delta})" }
}

data class TriageResult(
    val causes: List<ExpertRankedCause>,
    val severity: Severity,
    val status: TriageStatus,
    val message: String,
    val disclaimer: String,
    val reviewNotice: String,
    val safetyReasons: List<String>,
)

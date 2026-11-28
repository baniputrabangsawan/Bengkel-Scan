package com.fr3ts0n.ecu.gui.androbd.diagnosis.core

import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge.Cause
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge.Complaint
import java.util.Collections
import java.util.Locale

/** One scoring boundary for saved question/check cases and the new context/audio triage. */
class DiagnosisScorer private constructor() {
    class RankedCause internal constructor(
        @JvmField val cause: Cause,
        @JvmField val score: Int,
        @JvmField val reasons: List<String>,
    )

    companion object {
        private const val CHECK_WEIGHT = 100

        /** Existing saved-case contract: missing answers are not observations of normal operation. */
        @JvmStatic fun rank(
            complaint: Complaint?,
            answers: Map<String?, String?>?,
            checks: Map<String?, CheckStatus?>?,
        ): List<RankedCause> {
            require(complaint != null && answers != null && checks != null) { "diagnosis.scoring.invalid_arguments" }
            for ((id, option) in answers) {
                val question = complaint.questions.firstOrNull { it.id == id }
                require(question != null) { "diagnosis.scoring.invalid_question_id" }
                require(question.options.any { it.id == option }) { "diagnosis.scoring.invalid_option_id" }
            }
            for ((id, status) in checks) {
                require(id != null && complaint.findCause(id) != null) { "diagnosis.scoring.invalid_cause_id" }
                require(status != null) { "diagnosis.scoring.invalid_check_status" }
            }
            val ranked = complaint.causes.map { cause ->
                var score = cause.baseWeight
                val reasons = ArrayList<String>()
                for (rule in cause.scoreRules) {
                    if (rule.optionId == answers[rule.questionId]) {
                        score += rule.delta
                        reasons.add(rule.reason)
                    }
                }
                when (checks[cause.id]) {
                    CheckStatus.CONFIRMED -> score += CHECK_WEIGHT
                    CheckStatus.RULED_OUT -> score -= CHECK_WEIGHT
                    else -> Unit
                }
                RankedCause(cause, score, frozen(reasons))
            }.sortedWith(compareByDescending<RankedCause> { it.score }.thenBy { it.cause.id })
            return frozen(ranked)
        }

        /**
         * Ranks only reported symptoms/accepted audio classes. Configuration owns all decision
         * thresholds; a heuristic score is never normalized or presented as a fault probability.
         */
        @JvmStatic @JvmOverloads fun rank(
            knowledge: ExpertKnowledge,
            context: VehicleContext,
            symptoms: Set<String>,
            audioProbabilities: Map<String, Double> = emptyMap(),
            causeContributions: Map<String, List<ScoreContribution>> = emptyMap(),
        ): TriageResult {
            val ids = knowledge.entries.mapTo(HashSet()) { it.id }
            require(symptoms.all { it in ids }) { "triage.unknown_symptom" }
            require(audioProbabilities.all { (id, probability) ->
                id in ids && probability.isFinite() && probability in 0.0..1.0
            }) { "triage.invalid_audio_probabilities" }
            val causeIds = knowledge.entries.flatMap { it.candidateCauses }.mapTo(HashSet()) { it.id }
            require(causeContributions.all { (id, values) ->
                id in causeIds && values.all {
                    it.delta.isFinite() && it.source.isNotBlank() && it.reason.isNotBlank()
                }
            }) { "triage.invalid_cause_contributions" }
            val config = knowledge.scoring
            val facts = ContextFacts(context)
            val ranked = ArrayList<ExpertRankedCause>()
            var severity: Severity? = null
            var uncertain = false
            val safetyReasons = ArrayList<String>()
            for (entry in knowledge.entries) {
                val manual = entry.id in symptoms
                val probability = audioProbabilities[entry.id]
                val acceptedAudio = probability != null && probability >= config.audioMinProbability
                val observed = manual || acceptedAudio
                val contributed = entry.candidateCauses.any { !causeContributions[it.id].isNullOrEmpty() }
                if (!observed && !contributed) continue
                severity = higher(severity, entry.severity)
                // No applicability filter or top-three cutoff may hide a safety concern.
                if (entry.severity == Severity.STOP) safetyReasons.add(entry.recommendedAction)
                if (entry.reviewStatus == "needs_mechanic_verification" || !entry.diagnostic) uncertain = true
                for (cause in entry.candidateCauses) {
                    // An external observation activates only its target, not every sibling cause.
                    if (!observed && causeContributions[cause.id].isNullOrEmpty()) continue
                    val applicability = facts.match(cause.applicability)
                    if (applicability == Match.MISMATCH) continue
                    val contributions = ArrayList<ScoreContribution>()
                    contributions.add(ScoreContribution("base", cause.baseWeight, config.baseReason))
                    if (manual) contributions.add(ScoreContribution("symptom", config.manualSymptomWeight, config.manualReason))
                    if (acceptedAudio) contributions.add(ScoreContribution("audio",
                        config.audioWeight * requireNotNull(probability), config.audioReason))
                    if (observed && entry.triggerConditions.any { it in facts.triggers }) {
                        contributions.add(ScoreContribution("trigger", config.triggerMatchWeight, config.triggerReason))
                    }
                    if (observed && facts.location != null && facts.location in entry.location) {
                        contributions.add(ScoreContribution("location", config.locationMatchWeight, config.locationReason))
                    }
                    if (applicability == Match.UNKNOWN) {
                        uncertain = true
                        contributions.add(ScoreContribution("missing_context", 0.0, config.missingContextReason))
                    }
                    // External evidence can activate a cause without observing its entry's sound.
                    if (observed) {
                        for (modifier in cause.contextModifiers) {
                            if (facts.match(modifier.conditions) == Match.MATCH) {
                                contributions.add(ScoreContribution("context", modifier.delta, modifier.reason))
                            }
                        }
                    }
                    causeContributions[cause.id]?.let(contributions::addAll)
                    val score = contributions.sumOf { it.delta }
                    require(score.isFinite()) { "triage.score_overflow" }
                    if (score >= config.minimumScore) {
                        ranked.add(ExpertRankedCause(entry, cause, score, frozen(contributions)))
                    }
                }
            }
            for (rule in knowledge.safetyRules) {
                if (facts.match(rule.conditions) == Match.MATCH) {
                    severity = higher(severity, rule.severity)
                    safetyReasons.add(rule.reason)
                }
            }
            ranked.sortWith(compareByDescending<ExpertRankedCause> { it.score }.thenBy { it.cause.id })
            if (ranked.size > 1 && ranked[0].score - ranked[1].score < config.minimumMargin) uncertain = true
            val top = frozen(ranked.take(config.maxResults))
            val status = if (uncertain || top.isEmpty()) TriageStatus.UNCERTAIN else TriageStatus.RANKED
            return TriageResult(top, severity ?: Severity.INSPECT, status,
                when {
                    top.isEmpty() -> config.noMatchMessage
                    status == TriageStatus.UNCERTAIN -> config.uncertainMessage
                    else -> config.rankedMessage
                }, knowledge.disclaimer, knowledge.reviewNotice, frozen(safetyReasons.distinct()))
        }

        private fun higher(current: Severity?, next: Severity): Severity =
            if (current == null || next.ordinal > current.ordinal) next else current

        private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(values)
    }

    private enum class Match { MATCH, MISMATCH, UNKNOWN }

    /** Normalize once; never infer a fuel system from vehicle age, fuel, or cylinder count. */
    private class ContextFacts(context: VehicleContext) {
        val triggers = context.triggerConditions.mapTo(HashSet(), ::normalize)
        val location = known(context.location)
        private val flags = context.flags.mapTo(HashSet(), ::normalize)
        private val strings = mapOf(
            "fuel_type" to known(context.fuelType), "fuel_system" to known(context.fuelSystem),
            "engine_temp_state" to known(context.engineTempState),
            "temperature_pattern" to (known(context.temperaturePattern)
                ?: if ("fades_when_warm" in flags) "fades_when_warm" else null),
            "power_steering_type" to known(context.powerSteeringType), "location" to location,
        )
        private val numbers = mapOf("cylinder_count" to context.cylinderCount, "mileage_km" to context.mileageKm)

        fun match(conditions: List<ContextPredicate>): Match {
            var unknown = false
            for (condition in conditions) {
                val result = match(condition)
                if (result == Match.MISMATCH) return result
                if (result == Match.UNKNOWN) unknown = true
            }
            return if (unknown) Match.UNKNOWN else Match.MATCH
        }

        private fun match(condition: ContextPredicate): Match {
            val matches: Boolean = when (condition.field) {
                "trigger_conditions", "flags" -> {
                    val values = if (condition.field == "flags") flags else triggers
                    if (values.isEmpty()) return Match.UNKNOWN
                    normalize(requireNotNull(condition.textValue)) in values
                }
                "cylinder_count", "mileage_km" -> {
                    val value = numbers[condition.field]?.toDouble() ?: return Match.UNKNOWN
                    val threshold = requireNotNull(condition.numberValue)
                    when (condition.operator) {
                        "eq" -> value == threshold
                        "gte" -> value >= threshold
                        "lte" -> value <= threshold
                        else -> throw IllegalArgumentException("triage.invalid_numeric_operator")
                    }
                }
                else -> {
                    val value = strings[condition.field] ?: return Match.UNKNOWN
                    value == normalize(requireNotNull(condition.textValue))
                }
            }
            return if (matches) Match.MATCH else Match.MISMATCH
        }

        companion object {
            private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)
            private fun known(value: String?): String? = value?.let(::normalize)?.takeUnless { it.isEmpty() || it == "unknown" }
        }
    }
}

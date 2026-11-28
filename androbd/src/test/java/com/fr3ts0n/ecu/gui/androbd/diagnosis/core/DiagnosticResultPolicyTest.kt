package com.fr3ts0n.ecu.gui.androbd.diagnosis.core

import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.ExpertKnowledgeLoader
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticResultPolicyTest {
    private val config = ResultConfig(0.75, 0.6, 0.6, 0.2, "feedback-export-id-v1")

    @Test fun heuristicScoresAndAudioClassesNeverBecomeCausePercentages() {
        val ranked = candidate("belt_squeal.belt_slip", score = 999.0)
        val result = resolve(ResultInput(triage = triage(ranked),
            audioProbabilities = mapOf("belt_squeal" to 0.99)))
        assertEquals(DiagnosticOutcome.EXPERT_ONLY, result.outcome)
        assertNull(result.causes.single().probability)
        assertEquals(ranked.cause.name, result.causes.single().title)
        assertEquals(ranked.entry.id, result.causes.single().entryId)
        assertEquals(ranked.entry.verificationTests, result.causes.single().verificationTests)
        assertEquals(ranked.entry.recommendedAction, result.causes.single().recommendedAction)
        assertEquals(ranked.reason, result.causes.single().reason)
    }

    @Test fun onlyTheFirstThreeExpertCandidatesAreShownWithoutReorderingByCalibration() {
        val candidates = (1..4).map { candidate("belt_squeal.cause$it") }
        val result = resolve(ResultInput(triage = triage(*candidates.toTypedArray()),
            calibratedCauseProbabilities = mapOf(candidates.last().cause.id to 1.0)))
        assertEquals(candidates.take(3).map { it.cause.id }, result.causes.map { it.id })
        assertTrue(result.causes.all { it.probability == null })
        assertEquals(DiagnosticOutcome.EXPERT_ONLY, result.outcome)
    }

    @Test fun confidentRequiresActualCauseCalibrationAndAnUnrestrictedTriage() {
        val ranked = candidate("belt_squeal.belt_slip")
        val input = ResultInput(triage = triage(ranked),
            calibratedCauseProbabilities = mapOf(ranked.cause.id to 0.75))
        assertEquals(DiagnosticOutcome.CONFIDENT, resolve(input).outcome)
        assertEquals(0.75, resolve(input).causes.single().probability!!, 0.0)
        assertEquals(DiagnosticOutcome.EXPERT_ONLY,
            resolve(input.copy(calibratedCauseProbabilities = emptyMap())).outcome)
        assertEquals(DiagnosticOutcome.EXPERT_ONLY,
            resolve(input.copy(calibratedCauseProbabilities = mapOf(ranked.cause.id to 0.749))).outcome)
        assertEquals(DiagnosticOutcome.EXPERT_ONLY,
            resolve(input.copy(triage = triage(ranked).copy(status = TriageStatus.UNCERTAIN))).outcome)
        assertEquals(DiagnosticOutcome.EXPERT_ONLY,
            resolve(input.copy(triage = triage(ranked).copy(reviewNotice = "Masih draf"))).outcome)
        val draft = ranked.copy(entry = ranked.entry.copy(reviewStatus = "needs_mechanic_verification"))
        assertEquals(DiagnosticOutcome.EXPERT_ONLY,
            resolve(input.copy(triage = triage(draft))).outcome)
    }

    @Test fun debugTechnicalExampleCanDemonstrateCalibrationWithoutClaimingModelDiagnosis() {
        val ranked = candidate("belt_squeal.belt_slip")
        val input = ResultInput(triage = triage(ranked).copy(status = TriageStatus.UNCERTAIN,
            reviewNotice = "Draf"), calibratedCauseProbabilities = mapOf(ranked.cause.id to 0.9),
            synthetic = true, technicalExample = true)
        val result = resolve(input)
        assertEquals(DiagnosticOutcome.CONFIDENT, result.outcome)
        assertTrue(result.technicalExample)
        assertEquals(0.9, result.causes.single().probability!!, 0.0)
    }

    @Test fun syntheticProductionIgnoresClassAndCausePercentagesButRetainsIndependentManualTriage() {
        val ranked = candidate("belt_squeal.belt_slip")
        val input = ResultInput(triage = triage(ranked), synthetic = true,
            audioProbabilities = mapOf("background_noise" to 1.0, "bogus" to Double.NaN),
            calibratedCauseProbabilities = mapOf(ranked.cause.id to 1.0))
        val result = resolve(input)
        assertEquals(DiagnosticOutcome.EXPERT_ONLY, result.outcome)
        assertNull(result.causes.single().probability)
        assertEquals(DiagnosticOutcome.TECHNICAL_ONLY,
            resolve(input.copy(triage = null, calibratedCauseProbabilities = null)).outcome)
    }

    @Test fun rejectionClearsCandidatesButNotExplicitStopOrOriginalSafetyReasons() {
        val ranked = candidate("belt_squeal.belt_slip")
        val input = ResultInput(triage = triage(ranked).copy(severity = Severity.STOP,
            safetyReasons = listOf("Suhu terus naik", "Rem berubah")), recordingRejected = true,
            audioProbabilities = mapOf("background_noise" to 1.0),
            calibratedCauseProbabilities = mapOf(ranked.cause.id to 1.0))
        val result = resolve(input)
        assertEquals(DiagnosticOutcome.RECORDING_REJECTED, result.outcome)
        assertTrue(result.causes.isEmpty())
        assertTrue(result.stopWarning)
        assertEquals(Severity.STOP, result.severity)
        assertEquals(input.triage!!.safetyReasons, result.safetyReasons)
        assertEquals(DiagnosticOutcome.RECORDING_REJECTED, resolve(input.copy(synthetic = true)).outcome)
    }

    @Test fun backgroundNoiseWinsTiesAndPrecedesAnOtherwiseIndependentConflict() {
        val ranked = candidate("belt_squeal.belt_slip")
        val input = ResultInput(triage = triage(ranked).copy(severity = Severity.STOP,
            safetyReasons = listOf("Hentikan berkendara")), reportedSymptoms = setOf("brake_squeal"),
            audioProbabilities = mapOf("background_noise" to 0.8, "belt_squeal" to 0.8))
        val result = resolve(input)
        assertEquals(DiagnosticOutcome.BACKGROUND_NOISE, result.outcome)
        assertTrue(result.stopWarning)
        assertEquals(input.triage!!.safetyReasons, result.safetyReasons)
        assertEquals(1, result.causes.size)
        assertEquals(DiagnosticOutcome.CONFLICTING_EVIDENCE,
            resolve(input.copy(audioProbabilities = mapOf("background_noise" to 0.6, "belt_squeal" to 0.8))).outcome)
    }

    @Test fun conflictUsesStrongestKnownDiagnosticClassAndInclusiveConfiguredBoundary() {
        val input = ResultInput(reportedSymptoms = setOf("brake_squeal"),
            audioProbabilities = mapOf("belt_squeal" to 0.6))
        assertEquals(DiagnosticOutcome.CONFLICTING_EVIDENCE, resolve(input).outcome)
        assertEquals(DiagnosticOutcome.LOW_CONFIDENCE,
            resolve(input.copy(audioProbabilities = mapOf("belt_squeal" to 0.599))).outcome)
        assertEquals(DiagnosticOutcome.LOW_CONFIDENCE,
            resolve(input.copy(reportedSymptoms = setOf("belt_squeal", "brake_squeal"))).outcome)
        assertEquals(DiagnosticOutcome.LOW_CONFIDENCE,
            resolve(input.copy(reportedSymptoms = setOf("normal_idle", "unknown", "invalid-id", "hiss"))).outcome)
        for (nonDiagnostic in listOf("normal_idle", "unknown", "hiss")) {
            assertEquals(DiagnosticOutcome.LOW_CONFIDENCE,
                resolve(input.copy(audioProbabilities = mapOf(nonDiagnostic to 1.0))).outcome)
        }
    }

    @Test fun diagnosticAudioTiesDoNotCreateAnOrderDependentContradiction() {
        val input = ResultInput(reportedSymptoms = setOf("brake_squeal"),
            audioProbabilities = linkedMapOf("belt_squeal" to 0.8, "brake_squeal" to 0.8))
        assertEquals(DiagnosticOutcome.LOW_CONFIDENCE, resolve(input).outcome)
        assertEquals(resolve(input).outcome,
            resolve(input.copy(audioProbabilities = input.audioProbabilities.toList().reversed().toMap())).outcome)
    }

    @Test fun newJsonCatalogClassParticipatesWithoutAPolicyWhitelistChange() {
        val text = requireNotNull(javaClass.classLoader).getResourceAsStream("diagnosis/expert-knowledge.json")!!
            .bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        val entries = root.getJSONArray("entries")
        val template = (0 until entries.length()).map { entries.getJSONObject(it) }
            .first { it.getString("id") == "belt_squeal" }
        val added = JSONObject(template.toString()).put("id", "fixture_a")
        val candidates = added.getJSONArray("candidate_causes")
        for (index in 0 until candidates.length()) {
            val cause = candidates.getJSONObject(index)
            cause.put("id", "fixture_a." + cause.getString("id").substringAfter('.'))
        }
        entries.put(added)
        val knowledge = ExpertKnowledgeLoader.parse(root.toString())
        val triage = DiagnosisScorer.rank(knowledge, VehicleContext(), setOf("fixture_a"))
        val input = ResultInput(triage = triage, reportedSymptoms = setOf("belt_squeal"),
            audioProbabilities = mapOf("fixture_a" to 0.9))
        val conflict = resolve(input)
        assertEquals(DiagnosticOutcome.CONFLICTING_EVIDENCE, conflict.outcome)
        assertTrue(conflict.causes.isNotEmpty())
        assertTrue(conflict.causes.all { it.entryId == "fixture_a" && it.probability == null })
        assertEquals(DiagnosticOutcome.EXPERT_ONLY,
            resolve(input.copy(reportedSymptoms = setOf("fixture_a"))).outcome)
        assertEquals(DiagnosticOutcome.CONFLICTING_EVIDENCE,
            resolve(input.copy(reportedSymptoms = setOf("fixture_a"),
                audioProbabilities = mapOf("fixture_b" to 0.9))).outcome)
    }

    @Test fun lowAudioConfidenceTakesPriorityOverCalibratedCandidates() {
        val ranked = candidate("belt_squeal.belt_slip")
        val input = ResultInput(triage = triage(ranked), audioProbabilities = mapOf("belt_squeal" to 0.74),
            calibratedCauseProbabilities = mapOf(ranked.cause.id to 0.99))
        assertEquals(DiagnosticOutcome.LOW_CONFIDENCE, resolve(input).outcome)
        assertEquals(DiagnosticOutcome.CONFIDENT,
            resolve(input.copy(audioProbabilities = mapOf("belt_squeal" to 0.75))).outcome)
    }

    @Test fun lowNoiseBelowThresholdDoesNotHideManualExpertGuidance() {
        val ranked = candidate("belt_squeal.belt_slip")
        val input = ResultInput(triage = triage(ranked),
            audioProbabilities = mapOf("background_noise" to 0.59, "belt_squeal" to 0.3))
        assertEquals(DiagnosticOutcome.LOW_CONFIDENCE, resolve(input).outcome)
        assertEquals(1, resolve(input).causes.size)
    }

    @Test fun missingModelRetainsExpertGuidanceAndOtherwiseReportsUnavailable() {
        assertEquals(DiagnosticOutcome.MODEL_UNAVAILABLE, resolve(ResultInput(modelAvailable = false)).outcome)
        assertEquals(DiagnosticOutcome.EXPERT_ONLY,
            resolve(ResultInput(modelAvailable = false, triage = triage(candidate("belt_squeal.belt_slip")),
                audioProbabilities = mapOf("background_noise" to 1.0))).outcome)
        assertEquals(DiagnosticOutcome.LOW_CONFIDENCE, resolve(ResultInput()).outcome)
        assertFalse(resolve(ResultInput()).stopWarning)
        assertEquals(Severity.INSPECT, resolve(ResultInput()).severity)
    }

    @Test fun secondaryUncalibratedStopCandidateWarnsInEveryEvidenceState() {
        val first = candidate("belt_squeal.belt_slip")
        val second = candidate("brake_grinding.pad_wear", severity = Severity.STOP)
        val base = ResultInput(triage = triage(first, second), reportedSymptoms = setOf("brake_squeal"))
        for (input in listOf(base, base.copy(synthetic = true), base.copy(modelAvailable = false),
            base.copy(audioProbabilities = mapOf("background_noise" to 0.9)),
            base.copy(audioProbabilities = mapOf("belt_squeal" to 0.9)))) {
            val result = resolve(input)
            assertTrue(result.stopWarning)
            assertEquals(Severity.STOP, result.severity)
            assertTrue(result.safetyReasons.contains(second.entry.recommendedAction))
            assertNull(result.causes[1].probability)
        }
    }

    @Test fun explicitStopDoesNotDependOnProbabilityOrPresenceOfSafetyReasonText() {
        val ranked = candidate("brake_grinding.pad_wear", severity = Severity.STOP)
        val input = ResultInput(triage = triage(ranked).copy(severity = Severity.STOP),
            calibratedCauseProbabilities = mapOf(ranked.cause.id to 0.0))
        assertTrue(resolve(input).stopWarning)
        assertTrue(resolve(input.copy(recordingRejected = true)).stopWarning)
    }

    @Test fun calibrationRequiresFiniteBoundedValuesAndKnownCandidateIds() {
        val ranked = candidate("belt_squeal.belt_slip")
        for (value in listOf(-0.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                resolve(ResultInput(triage = triage(ranked), calibratedCauseProbabilities = mapOf(ranked.cause.id to value)))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolve(ResultInput(triage = triage(ranked), calibratedCauseProbabilities = mapOf("invented.cause" to 0.9)))
        }
        for (value in listOf(0.0, 1.0)) {
            assertEquals(value, resolve(ResultInput(triage = triage(ranked),
                calibratedCauseProbabilities = mapOf(ranked.cause.id to value))).causes.single().probability!!, 0.0)
        }
    }

    @Test fun invalidRealAudioIsRejectedRatherThanBecomingAConfidentResult() {
        for (audio in listOf(mapOf("belt_squeal" to Double.NaN), mapOf("belt_squeal" to -0.1),
            mapOf("belt_squeal" to 1.1), mapOf("invalid-class" to 0.9), mapOf("1invalid" to 0.9),
            mapOf("" to 0.9))) {
            assertThrows(IllegalArgumentException::class.java) { resolve(ResultInput(audioProbabilities = audio)) }
        }
    }

    @Test fun thresholdsAreReadFromConfigRatherThanDuplicatedInPolicy() {
        val ranked = candidate("belt_squeal.belt_slip")
        val input = ResultInput(triage = triage(ranked), audioProbabilities = mapOf("belt_squeal" to 0.7),
            calibratedCauseProbabilities = mapOf(ranked.cause.id to 0.7))
        assertEquals(DiagnosticOutcome.CONFIDENT,
            DiagnosticResultPolicy.resolve(input, config.copy(confidentProbability = 0.65)).outcome)
        assertEquals(DiagnosticOutcome.LOW_CONFIDENCE, resolve(input).outcome)
        val noise = ResultInput(audioProbabilities = mapOf("background_noise" to 0.5))
        assertEquals(DiagnosticOutcome.BACKGROUND_NOISE,
            DiagnosticResultPolicy.resolve(noise, config.copy(noiseProbability = 0.5)).outcome)
        val conflict = ResultInput(audioProbabilities = mapOf("belt_squeal" to 0.5),
            reportedSymptoms = setOf("brake_squeal"))
        assertEquals(DiagnosticOutcome.CONFLICTING_EVIDENCE,
            DiagnosticResultPolicy.resolve(conflict, config.copy(conflictProbability = 0.5)).outcome)
    }

    @Test fun allResultPathsCarryThePersistentIndonesianDisclaimer() {
        val inputs = listOf(ResultInput(), ResultInput(synthetic = true), ResultInput(modelAvailable = false),
            ResultInput(recordingRejected = true), ResultInput(audioProbabilities = mapOf("background_noise" to 1.0)),
            ResultInput(triage = triage(candidate("belt_squeal.belt_slip"))))
        for (input in inputs) {
            assertTrue(resolve(input).disclaimer.contains("Hasil ini hanya saran. Keputusan akhir ada pada mekanik."))
        }
        assertTrue(resolve(inputs.last()).disclaimer.contains("Pengetahuan uji"))
    }

    private fun resolve(input: ResultInput) = DiagnosticResultPolicy.resolve(input, config)

    private fun candidate(id: String, severity: Severity = Severity.INSPECT, score: Double = 5.0): ExpertRankedCause {
        val cause = ExpertCause(id, "Dugaan $id", 1.0, emptyList(), emptyList())
        val entry = ExpertEntry(id.substringBefore('.'), "Bunyi uji", "Deskripsi uji", emptySet(), emptySet(),
            listOf(cause), severity, listOf("Minta pemeriksaan mekanik"), "Tindakan $id", "unknown", "verified", true)
        return ExpertRankedCause(entry, cause, score, listOf(ScoreContribution("manual", score, "Keluhan pengguna")))
    }

    private fun triage(vararg causes: ExpertRankedCause) = TriageResult(causes.toList(), Severity.INSPECT,
        TriageStatus.RANKED, "Urutan dugaan", "Pengetahuan uji", "", emptyList())
}

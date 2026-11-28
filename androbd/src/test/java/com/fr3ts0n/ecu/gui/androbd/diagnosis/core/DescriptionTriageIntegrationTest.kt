package com.fr3ts0n.ecu.gui.androbd.diagnosis.core

import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.ExpertKnowledgeLoader
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.SymptomLexiconLoader
import org.junit.Assert.*
import org.junit.Test

class DescriptionTriageIntegrationTest {
    private val knowledge = ExpertKnowledgeLoader.load(requireNotNull(javaClass.classLoader?.getResourceAsStream("diagnosis/expert-knowledge.json")))
    private val parser = SymptomLexiconLoader.load(requireNotNull(javaClass.classLoader?.getResourceAsStream("diagnosis/symptom-synonyms.json")))

    @Test fun brakeSquealIsNotGrindingAndReceivesInspectionWithoutCertainty() {
        val match = parser.parse("Rem cit-cit saat ngerem")
        val result = DiagnosisScorer.rank(knowledge, VehicleContext(triggerConditions = match.triggerConditions), match.symptomIds)
        assertEquals(setOf("brake_squeal"), match.symptomIds)
        assertEquals(Severity.INSPECT, result.severity)
        assertEquals(TriageStatus.UNCERTAIN, result.status)
        assertTrue(result.causes.all { it.entry.id == "brake_squeal" })
    }

    @Test fun genericHissAsksForEvidenceAndNeverInventsAVacuumFault() {
        val match = parser.parse("Mendesis dari ruang mesin saat langsam")
        assertEquals(setOf("hiss"), match.symptomIds)
        assertEquals(setOf("steam", "sweet_smell", "temperature_rising"), match.followUpQuestions)
        val result = DiagnosisScorer.rank(knowledge, VehicleContext(triggerConditions = match.triggerConditions,
            location = match.location), match.symptomIds)
        assertTrue(result.causes.isEmpty())
        assertEquals(Severity.INSPECT, result.severity)
        listOf("steam", "temperature_rising").forEach { observation ->
            val warning = DiagnosisScorer.rank(knowledge, VehicleContext(flags = setOf(observation)), match.symptomIds)
            assertEquals(observation, Severity.STOP, warning.severity)
        }
    }

    @Test fun thermalPatternChangesWeightWithoutForcingCurrentTemperature() {
        val pattern = parser.parse("Tek tek saat dingin lalu hilang saat panas")
        assertEquals("fades_when_warm", pattern.temperaturePattern)
        assertFalse(pattern.needsClarification)
        val context = VehicleContext(fuelType = "petrol", fuelSystem = "carburetor",
            temperaturePattern = pattern.temperaturePattern)
        val withPattern = DiagnosisScorer.rank(knowledge, context, pattern.symptomIds)
        val withoutPattern = DiagnosisScorer.rank(knowledge, context.copy(temperaturePattern = null), pattern.symptomIds)
        val before = withoutPattern.causes.single { it.cause.id == "valvetrain_tick.piston_clearance" }
        val after = withPattern.causes.single { it.cause.id == "valvetrain_tick.piston_clearance" }
        assertEquals(3.0, after.score - before.score, 0.000001)
    }

    @Test fun combinedSafetySymptomsStayDangerousWithoutEngineStallAssumption() {
        val match = parser.parse("Rem blong dan setir mendadak berat")
        assertEquals("engine_stalled", match.followUpQuestions.first())
        assertFalse("engine_stalled" in match.flags)
        val result = DiagnosisScorer.rank(knowledge, VehicleContext(flags = match.flags), match.symptomIds)
        assertEquals(Severity.STOP, result.severity)
        assertTrue(result.safetyReasons.isNotEmpty())
    }

    @Test fun tentativePositiveBrakeLossCannotBeReassuredByNormalAudio() {
        val match = parser.parse("Mungkin rem blong")
        assertTrue(match.needsClarification)
        assertTrue("brake_change" in match.flags)
        val result = DiagnosisScorer.rank(knowledge, VehicleContext(flags = match.flags), match.symptomIds,
            mapOf("normal_idle" to 1.0))
        assertEquals(Severity.STOP, result.severity)
    }
}

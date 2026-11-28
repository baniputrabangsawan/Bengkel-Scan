package com.fr3ts0n.ecu.gui.androbd.diagnosis.core

import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.ExpertKnowledgeLoader
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.ObdConfigLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Domain fixtures simulate observations; these tests make no real-adapter/vehicle claim. */
class ObdEvidenceFusionTest {
    private val knowledge = ExpertKnowledgeLoader.load(asset("expert-knowledge.json"))
    private val config = ObdConfigLoader.load(asset("obd-config.json"))
    private val now = 100_000L
    private val petrol = VehicleContext(fuelType = "petrol", fuelSystem = "injection")
    private val audio = mapOf("belt_squeal" to 0.99)

    @Test fun misfireReranksBeforeTopThreeAndExplainsActualCodeStatusAndEcu() {
        val result = merge(codes("P0302", "pending"))
        assertTrue(result.used)
        assertEquals(listOf("obd_misfire.ignition", "obd_misfire.injector", "obd_misfire.compression"),
            result.triage.causes.map { it.cause.id })
        assertEquals(TriageStatus.UNCERTAIN, result.triage.status)
        result.triage.causes.forEach { cause ->
            val contribution = cause.contributions.single { it.source == "obd_dtc" }
            assertTrue(contribution.reason.contains("P0302"))
            assertTrue(contribution.reason.contains("ECU engine"))
            assertEquals(cause.score, cause.contributions.sumOf { it.delta }, 0.000001)
        }
    }

    @Test fun obdOnlyCandidateCannotInventAnUnreportedSoundFromVehicleContext() {
        val warmIdle = petrol.copy(engineTempState = "warm", triggerConditions = setOf("idle"), location = "under_hood")
        for (distribution in listOf(emptyMap(), audio)) {
            val neutral = merge(codes("P0171"), petrol, distribution).triage.causes.single { it.cause.id == "vacuum_leak.intake_leak" }
            val warm = merge(codes("P0171"), warmIdle, distribution).triage.causes.single { it.cause.id == "vacuum_leak.intake_leak" }
            assertEquals("An unrelated sound and vehicle context cannot supply unreported hiss evidence",
                neutral.score, warm.score, 0.000001)
        }
        // Reported hiss must still retain the existing context-based refinement.
        val reported = DiagnosisScorer.rank(knowledge, warmIdle, setOf("vacuum_leak")).causes.single {
            it.cause.id == "vacuum_leak.intake_leak"
        }
        val withoutWarmIdle = DiagnosisScorer.rank(knowledge, petrol, setOf("vacuum_leak")).causes.single {
            it.cause.id == "vacuum_leak.intake_leak"
        }
        assertTrue(reported.score > withoutWarmIdle.score)
    }

    @Test fun repeatedCodesDoNotMultiplyFamilyWeightOrInventASpecificFailedPart() {
        val one = merge(codes("P0301"))
        val repeated = merge(codes("P0301").copy(codes = listOf(
            ObdDtcEvidence("P0301", "stored", now, "engine"),
            ObdDtcEvidence("P0301", "pending", now, "engine"),
            ObdDtcEvidence("P0302", "stored", now, "engine"))))
        assertEquals(one.triage.causes.map { it.score }, repeated.triage.causes.map { it.score })
    }

    @Test fun provenanceDisconnectMissingSessionAndUnsupportedContextPreserveBaselineExactly() {
        val evidence = codes("P0301")
        val baseline = DiagnosisScorer.rank(knowledge, petrol, emptySet(), audio)
        listOf(evidence.copy(source = "NONE"), evidence.copy(source = "FILE"),
            evidence.copy(source = "DEMO"), evidence.copy(connected = false), evidence.copy(sessionId = 0))
            .forEach {
                val result = merge(it)
                assertFalse(result.used)
                assertEquals(baseline, result.triage)
                assertTrue(result.notes.isNotEmpty())
            }
        listOf(petrol.copy(fuelSystem = "carburetor"), petrol.copy(fuelType = "electric"),
            petrol.copy(fuelType = "hybrid"), petrol.copy(fuelType = null)).forEach { context ->
            val result = merge(evidence, context)
            assertFalse(result.used)
            assertEquals(DiagnosisScorer.rank(knowledge, context, emptySet(), audio), result.triage)
        }
    }

    @Test fun staleFutureAndNonfiniteEvidenceCannotAffectRanking() {
        val baseline = DiagnosisScorer.rank(knowledge, petrol, emptySet(), audio)
        listOf(codes("P0301").copy(codes = listOf(ObdDtcEvidence("P0301", "stored", now - config.dtcFreshnessMs - 1))),
            codes("P0301").copy(codes = listOf(ObdDtcEvidence("P0301", "stored", now + 1))),
            real(samples = trimSamples().map { it.copy(receivedAtMs = now - config.pidFreshnessMs - 1) }),
            real(samples = trimSamples().map { it.copy(value = Double.NaN) }),
            real(samples = trimSamples().map { it.copy(receivedAtMs = now + 1) })).forEach {
            val result = merge(it)
            assertFalse(result.used)
            assertEquals(baseline, result.triage)
        }
    }

    @Test fun permanentOnlyAndUnknownCodesAreNotActiveFaultsOrHealthyAssertions() {
        listOf(codes("P0301", "permanent"), codes("P2999"), codes("P0301", "unknown"), real()).forEach {
            val result = merge(it)
            assertFalse(result.used)
            assertEquals(DiagnosisScorer.rank(knowledge, petrol, emptySet(), audio), result.triage)
            assertTrue(result.notes.isNotEmpty())
        }
        assertTrue(merge(codes("P0301", "permanent")).notes.any { "bukan bukti gangguan aktif" in it })
        assertTrue(merge(codes("P2999")).notes.any { "belum memiliki aturan" in it })
    }

    @Test fun warmClosedLoopRepeatedPositiveTrimNamesBankMeasuredValuesAndDirection() {
        val result = merge(real(samples = trimSamples()), audioProbabilities = emptyMap())
        assertTrue(result.used)
        assertEquals("vacuum_leak.intake_leak", result.triage.causes.first().cause.id)
        val reason = result.triage.causes.first().contributions.single { it.source == "obd_fuel_trim" }.reason
        listOf("bank 1", "ECU engine", "STFT 10.0%", "LTFT 10.0%", "3 pasangan", "menambah").forEach {
            assertTrue(reason, it in reason)
        }
    }

    @Test fun scheduledCoolantAndFuelStatusObservationsRemainEligibleWithoutRetimestamping() {
        // Existing scanner schedules coolant every 10 s and fuel-system status every 5 s.
        val samples = trimSamples().filter {
            it.mnemonic != "engine_coolant_temperature" && it.mnemonic != "status_fuel_system_1"
        } + listOf(
            ObdPidSample("engine_coolant_temperature", 85.0, now - 10_000, "engine"),
            ObdPidSample("status_fuel_system_1", 2.0, now - 5_000, "engine"))
        assertTrue(merge(real(samples = samples)).used)
        val expiredCoolant = samples.map {
            if (it.mnemonic == "engine_coolant_temperature")
                it.copy(receivedAtMs = now - config.fuelTrim.supportFreshnessMs - 1) else it
        }
        assertFalse(merge(real(samples = expiredCoolant)).used)
        val expiredStatus = samples.map {
            if (it.mnemonic == "status_fuel_system_1")
                it.copy(receivedAtMs = now - config.fuelTrim.supportFreshnessMs - 1) else it
        }
        assertFalse(merge(real(samples = expiredStatus)).used)
    }

    @Test fun negativeTrimAndRichCodesNeverActivatePositiveVacuumLeakRule() {
        val negative = merge(real(samples = trimSamples(-10.0)), audioProbabilities = emptyMap())
        assertEquals(listOf("obd_fuel_metering.excess"), negative.triage.causes.map { it.cause.id })
        assertTrue(negative.triage.causes.single().reason.contains("mengurangi"))
        listOf("P0172", "P0175").forEach {
            val rich = merge(codes(it), audioProbabilities = emptyMap())
            assertEquals(listOf("obd_fuel_metering.excess"), rich.triage.causes.map { cause -> cause.cause.id })
        }
    }

    @Test fun leanDtcAndRepeatedTrimHaveIndependentExplicitContributions() {
        val result = merge(codes("P0171").copy(samples = trimSamples()), audioProbabilities = emptyMap())
        val intake = result.triage.causes.first { it.cause.id == "vacuum_leak.intake_leak" }
        assertEquals(setOf("obd_dtc", "obd_fuel_trim"), intake.contributions.filter { it.source.startsWith("obd_") }.map { it.source }.toSet())
        assertTrue(intake.reason.contains("P0171"))
        assertTrue(intake.reason.contains("bank 1"))
    }

    @Test fun missingUnsupportedColdStoppedOrOpenLoopPidsNeverTriggerTrimRules() {
        val samples = trimSamples()
        val invalid = listOf(
            samples.filter { it.mnemonic != "fuel_trim_long_b1" },
            samples.filter { it.mnemonic != "engine_coolant_temperature" },
            samples.filter { it.mnemonic != "status_fuel_system_1" },
            samples.map { if (it.mnemonic == "engine_coolant_temperature") it.copy(value = 20.0) else it },
            samples.map { if (it.mnemonic == "engine_speed") it.copy(value = 0.0) else it },
            samples.map { if (it.mnemonic == "status_fuel_system_1") it.copy(value = 1.0) else it },
            samples.filterNot { it.mnemonic.startsWith("fuel_trim") && it.receivedAtMs < now },
            samples.map { if (it.mnemonic == "status_fuel_system_1") it.copy(mnemonic = "status_misfires") else it })
        invalid.forEach { assertFalse(merge(real(samples = it)).used) }
        assertFalse(merge(real(samples = samples), petrol.copy(fuelType = "diesel")).used)
        assertFalse(merge(real(samples = samples), petrol.copy(fuelSystem = null)).used)
    }

    @Test fun neverCombinesBanksEcusOrReusesOneCachedLongTermObservation() {
        val samples = trimSamples()
        listOf(
            samples.map { if (it.mnemonic == "fuel_trim_long_b1") it.copy(mnemonic = "fuel_trim_long_b2") else it },
            samples.map { if (it.mnemonic == "fuel_trim_long_b1") it.copy(ecu = "other") else it },
            samples.filterNot { it.mnemonic == "fuel_trim_long_b1" && it.receivedAtMs != now },
            samples.map { if (it.mnemonic == "fuel_trim_long_b1") it.copy(receivedAtMs = now) else it }
        ).forEach { assertFalse(merge(real(samples = it)).used) }
        val bank2 = samples.map { it.copy(mnemonic = it.mnemonic.replace("_b1", "_b2")) }
        assertTrue(merge(real(samples = bank2)).used)
    }

    @Test fun oneSpikingPairAndContradictoryRecentPatternAreNotRepeatedEvidence() {
        val mixed = trimSamples().map {
            if (it.mnemonic.startsWith("fuel_trim") && it.receivedAtMs == now - 1000) it.copy(value = 0.0) else it
        }
        assertFalse(merge(real(samples = mixed)).used)
        val shortSpan = trimSamples().map { it.copy(receivedAtMs = now - (now - it.receivedAtMs) / 10) }
        assertFalse(merge(real(samples = shortSpan)).used)
    }

    @Test fun dieselMisfireExcludesSparkIgnitionWithoutAssumingPetrol() {
        val result = merge(codes("P0301"), petrol.copy(fuelType = "diesel"), emptyMap())
        assertTrue(result.used)
        assertEquals(setOf("obd_misfire.injector", "obd_misfire.compression"), result.triage.causes.map { it.cause.id }.toSet())
    }

    @Test fun stopSafetySurvivesDisagreementNoCodesDisconnectAndObdTopThreeCutoff() {
        val context = petrol.copy(flags = setOf("heavy_engine_knock"))
        listOf(codes("P0301"), real(), real(samples = trimSamples()), codes("P0301").copy(connected = false)).forEach {
            val result = merge(it, context, mapOf("normal_idle" to 1.0))
            assertEquals(Severity.STOP, result.triage.severity)
            assertTrue(result.triage.safetyReasons.isNotEmpty())
        }
        val result = ObdEvidenceFusion.merge(knowledge, petrol, setOf("brake_grinding"), audio,
            codes("P0301"), config, now)
        assertEquals(Severity.STOP, result.triage.severity)
        assertTrue(result.triage.safetyReasons.isNotEmpty())
    }

    @Test fun causeApiActivatesOnlyTargetAndRejectsUnknownNonfiniteAndBlankContributions() {
        val contribution = ScoreContribution("obd_dtc", 20.0, "P0301 pending: petunjuk draf.")
        val result = DiagnosisScorer.rank(knowledge, petrol, emptySet(), emptyMap(), mapOf("obd_misfire.injector" to listOf(contribution)))
        assertEquals(listOf("obd_misfire.injector"), result.causes.map { it.cause.id })
        listOf(mapOf("not_known.cause" to listOf(contribution)),
            mapOf("obd_misfire.injector" to listOf(contribution.copy(delta = Double.NaN))),
            mapOf("obd_misfire.injector" to listOf(contribution.copy(delta = Double.POSITIVE_INFINITY))),
            mapOf("obd_misfire.injector" to listOf(contribution.copy(reason = " ")))).forEach {
            assertThrows(IllegalArgumentException::class.java) { DiagnosisScorer.rank(knowledge, petrol, emptySet(), emptyMap(), it) }
        }
        val diesel = DiagnosisScorer.rank(knowledge, petrol.copy(fuelType = "diesel"), emptySet(), emptyMap(),
            mapOf("obd_misfire.ignition" to listOf(contribution)))
        assertTrue(diesel.causes.isEmpty())
        assertEquals(DiagnosisScorer.rank(knowledge, petrol, setOf("belt_squeal")),
            DiagnosisScorer.rank(knowledge, petrol, setOf("belt_squeal"), emptyMap(), emptyMap()))
    }

    private fun merge(evidence: ObdEvidence, context: VehicleContext = petrol,
        audioProbabilities: Map<String, Double> = audio) =
        ObdEvidenceFusion.merge(knowledge, context, emptySet(), audioProbabilities, evidence, config, now)
    private fun codes(code: String, status: String = "stored") = real(codes = listOf(ObdDtcEvidence(code, status, now, "engine")))
    private fun real(codes: List<ObdDtcEvidence> = emptyList(), samples: List<ObdPidSample> = emptyList()) =
        ObdEvidence(7, true, "REAL", codes, samples)
    private fun trimSamples(value: Double = 10.0): List<ObdPidSample> = listOf(now - 2000, now - 1000, now).flatMap { at ->
        listOf(ObdPidSample("engine_speed", 800.0, at, "engine"),
            ObdPidSample("engine_coolant_temperature", 85.0, at, "engine"),
            ObdPidSample("status_fuel_system_1", 2.0, at, "engine"),
            ObdPidSample("fuel_trim_short_b1", value, at, "engine"),
            ObdPidSample("fuel_trim_long_b1", value, at, "engine"))
    }
    private fun asset(name: String) = requireNotNull(javaClass.classLoader?.getResourceAsStream("diagnosis/$name"))
}

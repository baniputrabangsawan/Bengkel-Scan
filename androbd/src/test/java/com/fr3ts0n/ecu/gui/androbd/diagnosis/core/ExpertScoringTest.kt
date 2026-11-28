package com.fr3ts0n.ecu.gui.androbd.diagnosis.core

import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.ExpertKnowledgeLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpertScoringTest {
    @Test fun onlyAnObservedWarmFadeAddsThePistonClearanceModifier() {
        val cold = VehicleContext(fuelType = "petrol", fuelSystem = "carburetor", engineTempState = "cold")
        val unobserved = DiagnosisScorer.rank(knowledge, cold, setOf("valvetrain_tick"))
        val observed = DiagnosisScorer.rank(knowledge,
            cold.copy(flags = setOf("fades_when_warm")), setOf("valvetrain_tick"))
        assertEquals("valvetrain_tick.valve_clearance", unobserved.causes.first().cause.id)
        assertEquals("valvetrain_tick.piston_clearance", observed.causes.first().cause.id)
        val before = unobserved.causes.single { it.cause.id == "valvetrain_tick.piston_clearance" }
        val after = observed.causes.single { it.cause.id == "valvetrain_tick.piston_clearance" }
        assertEquals(3.0, after.score - before.score, 0.000001)
        val heavy = DiagnosisScorer.rank(knowledge,
            cold.copy(flags = setOf("fades_when_warm", "heavy_engine_knock")), setOf("valvetrain_tick"))
        assertEquals(listOf("valvetrain_tick.internal_knock", "valvetrain_tick.piston_clearance",
            "valvetrain_tick.valve_clearance"), heavy.causes.map { it.cause.id })
        assertEquals(Severity.STOP, heavy.severity)
    }

    @Test fun bundledContextScenariosHaveExplicitExpectedCauseOrder() {
        // Expectations are derived from the bundled draft rules, not clinical calibration.
        val scenarios = listOf(
            scenario("cold accessory belt", "belt_squeal",
                VehicleContext(engineTempState = "cold", triggerConditions = setOf("cold"), location = "under_hood"),
                "belt_slip", "pulley_bearing", "accessory_load"),
            scenario("warm accessory belt", "belt_squeal",
                VehicleContext(engineTempState = "warm", triggerConditions = setOf("idle"), location = "under_hood"),
                "pulley_bearing", "belt_slip", "accessory_load"),
            scenario("belt under hydraulic steering load", "belt_squeal",
                VehicleContext(powerSteeringType = "hydraulic", triggerConditions = setOf("turning")),
                "accessory_load", "belt_slip", "pulley_bearing"),
            scenario("cold petrol injection ticking", "valvetrain_tick",
                VehicleContext(fuelType = "petrol", fuelSystem = "injection", engineTempState = "cold"),
                "injector_tick", "valve_clearance", "piston_clearance"),
            scenario("cold diesel injection ticking", "valvetrain_tick",
                VehicleContext(fuelType = "diesel", fuelSystem = "injection", engineTempState = "cold"),
                "injector_tick", "valve_clearance", "piston_clearance"),
            scenario("cold carbureted petrol ticking", "valvetrain_tick",
                VehicleContext(fuelType = "petrol", fuelSystem = "carburetor", engineTempState = "cold"),
                "valve_clearance", "piston_clearance", "internal_knock"),
            scenario("cold ticking with explicitly observed warm fade", "valvetrain_tick",
                VehicleContext(fuelType = "petrol", fuelSystem = "carburetor", engineTempState = "cold",
                    flags = setOf("fades_when_warm")),
                "piston_clearance", "valve_clearance", "internal_knock"),
            scenario("warm carbureted petrol ticking", "valvetrain_tick",
                VehicleContext(fuelType = "petrol", fuelSystem = "carburetor", engineTempState = "warm"),
                "valve_clearance", "internal_knock", "piston_clearance"),
            scenario("warm injected petrol ticking", "valvetrain_tick",
                VehicleContext(fuelType = "petrol", fuelSystem = "injection", engineTempState = "warm"),
                "injector_tick", "valve_clearance", "internal_knock"),
            scenario("heavy knock cannot be ruled out by cold ticking", "valvetrain_tick",
                VehicleContext(fuelType = "petrol", fuelSystem = "carburetor", engineTempState = "cold",
                    flags = setOf("heavy_engine_knock")),
                "internal_knock", "valve_clearance", "piston_clearance", severity = Severity.STOP),
            scenario("warm petrol detonation under load", "detonation_pinging",
                VehicleContext(fuelType = "petrol", engineTempState = "warm", triggerConditions = setOf("throttle")),
                "petrol_detonation", "mechanical_knock"),
            scenario("cold diesel combustion is not petrol detonation", "detonation_pinging",
                VehicleContext(fuelType = "diesel", engineTempState = "cold", triggerConditions = setOf("throttle")),
                "diesel_combustion", "mechanical_knock"),
            scenario("missing fuel does not silently assume petrol", "detonation_pinging",
                VehicleContext(), "diesel_combustion", "petrol_detonation", "mechanical_knock"),
            scenario("old carbureted petrol intake complaint", "vacuum_leak",
                VehicleContext(fuelType = "petrol", fuelSystem = "carburetor", mileageKm = 240_000,
                    engineTempState = "cold", triggerConditions = setOf("idle")),
                "carburetor_metering", "intake_leak", "ignition_misfire"),
            scenario("injected petrol intake complaint", "vacuum_leak",
                VehicleContext(fuelType = "petrol", fuelSystem = "injection", engineTempState = "cold"),
                "intake_leak", "ignition_misfire"),
            scenario("diesel air path excludes carburetor and spark ignition", "vacuum_leak",
                VehicleContext(fuelType = "diesel", fuelSystem = "injection", triggerConditions = setOf("throttle")),
                "diesel_air_path"),
            scenario("missing intake context preserves competing systems cautiously", "vacuum_leak",
                VehicleContext(), "intake_leak", "carburetor_metering", "diesel_air_path"),
            scenario("cold hydraulic steering", "power_steering_whine",
                VehicleContext(powerSteeringType = "hydraulic", engineTempState = "cold",
                    triggerConditions = setOf("turning"), location = "front"),
                "hydraulic_fluid", "pump_wear", "other_accessory"),
            scenario("warm hydraulic steering", "power_steering_whine",
                VehicleContext(powerSteeringType = "hydraulic", engineTempState = "warm",
                    triggerConditions = setOf("turning"), location = "front"),
                "pump_wear", "hydraulic_fluid", "other_accessory"),
            scenario("EPS has no hydraulic pump diagnosis", "power_steering_whine",
                VehicleContext(powerSteeringType = "eps", triggerConditions = setOf("turning")),
                "other_accessory"),
            scenario("unknown steering type does not assert hydraulic system", "power_steering_whine",
                VehicleContext(triggerConditions = setOf("turning")),
                "hydraulic_fluid", "pump_wear", "other_accessory"),
            scenario("wheel hum previously observed while turning", "wheel_bearing_hum",
                VehicleContext(triggerConditions = setOf("turning"), location = "wheel"),
                "bearing_wear", "tyre_noise", "driveline_noise"),
            scenario("wheel hum previously linked to throttle", "wheel_bearing_hum",
                VehicleContext(triggerConditions = setOf("throttle"), location = "rear"),
                "driveline_noise", "bearing_wear", "tyre_noise"),
            scenario("wheel hum following tyre change", "wheel_bearing_hum",
                VehicleContext(flags = setOf("tyre_recent_change")),
                "tyre_noise", "bearing_wear", "driveline_noise"),
            scenario("wheel play is a stop condition", "wheel_bearing_hum",
                VehicleContext(flags = setOf("wheel_play"), location = "wheel"),
                "bearing_wear", "tyre_noise", "driveline_noise", severity = Severity.STOP),
            scenario("grinding already observed during braking", "brake_grinding",
                VehicleContext(triggerConditions = setOf("braking"), location = "wheel"),
                "friction_wear", "trapped_debris", "hardware_issue", severity = Severity.STOP),
            scenario("debris possibility does not downgrade brake warning", "brake_grinding",
                VehicleContext(flags = setOf("recent_debris")),
                "trapped_debris", "friction_wear", "hardware_issue", severity = Severity.STOP),
            scenario("three cylinder familiar idle is not a fault", "normal_idle",
                VehicleContext(fuelType = "petrol", cylinderCount = 3, engineTempState = "warm"), severity = null),
            scenario("background audio does not invent a cause", "background_noise",
                VehicleContext(), severity = null),
            scenario("unknown sound does not invent a cause", "unknown", VehicleContext(), severity = null),
            scenario("three cylinders do not increase ticking damage weight", "valvetrain_tick",
                VehicleContext(fuelType = "petrol", fuelSystem = "carburetor", cylinderCount = 3, engineTempState = "warm"),
                "valve_clearance", "internal_knock", "piston_clearance")
        )
        scenarios.forEach { case ->
            val result = DiagnosisScorer.rank(knowledge, case.context, setOf(case.symptom))
            assertEquals(case.label, case.expectedCauseIds, result.causes.map { it.cause.id })
            assertEquals(case.label, TriageStatus.UNCERTAIN, result.status)
            case.severity?.let { assertEquals(case.label, it, result.severity) }
            assertEquals(case.label, knowledge.disclaimer, result.disclaimer)
            assertEquals(case.label, knowledge.reviewNotice, result.reviewNotice)
            assertTrue(case.label, result.message.isNotBlank())
            result.causes.forEach { ranked ->
                assertEquals(case.label, ranked.score, ranked.contributions.sumOf { it.delta }, 0.000001)
                assertTrue(case.label, ranked.reason.isNotBlank())
                assertTrue(case.label, ranked.contributions.all { it.reason.isNotBlank() })
            }
        }
    }

    @Test fun threeCylinderCountAddsAnExplainedZeroRatherThanFaultEvidence() {
        val context = VehicleContext(fuelType = "petrol", fuelSystem = "carburetor", engineTempState = "warm")
        val withoutCount = DiagnosisScorer.rank(knowledge, context, setOf("valvetrain_tick"))
        val withCount = DiagnosisScorer.rank(knowledge, context.copy(cylinderCount = 3), setOf("valvetrain_tick"))
        assertEquals(withoutCount.causes.map { it.cause.id to it.score }, withCount.causes.map { it.cause.id to it.score })
        val internal = withCount.causes.single { it.cause.id == "valvetrain_tick.internal_knock" }
        assertTrue(internal.contributions.any { it.delta == 0.0 && it.reason.contains("tiga silinder") })
    }

    @Test fun missingHydraulicContextIsExplainedAndEpsCannotRetainPumpCandidates() {
        val missing = DiagnosisScorer.rank(knowledge, VehicleContext(), setOf("power_steering_whine"))
        missing.causes.filter { it.cause.id != "power_steering_whine.other_accessory" }.forEach {
            assertTrue(it.contributions.any { term ->
                term.delta == 0.0 && term.reason == knowledge.scoring.missingContextReason
            })
        }
        val eps = DiagnosisScorer.rank(knowledge, VehicleContext(powerSteeringType = "eps"), setOf("power_steering_whine"))
        assertEquals(listOf("power_steering_whine.other_accessory"), eps.causes.map { it.cause.id })
    }

    private data class Scenario(val label: String, val symptom: String, val context: VehicleContext,
        val expectedCauseIds: List<String>, val severity: Severity?)

    private fun scenario(label: String, symptom: String, context: VehicleContext,
        vararg suffixes: String, severity: Severity? = Severity.INSPECT) =
        Scenario(label, symptom, context, suffixes.map { "$symptom.$it" }, severity)

    @Test fun configuredLimitAndEvidenceIterationOrderDoNotChangeRanking() {
        val model = fixture(cause("test_sound.a", 2.0), cause("test_sound.c", 2.0))
        val second = model.entries.single().copy(id = "second_sound",
            candidateCauses = listOf(cause("second_sound.b", 2.0)))
        val expanded = model.copy(entries = model.entries + second)
        val forward = DiagnosisScorer.rank(expanded, VehicleContext(),
            linkedSetOf("test_sound", "second_sound"), linkedMapOf("test_sound" to 0.8, "second_sound" to 0.8))
        val reverse = DiagnosisScorer.rank(expanded.copy(entries = expanded.entries.reversed()), VehicleContext(),
            linkedSetOf("second_sound", "test_sound"), linkedMapOf("second_sound" to 0.8, "test_sound" to 0.8))
        assertEquals(listOf("second_sound.b", "test_sound.a", "test_sound.c"), forward.causes.map { it.cause.id })
        assertEquals(forward.causes, reverse.causes)
        val limited = DiagnosisScorer.rank(expanded.copy(scoring = expanded.scoring.copy(maxResults = 1)),
            VehicleContext(), setOf("test_sound", "second_sound"))
        assertEquals(listOf("second_sound.b"), limited.causes.map { it.cause.id })
    }

    @Test fun partialOrUnknownSafetyConditionsDoNotInventAnEscalation() {
        val model = fixture(cause("test_sound.a", 1.0))
        val rule = SafetyRule(listOf(
            ContextPredicate("flags", "contains", textValue = "wheel_play"),
            text("location", "wheel")
        ), Severity.STOP, "Kelonggaran roda dilaporkan di area roda.")
        val configured = model.copy(safetyRules = listOf(rule))
        listOf(VehicleContext(flags = setOf("wheel_play")), VehicleContext(location = "wheel"),
            VehicleContext(location = "unknown", flags = setOf("wheel_play"))).forEach { context ->
            val result = DiagnosisScorer.rank(configured, context, setOf("test_sound"))
            assertEquals(Severity.INSPECT, result.severity)
            assertTrue(result.safetyReasons.isEmpty())
        }
        val matched = DiagnosisScorer.rank(configured,
            VehicleContext(location = "wheel", flags = setOf("wheel_play")), setOf("test_sound"))
        assertEquals(Severity.STOP, matched.severity)
        assertEquals(listOf(rule.reason), matched.safetyReasons)
    }

    private val knowledge by lazy {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("diagnosis/expert-knowledge.json")) {
            "Missing bundled expert catalog"
        }
        ExpertKnowledgeLoader.load(stream)
    }

    @Test fun signedBreakdownIncludesEveryNumericTermAndNonemptyReasons() {
        val model = fixture(cause("test_sound.clearance", 1.25,
            modifiers = listOf(
                ContextModifier(listOf(text("engine_temp_state", "cold")), -0.5, "Kondisi dingin mengurangi dukungan."),
                ContextModifier(listOf(text("engine_temp_state", "cold")), 0.0, "Penyesuaian nol tetap dijelaskan.")
            )))
        val result = DiagnosisScorer.rank(model,
            VehicleContext(engineTempState = "cold", triggerConditions = setOf("cold"), location = "under_hood"),
            setOf("test_sound"), mapOf("test_sound" to 0.75))
        val ranked = result.causes.single()
        assertEquals(6.25, ranked.score, 0.000001)
        assertEquals(listOf(-0.5, 0.0, 1.0, 1.0, 1.25, 1.5, 2.0), ranked.contributions.map { it.delta }.sorted())
        assertEquals(ranked.score, ranked.contributions.sumOf { it.delta }, 0.000001)
        assertTrue(ranked.contributions.all { it.reason.isNotBlank() && it.source.isNotBlank() })
        assertTrue(ranked.reason.contains("Penyesuaian nol tetap dijelaskan."))
        assertEquals(TriageStatus.UNCERTAIN, result.status)
    }

    @Test fun manualAndAudioEvidenceDoNotInventTriggerOrLocationObservations() {
        val model = fixture(cause("test_sound.clearance", 1.0))
        val result = DiagnosisScorer.rank(model, VehicleContext(), setOf("test_sound"), mapOf("test_sound" to 1.0))
        assertEquals(5.0, result.causes.single().score, 0.000001)
        assertFalse(result.causes.single().contributions.any { it.reason == model.scoring.triggerReason })
        assertFalse(result.causes.single().contributions.any { it.reason == model.scoring.locationReason })
    }

    @Test fun manualEvidenceWorksWithoutAudioAndAudioBelowThresholdDoesNotActivate() {
        val model = fixture(cause("test_sound.clearance", 1.0))
        assertEquals(3.0, DiagnosisScorer.rank(model, VehicleContext(), setOf("test_sound"))
            .causes.single().score, 0.000001)
        val below = DiagnosisScorer.rank(model, VehicleContext(), emptySet(), mapOf("test_sound" to 0.599999))
        assertTrue(below.causes.isEmpty())
        val threshold = DiagnosisScorer.rank(model, VehicleContext(), emptySet(), mapOf("test_sound" to 0.6))
        assertEquals(2.2, threshold.causes.single().score, 0.000001)
        val manualWithLowAudio = DiagnosisScorer.rank(model, VehicleContext(), setOf("test_sound"), mapOf("test_sound" to 0.2))
        assertEquals(3.0, manualWithLowAudio.causes.single().score, 0.000001)
    }

    @Test fun audioProbabilitiesAreIndependentEvidenceNotRequiredToSumToOne() {
        val result = DiagnosisScorer.rank(knowledge, VehicleContext(), emptySet(),
            mapOf("belt_squeal" to 0.9, "valvetrain_tick" to 0.9))
        assertTrue(result.causes.isNotEmpty())
        assertEquals(TriageStatus.UNCERTAIN, result.status)
        assertTrue(result.causes.all { candidate -> candidate.contributions.any { it.delta == 1.8 } })
    }

    @Test fun invalidAudioAndUnknownEvidenceIdsAreRejectedRatherThanDropped() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.01, 1.01).forEach { probability ->
            assertThrows(IllegalArgumentException::class.java) {
                DiagnosisScorer.rank(knowledge, VehicleContext(), emptySet(), mapOf("belt_squeal" to probability))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosisScorer.rank(knowledge, VehicleContext(), setOf("unregistered_sound"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosisScorer.rank(knowledge, VehicleContext(), emptySet(), mapOf("unregistered_sound" to 0.0))
        }
    }

    @Test fun knownApplicabilityMismatchExcludesEvenAnOtherwiseVeryHighScore() {
        val model = fixture(cause("test_sound.petrol_only", 1000.0,
            applicability = listOf(text("fuel_type", "petrol"))))
        val result = DiagnosisScorer.rank(model, VehicleContext(fuelType = "diesel"), setOf("test_sound"))
        assertTrue(result.causes.isEmpty())
        assertEquals(TriageStatus.UNCERTAIN, result.status)
    }

    @Test fun missingAndUnknownApplicabilityContextRetainsCandidateWithExplicitCaution() {
        val model = fixture(cause("test_sound.petrol_only", 3.0,
            applicability = listOf(text("fuel_type", "petrol"))))
        listOf(VehicleContext(), VehicleContext(fuelType = "unknown")).forEach { context ->
            val result = DiagnosisScorer.rank(model, context, setOf("test_sound"))
            assertEquals(listOf("test_sound.petrol_only"), result.causes.map { it.cause.id })
            assertEquals(TriageStatus.UNCERTAIN, result.status)
            assertTrue(result.causes.single().contributions.any {
                it.delta == 0.0 && it.reason == model.scoring.missingContextReason
            })
        }
    }

    @Test fun predicateArraysUseAndAndNumericBoundsAreInclusive() {
        val modifier = ContextModifier(listOf(
            text("fuel_type", "petrol"), ContextPredicate("cylinder_count", "eq", numberValue = 3.0),
            ContextPredicate("mileage_km", "gte", numberValue = 100.0),
            ContextPredicate("mileage_km", "lte", numberValue = 200.0),
            ContextPredicate("trigger_conditions", "contains", textValue = "idle"),
            ContextPredicate("flags", "contains", textValue = "fades_when_warm")
        ), 4.0, "Seluruh konteks yang dipilih cocok.")
        val model = fixture(cause("test_sound.clearance", 1.0, modifiers = listOf(modifier)))
        val context = VehicleContext(fuelType = "petrol", cylinderCount = 3, mileageKm = 100,
            triggerConditions = setOf("idle"), flags = setOf("fades_when_warm"))
        listOf(100, 200).forEach { mileage ->
            assertEquals(7.0, DiagnosisScorer.rank(model, context.copy(mileageKm = mileage), setOf("test_sound"))
                .causes.single().score, 0.000001)
        }
        listOf(context.copy(fuelType = "diesel"), context.copy(cylinderCount = 4),
            context.copy(mileageKm = 99), context.copy(mileageKm = 201), context.copy(mileageKm = null),
            context.copy(triggerConditions = emptySet()), context.copy(flags = emptySet()))
            .forEach { mismatch ->
                assertEquals(3.0, DiagnosisScorer.rank(model, mismatch, setOf("test_sound"))
                    .causes.single().score, 0.000001)
            }
    }

    @Test fun deterministicTopThreeBreaksTiesByCauseIdRegardlessOfInputOrder() {
        val ids = listOf("test_sound.e", "test_sound.c", "test_sound.a", "test_sound.d", "test_sound.b")
        val model = fixture(*ids.map { cause(it, 2.0) }.toTypedArray())
        val forward = DiagnosisScorer.rank(model, VehicleContext(), linkedSetOf("test_sound"))
        val reverse = DiagnosisScorer.rank(model.copy(entries = model.entries.map {
            it.copy(candidateCauses = it.candidateCauses.reversed())
        }), VehicleContext(), linkedSetOf("test_sound"))
        assertEquals(listOf("test_sound.a", "test_sound.b", "test_sound.c"), forward.causes.map { it.cause.id })
        assertEquals(forward.causes.map { it.cause.id to it.score }, reverse.causes.map { it.cause.id to it.score })
        assertEquals(forward.causes.map { it.contributions }, reverse.causes.map { it.contributions })
        assertEquals(3, forward.causes.size)
    }

    @Test fun scoresBelowMinimumAreOmittedWithoutPaddingOrClamping() {
        val model = fixture(cause("test_sound.a", 0.0), cause("test_sound.b", 0.0,
            modifiers = listOf(ContextModifier(listOf(text("engine_temp_state", "cold")), -10.0,
                "Kondisi dingin mengurangi dukungan."))))
        val result = DiagnosisScorer.rank(model, VehicleContext(engineTempState = "cold"), setOf("test_sound"))
        assertEquals(listOf("test_sound.a"), result.causes.map { it.cause.id })
        assertEquals(2.0, result.causes.single().score, 0.000001)
        assertFalse(result.causes.single().reason.contains("mengurangi dukungan"))
        val exactThreshold = model.copy(scoring = model.scoring.copy(minimumScore = 2.0))
        assertEquals(1, DiagnosisScorer.rank(exactThreshold, VehicleContext(engineTempState = "cold"), setOf("test_sound"))
            .causes.size)
    }

    @Test fun entrySeveritySurvivesApplicabilityExclusionAndTopThreeTruncation() {
        val model = fixture(cause("test_sound.low", 0.0,
            applicability = listOf(text("fuel_type", "petrol"))))
        val hazardous = model.entries.single().copy(id = "hazard", severity = Severity.STOP)
        val high = model.entries.single().copy(candidateCauses = listOf(
            cause("test_sound.a", 100.0), cause("test_sound.b", 99.0), cause("test_sound.c", 98.0)))
        val result = DiagnosisScorer.rank(model.copy(entries = listOf(high, hazardous)),
            VehicleContext(fuelType = "diesel"), setOf("test_sound", "hazard"))
        assertEquals(listOf("test_sound.a", "test_sound.b", "test_sound.c"), result.causes.map { it.cause.id })
        assertEquals(Severity.STOP, result.severity)
    }

    @Test fun everySafetyFlagStopsEvenWithOnlyHighNormalAudioAndNoFaultCandidates() {
        listOf("heavy_engine_knock", "overheating", "brake_change", "steering_change", "wheel_play").forEach { flag ->
            val result = DiagnosisScorer.rank(knowledge, VehicleContext(flags = setOf(flag)),
                setOf("normal_idle"), mapOf("normal_idle" to 1.0, "background_noise" to 0.99))
            assertEquals(flag, Severity.STOP, result.severity)
            assertTrue(flag, result.safetyReasons.isNotEmpty())
            assertTrue(result.safetyReasons.all(String::isNotBlank))
            assertTrue(result.causes.isEmpty())
            assertEquals(TriageStatus.UNCERTAIN, result.status)
        }
    }

    @Test fun highNormalProbabilityCannotSuppressActivatedBrakeFaultOrItsSeverity() {
        val withNormal = DiagnosisScorer.rank(knowledge, VehicleContext(triggerConditions = setOf("braking")),
            setOf("brake_grinding"), mapOf("normal_idle" to 1.0))
        val withoutNormal = DiagnosisScorer.rank(knowledge, VehicleContext(triggerConditions = setOf("braking")),
            setOf("brake_grinding"))
        assertEquals(withoutNormal.causes, withNormal.causes)
        assertEquals(Severity.STOP, withNormal.severity)
    }

    @Test fun noEvidenceAndNonFaultClassesNeverCertifyHealthOrInventCauses() {
        listOf(emptySet(), setOf("normal_idle"), setOf("background_noise"), setOf("unknown"),
            setOf("normal_idle", "background_noise", "unknown")).forEach { symptoms ->
            val result = DiagnosisScorer.rank(knowledge, VehicleContext(cylinderCount = 3, fuelType = "petrol"), symptoms)
            assertTrue(result.causes.isEmpty())
            assertEquals(TriageStatus.UNCERTAIN, result.status)
            assertTrue(result.message.isNotBlank())
            assertEquals(knowledge.disclaimer, result.disclaimer)
            assertEquals(knowledge.reviewNotice, result.reviewNotice)
        }
        assertEquals(Severity.INSPECT, DiagnosisScorer.rank(knowledge, VehicleContext(), emptySet()).severity)
    }

    @Test fun bundledDraftsAlwaysKeepCautionAndAReconcilableExplanation() {
        val result = DiagnosisScorer.rank(knowledge,
            VehicleContext(fuelType = "petrol", engineTempState = "cold", location = "under_hood",
                triggerConditions = setOf("cold", "idle")), setOf("belt_squeal", "valvetrain_tick"))
        assertTrue(result.causes.isNotEmpty())
        assertEquals(TriageStatus.UNCERTAIN, result.status)
        assertTrue(result.message.isNotBlank())
        assertEquals(knowledge.disclaimer, result.disclaimer)
        assertEquals(knowledge.reviewNotice, result.reviewNotice)
        result.causes.forEach {
            assertEquals(it.score, it.contributions.sumOf { term -> term.delta }, 0.000001)
            assertTrue(it.contributions.isNotEmpty())
            assertTrue(it.contributions.all { term -> term.reason.isNotBlank() })
            assertTrue(it.reason.isNotBlank())
            assertTrue(it.score >= knowledge.scoring.minimumScore)
        }
    }

    private fun text(field: String, value: String) = ContextPredicate(field, "eq", textValue = value)

    private fun cause(id: String, base: Double, applicability: List<ContextPredicate> = emptyList(),
        modifiers: List<ContextModifier> = emptyList()) =
        ExpertCause(id, "Kemungkinan penyebab uji", base, applicability, modifiers)

    private fun fixture(vararg causes: ExpertCause): ExpertKnowledge {
        val template = knowledge.entries.first { it.diagnostic }
        return knowledge.copy(safetyRules = emptyList(), entries = listOf(template.copy(
            id = "test_sound", triggerConditions = setOf("cold"), location = setOf("under_hood"),
            candidateCauses = causes.toList(), severity = Severity.INSPECT)))
    }
}

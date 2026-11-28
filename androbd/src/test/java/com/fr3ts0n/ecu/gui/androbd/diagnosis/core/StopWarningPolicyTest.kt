package com.fr3ts0n.ecu.gui.androbd.diagnosis.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopWarningPolicyTest {
    private val config = ResultConfig(0.75, 0.6, 0.6, 0.2, "feedback-export-id-v1")

    @Test fun secondAndThirdStopCausesWarnEvenWhenTheLeadingCauseIsNotStop() {
        val ordinary = cause(Severity.INSPECT, 0.95)
        val stop = cause(Severity.STOP, 0.21)
        assertTrue(StopWarningPolicy.shouldWarn(listOf(ordinary, stop), false, config))
        assertTrue(StopWarningPolicy.shouldWarn(listOf(ordinary, ordinary, stop), false, config))
    }

    @Test fun nullStopProbabilityWarnsConservativelyAtEveryTopThreePosition() {
        val ordinary = cause(Severity.INSPECT, 1.0)
        val stop = cause(Severity.STOP, null)
        assertTrue(StopWarningPolicy.shouldWarn(listOf(stop), false, config))
        assertTrue(StopWarningPolicy.shouldWarn(listOf(ordinary, stop), false, config))
        assertTrue(StopWarningPolicy.shouldWarn(listOf(ordinary, ordinary, stop), false, config))
        assertFalse(StopWarningPolicy.shouldWarn(listOf(cause(Severity.INSPECT, null)), false, config))
    }

    @Test fun probabilityMustBeStrictlyGreaterThanTheConfiguredLowerThreshold() {
        assertFalse(StopWarningPolicy.shouldWarn(listOf(cause(Severity.STOP, 0.0)), false, config))
        assertFalse(StopWarningPolicy.shouldWarn(listOf(cause(Severity.STOP, 0.199)), false, config))
        assertFalse(StopWarningPolicy.shouldWarn(listOf(cause(Severity.STOP, 0.2)), false, config))
        assertTrue(StopWarningPolicy.shouldWarn(listOf(cause(Severity.STOP, 0.200001)), false, config))
        assertFalse(StopWarningPolicy.shouldWarn(listOf(cause(Severity.STOP, 0.21)), false,
            config.copy(stopProbability = 0.3)))
        assertTrue(StopWarningPolicy.shouldWarn(listOf(cause(Severity.STOP, 0.21)), false,
            config.copy(stopProbability = 0.1)))
    }

    @Test fun explicitStopAlwaysWinsIncludingEmptyOrLowProbabilityCandidates() {
        assertTrue(StopWarningPolicy.shouldWarn(emptyList(), true, config))
        assertTrue(StopWarningPolicy.shouldWarn(listOf(cause(Severity.STOP, 0.0)), true, config))
        assertTrue(StopWarningPolicy.shouldWarn(listOf(cause(Severity.MONITOR, 1.0)), true, config))
        assertFalse(StopWarningPolicy.shouldWarn(emptyList(), false, config))
    }

    @Test fun onlyTheTopThreeCandidatesParticipateInTheProbabilityRule() {
        val ordinary = cause(Severity.INSPECT, 1.0)
        assertFalse(StopWarningPolicy.shouldWarn(listOf(ordinary, ordinary, ordinary,
            cause(Severity.STOP, null)), false, config))
        assertTrue(StopWarningPolicy.shouldWarn(listOf(ordinary, ordinary, ordinary,
            cause(Severity.STOP, null)), true, config))
    }

    private fun cause(severity: Severity, probability: Double?) = ResultCause("test.cause", "Dugaan uji",
        "test", severity, listOf("Minta pemeriksaan mekanik"), "Hentikan berkendara", "Alasan uji", probability)
}

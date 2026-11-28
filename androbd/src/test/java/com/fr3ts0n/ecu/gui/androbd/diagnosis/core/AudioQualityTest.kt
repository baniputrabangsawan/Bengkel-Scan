package com.fr3ts0n.ecu.gui.androbd.diagnosis.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin

class AudioQualityTest {
    private val config = RecordingConfig("test-draft-1", 3, 2, 1, 120,
        1.0, 2.0, -45.0, -3.0, 0.99, 0.001, 10.0, -20.0)
    private val reference = tone(PcmFormat.SAMPLE_RATE, 0.005)

    @Test fun goodToneWithSeparateQuietBackgroundIsAccepted() {
        val result = AudioQuality.evaluate(tone(32000, 0.2), reference, config)
        assertTrue(result.accepted)
        assertEquals(emptyList<QualityFailure>(), result.reasons)
        assertEquals(32000L, result.metrics.sampleCount)
        assertEquals(2.0, result.metrics.durationSeconds, 0.0)
        assertEquals(0.2 / kotlin.math.sqrt(2.0), result.metrics.rms, 0.0001)
        assertEquals(20 * log10(result.metrics.rms), result.metrics.rmsDbfs, 0.000001)
        assertEquals(0.2, result.metrics.peak, 0.0001)
        assertEquals(0.0, result.metrics.clippingRatio, 0.0)
        assertFinite(result.metrics)
    }

    @Test fun silenceAndEmptyInputRejectWithoutNonfiniteMetrics() {
        for (samples in listOf(ShortArray(32000), ShortArray(0))) {
            val result = AudioQuality.evaluate(samples, reference, config)
            assertFalse(result.accepted)
            assertTrue(QualityFailure.TOO_QUIET in result.reasons)
            assertTrue(QualityFailure.TOO_NOISY in result.reasons)
            assertEquals(0.0, result.metrics.rms, 0.0)
            assertEquals(0.0, result.metrics.peak, 0.0)
            assertFinite(result.metrics)
        }
        val empty = AudioQuality.evaluate(ShortArray(0), reference, config)
        assertTrue(QualityFailure.TOO_SHORT in empty.reasons)
        assertEquals(0.0, empty.metrics.durationSeconds, 0.0)
    }

    @Test fun bothFullScaleSignsRejectIncludingShortMinValue() {
        for (value in listOf(Short.MIN_VALUE, Short.MAX_VALUE)) {
            val result = AudioQuality.evaluate(ShortArray(32000) { value }, reference, config)
            assertEquals(listOf(QualityFailure.TOO_LOUD, QualityFailure.CLIPPING), result.reasons)
            assertEquals(1.0, result.metrics.clippingRatio, 0.0)
            assertEquals(kotlin.math.abs(value.toInt()) / 32768.0, result.metrics.peak, 0.0)
            assertFinite(result.metrics)
        }
    }

    @Test fun clippedMinorityRejectsEvenWhenOverallRmsIsAcceptable() {
        val samples = tone(32000, 0.2)
        repeat(33) { samples[it] = Short.MIN_VALUE }
        val result = AudioQuality.evaluate(samples, reference, config)
        assertEquals(listOf(QualityFailure.CLIPPING), result.reasons)
        assertEquals(33.0 / 32000, result.metrics.clippingRatio, 0.0)
        assertTrue(result.metrics.rmsDbfs in config.minRmsDbfs..config.maxRmsDbfs)
    }

    @Test fun clippingRatioLimitIsInclusiveAndOneMoreSampleRejects() {
        val samples = tone(32000, 0.2)
        repeat(32) { samples[it] = Short.MAX_VALUE }
        assertTrue(AudioQuality.evaluate(samples, reference, config).accepted)
        samples[32] = Short.MAX_VALUE
        assertEquals(listOf(QualityFailure.CLIPPING),
            AudioQuality.evaluate(samples, reference, config).reasons)
    }

    @Test fun exactSampleDurationDeterminesBothBoundaries() {
        assertTrue(AudioQuality.evaluate(tone(16000, 0.2), reference, config).accepted)
        assertTrue(AudioQuality.evaluate(tone(32000, 0.2), reference, config).accepted)
        val short = AudioQuality.evaluate(tone(15999, 0.2), reference, config)
        assertEquals(listOf(QualityFailure.TOO_SHORT), short.reasons)
        assertEquals(15999.0 / 16000, short.metrics.durationSeconds, 0.0)
        val long = AudioQuality.evaluate(tone(32001, 0.2), reference, config)
        assertEquals(listOf(QualityFailure.TOO_LONG), long.reasons)
    }

    @Test fun quietNonzeroToneRejectsWithoutBeingMistakenForInvalidBackground() {
        val result = AudioQuality.evaluate(tone(32000, 0.001), reference, config)
        assertEquals(listOf(QualityFailure.TOO_QUIET, QualityFailure.TOO_NOISY), result.reasons)
        assertTrue(result.metrics.noiseRms!! > 0)
    }

    @Test fun estimateUsesSignalPowerMinusExplicitBackgroundPower() {
        val samples = tone(32000, 0.2)
        val result = AudioQuality.evaluate(samples, reference, config)
        val signalPower = result.metrics.rms * result.metrics.rms
        val noisePower = result.metrics.noiseRms!! * result.metrics.noiseRms
        assertEquals(10 * log10((signalPower - noisePower) / noisePower),
            result.metrics.snrEstimateDb!!, 0.0000001)
        // This is a relative digital estimate, not a microphone-calibrated acoustic/SPL value.
        assertTrue(result.metrics.snrEstimateDb!! > 30)
    }

    @Test fun similarlyLoudValidBackgroundRejectsAsTooNoisy() {
        val background = tone(16000, 0.05)
        val result = AudioQuality.evaluate(tone(32000, 0.1), background, config)
        assertEquals(listOf(QualityFailure.TOO_NOISY), result.reasons)
        assertEquals(10 * log10(3.0), result.metrics.snrEstimateDb!!, 0.01)
    }

    @Test fun equalOrGreaterBackgroundPowerHasFiniteRejectingEstimate() {
        val background = tone(16000, 0.05)
        for (amplitude in listOf(0.05, 0.025)) {
            val result = AudioQuality.evaluate(tone(32000, amplitude), background, config)
            assertEquals(listOf(QualityFailure.TOO_NOISY), result.reasons)
            assertTrue(result.metrics.snrEstimateDb!! < 0)
            assertFinite(result.metrics)
        }
    }

    @Test fun absentShortOverlongSilentLoudAndClippedReferencesAreExplicitlyInvalid() {
        val clipped = reference.copyOf().also { it[0] = Short.MIN_VALUE }
        val backgrounds = listOf(ShortArray(0), reference.copyOf(15999), reference.copyOf(16001),
            ShortArray(16000), tone(16000, 0.2), clipped)
        for (background in backgrounds) {
            val result = AudioQuality.evaluate(tone(32000, 0.2), background, config)
            assertEquals(listOf(QualityFailure.REFERENCE_INVALID), result.reasons)
            assertFalse(result.accepted)
            assertNull(result.metrics.snrEstimateDb)
            assertFinite(result.metrics)
        }
    }

    @Test fun separateReferenceIsRequiredEvenForAnOtherwiseGoodTone() {
        val samples = tone(32000, 0.2)
        val without = AudioQuality.evaluate(samples, ShortArray(0), config)
        assertFalse(without.accepted)
        assertTrue(AudioQuality.evaluate(samples, reference, config).accepted)
        assertEquals(without.metrics.rms,
            AudioQuality.evaluate(samples, reference, config).metrics.rms, 0.0)
    }

    @Test fun byteIdenticalSamplesHaveIdenticalMetricsAcrossReadChunkSizes() {
        val original = tone(31999, 0.2)
        val expected = AudioQuality.evaluate(original, reference, config)
        for (chunkSize in listOf(1, 257, 1600, 4096)) {
            val assembled = ShortArray(original.size)
            var weightedPower = 0.0
            var offset = 0
            while (offset < original.size) {
                val end = minOf(original.size, offset + chunkSize)
                original.copyInto(assembled, offset, offset, end)
                val count = end - offset
                weightedPower += Math.pow(10.0, AudioQuality.rmsDbfs(original, offset, count) / 10.0) * count
                offset = end
            }
            assertEquals(expected, AudioQuality.evaluate(assembled, reference, config))
            assertEquals(expected.metrics.rms, kotlin.math.sqrt(weightedPower / original.size), 0.000000000001)
        }
    }

    @Test fun loadedThresholdsDetermineRejectsAndEqualityIsAccepted() {
        val samples = tone(32000, 0.2)
        val baseline = AudioQuality.evaluate(samples, reference, config).metrics
        assertTrue(AudioQuality.evaluate(samples, reference,
            config.copy(minRmsDbfs = baseline.rmsDbfs)).accepted)
        assertTrue(AudioQuality.evaluate(samples, reference,
            config.copy(maxRmsDbfs = baseline.rmsDbfs)).accepted)
        assertTrue(AudioQuality.evaluate(samples, reference,
            config.copy(minSnrDb = baseline.snrEstimateDb!!)).accepted)
        assertEquals(listOf(QualityFailure.TOO_QUIET), AudioQuality.evaluate(samples, reference,
            config.copy(minRmsDbfs = baseline.rmsDbfs + 0.001)).reasons)
        assertEquals(listOf(QualityFailure.TOO_LOUD), AudioQuality.evaluate(samples, reference,
            config.copy(maxRmsDbfs = baseline.rmsDbfs - 0.001)).reasons)
        assertEquals(listOf(QualityFailure.TOO_NOISY), AudioQuality.evaluate(samples, reference,
            config.copy(minSnrDb = baseline.snrEstimateDb!! + 0.001)).reasons)
    }

    @Test fun levelWindowUsesOnlyRequestedSamplesWithoutOverflow() {
        val samples = shortArrayOf(Short.MIN_VALUE, 16384, -16384, Short.MAX_VALUE)
        assertEquals(20 * log10(0.5), AudioQuality.rmsDbfs(samples, 1, 2), 0.0000001)
        assertEquals(0.0, AudioQuality.rmsDbfs(samples, 0, 1), 0.0)
        assertTrue(AudioQuality.rmsDbfs(samples, samples.size, 0).isFinite())
        assertThrows(IllegalArgumentException::class.java) { AudioQuality.rmsDbfs(samples, -1, 1) }
        assertThrows(IllegalArgumentException::class.java) { AudioQuality.rmsDbfs(samples, 0, -1) }
        assertThrows(IllegalArgumentException::class.java) { AudioQuality.rmsDbfs(samples, 3, 2) }
        assertThrows(IllegalArgumentException::class.java) { AudioQuality.rmsDbfs(samples, Int.MAX_VALUE, 1) }
        assertThrows(IllegalArgumentException::class.java) { AudioQuality.rmsDbfs(samples, 1, Int.MAX_VALUE) }
    }

    @Test fun invalidDirectConfigurationsCannotBypassLoaderGuards() {
        val changes: List<() -> RecordingConfig> = listOf(
            { config.copy(revision = "") }, { config.copy(countdownSeconds = -1) },
            { config.copy(recordingSeconds = 16) }, { config.copy(referenceSeconds = 0) },
            { config.copy(referenceMaxAgeSeconds = 0) }, { config.copy(minDurationSeconds = 3.0) },
            { config.copy(maxDurationSeconds = 1.5) }, { config.copy(minRmsDbfs = Double.NaN) },
            { config.copy(maxRmsDbfs = Double.POSITIVE_INFINITY) }, { config.copy(clippingAmplitude = 0.0) },
            { config.copy(maxClippingRatio = -0.1) }, { config.copy(minSnrDb = Double.NEGATIVE_INFINITY) },
            { config.copy(maxReferenceRmsDbfs = 1.0) },
        )
        for (change in changes) assertThrows(IllegalArgumentException::class.java) { change() }
    }

    @Test fun evaluatorRejectsUnboundedInputRatherThanProcessingArbitraryAudio() {
        val excessive = ShortArray(PcmFormat.SAMPLE_RATE * 60 + 1)
        assertThrows(IllegalArgumentException::class.java) {
            AudioQuality.evaluate(excessive, reference, config)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AudioQuality.evaluate(tone(32000, 0.2), excessive, config)
        }
    }

    private fun tone(count: Int, amplitude: Double): ShortArray = ShortArray(count) {
        (sin(2.0 * PI * 400 * it / PcmFormat.SAMPLE_RATE) * amplitude * 32768).toInt().toShort()
    }

    private fun assertFinite(metrics: QualityMetrics) {
        val numbers = listOf(metrics.durationSeconds, metrics.rms, metrics.rmsDbfs,
            metrics.peak, metrics.peakDbfs, metrics.clippingRatio, metrics.noiseRms, metrics.snrEstimateDb)
        assertTrue(numbers.filterNotNull().all(Double::isFinite))
    }
}

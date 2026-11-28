package com.fr3ts0n.ecu.gui.androbd.diagnosis.core

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/** The only supported capture format; samples must already be mono signed little-endian PCM16 at 16 kHz. */
object PcmFormat {
    const val SAMPLE_RATE = 16000
    const val CHANNELS = 1
    const val BYTES_PER_SAMPLE = 2
}

/** Engineering draft thresholds, not mechanical validation or microphone/SPL calibration. */
data class RecordingConfig(
    val revision: String,
    val countdownSeconds: Int,
    val recordingSeconds: Int,
    val referenceSeconds: Int,
    val referenceMaxAgeSeconds: Int,
    val minDurationSeconds: Double,
    val maxDurationSeconds: Double,
    val minRmsDbfs: Double,
    val maxRmsDbfs: Double,
    val clippingAmplitude: Double,
    val maxClippingRatio: Double,
    val minSnrDb: Double,
    val maxReferenceRmsDbfs: Double,
) {
    init {
        require(revision.isNotBlank() && revision.length <= 64 &&
            revision.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-" }) {
            "recording.invalid_revision"
        }
        require(countdownSeconds in 0..10 && recordingSeconds in 1..15 &&
            referenceSeconds in 1..10 && referenceMaxAgeSeconds in 1..3600) { "recording.invalid_timing" }
        require(minDurationSeconds.isFinite() && maxDurationSeconds.isFinite() &&
            minDurationSeconds in 0.1..recordingSeconds.toDouble() &&
            maxDurationSeconds in recordingSeconds.toDouble()..15.0) { "recording.invalid_duration" }
        require(minRmsDbfs.isFinite() && maxRmsDbfs.isFinite() &&
            minRmsDbfs >= -120.0 && maxRmsDbfs <= 0.0 && minRmsDbfs < maxRmsDbfs) {
            "recording.invalid_rms_thresholds"
        }
        require(clippingAmplitude.isFinite() && clippingAmplitude in 0.5..1.0 &&
            maxClippingRatio.isFinite() && maxClippingRatio in 0.0..0.1) { "recording.invalid_clipping" }
        require(minSnrDb.isFinite() && minSnrDb in 0.0..120.0 &&
            maxReferenceRmsDbfs.isFinite() && maxReferenceRmsDbfs in -120.0..maxRmsDbfs) {
            "recording.invalid_reference_thresholds"
        }
    }
}

data class QualityMetrics(
    val sampleCount: Long,
    val durationSeconds: Double,
    val rms: Double,
    val rmsDbfs: Double,
    val peak: Double,
    val peakDbfs: Double,
    val clippingRatio: Double,
    val noiseRms: Double?,
    val snrEstimateDb: Double?,
)

enum class QualityFailure {
    TOO_SHORT, TOO_LONG, TOO_QUIET, TOO_LOUD, CLIPPING, TOO_NOISY, REFERENCE_INVALID,
}

data class QualityResult(val metrics: QualityMetrics, val reasons: List<QualityFailure>) {
    val accepted get() = reasons.isEmpty()
}

/**
 * Deterministic digital quality checks, independent of capture chunks and device identity.
 *
 * The caller must verify actual source format, route and reference age; a ShortArray alone cannot
 * prove its sampling rate or that a separate reference contains only background. No resampling,
 * acoustic sound-pressure measurement or mechanical diagnosis is performed here.
 */
object AudioQuality {
    private const val MAX_SAMPLES = PcmFormat.SAMPLE_RATE * 60
    private const val PCM_SCALE = 32768.0
    private const val PCM_POWER_SCALE = PCM_SCALE * PCM_SCALE
    private const val DB_FLOOR = -160.0

    /**
     * Reference must contain exactly referenceSeconds of separately captured background. Empty,
     * short, overlong, zero-power, any clipped sample, or RMS above maxReferenceRmsDbfs makes it
     * REFERENCE_INVALID; its SNR is unavailable, never guessed from the target recording.
     *
     * Estimate = 10 log10((targetPower - backgroundPower) / backgroundPower). This assumes stable
     * independent additive background and is not acoustic truth. Nonpositive estimated target-only
     * power is reported at the finite -160 dB floor and rejected by the configured SNR minimum.
     * Silence in RMS/peak reporting uses the same finite floor, not negative infinity.
     */
    @JvmStatic fun evaluate(samples: ShortArray, reference: ShortArray, config: RecordingConfig): QualityResult {
        require(samples.size <= MAX_SAMPLES && reference.size <= MAX_SAMPLES) { "recording.audio_too_large" }
        val main = measure(samples, config.clippingAmplitude)
        val background = measure(reference, config.clippingAmplitude)
        val duration = samples.size.toDouble() / PcmFormat.SAMPLE_RATE
        val referenceValid = reference.size == config.referenceSeconds * PcmFormat.SAMPLE_RATE &&
            background.power > 0.0 && background.clippedSamples == 0 &&
            dbfs(background.rms) <= config.maxReferenceRmsDbfs
        val snr = if (referenceValid) {
            val signalPower = main.power - background.power
            if (signalPower <= 0.0) DB_FLOOR else
                (10.0 * log10(signalPower / background.power)).coerceIn(DB_FLOOR, -DB_FLOOR)
        } else null
        val metrics = QualityMetrics(samples.size.toLong(), duration, main.rms, dbfs(main.rms),
            main.peak, dbfs(main.peak), main.clippingRatio,
            background.rms.takeIf { reference.isNotEmpty() }, snr)
        val reasons = buildList {
            if (duration < config.minDurationSeconds) add(QualityFailure.TOO_SHORT)
            if (duration > config.maxDurationSeconds) add(QualityFailure.TOO_LONG)
            if (metrics.rmsDbfs < config.minRmsDbfs) add(QualityFailure.TOO_QUIET)
            if (metrics.rmsDbfs > config.maxRmsDbfs) add(QualityFailure.TOO_LOUD)
            if (metrics.clippingRatio > config.maxClippingRatio) add(QualityFailure.CLIPPING)
            if (!referenceValid) add(QualityFailure.REFERENCE_INVALID)
            else if (snr != null && snr < config.minSnrDb) add(QualityFailure.TOO_NOISY)
        }
        return QualityResult(metrics, reasons)
    }

    /** Bounded level window for live display; zero count is finite silence. */
    @JvmStatic fun rmsDbfs(samples: ShortArray, offset: Int, count: Int): Double {
        require(offset >= 0 && offset <= samples.size && count >= 0 &&
            count <= samples.size - offset && count <= MAX_SAMPLES) { "recording.invalid_level_window" }
        if (count == 0) return DB_FLOOR
        var squareSum = 0L
        val end = offset + count
        for (index in offset until end) {
            val value = samples[index].toLong()
            squareSum += value * value
        }
        return dbfs(sqrt(squareSum.toDouble() / count / PCM_POWER_SCALE))
    }

    private data class Measurement(val power: Double, val peak: Double, val clippedSamples: Int, val count: Int) {
        val rms = sqrt(power)
        val clippingRatio = if (count == 0) 0.0 else clippedSamples.toDouble() / count
    }

    private fun measure(samples: ShortArray, clippingAmplitude: Double): Measurement {
        var squareSum = 0L
        var peak = 0
        var clipped = 0
        val clippingLevel = clippingAmplitude * PCM_SCALE
        for (sample in samples) {
            val value = sample.toInt()
            val magnitude = abs(value)
            squareSum += value.toLong() * value
            if (magnitude > peak) peak = magnitude
            if (magnitude >= clippingLevel) clipped++
        }
        val power = if (samples.isEmpty()) 0.0 else squareSum.toDouble() / samples.size / PCM_POWER_SCALE
        return Measurement(power, peak / PCM_SCALE, clipped, samples.size)
    }

    private fun dbfs(amplitude: Double): Double =
        if (amplitude <= 0.0) DB_FLOOR else (20.0 * log10(amplitude)).coerceAtLeast(DB_FLOOR)
}

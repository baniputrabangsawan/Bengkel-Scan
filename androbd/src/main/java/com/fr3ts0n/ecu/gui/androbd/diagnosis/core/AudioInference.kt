package com.fr3ts0n.ecu.gui.androbd.diagnosis.core

import java.io.InputStream
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Parameters shared with the Phase 4 recording-level preprocessing pipeline. */
data class AudioPreprocessing(
    val sampleRate: Int,
    val maxAudioSeconds: Double,
    val trimFrameSamples: Int,
    val trimHopSamples: Int,
    val trimThresholdDbfs: Double,
    val trimMarginSamples: Int,
    val normalizePeak: Double,
    val windowSamples: Int,
    val hopSamples: Int
) {
    init {
        require(sampleRate == 16000) { "Only 16000 Hz recordings are supported" }
        require(maxAudioSeconds.isFinite() && maxAudioSeconds > 0 && maxAudioSeconds <= 60) {
            "Audio duration bound must be in (0, 60] seconds"
        }
        require(maxSamples > 0) { "Audio duration bound must include at least one sample" }
        require(trimFrameSamples in 1..maxSamples) { "Invalid trim frame length" }
        require(trimHopSamples in 1..trimFrameSamples) { "Invalid trim hop length" }
        require(trimThresholdDbfs.isFinite() && trimThresholdDbfs <= 0) { "Invalid trim threshold" }
        require(trimMarginSamples in 0..maxSamples) { "Invalid trim margin" }
        require(normalizePeak.isFinite() && normalizePeak > 0 && normalizePeak <= 1) { "Invalid normalization peak" }
        require(windowSamples in 1..maxSamples) { "Invalid model window length" }
        require(hopSamples in 1..maxSamples) { "Invalid model hop length" }
    }

    internal val maxSamples: Int get() = (sampleRate * maxAudioSeconds).toInt()
}

data class PreparedAudio(
    val samples: FloatArray,
    val trimStart: Int,
    val trimEnd: Int,
    val windowCount: Int
)

object AudioInference {
    // Bound ancillary RIFF chunks as well as PCM data; never trust a file's length fields.
    private const val MAX_ANCILLARY_BYTES = 1048576L

    /** Decode only mono PCM16 RIFF/WAVE. The caller retains ownership of [input]. */
    fun decodeWave(input: InputStream, config: AudioPreprocessing): FloatArray {
        val buffer = ByteArray(8192)
        readFully(input, buffer, 12)
        require(tag(buffer, 0) == "RIFF" && tag(buffer, 8) == "WAVE") { "Expected RIFF/WAVE" }
        val riffSize = unsignedInt(buffer, 4)
        require(riffSize >= 4 && riffSize <= config.maxSamples * 2L + MAX_ANCILLARY_BYTES) {
            "Invalid or oversized RIFF container"
        }
        var remaining = riffSize - 4
        var formatSeen = false
        var decoded: FloatArray? = null
        while (remaining > 0) {
            require(remaining >= 8) { "Incomplete RIFF chunk header" }
            readFully(input, buffer, 8)
            val id = tag(buffer, 0)
            val size = unsignedInt(buffer, 4)
            val paddedSize = size + (size and 1L)
            remaining -= 8
            require(paddedSize <= remaining) { "Chunk exceeds RIFF container" }
            when (id) {
                "fmt " -> {
                    require(!formatSeen && decoded == null && size >= 16) { "Invalid PCM format chunk" }
                    readFully(input, buffer, 16)
                    require(unsignedShort(buffer, 0) == 1 && unsignedShort(buffer, 2) == 1 &&
                        unsignedInt(buffer, 4) == config.sampleRate.toLong() &&
                        unsignedInt(buffer, 8) == config.sampleRate * 2L &&
                        unsignedShort(buffer, 12) == 2 && unsignedShort(buffer, 14) == 16) {
                        "Expected 16000 Hz mono PCM16"
                    }
                    discard(input, buffer, size - 16)
                    formatSeen = true
                }
                "data" -> {
                    require(formatSeen && decoded == null && size > 0 && size % 2L == 0L &&
                        size / 2 <= config.maxSamples) { "Invalid or oversized PCM data chunk" }
                    val samples = FloatArray((size / 2).toInt())
                    var index = 0
                    while (index < samples.size) {
                        val count = min(buffer.size / 2, samples.size - index)
                        readFully(input, buffer, count * 2)
                        for (i in 0 until count) {
                            samples[index + i] = unsignedShort(buffer, i * 2).toShort() / 32768f
                        }
                        index += count
                    }
                    decoded = samples
                }
                else -> discard(input, buffer, size)
            }
            if (size and 1L != 0L) readFully(input, buffer, 1)
            remaining -= paddedSize
        }
        require(input.read() == -1) { "Bytes outside RIFF container" }
        return requireNotNull(decoded) { "Missing PCM data chunk" }
    }

    /** Absolute float64 cumulative RMS trim, then float64 peak normalization to float32. */
    fun preprocess(samples: FloatArray, config: AudioPreprocessing): PreparedAudio {
        require(samples.isNotEmpty() && samples.size <= config.maxSamples) { "Invalid audio sample count" }
        val energy = DoubleArray(samples.size + 1)
        for (i in samples.indices) {
            val sample = samples[i].toDouble()
            require(sample.isFinite() && sample >= -1 && sample <= 1) { "Invalid audio sample" }
            energy[i + 1] = energy[i] + sample * sample
        }
        val threshold = 10.0.pow(config.trimThresholdDbfs / 20)
        var trimStart = -1
        var trimEnd = -1
        var start = 0
        while (start < samples.size) {
            val end = min(start.toLong() + config.trimFrameSamples, samples.size.toLong()).toInt()
            val rms = sqrt(max(energy[end] - energy[start], 0.0) / (end - start))
            if (rms > threshold) {
                if (trimStart < 0) trimStart = max(0, start - config.trimMarginSamples)
                trimEnd = min(end.toLong() + config.trimMarginSamples, samples.size.toLong()).toInt()
            }
            start += config.trimHopSamples
        }
        require(trimStart >= 0) { "Audio is silent at the configured absolute RMS threshold" }
        val length = trimEnd - trimStart
        require(length >= config.windowSamples) { "Trimmed audio is too short" }
        var peak = 0.0
        for (i in trimStart until trimEnd) peak = max(peak, abs(samples[i].toDouble()))
        val scale = config.normalizePeak / peak
        val normalized = FloatArray(length) { (samples[trimStart + it].toDouble() * scale).toFloat() }
        return PreparedAudio(normalized, trimStart, trimEnd,
            1 + (length - config.windowSamples) / config.hopSamples)
    }

    /** Apply temperature once, after recording-level mean logits; no class/window filtering. */
    fun calibratedProbabilities(meanLogits: DoubleArray, temperature: Double): DoubleArray {
        require(meanLogits.isNotEmpty()) { "Logits must not be empty" }
        require(temperature.isFinite() && temperature > 0) { "Temperature must be finite and positive" }
        var maximum = Double.NEGATIVE_INFINITY
        for (logit in meanLogits) {
            require(logit.isFinite()) { "Logits must be finite" }
            maximum = max(maximum, logit)
        }
        val probabilities = DoubleArray(meanLogits.size)
        var sum = 0.0
        for (i in meanLogits.indices) {
            // Subtract first: a shared extreme finite offset must not overflow on division.
            probabilities[i] = exp((meanLogits[i] - maximum) / temperature)
            sum += probabilities[i]
        }
        for (i in probabilities.indices) probabilities[i] /= sum
        return probabilities
    }

    private fun unsignedShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)

    private fun unsignedInt(bytes: ByteArray, offset: Int): Long =
        unsignedShort(bytes, offset).toLong() or (unsignedShort(bytes, offset + 2).toLong() shl 16)

    private fun tag(bytes: ByteArray, offset: Int): String = String(bytes, offset, 4, Charsets.US_ASCII)

    private fun readFully(input: InputStream, buffer: ByteArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val read = input.read(buffer, offset, count - offset)
            require(read >= 0) { "Truncated WAV" }
            if (read == 0) {
                val byte = input.read()
                require(byte >= 0) { "Truncated WAV" }
                buffer[offset++] = byte.toByte()
            } else offset += read
        }
    }

    private fun discard(input: InputStream, buffer: ByteArray, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val size = min(remaining, buffer.size.toLong()).toInt()
            readFully(input, buffer, size)
            remaining -= size
        }
    }
}

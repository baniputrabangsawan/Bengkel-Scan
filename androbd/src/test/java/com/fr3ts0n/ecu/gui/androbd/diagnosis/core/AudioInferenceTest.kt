package com.fr3ts0n.ecu.gui.androbd.diagnosis.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

class AudioInferenceTest {
    private val config = AudioPreprocessing(16000, 60.0, 320, 160, -60.0, 160, 0.9, 15600, 7800)

    @Test fun pcmDigitalValuesAreExactAndInputRemainsOpen() {
        var closed = false
        val input = object : ByteArrayInputStream(wave(pcm(-32768, -32767, -1, 0, 1, 32767))) {
            override fun close() { closed = true; super.close() }
        }
        assertArrayEquals(floatArrayOf(-1f, -32767 / 32768f, -1 / 32768f, 0f, 1 / 32768f,
            32767 / 32768f), AudioInference.decodeWave(input, config), 0f)
        assertFalse(closed)
    }

    @Test fun arbitraryReadBoundariesAndZeroLengthReadsPreserveSamples() {
        val values = IntArray(9001) { (it * 71 % 65536) - 32768 }
        val bytes = wave(pcm(*values))
        val expected = FloatArray(values.size) { values[it] / 32768f }
        for (chunk in intArrayOf(1, 2, 3, 17, 4095, 8192)) {
            val input = object : InputStream() {
                val source = ByteArrayInputStream(bytes)
                var returnZero = true
                override fun read(): Int = source.read()
                override fun read(target: ByteArray, offset: Int, length: Int): Int {
                    returnZero = !returnZero
                    return if (returnZero) 0 else source.read(target, offset, minOf(length, chunk))
                }
            }
            assertArrayEquals(expected, AudioInference.decodeWave(input, config), 0f)
        }
    }

    @Test fun ancillaryChunksAndOddPaddingAreConsumedWithinRiffBounds() {
        val bytes = riff(chunk("JUNK", byteArrayOf(4, 5, 6)), chunk("fmt ", format()),
            chunk("LIST", byteArrayOf(7)), chunk("data", pcm(123, -456)), chunk("tail", byteArrayOf(9)))
        assertArrayEquals(floatArrayOf(123 / 32768f, -456 / 32768f), decode(bytes), 0f)
        val extendedFormat = format() + byteArrayOf(0, 0)
        assertArrayEquals(floatArrayOf(1 / 32768f),
            decode(riff(chunk("fmt ", extendedFormat), chunk("data", pcm(1)))), 0f)
    }

    @Test fun everyTruncatedPrefixFailsIncludingFinalAncillaryPadding() {
        val bytes = riff(chunk("fmt ", format()), chunk("data", pcm(12, 34)),
            chunk("JUNK", byteArrayOf(1)))
        for (length in 0 until bytes.size) {
            assertThrows("prefix length $length", IllegalArgumentException::class.java) {
                decode(bytes.copyOf(length))
            }
        }
    }

    @Test fun malformedRiffSizesAndChunkBoundsFailClosed() {
        val bytes = wave(pcm(1))
        val invalid = listOf(
            bytes.copyOf().apply { this[0] = 'X'.code.toByte() },
            bytes.copyOf().apply { this[8] = 'X'.code.toByte() },
            bytes.copyOf().apply { putInt(this, 4, 3) },
            bytes.copyOf().apply { putInt(this, 4, -1) },
            bytes.copyOf().apply { putInt(this, 40, -1) },
            bytes.copyOf().apply { putInt(this, 40, 4) },
            bytes.copyOf().apply { putInt(this, 40, 1) },
            bytes + byteArrayOf(0),
            riff(chunk("fmt ", format()), byteArrayOf(1, 2, 3)),
            riff(chunk("fmt ", format()), "JUNK".toByteArray() + littleInt(1) + byteArrayOf(1))
        )
        for (bad in invalid) assertThrows(IllegalArgumentException::class.java) { decode(bad) }
    }

    @Test fun formatMismatchMissingAndDuplicateChunksAreRejected() {
        for ((offset, value) in listOf(0 to 3, 2 to 2, 4 to 8000, 8 to 1, 12 to 4, 14 to 8)) {
            val fmt = format().apply {
                if (offset == 4 || offset == 8) putInt(this, offset, value)
                else { this[offset] = value.toByte(); this[offset + 1] = 0 }
            }
            assertThrows(IllegalArgumentException::class.java) {
                decode(riff(chunk("fmt ", fmt), chunk("data", pcm(1))))
            }
        }
        for (bad in listOf(riff(), riff(chunk("fmt ", format())), riff(chunk("data", pcm(1))),
            wave(byteArrayOf()), riff(chunk("fmt ", format().copyOf(15)), chunk("data", pcm(1))),
            riff(chunk("fmt ", format()), chunk("fmt ", format()), chunk("data", pcm(1))),
            riff(chunk("fmt ", format()), chunk("data", pcm(1)), chunk("data", pcm(2))))) {
            assertThrows(IllegalArgumentException::class.java) { decode(bad) }
        }
    }

    @Test fun sampleDurationLimitIsInclusiveAndAncillaryLengthIsBounded() {
        val bounded = config.copy(maxAudioSeconds = 1.0)
        assertEquals(16000, AudioInference.decodeWave(ByteArrayInputStream(wave(ByteArray(32000))), bounded).size)
        assertThrows(IllegalArgumentException::class.java) {
            AudioInference.decodeWave(ByteArrayInputStream(wave(ByteArray(32002))), bounded)
        }
        val header = wave(pcm(1)).copyOf(12).apply { putInt(this, 4, 2000000) }
        assertThrows(IllegalArgumentException::class.java) { AudioInference.decodeWave(ByteArrayInputStream(header), bounded) }
    }

    @Test fun trimUsesHopGridMarginsPreservesInteriorSilenceAndDropsWindowTail() {
        val input = FloatArray(40000)
        for (i in 1000 until 18000) input[i] = if (i % 2 == 0) 0.25f else -0.5f
        for (i in 22000 until 35000) input[i] = 0.125f
        val original = input.copyOf()
        val result = AudioInference.preprocess(input, config)
        // First active frame starts at 800; last starts at 34880 and ends at 35200.
        assertEquals(640, result.trimStart)
        assertEquals(35360, result.trimEnd)
        assertEquals(3, result.windowCount)
        assertEquals(34720, result.samples.size)
        assertEquals(0.45f, result.samples[1000 - result.trimStart], 0f)
        assertEquals(-0.9f, result.samples[1001 - result.trimStart], 0f)
        assertEquals(0f, result.samples[20000 - result.trimStart], 0f)
        assertArrayEquals(original, input, 0f)
    }

    @Test fun finalPartialFrameUsesActualLengthInsteadOfZeroPadding() {
        val local = config.copy(trimFrameSamples = 4, trimHopSamples = 2, trimMarginSamples = 0,
            trimThresholdDbfs = -6.020599913279624, windowSamples = 1, hopSamples = 1)
        val result = AudioInference.preprocess(floatArrayOf(0f, 0f, 0f, 0f, 0.6f), local)
        // At start 2, RMS=.6/sqrt(3)<.5; the one-sample frame at 4 has RMS=.6.
        assertEquals(4, result.trimStart)
        assertEquals(5, result.trimEnd)
        assertArrayEquals(floatArrayOf(0.9f), result.samples, 0f)
    }

    @Test fun absoluteThresholdDoesNotAmplifyQuietAudioIntoActivity() {
        assertThrows(IllegalArgumentException::class.java) { AudioInference.preprocess(FloatArray(16000) { 0.0005f }, config) }
        val threshold = config.copy(trimThresholdDbfs = -6.020599913279624)
        assertThrows(IllegalArgumentException::class.java) { AudioInference.preprocess(FloatArray(16000) { 0.5f }, threshold) }
        assertEquals(1, AudioInference.preprocess(FloatArray(16000) { 0.5001f }, threshold).windowCount)
    }

    @Test fun emptySilentShortInvalidAndOverlongInputsRejectWithoutPadding() {
        for (samples in listOf(FloatArray(0), FloatArray(16000), FloatArray(15599) { 0.5f },
            FloatArray(960001) { 0.5f }, floatArrayOf(Float.NaN), floatArrayOf(Float.POSITIVE_INFINITY),
            floatArrayOf(Float.NEGATIVE_INFINITY), floatArrayOf(1.0001f), floatArrayOf(-1.0001f))) {
            assertThrows(IllegalArgumentException::class.java) { AudioInference.preprocess(samples, config) }
        }
        val mostlySilent = FloatArray(20000).apply { for (i in 1000 until 2000) this[i] = 0.5f }
        assertThrows(IllegalArgumentException::class.java) { AudioInference.preprocess(mostlySilent, config) }
    }

    @Test fun completeWindowBoundariesAreExactAndNoTailPaddingOccurs() {
        for ((count, windows) in listOf(15600 to 1, 23399 to 1, 23400 to 2, 31200 to 3)) {
            val prepared = AudioInference.preprocess(FloatArray(count) { 1f }, config)
            assertEquals(count, prepared.samples.size)
            assertEquals(windows, prepared.windowCount)
            assertEquals(0, prepared.trimStart)
            assertEquals(count, prepared.trimEnd)
        }
    }

    @Test fun normalizationUsesDoubleBeforeFloatIncludingTinyAndFullScaleSigns() {
        val local = config.copy(trimThresholdDbfs = -1000.0, windowSamples = 2)
        val tiny = AudioInference.preprocess(FloatArray(320) { if (it % 2 == 0) Float.MIN_VALUE else -Float.MIN_VALUE }, local)
        assertEquals(0.9f, tiny.samples[0], 0f)
        assertEquals(-0.9f, tiny.samples[1], 0f)
        val full = AudioInference.preprocess(FloatArray(15600) { if (it % 2 == 0) -1f else 32767 / 32768f }, config)
        assertEquals(-0.9f, full.samples[0], 0f)
        assertEquals(((32767 / 32768f).toDouble() * 0.9).toFloat(), full.samples[1], 0f)
        val tinyPeak = config.copy(normalizePeak = Double.MIN_VALUE)
        assertTrue(AudioInference.preprocess(FloatArray(15600) { 1f }, tinyPeak).samples.all { it == 0f })
    }

    @Test fun invalidConfigurationCannotCreateUnboundedLoopsOrInvalidArithmetic() {
        val invalid: List<() -> AudioPreprocessing> = listOf(
            { config.copy(sampleRate = 8000) }, { config.copy(maxAudioSeconds = 0.0) },
            { config.copy(maxAudioSeconds = Double.NaN) }, { config.copy(maxAudioSeconds = Double.POSITIVE_INFINITY) },
            { config.copy(maxAudioSeconds = 61.0) }, { config.copy(maxAudioSeconds = 0.000001) },
            { config.copy(trimFrameSamples = 0) }, { config.copy(trimFrameSamples = Int.MAX_VALUE) },
            { config.copy(trimHopSamples = 0) }, { config.copy(trimHopSamples = 321) },
            { config.copy(trimThresholdDbfs = 1.0) }, { config.copy(trimThresholdDbfs = Double.NEGATIVE_INFINITY) },
            { config.copy(trimMarginSamples = -1) }, { config.copy(trimMarginSamples = Int.MAX_VALUE) },
            { config.copy(normalizePeak = 0.0) }, { config.copy(normalizePeak = 1.01) },
            { config.copy(normalizePeak = Double.NaN) }, { config.copy(windowSamples = 0) },
            { config.copy(windowSamples = Int.MAX_VALUE) }, { config.copy(hopSamples = 0) },
            { config.copy(hopSamples = Int.MAX_VALUE) })
        for (factory in invalid) assertThrows(IllegalArgumentException::class.java) { factory() }
    }

    @Test fun softmaxAppliesTemperatureOnceAndPreservesInputAndClassOrder() {
        val logits = doubleArrayOf(2.0, 0.0, -2.0)
        val original = logits.copyOf()
        val denominator = exp(0.0) + exp(-1.0) + exp(-2.0)
        val expected = doubleArrayOf(1 / denominator, exp(-1.0) / denominator, exp(-2.0) / denominator)
        assertArrayEquals(expected, AudioInference.calibratedProbabilities(logits, 2.0), 1e-15)
        assertArrayEquals(original, logits, 0.0)
        assertArrayEquals(expected, AudioInference.calibratedProbabilities(doubleArrayOf(1002.0, 1000.0, 998.0), 2.0), 1e-15)
    }

    @Test fun extremeFiniteLogitsAndTemperaturesStayFiniteAndNormalized() {
        assertArrayEquals(doubleArrayOf(0.5, 0.5), AudioInference.calibratedProbabilities(
            doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE), Double.MIN_VALUE), 0.0)
        assertArrayEquals(doubleArrayOf(1.0, 0.0), AudioInference.calibratedProbabilities(
            doubleArrayOf(Double.MAX_VALUE, -Double.MAX_VALUE), 1.0), 0.0)
        assertArrayEquals(doubleArrayOf(0.0, 1.0), AudioInference.calibratedProbabilities(
            doubleArrayOf(-1000.0, 1000.0), Double.MIN_VALUE), 0.0)
        assertArrayEquals(doubleArrayOf(1.0), AudioInference.calibratedProbabilities(doubleArrayOf(-Double.MAX_VALUE), 1.0), 0.0)
        val flat = AudioInference.calibratedProbabilities(doubleArrayOf(-1.0, 1.0, 0.0), Double.MAX_VALUE)
        assertEquals(1.0, flat.sum(), 1e-15)
        assertTrue(flat.all { it.isFinite() && it >= 0 && it <= 1 })
    }

    @Test fun calibrationRejectsEmptyNonfiniteLogitsAndInvalidTemperature() {
        for (logits in listOf(doubleArrayOf(), doubleArrayOf(Double.NaN),
            doubleArrayOf(1.0, Double.POSITIVE_INFINITY), doubleArrayOf(Double.NEGATIVE_INFINITY))) {
            assertThrows(IllegalArgumentException::class.java) { AudioInference.calibratedProbabilities(logits, 1.0) }
        }
        for (temperature in doubleArrayOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { AudioInference.calibratedProbabilities(doubleArrayOf(1.0), temperature) }
        }
    }

    private fun decode(bytes: ByteArray): FloatArray = AudioInference.decodeWave(ByteArrayInputStream(bytes), config)

    private fun pcm(vararg values: Int): ByteArray = ByteArray(values.size * 2).also { bytes ->
        for (i in values.indices) {
            bytes[i * 2] = values[i].toByte()
            bytes[i * 2 + 1] = (values[i] shr 8).toByte()
        }
    }

    private fun format(): ByteArray = byteArrayOf(1, 0, 1, 0) + littleInt(16000) + littleInt(32000) + byteArrayOf(2, 0, 16, 0)
    private fun wave(data: ByteArray): ByteArray = riff(chunk("fmt ", format()), chunk("data", data))
    private fun chunk(id: String, data: ByteArray): ByteArray = id.toByteArray(Charsets.US_ASCII) +
        littleInt(data.size) + data + if (data.size % 2 == 0) byteArrayOf() else byteArrayOf(0)

    private fun riff(vararg chunks: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("WAVE".toByteArray(Charsets.US_ASCII))
        for (chunk in chunks) body.write(chunk)
        return "RIFF".toByteArray(Charsets.US_ASCII) + littleInt(body.size()) + body.toByteArray()
    }

    private fun littleInt(value: Int): ByteArray = ByteArray(4).also { putInt(it, 0, value) }
    private fun putInt(bytes: ByteArray, offset: Int, value: Int) {
        for (i in 0..3) bytes[offset + i] = (value ushr (i * 8)).toByte()
    }
}

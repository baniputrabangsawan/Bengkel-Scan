package com.fr3ts0n.ecu.gui.androbd.diagnosis.data

import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.PcmFormat
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class RecordingConfigLoaderTest {
    @Test fun shippedDraftHasRequestedThresholdsAndFixedSourceFormat() {
        val json = JSONObject(shipped())
        val config = RecordingConfigLoader.parse(shipped())
        assertEquals(1, json.getInt("schemaVersion"))
        assertEquals(PcmFormat.SAMPLE_RATE, json.getInt("sampleRate"))
        assertEquals(PcmFormat.CHANNELS, json.getInt("channels"))
        assertEquals("PCM_16BIT", json.getString("encoding"))
        assertTrue(config.revision.contains("draft"))
        assertEquals(3, config.countdownSeconds)
        assertEquals(15, config.recordingSeconds)
        assertEquals(2, config.referenceSeconds)
        assertEquals(120, config.referenceMaxAgeSeconds)
        assertEquals(14.0, config.minDurationSeconds, 0.0)
        assertEquals(15.0, config.maxDurationSeconds, 0.0)
        assertEquals(-45.0, config.minRmsDbfs, 0.0)
        assertEquals(-3.0, config.maxRmsDbfs, 0.0)
        assertEquals(0.99, config.clippingAmplitude, 0.0)
        assertEquals(0.001, config.maxClippingRatio, 0.0)
        assertEquals(10.0, config.minSnrDb, 0.0)
        assertEquals(-20.0, config.maxReferenceRmsDbfs, 0.0)
    }

    @Test fun schemaFieldsAndSourceSamplingCannotBeSilentlyIgnored() {
        for ((field, value) in listOf("schemaVersion" to 2, "sampleRate" to 44100,
            "channels" to 2, "encoding" to "PCM_FLOAT", "countdownSeconds" to 2.5,
            "recordingSeconds" to "15", "minRmsDbfs" to "-45", "referenceSeconds" to true)) {
            rejects(JSONObject(shipped()).put(field, value).toString())
        }
        rejects(JSONObject(shipped()).put("unknownThreshold", 1).toString())
        val missing = JSONObject(shipped())
        missing.remove("minSnrDb")
        rejects(missing.toString())
        rejects(JSONObject(shipped()).put("minSnrDb", JSONObject.NULL).toString())
    }

    @Test fun thresholdChangesAreLoadedRatherThanHardcoded() {
        val changed = JSONObject(shipped()).put("minRmsDbfs", -50).put("minSnrDb", 15)
            .put("maxClippingRatio", 0.002).put("revision", "local-draft-2")
        val config = RecordingConfigLoader.parse(changed.toString())
        assertEquals(-50.0, config.minRmsDbfs, 0.0)
        assertEquals(15.0, config.minSnrDb, 0.0)
        assertEquals(0.002, config.maxClippingRatio, 0.0)
        assertEquals("local-draft-2", config.revision)
    }

    @Test fun finiteBoundedAndConsistentValuesAreRequired() {
        val invalid = listOf("countdownSeconds" to 11, "recordingSeconds" to 16,
            "referenceSeconds" to 11, "referenceMaxAgeSeconds" to 3601,
            "minDurationSeconds" to 16, "maxDurationSeconds" to 14,
            "minRmsDbfs" to -121, "maxRmsDbfs" to 1, "clippingAmplitude" to 1.1,
            "maxClippingRatio" to -0.01, "minSnrDb" to -1, "maxReferenceRmsDbfs" to -1,
            "revision" to "", "revision" to "x".repeat(65))
        for ((field, value) in invalid) rejects(JSONObject(shipped()).put(field, value).toString())
        for (number in listOf("NaN", "Infinity", "-Infinity", "1e999", "-1e999")) {
            rejects(shipped().replace("\"minSnrDb\": 10", "\"minSnrDb\": $number"))
        }
    }

    @Test fun permissivePlatformJsonSyntaxAndDuplicateFieldsAreRejected() {
        val valid = shipped()
        for (invalid in listOf(valid + "{}", valid + " trailing", "[$valid]",
            valid.replace("\"sampleRate\"", "sampleRate"),
            valid.replace("\"revision\"", "'revision'"),
            valid.replace("16000", "016000"), valid.replace("16000", "+16000"),
            valid.replace("16000", "16000 /* comment */"),
            valid.replace("\"sampleRate\": 16000", "\"sampleRate\": 16000, \"sampleRate\": 16000"),
            valid.replace("\"sampleRate\": 16000", "\"sampleRate\": {\"nested\": 16000}"),
            valid.replace("\n}", ",\n}"))) {
            rejects(invalid)
        }
    }

    @Test fun boundedUtf8AssetReadingClosesStreamOnSuccessAndFailure() {
        val good = TrackedStream(shipped().toByteArray(Charsets.UTF_8))
        assertEquals(RecordingConfigLoader.parse(shipped()), RecordingConfigLoader.load(good))
        assertTrue(good.closed)
        for (bytes in listOf(ByteArray(8193) { 32 }, byteArrayOf(0xC3.toByte(), 0x28))) {
            val bad = TrackedStream(bytes)
            assertThrows(IllegalArgumentException::class.java) { RecordingConfigLoader.load(bad) }
            assertTrue(bad.closed)
        }
    }

    @Test fun parseBoundsCharactersAndEncodedBytesWithoutTruncating() {
        rejects(" ".repeat(8193))
        rejects("é".repeat(4097))
        rejects(shipped().replace("engineering-draft-1", "draft\\ninvalid"))
    }

    private fun rejects(json: String) {
        assertThrows(IllegalArgumentException::class.java) { RecordingConfigLoader.parse(json) }
    }

    private fun shipped(): String = checkNotNull(javaClass.classLoader)
        .getResourceAsStream("diagnosis/recording-config.json")!!.bufferedReader().use { it.readText() }

    private class TrackedStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() { closed = true; super.close() }
    }
}

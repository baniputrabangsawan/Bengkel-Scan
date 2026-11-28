package com.fr3ts0n.ecu.gui.androbd.diagnosis.data

import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.ResultConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class ResultConfigLoaderTest {
    @Test fun shippedEngineeringDraftHasEveryRequiredThresholdAndConsentVersion() {
        val text = shipped()
        val root = JSONObject(text)
        assertEquals(setOf("schema_version", "confident_probability", "noise_probability",
            "conflict_probability", "stop_probability", "consent_text_version"), root.keys().asSequence().toSet())
        assertEquals(1, root.getInt("schema_version"))
        assertEquals(ResultConfig(0.75, 0.6, 0.6, 0.2, "feedback-export-id-v1"), ResultConfigLoader.parse(text))
    }

    @Test fun configuredThresholdsAreLoadedWithoutHardcodedDefaults() {
        val root = JSONObject(shipped()).put("confident_probability", 0.8).put("noise_probability", 0.7)
            .put("conflict_probability", 0.9).put("stop_probability", 0.1)
            .put("consent_text_version", "feedback-export-id-v2")
        assertEquals(ResultConfig(0.8, 0.7, 0.9, 0.1, "feedback-export-id-v2"), ResultConfigLoader.parse(root.toString()))
    }

    @Test fun missingUnknownAndWronglyTypedFieldsFailWithoutDefaults() {
        for (key in JSONObject(shipped()).keys().asSequence().toList()) {
            val root = JSONObject(shipped())
            root.remove(key)
            rejects(root.toString())
            rejects(JSONObject(shipped()).put(key, JSONObject.NULL).toString())
            rejects(JSONObject(shipped()).put(key, true).toString())
        }
        rejects(JSONObject(shipped()).put("unexpected", 1).toString())
        rejects(JSONObject(shipped()).put("schema_version", 2).toString())
        rejects(JSONObject(shipped()).put("schema_version", 1.5).toString())
        rejects(JSONObject(shipped()).put("schema_version", "1").toString())
        rejects(JSONObject(shipped()).put("confident_probability", "0.75").toString())
        rejects(JSONObject(shipped()).put("consent_text_version", 1).toString())
    }

    @Test fun allProbabilitiesAreFiniteAndBoundedAndStopCannotExceedConfident() {
        for (key in listOf("confident_probability", "noise_probability", "conflict_probability", "stop_probability")) {
            for (value in listOf(-0.001, 1.001)) rejects(JSONObject(shipped()).put(key, value).toString())
            for (literal in listOf("NaN", "Infinity", "-Infinity", "1e9999")) {
                rejects(shipped().replace(Regex("\"$key\"\\s*:\\s*[0-9.]+"), "\"$key\": $literal"))
            }
        }
        rejects(JSONObject(shipped()).put("stop_probability", 0.76).toString())
        assertEquals(0.0, ResultConfigLoader.parse(JSONObject(shipped()).put("stop_probability", 0).toString()).stopProbability, 0.0)
        assertEquals(1.0, ResultConfigLoader.parse(JSONObject(shipped()).put("confident_probability", 1)
            .put("noise_probability", 1).put("conflict_probability", 1).put("stop_probability", 1).toString()).stopProbability, 0.0)
    }

    @Test fun consentVersionMustBeAShortNonemptyIdentifier() {
        for (version in listOf("", " ", "v1\n", "a".repeat(65), "versi/1", "versi uji")) {
            rejects(JSONObject(shipped()).put("consent_text_version", version).toString())
        }
        assertEquals("a".repeat(64), ResultConfigLoader.parse(JSONObject(shipped())
            .put("consent_text_version", "a".repeat(64)).toString()).consentTextVersion)
    }

    @Test fun malformedAndPermissivePlatformJsonSyntaxAreRejected() {
        val text = shipped()
        for (invalid in listOf("", "[]", "null", "{", text + "{}", text + " trailing",
            text.replaceFirst("{", "{/* comment */"), text.replace("\"schema_version\"", "schema_version"),
            text.replace("\"schema_version\"", "'schema_version'"), text.replace("0.75", "+0.75"),
            text.replace("0.75", ".75"), text.replace("0.75", "00.75"), text.replace("0.75", "[0.75]"),
            text.replaceFirst("{", "{\"schema_version\":1,"), text.replaceFirst("{", "{\"schema\\u005fversion\":1,"),
            text.replace("\n}", ",\n}"), text.replace("feedback-export-id-v1", "bad\nversion"),
            text.replace("feedback-export-id-v1", "bad\\xversion"))) {
            rejects(invalid)
        }
    }

    @Test fun boundedUtf8ReadingClosesTheInputOnSuccessAndEveryFailure() {
        val stream = TrackedStream(shipped().toByteArray(Charsets.UTF_8))
        assertEquals(ResultConfigLoader.parse(shipped()), ResultConfigLoader.load(stream))
        assertTrue(stream.closed)
        for (bytes in listOf(ByteArray(4097) { ' '.code.toByte() }, byteArrayOf(0xc3.toByte(), 0x28),
            "{}".toByteArray())) {
            val invalid = TrackedStream(bytes)
            assertThrows(IllegalArgumentException::class.java) { ResultConfigLoader.load(invalid) }
            assertTrue(invalid.closed)
        }
    }

    @Test fun textAndUtf8ByteBoundsAreBothEnforced() {
        rejects(" ".repeat(4097))
        rejects("é".repeat(2049))
        val text = shipped()
        val padded = text + " ".repeat(4096 - text.toByteArray(Charsets.UTF_8).size)
        assertEquals(ResultConfigLoader.parse(text), ResultConfigLoader.parse(padded))
    }

    @Test fun directConfigConstructionCannotBypassValidation() {
        val valid = ResultConfigLoader.parse(shipped())
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.1, 1.1)) {
            assertThrows(IllegalArgumentException::class.java) { valid.copy(confidentProbability = value) }
            assertThrows(IllegalArgumentException::class.java) { valid.copy(noiseProbability = value) }
            assertThrows(IllegalArgumentException::class.java) { valid.copy(conflictProbability = value) }
            assertThrows(IllegalArgumentException::class.java) { valid.copy(stopProbability = value) }
        }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(stopProbability = 0.8) }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(consentTextVersion = "") }
    }

    private fun rejects(text: String) {
        assertThrows(IllegalArgumentException::class.java) { ResultConfigLoader.parse(text) }
    }

    private fun shipped(): String = requireNotNull(javaClass.classLoader)
        .getResourceAsStream("diagnosis/result-config.json")!!.bufferedReader().use { it.readText() }

    private class TrackedStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() { closed = true; super.close() }
    }
}

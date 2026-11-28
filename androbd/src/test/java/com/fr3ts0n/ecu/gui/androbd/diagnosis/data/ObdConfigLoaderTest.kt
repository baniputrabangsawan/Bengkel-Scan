package com.fr3ts0n.ecu.gui.androbd.diagnosis.data

import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.ObdDtcEvidence
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.ObdEvidence
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.ObdEvidenceFusion
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.VehicleContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class ObdConfigLoaderTest {
    @Test fun bundledDraftExposesFreshnessAndAllConfiguredCauseIdsExist() {
        val config = ObdConfigLoader.parse(bundled())
        val knowledge = ExpertKnowledgeLoader.load(asset("expert-knowledge.json"))
        val ids = knowledge.entries.flatMap { it.candidateCauses }.map { it.id }.toSet()
        assertEquals("phase7-obd-engineering-draft-1", config.revision)
        assertEquals(15000L, config.pidFreshnessMs)
        assertEquals(60000L, config.dtcFreshnessMs)
        assertEquals(config.pidFreshnessMs, config.fuelTrim.supportFreshnessMs)
        assertTrue(config.reviewNotice.contains("draf rekayasa"))
        assertTrue(config.dtcRules.flatMap { it.causeWeights.keys }.all { it in ids })
        assertTrue(config.fuelTrim.positiveCauseWeights.keys.all { it in ids })
        assertTrue(config.fuelTrim.negativeCauseWeights.keys.all { it in ids })
    }

    @Test fun newDtcRuleNeedsOnlyJsonAndUsesExistingScorerAndCause() {
        val root = JSONObject(bundled())
        root.getJSONArray("dtc_rules").put(JSONObject()
            .put("id", "injector_circuit_fixture").put("codes", JSONArray().put("P0201"))
            .put("fuel_types", JSONArray().put("petrol")).put("description", "Petunjuk rangkaian injektor pada fixture, bukan bukti injektor rusak.")
            .put("cause_weights", JSONObject().put("obd_misfire.injector", 20)))
        val config = ObdConfigLoader.parse(root.toString())
        val knowledge = ExpertKnowledgeLoader.load(asset("expert-knowledge.json"))
        val result = ObdEvidenceFusion.merge(knowledge, VehicleContext(fuelType = "petrol", fuelSystem = "injection"),
            emptySet(), emptyMap(), ObdEvidence(3, true, "REAL", listOf(ObdDtcEvidence("P0201", "pending", 100))), config, 100)
        assertTrue(result.used)
        assertEquals("obd_misfire.injector", result.triage.causes.single().cause.id)
        assertTrue(result.triage.causes.single().reason.contains("P0201"))
    }

    @Test fun requiredFieldsTypesSignsAndFiniteWeightsAreValidatedWithoutCoercion() {
        listOf("schema_version", "revision", "review_notice", "pid_freshness_ms", "dtc_freshness_ms", "dtc_rules", "fuel_trim").forEach { field ->
            invalid { it.remove(field) }
            invalid { it.put(field, JSONObject.NULL) }
        }
        listOf<Any>(0, -1, "1000", true, 1.5).forEach { value -> invalid { it.put("pid_freshness_ms", value) } }
        listOf<Any>(0, 2, "1", 1.5).forEach { value -> invalid { it.put("schema_version", value) } }
        invalid { it.put("revision", " ") }
        invalid { it.put("unexpected", 1) }
        listOf<Any>(0, -1, "12", JSONObject.NULL).forEach { value ->
            invalid { it.getJSONArray("dtc_rules").getJSONObject(0).getJSONObject("cause_weights").put("obd_misfire.ignition", value) }
        }
        rejects(bundled().replace("\"obd_misfire.ignition\": 12", "\"obd_misfire.ignition\": 1e999"))
    }

    @Test fun trimRequiresMultipleObservationsConsistentWindowsAndCorrectBankMapping() {
        listOf<Any>(0, 1, 2.5, "3").forEach { value -> invalid { trim(it).put("minimum_observations", value) } }
        listOf("minimum_span_ms", "maximum_pair_skew_ms", "support_freshness_ms").forEach { name ->
            invalid { trim(it).put(name, 0) }
            invalid { trim(it).put(name, 15001) }
        }
        invalid { trim(it).put("positive_combined_percent", 0) }
        invalid { trim(it).put("negative_combined_percent", 1) }
        invalid { trim(it).put("minimum_rpm", 0) }
        invalid { trim(it).put("closed_loop_value", 1) }
        invalid { trim(it).getJSONArray("banks").getJSONObject(0).put("long_mnemonic", "fuel_trim_long_b2") }
        invalid { trim(it).getJSONArray("banks").put(trim(it).getJSONArray("banks").getJSONObject(0)) }
        invalid { trim(it).put("banks", JSONArray()) }
    }

    @Test fun invalidDuplicateOrAmbiguousRulesAreRejected() {
        invalid { it.put("dtc_rules", JSONArray()) }
        invalid { root -> root.getJSONArray("dtc_rules").put(root.getJSONArray("dtc_rules").getJSONObject(0)) }
        invalid { it.getJSONArray("dtc_rules").getJSONObject(0).put("codes", JSONArray().put("P03XX")) }
        invalid { it.getJSONArray("dtc_rules").getJSONObject(0).put("fuel_types", JSONArray().put("unknown")) }
        invalid { it.getJSONArray("dtc_rules").getJSONObject(0).put("cause_weights", JSONObject().put("bad_id", 1)) }
        rejects(bundled().replaceFirst("\"revision\":", "\"schema_version\": 1, \"revision\":"))
        rejects(bundled().replaceFirst("\"obd_misfire.ignition\": 12", "\"obd_misfire.ignition\": 12, \"obd_misfire.ignition\": 13"))
    }

    @Test fun malformedPermissiveJsonSyntaxIsRejected() {
        val text = bundled()
        listOf(text + "{}", text.replaceFirst("15000", "015000"), text.replaceFirst("15000", "+15000"),
            text.replaceFirst("15000", "NaN"), text.replaceFirst("[\"P0300\"", "[\"P0300\",]"),
            text.replaceFirst("\"phase7", "'phase7"), text.replaceFirst("phase7", "phase\\q7"),
            "[".repeat(18) + "0" + "]".repeat(18)).forEach(::rejects)
    }

    @Test fun loaderClosesInputAndRejectsMalformedUtf8OrOversizedInput() {
        val valid = TrackedStream(bundled().toByteArray())
        assertEquals("phase7-obd-engineering-draft-1", ObdConfigLoader.load(valid).revision)
        assertTrue(valid.closed)
        listOf(byteArrayOf(0xc3.toByte(), 0x28), ByteArray(65537) { 32 }).forEach {
            val stream = TrackedStream(it)
            assertThrows(IllegalArgumentException::class.java) { ObdConfigLoader.load(stream) }
            assertTrue(stream.closed)
        }
        rejects(" ".repeat(65537))
    }

    @Test fun unknownCauseReferencesFailAtKnowledgeBoundaryInsteadOfInventingCandidates() {
        val root = JSONObject(bundled())
        root.getJSONArray("dtc_rules").getJSONObject(0).getJSONObject("cause_weights").put("missing.cause", 1)
        val config = ObdConfigLoader.parse(root.toString())
        val knowledge = ExpertKnowledgeLoader.load(asset("expert-knowledge.json"))
        assertThrows(IllegalArgumentException::class.java) {
            ObdEvidenceFusion.merge(knowledge, VehicleContext(fuelType = "petrol", fuelSystem = "injection"),
                emptySet(), emptyMap(), ObdEvidence(1, true, "REAL", listOf(ObdDtcEvidence("P0301", "stored", 100))), config, 100)
        }
    }

    private fun trim(root: JSONObject) = root.getJSONObject("fuel_trim")
    private fun invalid(change: (JSONObject) -> Unit) {
        val root = JSONObject(bundled())
        change(root)
        rejects(root.toString())
    }
    private fun rejects(text: String) { assertThrows(IllegalArgumentException::class.java) { ObdConfigLoader.parse(text) } }
    private fun bundled() = asset("obd-config.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
    private fun asset(name: String) = requireNotNull(javaClass.classLoader?.getResourceAsStream("diagnosis/$name"))
    private class TrackedStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() { closed = true; super.close() }
    }
}

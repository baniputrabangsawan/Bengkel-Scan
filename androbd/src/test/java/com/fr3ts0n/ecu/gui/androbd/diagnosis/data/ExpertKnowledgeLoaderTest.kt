package com.fr3ts0n.ecu.gui.androbd.diagnosis.data

import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisScorer
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.Severity
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.VehicleContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class ExpertKnowledgeLoaderTest {
    @Test fun diagnosticEntryCannotCertifyMonitorSeverity() {
        invalid { entry(it).put("severity", "monitor") }
    }

    @Test fun malformedUtf8InsideAJsonStringCannotBeSilentlyReplaced() {
        val json = validJson()
        val prefix = json.substringBefore("test-1").toByteArray(Charsets.UTF_8)
        val suffix = json.substringAfter("test-1").toByteArray(Charsets.UTF_8)
        listOf(
            byteArrayOf(0xc3.toByte(), 0x28),
            byteArrayOf(0xc0.toByte(), 0xaf.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
            byteArrayOf(0xf4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte())
        ).forEach { malformed ->
            val stream = TrackedStream(prefix + malformed + suffix)
            assertThrows(IllegalArgumentException::class.java) { ExpertKnowledgeLoader.load(stream) }
            assertTrue(stream.closed)
        }
    }

    @Test fun strictSyntaxAlsoRejectsNestedDuplicatesArrayCommasAndMalformedNumbers() {
        val json = validJson()
        listOf(
            json.replaceFirst("\"base_weight\": 1", "\"base_weight\": 1, \"base_weight\": 2"),
            json.replaceFirst("[\"idle\"]", "[\"idle\",]"),
            json.replaceFirst("\"test-1\"", "\"test\\q\"")
        ).forEach { rejects(it) }
        listOf("01", "+1", ".5", "1.", "Infinity", "-Infinity").forEach { number ->
            rejects(json.replaceFirst("\"base_weight\": 1", "\"base_weight\": $number"))
        }
    }

    @Test fun utf8ByteLimitAcceptsBoundaryAndRejectsOneExtraByteInBothApis() {
        val prefix = validJson().toByteArray(Charsets.UTF_8)
        val limit = 1024 * 1024
        val boundary = prefix + ByteArray(limit - prefix.size) { 32 }
        assertEquals("test-1", ExpertKnowledgeLoader.load(ByteArrayInputStream(boundary)).revision)
        assertEquals("test-1", ExpertKnowledgeLoader.parse(boundary.toString(Charsets.UTF_8)).revision)
        val oversized = boundary + byteArrayOf(32)
        assertThrows(IllegalArgumentException::class.java) {
            ExpertKnowledgeLoader.load(ByteArrayInputStream(oversized))
        }
        rejects(oversized.toString(Charsets.UTF_8))
    }

    @Test fun byteLimitCountsMultibyteCharactersRatherThanKotlinStringLength() {
        val root = JSONObject(validJson()).put("revision", "é".repeat(600_000)).toString()
        assertTrue(root.length < 1024 * 1024)
        rejects(root)
    }

    @Test fun excessiveNestingAndEmptyCatalogAreRejected() {
        rejects("[".repeat(33) + "0" + "]".repeat(33))
        invalid { it.put("entries", JSONArray()) }
    }

    @Test fun minimalVersionedConfigurationLoadsWithoutAndroidContext() {
        val knowledge = ExpertKnowledgeLoader.parse(validJson())
        assertEquals(1, knowledge.schemaVersion)
        assertEquals("test-1", knowledge.revision)
        assertEquals("fixture_sound", knowledge.entries.single().id)
        assertEquals("fixture_sound.cause", knowledge.entries.single().candidateCauses.single().id)
        assertEquals(3, knowledge.scoring.maxResults)
        assertEquals(Severity.INSPECT, knowledge.entries.single().severity)
        assertTrue(knowledge.disclaimer.isNotBlank())
        assertTrue(knowledge.reviewNotice.isNotBlank())
    }

    @Test fun bundledDraftCatalogSeparatesObservedSoundsFromInferredFaults() {
        val knowledge = ExpertKnowledgeLoader.parse(bundledJson())
        assertEquals(setOf("normal_idle", "belt_squeal", "valvetrain_tick", "brake_grinding",
            "wheel_bearing_hum", "power_steering_whine", "background_noise", "unknown",
            "detonation_pinging", "vacuum_leak", "brake_squeal", "hiss", "obd_misfire",
            "obd_fuel_metering"), knowledge.entries.map { it.id }.toSet())
        knowledge.entries.forEach {
            assertEquals("needs_mechanic_verification", it.reviewStatus)
            assertTrue(it.verificationTests.isNotEmpty())
            assertTrue(it.verificationTests.all(String::isNotBlank))
        }
        knowledge.entries.filter { it.id in setOf("normal_idle", "background_noise", "unknown", "hiss") }
            .forEach {
                assertFalse(it.diagnostic)
                assertTrue(it.candidateCauses.isEmpty())
            }
    }

    @Test fun newDiagnosticEntryNeedsOnlyJsonAndImmediatelyRanks() {
        val root = JSONObject(validJson())
        val added = JSONObject(entry(root).toString())
            .put("id", "new_accessory_rattle")
            .put("symptom_name", "Bunyi aksesori baru")
        added.getJSONArray("candidate_causes").getJSONObject(0)
            .put("id", "new_accessory_rattle.loose_bracket").put("base_weight", 7)
        root.getJSONArray("entries").put(added)
        val knowledge = ExpertKnowledgeLoader.parse(root.toString())
        val result = DiagnosisScorer.rank(knowledge, VehicleContext(), setOf("new_accessory_rattle"))
        assertEquals(listOf("new_accessory_rattle.loose_bracket"), result.causes.map { it.cause.id })
        assertEquals(9.0, result.causes.single().score, 0.000001)
    }

    @Test fun requiredRootFieldsCannotBeMissingNullOrBlank() {
        listOf("schema_version", "revision", "disclaimer", "review_notice", "scoring", "safety_rules", "entries")
            .forEach { field ->
                invalid { it.remove(field) }
                invalid { it.put(field, JSONObject.NULL) }
            }
        listOf("revision", "disclaimer", "review_notice").forEach { field ->
            invalid { it.put(field, "  ") }
        }
    }

    @Test fun versionAndResultLimitsAreStrictIntegersWithSupportedBounds() {
        listOf<Any>(0, 2, 1.5, "1", true).forEach { value -> invalid { it.put("schema_version", value) } }
        listOf<Any>(0, 4, -1, 2.5, "3", false).forEach { value ->
            invalid { it.getJSONObject("scoring").put("max_results", value) }
        }
    }

    @Test fun scoringNumbersAndMessagesCannotBeMissingOrCoerced() {
        val weights = listOf("minimum_score", "minimum_margin", "manual_symptom_weight", "audio_weight",
            "audio_min_probability", "trigger_match_weight", "location_match_weight")
        weights.forEach { field ->
            listOf<Any>(-0.1, "2", true, JSONObject.NULL).forEach { value ->
                invalid { it.getJSONObject("scoring").put(field, value) }
            }
            invalid { it.getJSONObject("scoring").remove(field) }
        }
        invalid { it.getJSONObject("scoring").put("audio_min_probability", 1.01) }
        listOf("base_reason", "manual_reason", "audio_reason", "trigger_reason", "location_reason",
            "missing_context_reason", "uncertain_message", "ranked_message", "no_match_message")
            .forEach { field ->
                invalid { it.getJSONObject("scoring").remove(field) }
                invalid { it.getJSONObject("scoring").put(field, " ") }
            }
    }

    @Test fun entryFieldsAndEnumsAreValidated() {
        listOf("id", "symptom_name", "layman_sound_description", "trigger_conditions", "location",
            "candidate_causes", "severity", "verification_tests", "recommended_action", "cost_category",
            "review_status", "diagnostic").forEach { field -> invalid { entry(it).remove(field) } }
        listOf("id", "symptom_name", "layman_sound_description", "recommended_action").forEach { field ->
            invalid { entry(it).put(field, " ") }
        }
        invalid { entry(it).put("severity", "urgent") }
        invalid { entry(it).put("cost_category", "cheap") }
        invalid { entry(it).put("review_status", "verified") }
        invalid { entry(it).put("diagnostic", "true") }
        invalid { entry(it).put("id", "Invalid ID") }
        invalid { entry(it).put("verification_tests", JSONArray()) }
        invalid { entry(it).put("verification_tests", JSONArray().put("")) }
        invalid { entry(it).put("location", JSONArray().put(" ")) }
        invalid { entry(it).put("location", JSONArray().put("front").put("front")) }
        invalid { entry(it).put("trigger_conditions", JSONArray().put("racing")) }
        invalid { entry(it).put("trigger_conditions", JSONArray().put("cold").put("cold")) }
    }

    @Test fun diagnosticAndNonDiagnosticEntriesCannotHaveContradictoryCauses() {
        invalid { entry(it).put("candidate_causes", JSONArray()) }
        invalid { entry(it).put("diagnostic", false) }
        val root = JSONObject(validJson())
        entry(root).put("diagnostic", false).put("candidate_causes", JSONArray())
        assertFalse(ExpertKnowledgeLoader.parse(root.toString()).entries.single().diagnostic)
    }

    @Test fun entryAndCauseIdsAreUniqueAcrossTheCatalog() {
        invalid { it.getJSONArray("entries").put(JSONObject(entry(it).toString())) }
        invalid {
            val other = JSONObject(entry(it).toString()).put("id", "other_sound")
            it.getJSONArray("entries").put(other)
        }
        invalid {
            val causes = entry(it).getJSONArray("candidate_causes")
            causes.put(JSONObject(causes.getJSONObject(0).toString()))
        }
        invalid { cause(it).put("id", " ") }
        invalid { cause(it).put("id", "bad cause id") }
    }

    @Test fun causeWeightsNamesAndArraysAreRequiredAndTyped() {
        listOf("id", "name", "base_weight", "applicability", "context_modifiers").forEach { field ->
            invalid { cause(it).remove(field) }
        }
        invalid { cause(it).put("name", " ") }
        listOf<Any>(-1, "1", true, JSONObject.NULL).forEach { value ->
            invalid { cause(it).put("base_weight", value) }
        }
        invalid { cause(it).put("applicability", JSONObject()) }
        invalid { cause(it).put("context_modifiers", JSONObject()) }
    }

    @Test fun malformedPredicateFieldsOperatorsAndValuesAreRejected() {
        val badPredicates = listOf(
            predicate("unsupported", "eq", "x"), predicate("fuel_type", "gte", 1),
            predicate("fuel_type", "contains", "petrol"), predicate("fuel_type", "eq", 1),
            predicate("fuel_type", "eq", JSONObject.NULL), predicate("fuel_type", "eq", " "),
            predicate("flags", "eq", "wheel_play"), predicate("flags", "contains", 1),
            predicate("trigger_conditions", "contains", JSONArray().put("cold")),
            predicate("location", "lte", 1), predicate("mileage_km", "gte", "100"),
            predicate("mileage_km", "eq", -1), predicate("cylinder_count", "eq", 0),
            predicate("cylinder_count", "eq", 3.5), predicate("mileage_km", "gte", -1),
            predicate("fuel_type", "not_eq", "diesel")
        )
        badPredicates.forEach { p -> invalid { cause(it).put("applicability", JSONArray().put(p)) } }
        listOf("field", "operator", "value").forEach { field ->
            invalid { cause(it).put("applicability", JSONArray().put(predicate("fuel_type", "eq", "petrol").apply { remove(field) })) }
        }
    }

    @Test fun extensibleScalarContextAndNumericPredicateBoundariesRemainValid() {
        val root = JSONObject(validJson())
        cause(root).put("applicability", JSONArray()
            .put(predicate("fuel_system", "eq", "future_injection"))
            .put(predicate("cylinder_count", "eq", 3))
            .put(predicate("mileage_km", "gte", 0))
            .put(predicate("mileage_km", "lte", 999999)))
        val parsed = ExpertKnowledgeLoader.parse(root.toString())
        assertEquals(4, parsed.entries.single().candidateCauses.single().applicability.size)
    }

    @Test fun modifiersRequireConditionsFiniteDeltaAndExplanation() {
        listOf("when", "delta", "reason").forEach { field ->
            invalid { cause(it).put("context_modifiers", JSONArray().put(modifier().apply { remove(field) })) }
        }
        invalid { cause(it).put("context_modifiers", JSONArray().put(modifier().put("when", JSONArray()))) }
        invalid { cause(it).put("context_modifiers", JSONArray().put(modifier().put("delta", "2"))) }
        invalid { cause(it).put("context_modifiers", JSONArray().put(modifier().put("reason", " "))) }
        val root = JSONObject(validJson())
        cause(root).put("context_modifiers", JSONArray().put(modifier().put("delta", -2.5)))
        assertEquals(-2.5, ExpertKnowledgeLoader.parse(root.toString()).entries.single()
            .candidateCauses.single().contextModifiers.single().delta, 0.000001)
    }

    @Test fun safetyRulesRequireConditionsAnEscalatingSeverityAndExplanation() {
        listOf("when", "severity", "reason").forEach { field ->
            invalid { it.getJSONArray("safety_rules").getJSONObject(0).remove(field) }
        }
        invalid { it.getJSONArray("safety_rules").getJSONObject(0).put("when", JSONArray()) }
        invalid { it.getJSONArray("safety_rules").getJSONObject(0).put("severity", "monitor") }
        invalid { it.getJSONArray("safety_rules").getJSONObject(0).put("reason", " ") }
    }

    @Test fun unknownPropertiesAreRejectedAtEveryObjectLevel() {
        val targets: List<(JSONObject) -> JSONObject> = listOf(
            { it }, { it.getJSONObject("scoring") }, { entry(it) }, { cause(it) },
            { it.getJSONArray("safety_rules").getJSONObject(0) },
            { it.getJSONArray("safety_rules").getJSONObject(0).getJSONArray("when").getJSONObject(0) }
        )
        targets.forEach { target -> invalid { target(it).put("unexpected", 1) } }
        invalid {
            cause(it).put("context_modifiers", JSONArray().put(modifier().put("unexpected", 1)))
        }
    }

    @Test fun strictSyntaxRejectsTrailingDataLenientJsonAndDuplicateKeys() {
        val json = validJson()
        listOf("", "[]", "null", "$json {}", "$json trailing", json.dropLast(1),
            json.replaceFirst("\"schema_version\": 1", "schema_version: 1"),
            json.replaceFirst("\"schema_version\"", "'schema_version'"),
            json.replaceFirst("\"schema_version\": 1", "\"schema_version\": 1, \"schema_version\": 1"),
            json.replaceFirst("\"schema_version\": 1", "/* comment */ \"schema_version\": 1"),
            json.replaceFirst("\"base_weight\": 1", "\"base_weight\": NaN"),
            json.replaceFirst("\"base_weight\": 1", "\"base_weight\": 1e999"),
            json.dropLast(1) + ",}")
            .forEach { rejects(it) }
    }

    @Test fun loadingStreamUsesStrictUtf8AndClosesOnSuccessAndFailure() {
        val valid = TrackedStream(validJson().toByteArray(Charsets.UTF_8))
        assertEquals("test-1", ExpertKnowledgeLoader.load(valid).revision)
        assertTrue(valid.closed)
        val malformed = TrackedStream(byteArrayOf(0xc3.toByte(), 0x28))
        assertThrows(IllegalArgumentException::class.java) { ExpertKnowledgeLoader.load(malformed) }
        assertTrue(malformed.closed)
        val invalidJson = TrackedStream("{".toByteArray(Charsets.UTF_8))
        assertThrows(IllegalArgumentException::class.java) { ExpertKnowledgeLoader.load(invalidJson) }
        assertTrue(invalidJson.closed)
    }

    @Test fun oversizedConfigurationIsRejectedEvenWhenExtraBytesAreWhitespace() {
        val bytes = validJson().toByteArray(Charsets.UTF_8) + ByteArray(2 * 1024 * 1024) { 32 }
        val oversized = TrackedStream(bytes)
        assertThrows(IllegalArgumentException::class.java) { ExpertKnowledgeLoader.load(oversized) }
        assertTrue(oversized.closed)
    }

    private fun invalid(change: (JSONObject) -> Unit) {
        val root = JSONObject(validJson())
        change(root)
        rejects(root.toString())
    }

    private fun rejects(json: String) {
        assertThrows(IllegalArgumentException::class.java) { ExpertKnowledgeLoader.parse(json) }
    }

    private fun entry(root: JSONObject): JSONObject = root.getJSONArray("entries").getJSONObject(0)
    private fun cause(root: JSONObject): JSONObject = entry(root).getJSONArray("candidate_causes").getJSONObject(0)
    private fun predicate(field: String, operator: String, value: Any): JSONObject =
        JSONObject().put("field", field).put("operator", operator).put("value", value)
    private fun modifier(): JSONObject = JSONObject().put("when", JSONArray().put(predicate("flags", "contains", "test_flag")))
        .put("delta", 1).put("reason", "Kondisi yang dipilih menambah dukungan sementara.")

    private fun bundledJson(): String = requireNotNull(javaClass.classLoader
        ?.getResourceAsStream("diagnosis/expert-knowledge.json")) { "Missing bundled expert catalog" }
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

    private class TrackedStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() {
            closed = true
            super.close()
        }
    }

    private fun validJson(): String = """
        {
          "schema_version": 1,
          "revision": "test-1",
          "disclaimer": "Informasi awal, bukan diagnosis; minta pemeriksaan mekanik berkualifikasi.",
          "review_notice": "Draf ini masih memerlukan verifikasi mekanik.",
          "scoring": {
            "max_results": 3, "minimum_score": 1, "minimum_margin": 1,
            "manual_symptom_weight": 2, "audio_weight": 2, "audio_min_probability": 0.6,
            "trigger_match_weight": 1, "location_match_weight": 1,
            "base_reason": "Dukungan awal dari panduan.", "manual_reason": "Gejala dipilih pengguna.",
            "audio_reason": "Petunjuk audio tambahan, bukan kepastian.", "trigger_reason": "Kondisi bunyi sesuai.",
            "location_reason": "Lokasi bunyi sesuai.", "missing_context_reason": "Konteks belum lengkap.",
            "uncertain_message": "Hasil sementara memerlukan pemeriksaan mekanik.",
            "ranked_message": "Urutan kemungkinan, bukan diagnosis pasti.", "no_match_message": "Belum cukup petunjuk."
          },
          "safety_rules": [{
            "when": [{"field": "flags", "operator": "contains", "value": "brake_change"}],
            "severity": "stop", "reason": "Perubahan rem perlu penanganan segera."
          }],
          "entries": [{
            "id": "fixture_sound", "symptom_name": "Bunyi uji", "layman_sound_description": "Bunyi berulang.",
            "trigger_conditions": ["idle"], "location": ["under_hood"],
            "candidate_causes": [{
              "id": "fixture_sound.cause", "name": "Kemungkinan penyebab uji", "base_weight": 1,
              "applicability": [], "context_modifiers": []
            }],
            "severity": "inspect", "verification_tests": ["Catat kondisi bunyi dari tempat aman."],
            "recommended_action": "Minta pemeriksaan mekanik.", "cost_category": "unknown",
            "review_status": "needs_mechanic_verification", "diagnostic": true
          }]
        }
    """.trimIndent()
}

package com.fr3ts0n.ecu.gui.androbd.diagnosis.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class SymptomLexiconLoaderTest {
    @Test fun shippedRulesOnlyReferenceExistingExpertSymptoms() {
        val lexicon = JSONObject(shipped())
        val expert = JSONObject(resource("expert-knowledge.json"))
        val entries = expert.getJSONArray("entries")
        val ids = (0 until entries.length()).map { entries.getJSONObject(it).getString("id") }.toSet()
        val rules = lexicon.getJSONArray("rules")
        for (index in 0 until rules.length()) {
            val rule = rules.getJSONObject(index)
            if (rule.getString("kind") == "symptom") assertTrue(rule.getString("id") in ids)
        }
        assertEquals(setOf("detonation_pinging"), SymptomLexiconLoader.parse(shipped()).parse("ngelitik").symptomIds)
        SymptomLexiconLoader.parse(shipped(), ids)
    }

    @Test fun observationIdsComeFromTheSuppliedCatalogRatherThanAKotlinSymptomList() {
        val root = JSONObject(shipped())
        rule(root).put("id", "additional_observation")
        val json = root.toString()
        assertEquals(setOf("additional_observation"),
            SymptomLexiconLoader.parse(json).parse("Langsam normal").symptomIds)
        val ids = symptomIds() + "additional_observation"
        assertEquals(setOf("additional_observation"),
            SymptomLexiconLoader.parse(json, ids).parse("Langsam normal").symptomIds)
        assertThrows(IllegalArgumentException::class.java) {
            SymptomLexiconLoader.parse(json, symptomIds())
        }
        rule(root).put("id", "Invalid-Observation")
        assertThrows(IllegalArgumentException::class.java) { SymptomLexiconLoader.parse(root.toString()) }
    }

    @Test fun strictFieldsTypesAndReferencesRejectMalformedConfiguration() {
        listOf<(JSONObject) -> Unit>(
            { it.put("unexpected", true) },
            { it.remove("schema_version") },
            { it.put("schema_version", "1") },
            { it.put("schema_version", 2) },
            { it.put("rules", JSONObject()) },
            { it.put("rules", JSONArray()) },
            { rule(it).put("kind", "fuel_type") },
            { rule(it).put("id", "rod_knock") },
            { rule(it).put("kind", "trigger").put("id", "highway") },
            { rule(it).put("kind", "flag").put("id", "guessed_damage") },
            { rule(it).put("kind", "location").put("id", "engine") },
            { rule(it).put("unexpected", 1) },
            { rule(it).remove("explanation") },
            { rule(it).put("explanation", JSONObject.NULL) },
            { rule(it).put("explanation", " ") },
            { rule(it).put("phrases", JSONArray()) },
            { rule(it).put("phrases", JSONArray().put(7)) },
            { rule(it).put("phrases", JSONArray().put("ngelitik").put("ngelitik")) },
            { rule(it).put("phrases", JSONArray().put("Ngelitik")) },
            { rule(it).put("phrases", JSONArray().put("tek-tek")) },
            { rule(it).put("phrases", JSONArray().put("x".repeat(121))) },
            { rule(it).put("phrases", JSONArray((1..33).map { index -> "bunyi $index" })) },
            { rule(it).put("required_any", JSONArray().put(true)) },
            { rule(it).put("excluded_any", "rem") },
            { it.getJSONArray("rules").put(JSONObject(rule(it).toString())) },
            { root -> root.put("rules", JSONArray((1..97).map { rule(root) })) }
        ).forEach { mutate ->
            val root = JSONObject(shipped())
            mutate(root)
            rejects(root.toString())
        }
    }

    @Test fun androidPermissiveJsonSyntaxIsNotAccepted() {
        listOf("", "[]", "null", "{} trailing", "{schema_version:1,rules:[]}",
            "{'schema_version':1,'rules':[]}", "{\"schema_version\":01,\"rules\":[]}",
            "{\"schema_version\":1,\"rules\":[],}", "/*comment*/{}",
            "{\"schema_version\":1,\"schema_version\":1,\"rules\":[]}",
            "{\"schema_version\":1,\"rules\":[,]}", "[".repeat(20) + "]".repeat(20))
            .forEach(::rejects)
        rejects(shipped().replaceFirst("\"schema_version\"", "\"schema_version\": 1, \"schema_version\""))
        rejects(shipped().replaceFirst("\"kind\": \"symptom\"", "\"kind\": \"symptom\", \"kind\": \"symptom\""))
        rejects(shipped().replaceFirst("ngelitik", "ngeli\\qtik"))
    }

    @Test fun boundedUtf8LoadingOwnsAndClosesInputOnSuccessAndFailure() {
        val valid = TrackedStream(shipped().toByteArray(Charsets.UTF_8))
        assertEquals(setOf("detonation_pinging"), SymptomLexiconLoader.load(valid).parse("ngelitik").symptomIds)
        assertTrue(valid.closed)
        listOf(byteArrayOf(0xc3.toByte(), 0x28), ByteArray(128 * 1024 + 1) { 32 }, "{}".toByteArray())
            .forEach { bytes ->
                val stream = TrackedStream(bytes)
                assertThrows(IllegalArgumentException::class.java) { SymptomLexiconLoader.load(stream) }
                assertTrue(stream.closed)
            }
    }

    @Test fun assetBoundsCountUtf8BytesAndDoNotTruncate() {
        val json = shipped()
        val bytes = json.toByteArray(Charsets.UTF_8)
        val boundary = bytes + ByteArray(128 * 1024 - bytes.size) { 32 }
        assertEquals(setOf("detonation_pinging"), SymptomLexiconLoader.load(ByteArrayInputStream(boundary)).parse("ngelitik").symptomIds)
        assertEquals(setOf("detonation_pinging"), SymptomLexiconLoader.parse(boundary.toString(Charsets.UTF_8)).parse("ngelitik").symptomIds)
        rejects(boundary.toString(Charsets.UTF_8) + " ")
        val multibyte = "é".repeat(70_000)
        assertTrue(multibyte.length < 128 * 1024)
        rejects(multibyte)
    }

    private fun rejects(json: String) {
        assertThrows(IllegalArgumentException::class.java) { SymptomLexiconLoader.parse(json, symptomIds()) }
    }
    private fun rule(root: JSONObject): JSONObject = root.getJSONArray("rules").getJSONObject(0)
    private fun symptomIds(): Set<String> {
        val entries = JSONObject(resource("expert-knowledge.json")).getJSONArray("entries")
        return (0 until entries.length()).map { entries.getJSONObject(it).getString("id") }.toSet()
    }
    private fun shipped(): String = resource("symptom-synonyms.json")
    private fun resource(name: String): String = checkNotNull(javaClass.classLoader)
        .getResourceAsStream("diagnosis/$name")!!.bufferedReader().use { it.readText() }
    private class TrackedStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() { closed = true; super.close() }
    }
}

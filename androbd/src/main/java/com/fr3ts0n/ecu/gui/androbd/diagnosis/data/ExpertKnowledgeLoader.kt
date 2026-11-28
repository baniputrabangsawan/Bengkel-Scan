package com.fr3ts0n.ecu.gui.androbd.diagnosis.data

import android.content.Context
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.ContextModifier
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.ContextPredicate
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.ExpertCause
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.ExpertEntry
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.ExpertKnowledge
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.SafetyRule
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.ScoringConfig
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.Severity
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Collections

/** Platform JSON only at runtime. Parse/load are synchronous: invoke on the caller's I/O worker. */
object ExpertKnowledgeLoader {
    private const val MAX_BYTES = 1024 * 1024
    private const val MAX_DEPTH = 32
    private val numericFields = setOf("cylinder_count", "mileage_km")
    private val setFields = setOf("trigger_conditions", "flags")
    private val scalarFields = setOf("fuel_type", "fuel_system", "engine_temp_state", "temperature_pattern", "power_steering_type", "location")
    private val entryIdentifier = Regex("[a-z][a-z0-9_]*")
    private val causeIdentifier = Regex("[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*")
    private val triggers = setOf("cold", "warm", "idle", "throttle", "braking", "turning")
    private val scoringNumbers = arrayOf("minimum_score", "minimum_margin", "manual_symptom_weight", "audio_weight",
        "audio_min_probability", "trigger_match_weight", "location_match_weight")
    private val scoringText = arrayOf("base_reason", "manual_reason", "audio_reason", "trigger_reason", "location_reason",
        "missing_context_reason", "uncertain_message", "ranked_message", "no_match_message")

    @JvmStatic fun load(context: Context): ExpertKnowledge =
        load(context.assets.open("diagnosis/expert-knowledge.json"))

    /** Owns/closes input; malformed UTF-8 never silently becomes replacement characters. */
    @JvmStatic fun load(input: InputStream): ExpertKnowledge = input.use {
        val bytes = it.readBytesBounded()
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (error: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("triage.invalid_utf8", error)
        }
        parse(text)
    }

    @JvmStatic fun parse(json: String): ExpertKnowledge {
        require(json.length <= MAX_BYTES && json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "triage.file_too_large" }
        Syntax(json).validate()
        try {
            val root = JSONObject(json)
            root.fields("schema_version", "revision", "disclaimer", "review_notice", "scoring", "safety_rules", "entries")
            require(root.integer("schema_version") == 1) { "triage.unsupported_schema" }
            val entries = root.objects("entries").map(::entry)
            require(entries.isNotEmpty()) { "triage.empty_knowledge" }
            require(entries.map { it.id }.distinct().size == entries.size) { "triage.duplicate_entry_id" }
            val causes = entries.flatMap { it.candidateCauses }
            require(causes.map { it.id }.distinct().size == causes.size) { "triage.duplicate_cause_id" }
            val safety = root.objects("safety_rules").map { rule ->
                rule.fields("when", "severity", "reason")
                val severity = severity(rule.text("severity"))
                require(severity != Severity.MONITOR) { "triage.invalid_safety_rule" }
                SafetyRule(predicates(rule, "when", true), severity, rule.text("reason"))
            }
            return ExpertKnowledge(1, root.text("revision"), root.text("disclaimer"), root.text("review_notice"),
                scoring(root.getJSONObject("scoring")), frozen(safety), frozen(entries))
        } catch (error: JSONException) {
            throw IllegalArgumentException("triage.invalid_knowledge", error)
        }
    }

    private fun scoring(source: JSONObject): ScoringConfig {
        source.fields("max_results", *scoringNumbers, *scoringText)
        val max = source.integer("max_results")
        require(max in 1..3) { "triage.invalid_max_results" }
        val numbers = scoringNumbers.associateWith { source.number(it).also { value ->
            require(value >= 0) { "triage.negative_scoring_parameter" }
        } }
        require(numbers.getValue("audio_min_probability") <= 1) { "triage.invalid_audio_threshold" }
        return ScoringConfig(max, numbers.getValue("minimum_score"), numbers.getValue("minimum_margin"),
            numbers.getValue("manual_symptom_weight"), numbers.getValue("audio_weight"), numbers.getValue("audio_min_probability"),
            numbers.getValue("trigger_match_weight"), numbers.getValue("location_match_weight"),
            source.text("base_reason"), source.text("manual_reason"), source.text("audio_reason"), source.text("trigger_reason"),
            source.text("location_reason"), source.text("missing_context_reason"), source.text("uncertain_message"),
            source.text("ranked_message"), source.text("no_match_message"))
    }

    private fun entry(source: JSONObject): ExpertEntry {
        source.fields("id", "symptom_name", "layman_sound_description", "trigger_conditions", "location", "candidate_causes",
            "severity", "verification_tests", "recommended_action", "cost_category", "review_status", "diagnostic")
        require(source.text("review_status") == "needs_mechanic_verification") { "triage.invalid_review_status" }
        val diagnostic = source.get("diagnostic")
        require(diagnostic is Boolean) { "triage.invalid_boolean" }
        val causes = source.objects("candidate_causes").map { cause ->
            cause.fields("id", "name", "base_weight", "applicability", "context_modifiers")
            val weight = cause.number("base_weight")
            require(weight >= 0) { "triage.negative_base_weight" }
            val modifiers = cause.objects("context_modifiers").map { modifier ->
                modifier.fields("when", "delta", "reason")
                ContextModifier(predicates(modifier, "when", true), modifier.number("delta"), modifier.text("reason"))
            }
            ExpertCause(cause.id(causeIdentifier), cause.text("name"), weight, predicates(cause, "applicability", false), frozen(modifiers))
        }
        require(diagnostic == causes.isNotEmpty()) { "triage.invalid_candidate_cardinality" }
        val conditions = source.strings("trigger_conditions")
        require(conditions.all { it in triggers }) { "triage.invalid_trigger" }
        val locations = source.strings("location")
        val tests = source.strings("verification_tests")
        require(tests.isNotEmpty()) { "triage.missing_verification_tests" }
        val cost = source.text("cost_category")
        require(cost in setOf("none", "low", "medium", "high", "unknown")) { "triage.invalid_cost_category" }
        val severity = severity(source.text("severity"))
        require(!diagnostic || severity != Severity.MONITOR) { "triage.invalid_diagnostic_severity" }
        return ExpertEntry(source.id(entryIdentifier), source.text("symptom_name"), source.text("layman_sound_description"),
            frozenSet(conditions), frozenSet(locations), frozen(causes), severity, frozen(tests),
            source.text("recommended_action"), cost, source.text("review_status"), diagnostic)
    }

    private fun predicates(source: JSONObject, name: String, nonempty: Boolean): List<ContextPredicate> {
        val result = source.objects(name).map { predicate ->
            predicate.fields("field", "operator", "value")
            val field = predicate.text("field")
            val operator = predicate.text("operator")
            val value = predicate.get("value")
            when (field) {
                in numericFields -> {
                    require(operator in setOf("eq", "gte", "lte")) { "triage.invalid_numeric_operator" }
                    val number = predicate.number("value")
                    require(number >= 0 && number <= Int.MAX_VALUE &&
                        (operator != "eq" || (number % 1.0 == 0.0 && (field != "cylinder_count" || number > 0)))) {
                        "triage.invalid_context_number"
                    }
                    ContextPredicate(field, operator, numberValue = number)
                }
                in setFields -> {
                    require(operator == "contains") { "triage.invalid_set_operator" }
                    ContextPredicate(field, operator, textValue = predicate.text("value"))
                }
                in scalarFields -> {
                    require(operator == "eq" && value is String) { "triage.invalid_scalar_operator" }
                    ContextPredicate(field, operator, textValue = predicate.text("value"))
                }
                else -> throw IllegalArgumentException("triage.unknown_context_field")
            }
        }
        require(!nonempty || result.isNotEmpty()) { "triage.empty_conditions" }
        return frozen(result)
    }

    private fun severity(value: String): Severity = when (value) {
        "monitor" -> Severity.MONITOR
        "inspect" -> Severity.INSPECT
        "stop" -> Severity.STOP
        else -> throw IllegalArgumentException("triage.invalid_severity")
    }

    private fun JSONObject.fields(vararg names: String) {
        val allowed = names.toSet()
        require(keys().asSequence().all { it in allowed } && names.all(::has)) { "triage.invalid_fields" }
    }

    private fun JSONObject.text(name: String): String {
        val value = get(name)
        require(value is String && value.isNotBlank()) { "triage.invalid_text" }
        return value
    }

    private fun JSONObject.id(pattern: Regex): String = text("id").also {
        require(pattern.matches(it)) { "triage.invalid_id" }
    }

    private fun JSONObject.number(name: String): Double {
        val value = get(name)
        require(value is Number && value.toDouble().isFinite()) { "triage.invalid_number" }
        return value.toDouble()
    }

    private fun JSONObject.integer(name: String): Int {
        val value = number(name)
        require(value % 1.0 == 0.0 && value >= Int.MIN_VALUE && value <= Int.MAX_VALUE) { "triage.invalid_integer" }
        return value.toInt()
    }

    private fun JSONObject.objects(name: String): List<JSONObject> {
        val array = get(name)
        require(array is JSONArray) { "triage.invalid_array" }
        return (0 until array.length()).map { index ->
            val item = array.get(index)
            require(item is JSONObject) { "triage.invalid_object" }
            item
        }
    }

    private fun JSONObject.strings(name: String): List<String> {
        val array = get(name)
        require(array is JSONArray) { "triage.invalid_array" }
        val result = (0 until array.length()).map { index ->
            val value = array.get(index)
            require(value is String && value.isNotBlank()) { "triage.invalid_text" }
            value
        }
        require(result.distinct().size == result.size) { "triage.duplicate_value" }
        return result
    }

    private fun InputStream.readBytesBounded(): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count == -1) break
            require(count > 0) { "triage.stalled_stream" }
            require(count <= MAX_BYTES - bytes.size()) { "triage.file_too_large" }
            bytes.write(buffer, 0, count)
        }
        return bytes.toByteArray()
    }

    private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(values)
    private fun <T> frozenSet(values: List<T>): Set<T> = Collections.unmodifiableSet(LinkedHashSet(values))

    /** Android JSONTokener is permissive; validate RFC syntax before constructing its object tree. */
    private class Syntax(private val text: String) {
        private var position = 0
        fun validate() {
            whitespace()
            value(0)
            whitespace()
            require(position == text.length) { "triage.trailing_json" }
        }

        private fun value(depth: Int) {
            require(depth <= MAX_DEPTH) { "triage.json_too_deep" }
            whitespace()
            require(position < text.length) { "triage.incomplete_json" }
            when (text[position]) {
                '{' -> {
                    position++
                    val keys = HashSet<String>()
                    whitespace()
                    if (take('}')) return
                    do {
                        whitespace()
                        val start = position
                        string()
                        val key = JSONTokener(text.substring(start, position)).nextValue() as String
                        require(keys.add(key)) { "triage.duplicate_json_key" }
                        whitespace()
                        expect(':')
                        value(depth + 1)
                        whitespace()
                        if (take('}')) return
                        expect(',')
                    } while (true)
                }
                '[' -> {
                    position++
                    whitespace()
                    if (take(']')) return
                    do {
                        value(depth + 1)
                        whitespace()
                        if (take(']')) return
                        expect(',')
                    } while (true)
                }
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> {
                    val start = position
                    while (position < text.length && text[position] !in ",]} \t\r\n") position++
                    require(JSON_NUMBER.matches(text.substring(start, position))) { "triage.invalid_json_number" }
                }
            }
        }

        private fun string() {
            expect('"')
            while (position < text.length) {
                val character = text[position++]
                if (character == '"') return
                require(character >= ' ') { "triage.invalid_json_string" }
                if (character == '\\') {
                    require(position < text.length) { "triage.incomplete_json_escape" }
                    val escaped = text[position++]
                    if (escaped == 'u') {
                        repeat(4) {
                            require(position < text.length && text[position++].digitToIntOrNull(16) != null) {
                                "triage.invalid_unicode_escape"
                            }
                        }
                    } else require(escaped in "\"\\/bfnrt") { "triage.invalid_json_escape" }
                }
            }
            throw IllegalArgumentException("triage.incomplete_json_string")
        }

        private fun literal(value: String) {
            require(text.startsWith(value, position)) { "triage.invalid_json_literal" }
            position += value.length
        }
        private fun whitespace() { while (position < text.length && text[position] in " \t\r\n") position++ }
        private fun take(character: Char): Boolean = (position < text.length && text[position] == character).also { if (it) position++ }
        private fun expect(character: Char) { require(take(character)) { "triage.invalid_json_syntax" } }

        companion object {
            private val JSON_NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
        }
    }
}

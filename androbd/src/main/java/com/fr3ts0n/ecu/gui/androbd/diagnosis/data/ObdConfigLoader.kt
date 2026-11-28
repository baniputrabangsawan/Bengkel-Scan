package com.fr3ts0n.ecu.gui.androbd.diagnosis.data

import android.content.Context
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.ObdDtcRule
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.ObdFuelTrimConfig
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.ObdFusionConfig
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.ObdTrimBank
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Collections

/** Strict, bounded loading; invalid draft rules never silently become default diagnostic rules. */
object ObdConfigLoader {
    private const val MAX_BYTES = 65536
    private val causeId = Regex("[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*")
    private val identifier = Regex("[a-z][a-z0-9_]*")
    private val dtc = Regex("[PBCU][0-3][0-9A-F]{3}")

    @JvmStatic fun load(context: Context): ObdFusionConfig = load(context.assets.open("diagnosis/obd-config.json"))

    @JvmStatic fun load(input: InputStream): ObdFusionConfig = input.use { stream ->
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = stream.read(buffer)
            if (count == -1) break
            require(count > 0 && count <= MAX_BYTES - bytes.size()) { "obd.invalid_config_size" }
            bytes.write(buffer, 0, count)
        }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (error: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("obd.invalid_utf8", error)
        }
        parse(text)
    }

    @JvmStatic fun parse(text: String): ObdFusionConfig {
        require(text.length <= MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "obd.config_too_large" }
        Syntax(text).validate()
        try {
            val root = JSONObject(text)
            root.fields("schema_version", "revision", "review_notice", "pid_freshness_ms", "dtc_freshness_ms", "dtc_rules", "fuel_trim")
            require(root.integer("schema_version") == 1L) { "obd.unsupported_schema" }
            val pidFreshness = root.positiveLong("pid_freshness_ms")
            val rules = root.getJSONArray("dtc_rules").let { array ->
                (0 until array.length()).map { index ->
                    val rule = array.getJSONObject(index)
                    rule.fields("id", "codes", "fuel_types", "description", "cause_weights")
                    val id = rule.text("id")
                    require(identifier.matches(id)) { "obd.invalid_rule_id" }
                    val codes = rule.strings("codes").also { values -> require(values.all { dtc.matches(it) }) { "obd.invalid_dtc" } }
                    val fuels = rule.strings("fuel_types").also { values -> require(values.all { it in setOf("petrol", "diesel") }) { "obd.invalid_fuel" } }
                    ObdDtcRule(id, codes, fuels, rule.text("description"), rule.weights("cause_weights"))
                }
            }
            require(rules.isNotEmpty() && rules.map { it.id }.distinct().size == rules.size) { "obd.invalid_rules" }
            val codes = rules.flatMap { it.codes }
            require(codes.distinct().size == codes.size) { "obd.duplicate_dtc" }
            val trim = root.getJSONObject("fuel_trim")
            trim.fields("minimum_observations", "minimum_span_ms", "maximum_pair_skew_ms", "support_freshness_ms", "minimum_rpm", "minimum_coolant_c", "positive_combined_percent", "negative_combined_percent", "closed_loop_value", "banks", "positive_cause_weights", "negative_cause_weights")
            val observations = trim.integer("minimum_observations")
            require(observations in 2..Int.MAX_VALUE.toLong()) { "obd.insufficient_observations" }
            val span = trim.positiveLong("minimum_span_ms")
            val skew = trim.positiveLong("maximum_pair_skew_ms")
            val support = trim.positiveLong("support_freshness_ms")
            require(span <= pidFreshness && skew <= pidFreshness && support <= pidFreshness) { "obd.inconsistent_windows" }
            val rpm = trim.number("minimum_rpm").also { require(it > 0) { "obd.invalid_rpm_gate" } }
            val coolant = trim.number("minimum_coolant_c")
            val positive = trim.number("positive_combined_percent").also { require(it > 0) { "obd.invalid_positive_gate" } }
            val negative = trim.number("negative_combined_percent").also { require(it < 0) { "obd.invalid_negative_gate" } }
            // 0103 is an enumerated SAE status, not an engineering decision threshold.
            val closedLoop = trim.number("closed_loop_value").also { require(it == 2.0) { "obd.invalid_closed_loop_status" } }
            val banks = trim.getJSONArray("banks").let { array ->
                (0 until array.length()).map { index ->
                    val bank = array.getJSONObject(index)
                    bank.fields("bank", "short_mnemonic", "long_mnemonic")
                    val name = bank.text("bank")
                    require(name in setOf("1", "2")) { "obd.invalid_bank" }
                    val short = bank.text("short_mnemonic")
                    val long = bank.text("long_mnemonic")
                    require(short == "fuel_trim_short_b$name" && long == "fuel_trim_long_b$name") { "obd.crossed_bank" }
                    ObdTrimBank(name, short, long)
                }
            }
            require(banks.isNotEmpty() && banks.map { it.bank }.distinct().size == banks.size) { "obd.duplicate_bank" }
            val fuelTrim = ObdFuelTrimConfig(observations.toInt(), span, skew, support, rpm, coolant,
                positive, negative, closedLoop, frozen(banks), trim.weights("positive_cause_weights"), trim.weights("negative_cause_weights"))
            return ObdFusionConfig(root.text("revision"), pidFreshness, root.positiveLong("dtc_freshness_ms"),
                root.text("review_notice"), frozen(rules), fuelTrim)
        } catch (error: JSONException) {
            throw IllegalArgumentException("obd.invalid_config", error)
        }
    }

    private fun JSONObject.fields(vararg names: String) {
        require(keys().asSequence().toSet() == names.toSet()) { "obd.invalid_fields" }
    }
    private fun JSONObject.text(name: String): String {
        val value = get(name)
        require(value is String && value.isNotBlank()) { "obd.invalid_text" }
        return value
    }
    private fun JSONObject.number(name: String): Double {
        val value = get(name)
        require(value is Number && value.toDouble().isFinite()) { "obd.invalid_number" }
        return value.toDouble()
    }
    private fun JSONObject.integer(name: String): Long {
        val value = get(name)
        require(value is Int || value is Long) { "obd.invalid_integer" }
        return (value as Number).toLong()
    }
    private fun JSONObject.positiveLong(name: String): Long = integer(name).also { require(it > 0) { "obd.invalid_window" } }
    private fun JSONObject.strings(name: String): Set<String> {
        val array = getJSONArray(name)
        val values = (0 until array.length()).map { index ->
            val value = array.get(index)
            require(value is String && value.isNotBlank()) { "obd.invalid_list" }
            value
        }
        require(values.isNotEmpty() && values.distinct().size == values.size) { "obd.invalid_list" }
        return Collections.unmodifiableSet(LinkedHashSet(values))
    }
    private fun JSONObject.weights(name: String): Map<String, Double> {
        val value = getJSONObject(name)
        val result = LinkedHashMap<String, Double>()
        for (key in value.keys()) {
            require(causeId.matches(key)) { "obd.invalid_cause_id" }
            result[key] = value.number(key).also { require(it > 0) { "obd.invalid_weight" } }
        }
        require(result.isNotEmpty()) { "obd.empty_weights" }
        return Collections.unmodifiableMap(result)
    }
    private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(values)

    /** Validate RFC JSON before Android's permissive parser; includes duplicate-key rejection. */
    private class Syntax(private val text: String) {
        private var position = 0
        fun validate() {
            value(0)
            whitespace()
            require(position == text.length) { "obd.trailing_json" }
        }
        private fun value(depth: Int) {
            require(depth <= 16) { "obd.json_too_deep" }
            whitespace()
            require(position < text.length) { "obd.incomplete_json" }
            when (text[position]) {
                '{' -> {
                    position++
                    val keys = HashSet<String>()
                    whitespace()
                    if (take('}')) return
                    while (true) {
                        whitespace()
                        val start = position
                        string()
                        require(keys.add(JSONTokener(text.substring(start, position)).nextValue() as String)) { "obd.duplicate_key" }
                        whitespace()
                        expect(':')
                        value(depth + 1)
                        whitespace()
                        if (take('}')) return
                        expect(',')
                    }
                }
                '[' -> {
                    position++
                    whitespace()
                    if (take(']')) return
                    while (true) {
                        value(depth + 1)
                        whitespace()
                        if (take(']')) return
                        expect(',')
                    }
                }
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> {
                    val start = position
                    while (position < text.length && text[position] !in ",]} \t\r\n") position++
                    require(NUMBER.matches(text.substring(start, position))) { "obd.invalid_json_number" }
                }
            }
        }
        private fun string() {
            expect('"')
            while (position < text.length) {
                val character = text[position++]
                if (character == '"') return
                require(character >= ' ') { "obd.invalid_string" }
                if (character == '\\') {
                    require(position < text.length) { "obd.incomplete_escape" }
                    val escaped = text[position++]
                    if (escaped == 'u') repeat(4) {
                        require(position < text.length && text[position++].digitToIntOrNull(16) != null) { "obd.invalid_unicode" }
                    } else require(escaped in "\"\\/bfnrt") { "obd.invalid_escape" }
                }
            }
            throw IllegalArgumentException("obd.incomplete_string")
        }
        private fun literal(value: String) {
            require(text.startsWith(value, position)) { "obd.invalid_literal" }
            position += value.length
        }
        private fun whitespace() { while (position < text.length && text[position] in " \t\r\n") position++ }
        private fun take(character: Char): Boolean = (position < text.length && text[position] == character).also { if (it) position++ }
        private fun expect(character: Char) { require(take(character)) { "obd.invalid_syntax" } }
        companion object {
            private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
        }
    }
}

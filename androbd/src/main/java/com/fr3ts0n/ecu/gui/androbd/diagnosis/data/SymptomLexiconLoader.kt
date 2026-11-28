package com.fr3ts0n.ecu.gui.androbd.diagnosis.data

import android.content.Context
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DescriptionRule
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.SymptomDescriptionParser
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Synchronous asset loading; call from the existing diagnosis I/O worker, never persist complaints. */
object SymptomLexiconLoader {
    private const val MAX_BYTES = 128 * 1024
    private const val MAX_RULES = 96
    private const val MAX_DEPTH = 8
    private val symptomIdentifier = Regex("[a-z][a-z0-9_]*")
    private val references = mapOf(
        "trigger" to setOf("cold", "warm", "idle", "throttle", "braking", "turning"),
        "location" to setOf("front", "rear", "wheel", "under_hood", "cabin"),
        "flag" to setOf("heavy_engine_knock", "overheating", "brake_change", "steering_change",
            "wheel_play", "fades_when_warm", "recent_debris", "tyre_recent_change",
            "steam", "sweet_smell", "temperature_rising", "engine_stalled"),
        "temperature_pattern" to setOf("cold_only"),
    )

    @JvmStatic fun load(context: Context): SymptomDescriptionParser {
        val ids = ExpertKnowledgeLoader.load(context).entries.map { it.id }.toSet()
        return load(context.assets.open("diagnosis/symptom-synonyms.json"), ids)
    }

    /** Owns/closes input on every path; malformed UTF-8 is rejected, not silently repaired. */
    @JvmStatic @JvmOverloads fun load(input: InputStream, allowedSymptomIds: Set<String>? = null): SymptomDescriptionParser = input.use { stream ->
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = stream.read(buffer)
            if (count == -1) break
            require(count > 0) { "description.stalled_stream" }
            require(count <= MAX_BYTES - bytes.size()) { "description.file_too_large" }
            bytes.write(buffer, 0, count)
        }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val json = try {
            decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("description.invalid_utf8", error)
        }
        parse(json, allowedSymptomIds)
    }

    /** Pass catalog IDs to validate symptom references; without them, only identifier syntax is checked. */
    @JvmStatic @JvmOverloads fun parse(json: String, allowedSymptomIds: Set<String>? = null): SymptomDescriptionParser {
        require(json.length <= MAX_BYTES && json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
            "description.file_too_large"
        }
        Syntax(json).validate()
        try {
            val root = JSONObject(json)
            root.fields("schema_version", "rules")
            val version = root.get("schema_version")
            require(version is Number && version.toDouble() == 1.0) { "description.unsupported_schema" }
            val array = root.get("rules")
            require(array is JSONArray && array.length() in 1..MAX_RULES) { "description.invalid_rules" }
            val rules = (0 until array.length()).map { index ->
                val source = array.get(index)
                require(source is JSONObject) { "description.invalid_rule" }
                source.fields("kind", "id", "phrases", "required_any", "excluded_any", "explanation")
                val kind = source.text("kind", 24)
                val id = source.text("id", 64)
                val validReference = if (kind == "symptom") {
                    symptomIdentifier.matches(id) && (allowedSymptomIds == null || id in allowedSymptomIds)
                } else id in (references[kind] ?: emptySet())
                require(validReference) { "description.invalid_reference" }
                DescriptionRule(kind, id, source.phrases("phrases", true), source.phrases("required_any", false),
                    source.phrases("excluded_any", false), source.text("explanation", 500))
            }
            require(rules.map { "${it.kind}:${it.id}" }.distinct().size == rules.size) {
                "description.duplicate_rule"
            }
            return SymptomDescriptionParser(rules)
        } catch (error: JSONException) {
            throw IllegalArgumentException("description.invalid_lexicon", error)
        }
    }

    private fun JSONObject.fields(vararg expected: String) {
        val allowed = expected.toSet()
        require(expected.all(::has) && keys().asSequence().all { it in allowed }) { "description.invalid_fields" }
    }

    private fun JSONObject.text(field: String, limit: Int): String {
        val value = get(field)
        require(value is String && value.isNotBlank() && value.length <= limit &&
            value.none { Character.isISOControl(it) || Character.isSurrogate(it) }) { "description.invalid_text" }
        return value
    }

    private fun JSONObject.phrases(field: String, nonempty: Boolean): List<String> {
        val source = get(field)
        require(source is JSONArray && source.length() in (if (nonempty) 1 else 0)..32) {
            "description.invalid_phrases"
        }
        val phrases = (0 until source.length()).map { index ->
            val value = source.get(index)
            require(value is String && value.length in 1..120 &&
                value == SymptomDescriptionParser.normalizePhrase(value) && value.split(' ').size <= 12) {
                "description.invalid_phrase"
            }
            value
        }
        require(phrases.distinct().size == phrases.size) { "description.duplicate_phrase" }
        return phrases
    }

    /** The platform JSON parser accepts comments/unquoted fields; validate RFC JSON before using it. */
    private class Syntax(private val text: String) {
        private var position = 0
        fun validate() {
            whitespace()
            value(0)
            whitespace()
            require(position == text.length) { "description.trailing_json" }
        }

        private fun value(depth: Int) {
            require(depth <= MAX_DEPTH) { "description.json_too_deep" }
            whitespace()
            require(position < text.length) { "description.incomplete_json" }
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
                        require(keys.add(key)) { "description.duplicate_json_key" }
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
                    require(JSON_NUMBER.matches(text.substring(start, position))) { "description.invalid_json_number" }
                }
            }
        }

        private fun string() {
            expect('"')
            while (position < text.length) {
                val character = text[position++]
                if (character == '"') return
                require(character >= ' ') { "description.invalid_json_string" }
                if (character == '\\') {
                    require(position < text.length) { "description.incomplete_json_escape" }
                    val escaped = text[position++]
                    if (escaped == 'u') {
                        repeat(4) {
                            require(position < text.length && text[position++].digitToIntOrNull(16) != null) {
                                "description.invalid_unicode_escape"
                            }
                        }
                    } else require(escaped in "\"\\/bfnrt") { "description.invalid_json_escape" }
                }
            }
            throw IllegalArgumentException("description.incomplete_json_string")
        }

        private fun literal(value: String) {
            require(text.startsWith(value, position)) { "description.invalid_json_literal" }
            position += value.length
        }
        private fun whitespace() { while (position < text.length && text[position] in " \t\r\n") position++ }
        private fun take(character: Char): Boolean = (position < text.length && text[position] == character)
            .also { if (it) position++ }
        private fun expect(character: Char) { require(take(character)) { "description.invalid_json_syntax" } }

        companion object {
            private val JSON_NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
        }
    }
}

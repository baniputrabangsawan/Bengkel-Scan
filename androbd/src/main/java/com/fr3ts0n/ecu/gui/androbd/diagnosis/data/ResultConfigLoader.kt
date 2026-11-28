package com.fr3ts0n.ecu.gui.androbd.diagnosis.data

import android.content.Context
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.ResultConfig
import org.json.JSONException
import org.json.JSONObject
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Synchronous engineering-draft configuration loading. No silent fallback or diagnostic calibration. */
object ResultConfigLoader {
    private const val MAX_BYTES = 4096
    private val fields = setOf("schema_version", "confident_probability", "noise_probability",
        "conflict_probability", "stop_probability", "consent_text_version")
    private val number = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    @JvmStatic fun load(context: Context): ResultConfig = load(context.assets.open("diagnosis/result-config.json"))

    /** Owns/closes input even when it is oversized, invalid UTF-8, or malformed JSON. */
    @JvmStatic fun load(input: InputStream): ResultConfig = input.use { stream ->
        val bytes = ByteArray(MAX_BYTES + 1)
        var size = 0
        while (true) {
            val count = stream.read(bytes, size, bytes.size - size)
            if (count == -1) break
            require(count > 0) { "result.stalled_config_stream" }
            size += count
            require(size <= MAX_BYTES) { "result.config_too_large" }
        }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            decoder.decode(ByteBuffer.wrap(bytes, 0, size)).toString()
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("result.invalid_config_utf8", error)
        }
        parse(text)
    }

    @JvmStatic fun parse(text: String): ResultConfig {
        require(text.length <= MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
            "result.config_too_large"
        }
        FlatSyntax(text).validate()
        try {
            val root = JSONObject(text)
            require(root.keys().asSequence().toSet() == fields) { "result.invalid_config_fields" }
            require(root.decimal("schema_version") == 1.0) { "result.unsupported_config_schema" }
            val version = root.get("consent_text_version")
            require(version is String) { "result.invalid_consent_text_version" }
            return ResultConfig(root.decimal("confident_probability"), root.decimal("noise_probability"),
                root.decimal("conflict_probability"), root.decimal("stop_probability"), version)
        } catch (error: JSONException) {
            throw IllegalArgumentException("result.invalid_config", error)
        }
    }

    private fun JSONObject.decimal(field: String): Double {
        val value = get(field)
        require(value is Number && value.toDouble().isFinite()) { "result.invalid_config_number:$field" }
        return value.toDouble()
    }

    /** Match the existing recording-config convention: strict flat syntax before permissive org.json. */
    private class FlatSyntax(private val text: String) {
        private var position = 0

        fun validate() {
            whitespace()
            expect('{')
            whitespace()
            val seen = HashSet<String>()
            if (!take('}')) {
                while (true) {
                    val start = position
                    string()
                    val key = text.substring(start + 1, position - 1)
                    require(key in fields && seen.add(key)) { "result.invalid_or_duplicate_config_field" }
                    whitespace()
                    expect(':')
                    whitespace()
                    if (position < text.length && text[position] == '"') string() else {
                        val startNumber = position
                        while (position < text.length && text[position] !in ",} \t\r\n") position++
                        require(number.matches(text.substring(startNumber, position))) { "result.invalid_config_json_number" }
                    }
                    whitespace()
                    if (take('}')) break
                    expect(',')
                    whitespace()
                }
            }
            whitespace()
            require(position == text.length) { "result.trailing_config_json" }
        }

        private fun string() {
            expect('"')
            while (position < text.length) {
                val character = text[position++]
                if (character == '"') return
                require(character >= ' ') { "result.invalid_config_json_string" }
                if (character == '\\') {
                    require(position < text.length) { "result.incomplete_config_json_escape" }
                    val escaped = text[position++]
                    if (escaped == 'u') {
                        repeat(4) {
                            require(position < text.length && text[position++].digitToIntOrNull(16) != null) {
                                "result.invalid_config_unicode_escape"
                            }
                        }
                    } else require(escaped in "\"\\/bfnrt") { "result.invalid_config_json_escape" }
                }
            }
            throw IllegalArgumentException("result.incomplete_config_json_string")
        }

        private fun whitespace() { while (position < text.length && text[position] in " \t\r\n") position++ }
        private fun take(character: Char): Boolean = (position < text.length && text[position] == character)
            .also { if (it) position++ }
        private fun expect(character: Char) { require(take(character)) { "result.invalid_config_json_syntax" } }
    }
}

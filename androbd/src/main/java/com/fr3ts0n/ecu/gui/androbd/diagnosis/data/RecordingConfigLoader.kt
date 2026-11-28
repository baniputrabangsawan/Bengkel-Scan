package com.fr3ts0n.ecu.gui.androbd.diagnosis.data

import android.content.Context
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.PcmFormat
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.RecordingConfig
import org.json.JSONException
import org.json.JSONObject
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Synchronous, bounded asset loading; call from the diagnosis I/O worker. No fallback thresholds. */
object RecordingConfigLoader {
    private const val MAX_BYTES = 8192
    private val fields = setOf("schemaVersion", "revision", "sampleRate", "channels", "encoding",
        "countdownSeconds", "recordingSeconds", "referenceSeconds", "referenceMaxAgeSeconds",
        "minDurationSeconds", "maxDurationSeconds", "minRmsDbfs", "maxRmsDbfs",
        "clippingAmplitude", "maxClippingRatio", "minSnrDb", "maxReferenceRmsDbfs")
    private val number = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    @JvmStatic fun load(context: Context): RecordingConfig =
        load(context.assets.open("diagnosis/recording-config.json"))

    /** Owns and closes the input, including oversize, invalid UTF-8 and malformed-config paths. */
    @JvmStatic fun load(input: InputStream): RecordingConfig = input.use { stream ->
        val bytes = ByteArray(MAX_BYTES + 1)
        var size = 0
        while (true) {
            val count = stream.read(bytes, size, bytes.size - size)
            if (count == -1) break
            require(count > 0) { "recording.stalled_stream" }
            size += count
            require(size <= MAX_BYTES) { "recording.config_too_large" }
        }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val json = try {
            decoder.decode(ByteBuffer.wrap(bytes, 0, size)).toString()
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("recording.invalid_utf8", error)
        }
        parse(json)
    }

    @JvmStatic fun parse(json: String): RecordingConfig {
        require(json.length <= MAX_BYTES && json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
            "recording.config_too_large"
        }
        FlatSyntax(json).validate()
        try {
            val root = JSONObject(json)
            require(root.keys().asSequence().toSet() == fields) { "recording.invalid_config_fields" }
            require(root.integer("schemaVersion") == 1) { "recording.unsupported_config_schema" }
            require(root.integer("sampleRate") == PcmFormat.SAMPLE_RATE &&
                root.integer("channels") == PcmFormat.CHANNELS && root.get("encoding") == "PCM_16BIT") {
                "recording.unsupported_pcm_format"
            }
            val revision = root.get("revision")
            require(revision is String) { "recording.invalid_revision" }
            return RecordingConfig(revision, root.integer("countdownSeconds"), root.integer("recordingSeconds"),
                root.integer("referenceSeconds"), root.integer("referenceMaxAgeSeconds"),
                root.decimal("minDurationSeconds"), root.decimal("maxDurationSeconds"),
                root.decimal("minRmsDbfs"), root.decimal("maxRmsDbfs"), root.decimal("clippingAmplitude"),
                root.decimal("maxClippingRatio"), root.decimal("minSnrDb"), root.decimal("maxReferenceRmsDbfs"))
        } catch (error: JSONException) {
            throw IllegalArgumentException("recording.invalid_config", error)
        }
    }

    private fun JSONObject.decimal(field: String): Double {
        val value = get(field)
        require(value is Number && value.toDouble().isFinite()) { "recording.invalid_number:$field" }
        return value.toDouble()
    }

    private fun JSONObject.integer(field: String): Int {
        val value = decimal(field)
        require(value in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() && value % 1.0 == 0.0) {
            "recording.invalid_integer:$field"
        }
        return value.toInt()
    }

    /**
     * This versioned schema is flat with ASCII field names and string/number values only. Validate
     * strict JSON before org.json: Android otherwise accepts comments, unquoted keys and duplicates.
     * No recursive parsing is needed, so hostile nested input cannot exhaust the call stack.
     */
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
                    require(key in fields && seen.add(key)) { "recording.invalid_or_duplicate_field" }
                    whitespace()
                    expect(':')
                    whitespace()
                    if (position < text.length && text[position] == '"') string() else {
                        val numberStart = position
                        while (position < text.length && text[position] !in ",} \t\r\n") position++
                        require(number.matches(text.substring(numberStart, position))) { "recording.invalid_json_number" }
                    }
                    whitespace()
                    if (take('}')) break
                    expect(',')
                    whitespace()
                }
            }
            whitespace()
            require(position == text.length) { "recording.trailing_json" }
        }

        private fun string() {
            expect('"')
            while (position < text.length) {
                val character = text[position++]
                if (character == '"') return
                require(character >= ' ') { "recording.invalid_json_string" }
                if (character == '\\') {
                    require(position < text.length) { "recording.incomplete_json_escape" }
                    val escaped = text[position++]
                    if (escaped == 'u') {
                        repeat(4) {
                            require(position < text.length && text[position++].digitToIntOrNull(16) != null) {
                                "recording.invalid_unicode_escape"
                            }
                        }
                    } else require(escaped in "\"\\/bfnrt") { "recording.invalid_json_escape" }
                }
            }
            throw IllegalArgumentException("recording.incomplete_json_string")
        }

        private fun whitespace() { while (position < text.length && text[position] in " \t\r\n") position++ }
        private fun take(character: Char): Boolean = (position < text.length && text[position] == character)
            .also { if (it) position++ }
        private fun expect(character: Char) { require(take(character)) { "recording.invalid_json_syntax" } }
    }
}

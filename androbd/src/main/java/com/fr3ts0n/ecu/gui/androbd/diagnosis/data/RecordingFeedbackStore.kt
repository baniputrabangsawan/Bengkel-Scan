/* (C) 2026 Bengkel Scan contributors. GPL-2.0-or-later. */
package com.fr3ts0n.ecu.gui.androbd.diagnosis.data

import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.PcmFormat
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class FeedbackChoice { CORRECT, INCORRECT, ACTUAL_CAUSE }

data class RecordingFeedback(
    val id: String,
    val recordingId: String,
    val choice: FeedbackChoice,
    val actualCause: String?,
    val mechanicVerified: Boolean,
    val consentGranted: Boolean,
    val consentTextVersion: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

/**
 * One editable, local research label inside an existing RecordingStore bundle. Call on an IO dispatcher.
 * A mechanic checkbox is self-reported, never independent verification. Audio and reviewed free text
 * can contain personal information; whitelisting metadata is not automatic PII removal from either.
 * Only explicit manual export writes outside this store; there is no network/upload operation.
 */
class RecordingFeedbackStore(private val root: File) {
    fun save(
        recordingId: String,
        appVersion: String,
        knowledgeRevision: String,
        displayedResult: JSONObject,
        choice: FeedbackChoice,
        actualCause: String?,
        mechanicVerified: Boolean,
        consentTextVersion: String,
        nowMs: Long,
    ): RecordingFeedback = synchronized(LOCK) {
        check(!exportInProgress) { "feedback.export_in_progress" }
        validateVersion(consentTextVersion)
        validateText(appVersion, 128, nonblank = true)
        validateText(knowledgeRevision, 128, nonblank = true)
        require(nowMs > 0) { "feedback.invalid_time" }
        val cause = validateCause(choice, actualCause)
        val snapshot = sanitizeDisplayedResult(displayedResult)
        val recording = readRecording(recordingId)
        val previous = readFeedback(recordingId, recording)
        require(previous == null || nowMs >= previous.item.updatedAtMs) { "feedback.invalid_time" }
        val item = RecordingFeedback(previous?.item?.id ?: UUID.randomUUID().toString(), recordingId,
            choice, cause, mechanicVerified, false, consentTextVersion, previous?.item?.createdAtMs ?: nowMs, nowMs)
        val document = FeedbackDocument(item, appVersion, knowledgeRevision, recording.metadataSha256,
            snapshot, null, if (previous?.item?.consentGranted == true) nowMs else null)
        writeFeedback(recording.directory, document)
        item
    }

    fun load(recordingId: String): RecordingFeedback? = synchronized(LOCK) {
        val directory = recordingDirectory(recordingId)
        if (!directory.exists()) return@synchronized null
        readFeedback(recordingId, readRecording(recordingId))?.item
    }

    fun setExportConsent(
        recordingId: String,
        granted: Boolean,
        consentTextVersion: String,
        nowMs: Long,
    ): RecordingFeedback = synchronized(LOCK) {
        check(!exportInProgress) { "feedback.export_in_progress" }
        validateVersion(consentTextVersion)
        require(nowMs > 0) { "feedback.invalid_time" }
        val recording = readRecording(recordingId)
        val previous = requireNotNull(readFeedback(recordingId, recording)) { "feedback.not_found" }
        require(nowMs >= previous.item.updatedAtMs) { "feedback.invalid_time" }
        val item = previous.item.copy(consentGranted = granted, consentTextVersion = consentTextVersion, updatedAtMs = nowMs)
        val sameVersion = previous.item.consentTextVersion == consentTextVersion
        val document = previous.copy(item = item,
            grantedAtMs = if (granted) nowMs else if (sameVersion) previous.grantedAtMs else null,
            revokedAtMs = if (granted) null else nowMs)
        writeFeedback(recording.directory, document)
        item
    }

    /**
     * Holds the process-wide consent lock through the last ZIP byte. An already-authorized export can
     * finish before revocation returns; later exports require a new current-version grant. Previously
     * exported copies (including cloud-backed SAF destinations) cannot be recalled by this store.
     * The caller owns [output], even when validation or writing fails.
     */
    fun exportPackage(recordingId: String, consentTextVersion: String, output: OutputStream) = synchronized(LOCK) {
        check(!exportInProgress) { "feedback.export_in_progress" }
        validateVersion(consentTextVersion)
        val recording = readRecording(recordingId)
        val document = requireNotNull(readFeedback(recordingId, recording)) { "feedback.not_found" }
        require(document.item.consentGranted && document.item.consentTextVersion == consentTextVersion) {
            "feedback.export_consent_required"
        }
        val contextBytes = jsonBytes(JSONObject().put("schema_version", 1).put("recording_id", recordingId)
            .put("vehicle", recording.vehicle))
        val contextHash = sha256(contextBytes)
        val feedbackBytes = jsonBytes(document.json(includeOriginalMetadataHash = false))
        val feedbackHash = sha256(feedbackBytes)
        // Open and check the payload before the ZIP can write even its first header.
        FileInputStream(recording.audio).use { input ->
            require(input.channel.size() == recording.audioBytes) { "feedback.invalid_audio" }
            exportInProgress = true
            try {
                // Closing the wrapper releases ZIP/deflater resources without closing the SAF stream.
                ZipOutputStream(CallerOwnedOutput(output)).use { zip ->
                    val files = JSONArray()
                    startEntry(zip, "audio.wav")
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(8192)
                    var written = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(written + count <= recording.audioBytes) { "feedback.invalid_audio" }
                        zip.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        written += count
                    }
                    require(written == recording.audioBytes) { "feedback.invalid_audio" }
                    zip.closeEntry()
                    files.put(fileManifest("audio.wav", hex(digest.digest()), written))
                    writeEntry(zip, "vehicle-context.json", contextBytes)
                    files.put(fileManifest("vehicle-context.json", contextHash, contextBytes.size.toLong()))
                    writeEntry(zip, "feedback.json", feedbackBytes)
                    files.put(fileManifest("feedback.json", feedbackHash, feedbackBytes.size.toLong()))
                    val manifest = JSONObject().put("schema_version", 1).put("recording_id", recordingId)
                        .put("consent_text_version", consentTextVersion).put("files", files)
                        .put("redacted_metadata_sha256", contextHash)
                        .put("mechanic_verified", document.item.mechanicVerified)
                        .put("label_strength", labelStrength(document.item.mechanicVerified))
                        .put("verification_status", if (document.item.mechanicVerified)
                            "self_reported_mechanic_not_independently_verified" else "unverified_user_feedback")
                    // The manifest lists the other three files, never a circular self-hash.
                    writeEntry(zip, "manifest.json", jsonBytes(manifest))
                    zip.finish()
                }
            } finally {
                exportInProgress = false
            }
        }
    }

    private data class RecordingSnapshot(
        val directory: File,
        val audio: File,
        val audioBytes: Long,
        val metadataSha256: String,
        val vehicle: JSONObject,
    )

    private data class FeedbackDocument(
        val item: RecordingFeedback,
        val appVersion: String,
        val knowledgeRevision: String,
        val metadataSha256: String,
        val displayedResult: JSONObject,
        val grantedAtMs: Long?,
        val revokedAtMs: Long?,
    ) {
        fun json(includeOriginalMetadataHash: Boolean): JSONObject {
            val json = JSONObject().put("schema_version", 1).put("feedback_id", item.id)
                .put("recording_id", item.recordingId).put("app_version", appVersion)
                .put("knowledge_revision", knowledgeRevision).put("displayed_result", displayedResult)
                .put("choice", item.choice.name).put("actual_cause", item.actualCause ?: JSONObject.NULL)
                .put("mechanic_verified", item.mechanicVerified).put("label_strength", labelStrength(item.mechanicVerified))
                .put("consent_text_version", item.consentTextVersion).put("created_at_ms", item.createdAtMs)
                .put("updated_at_ms", item.updatedAtMs).put("export_consent", JSONObject()
                    .put("granted", item.consentGranted).put("text_version", item.consentTextVersion)
                    .put("granted_at_ms", grantedAtMs ?: JSONObject.NULL).put("revoked_at_ms", revokedAtMs ?: JSONObject.NULL))
            if (includeOriginalMetadataHash) json.put("recording_metadata_sha256", metadataSha256)
            return json
        }
    }

    private fun recordingDirectory(id: String): File {
        require(validId(id)) { "feedback.invalid_recording_id" }
        val directory = File(root.canonicalFile, id)
        require(directory.canonicalFile == directory && directory.parentFile == root.canonicalFile) { "feedback.invalid_path" }
        return directory
    }

    private fun confinedFile(directory: File, name: String): File {
        val file = File(directory, name)
        require(file.canonicalFile == file && file.parentFile == directory) { "feedback.invalid_path" }
        return file
    }

    private fun readRecording(id: String): RecordingSnapshot {
        val directory = recordingDirectory(id)
        require(directory.isDirectory) { "feedback.recording_not_found" }
        val metadataBytes = readBounded(confinedFile(directory, "metadata.json"), MAX_JSON_BYTES)
        val metadata = JSONObject(metadataBytes.toString(Charsets.UTF_8))
        require(integer(metadata, "schema_version") == 1L && text(metadata, "recording_id", 36) == id) {
            "feedback.invalid_recording_metadata"
        }
        val consent = metadata.getJSONObject("consent")
        require(boolean(consent, "local_storage") && boolean(consent, "audio_capture") &&
            consent.isNull("revoked_at_ms") && boolean(metadata.getJSONObject("quality"), "accepted")) {
            "feedback.recording_not_allowed"
        }
        val audioMetadata = metadata.getJSONObject("audio")
        require(text(audioMetadata, "file", 32) == "audio.wav" && integer(audioMetadata, "sample_rate_hz") == PcmFormat.SAMPLE_RATE.toLong() &&
            integer(audioMetadata, "channels") == 1L && text(audioMetadata, "encoding", 32) == "PCM_16BIT") { "feedback.invalid_audio" }
        val audio = confinedFile(directory, "audio.wav")
        val audioBytes = validateWav(audio, integer(audioMetadata, "sample_count"))
        return RecordingSnapshot(directory, audio, audioBytes, sha256(metadataBytes), sanitizeVehicle(metadata.getJSONObject("vehicle")))
    }

    private fun readFeedback(id: String, recording: RecordingSnapshot): FeedbackDocument? {
        val file = confinedFile(recording.directory, "feedback.json")
        if (!file.exists()) return null
        val json = JSONObject(readBounded(file, MAX_JSON_BYTES).toString(Charsets.UTF_8))
        require(integer(json, "schema_version") == 1L && text(json, "recording_id", 36) == id) { "feedback.invalid_document" }
        val feedbackId = text(json, "feedback_id", 36)
        require(validId(feedbackId)) { "feedback.invalid_document" }
        val metadataHash = text(json, "recording_metadata_sha256", 64)
        require(metadataHash == recording.metadataSha256) { "feedback.recording_changed" }
        val choice = FeedbackChoice.valueOf(text(json, "choice", 32))
        require(json.has("actual_cause")) { "feedback.invalid_document" }
        val cause = validateCause(choice, if (json.isNull("actual_cause")) null else text(json, "actual_cause", MAX_CAUSE_CHARS))
        val mechanic = boolean(json, "mechanic_verified")
        require(text(json, "label_strength", 64) == labelStrength(mechanic)) { "feedback.invalid_label_strength" }
        val version = text(json, "consent_text_version", MAX_VERSION_CHARS)
        validateVersion(version)
        val createdAt = integer(json, "created_at_ms")
        val updatedAt = integer(json, "updated_at_ms")
        require(createdAt > 0 && updatedAt >= createdAt) { "feedback.invalid_time" }
        val consent = json.getJSONObject("export_consent")
        val granted = boolean(consent, "granted")
        require(text(consent, "text_version", MAX_VERSION_CHARS) == version) { "feedback.invalid_consent" }
        val grantedAt = nullableTime(consent, "granted_at_ms", updatedAt)
        val revokedAt = nullableTime(consent, "revoked_at_ms", updatedAt)
        require(!granted || (grantedAt != null && grantedAt >= createdAt && revokedAt == null)) { "feedback.invalid_consent" }
        val item = RecordingFeedback(feedbackId, id, choice, cause, mechanic, granted, version, createdAt, updatedAt)
        return FeedbackDocument(item, text(json, "app_version", 128, nonblank = true),
            text(json, "knowledge_revision", 128, nonblank = true), metadataHash,
            sanitizeDisplayedResult(json.getJSONObject("displayed_result")), grantedAt, revokedAt)
    }

    private fun writeFeedback(directory: File, document: FeedbackDocument) {
        val bytes = jsonBytes(document.json(includeOriginalMetadataHash = true))
        val destination = confinedFile(directory, "feedback.json")
        val staging = File.createTempFile(".feedback-", ".pending", directory)
        try {
            require(staging.canonicalFile.parentFile == directory) { "feedback.invalid_path" }
            FileOutputStream(staging).use { output -> output.write(bytes); output.fd.sync() }
            // Never delete the old document first: a failed rename must not become a successful revoke/save.
            require(directory == recordingDirectory(document.item.recordingId) && directory.isDirectory &&
                destination == confinedFile(directory, "feedback.json")) { "feedback.invalid_path" }
            if (!staging.renameTo(destination)) throw IOException("feedback.commit_failed")
        } finally {
            if (staging.exists() && !staging.delete()) throw IOException("feedback.cleanup_failed")
        }
    }

    private fun validateWav(file: File, sampleCount: Long): Long {
        val size = file.length()
        require(file.isFile && size in 46L..MAX_AUDIO_BYTES && sampleCount in 1L..MAX_AUDIO_SAMPLES &&
            size == 44L + sampleCount * 2) { "feedback.invalid_audio" }
        val header = ByteArray(44)
        DataInputStream(FileInputStream(file)).use { it.readFully(header) }
        val view = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && view.getInt(4).toLong() == size - 8 &&
            String(header, 8, 8, Charsets.US_ASCII) == "WAVEfmt " && view.getInt(16) == 16 &&
            view.getShort(20).toInt() == 1 && view.getShort(22).toInt() == 1 &&
            view.getInt(24) == PcmFormat.SAMPLE_RATE && view.getInt(28) == PcmFormat.SAMPLE_RATE * 2 &&
            view.getShort(32).toInt() == 2 && view.getShort(34).toInt() == 16 &&
            String(header, 36, 4, Charsets.US_ASCII) == "data" && view.getInt(40).toLong() == sampleCount * 2) {
            "feedback.invalid_audio"
        }
        return size
    }

    private fun sanitizeVehicle(source: JSONObject): JSONObject {
        val safe = JSONObject()
        for (key in VEHICLE_TEXT_FIELDS) safe.put(key, text(source, key, 100))
        for (key in VEHICLE_NUMBER_FIELDS) {
            val value = if (source.isNull(key)) null else integer(source, key)
            require(value == null || value in 0L..Int.MAX_VALUE.toLong()) { "feedback.invalid_vehicle" }
            safe.put(key, value ?: JSONObject.NULL)
        }
        val triggers = source.getJSONArray("trigger_conditions")
        require(triggers.length() <= 6) { "feedback.invalid_vehicle" }
        val safeTriggers = JSONArray()
        for (index in 0 until triggers.length()) {
            val value = triggers.get(index)
            require(value is String && value.length <= 32) { "feedback.invalid_vehicle" }
            safeTriggers.put(value)
        }
        safe.put("trigger_conditions", safeTriggers).put("location", text(source, "location", 32))
            .put("safety_blocked", boolean(source, "safety_blocked"))
        val context = RecordingContext.parse(safe.toString())
        require(!context.safetyBlocked) { "feedback.recording_not_allowed" }
        // Regenerate only validated vehicle facts; location and safety-location never leave the device.
        return context.json().apply { remove("location"); remove("safety_blocked") }
    }

    private fun sanitizeDisplayedResult(source: JSONObject): JSONObject {
        val safe = JSONObject()
        for (key in RESULT_TEXT_FIELDS) if (source.has(key)) {
            safe.put(key, if (source.isNull(key) && key in NULLABLE_RESULT_TEXT_FIELDS) JSONObject.NULL else text(source, key, 128))
        }
        copyOptionalText(source, safe, "disclaimer", 4096)
        if (source.has("safety_reasons")) safe.put("safety_reasons", sanitizeTextArray(source.getJSONArray("safety_reasons")))
        for (key in RESULT_BOOLEAN_FIELDS) if (source.has(key)) safe.put(key, boolean(source, key))
        if (source.has("causes")) {
            val causes = source.getJSONArray("causes")
            require(causes.length() <= 3) { "feedback.invalid_result" }
            val safeCauses = JSONArray()
            for (index in 0 until causes.length()) {
                val cause = causes.getJSONObject(index)
                val safeCause = JSONObject()
                for (key in CAUSE_TEXT_FIELDS) if (cause.has(key)) {
                    safeCause.put(key, text(cause, key, if (key == "title") 512 else 128))
                }
                for (key in CAUSE_GUIDANCE_FIELDS) copyOptionalText(cause, safeCause, key, 4096)
                if (cause.has("verification_tests")) {
                    safeCause.put("verification_tests", sanitizeTextArray(cause.getJSONArray("verification_tests")))
                }
                if (cause.has("probability")) {
                    if (cause.isNull("probability")) safeCause.put("probability", JSONObject.NULL)
                    else {
                        val value = cause.get("probability")
                        require(value is Number && value.toDouble().isFinite() && value.toDouble() in 0.0..1.0) { "feedback.invalid_result" }
                        safeCause.put("probability", value.toDouble())
                    }
                }
                safeCauses.put(safeCause)
            }
            safe.put("causes", safeCauses)
        }
        return safe
    }

    private fun copyOptionalText(source: JSONObject, destination: JSONObject, key: String, maximum: Int) {
        if (source.has(key)) {
            destination.put(key, if (source.isNull(key)) JSONObject.NULL else text(source, key, maximum))
        }
    }

    private fun sanitizeTextArray(source: JSONArray): JSONArray {
        require(source.length() <= 32) { "feedback.invalid_result" }
        val safe = JSONArray()
        for (index in 0 until source.length()) {
            val value = source.get(index)
            require(value is String && value.length <= 2048) { "feedback.invalid_result" }
            safe.put(value)
        }
        return safe
    }

    private fun validateCause(choice: FeedbackChoice, actualCause: String?): String? {
        if (choice != FeedbackChoice.ACTUAL_CAUSE) {
            require(actualCause == null) { "feedback.unexpected_actual_cause" }
            return null
        }
        require(actualCause != null && actualCause.length <= MAX_CAUSE_CHARS && actualCause.isNotBlank()) { "feedback.invalid_actual_cause" }
        return actualCause.trim()
    }

    private fun readBounded(file: File, maximum: Int): ByteArray {
        require(file.isFile && file.length() in 1L..maximum.toLong()) { "feedback.invalid_document" }
        FileInputStream(file).use { input ->
            val bytes = ByteArrayOutputStream(minOf(file.length(), maximum.toLong()).toInt())
            val buffer = ByteArray(4096)
            var count = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                count += read
                require(count <= maximum) { "feedback.document_too_large" }
                bytes.write(buffer, 0, read)
            }
            return bytes.toByteArray()
        }
    }

    private fun jsonBytes(json: JSONObject): ByteArray {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_JSON_BYTES) { "feedback.document_too_large" }
        return bytes
    }

    private class CallerOwnedOutput(private val output: OutputStream) : OutputStream() {
        override fun write(value: Int) = output.write(value)
        override fun write(bytes: ByteArray, offset: Int, length: Int) = output.write(bytes, offset, length)
        override fun flush() = output.flush()
        override fun close() = Unit
    }

    companion object {
        // All instances, including aliases of the same root, share one consent/write/export boundary.
        private val LOCK = Any()
        private var exportInProgress = false
        private const val MAX_JSON_BYTES = 64 * 1024
        private const val MAX_AUDIO_BYTES = 2L * 1024 * 1024
        private const val MAX_AUDIO_SAMPLES = 60L * PcmFormat.SAMPLE_RATE
        private const val MAX_CAUSE_CHARS = 1024
        private const val MAX_VERSION_CHARS = 128
        private const val HEX = "0123456789abcdef"
        private val VEHICLE_TEXT_FIELDS = arrayOf("make", "model", "fuel_type", "transmission", "fuel_system", "power_steering_type", "engine_temp_state")
        private val VEHICLE_NUMBER_FIELDS = arrayOf("year", "cylinder_count", "mileage_km")
        private val RESULT_TEXT_FIELDS = arrayOf("outcome", "severity", "knowledge_revision", "model_id")
        private val NULLABLE_RESULT_TEXT_FIELDS = setOf("knowledge_revision", "model_id")
        private val RESULT_BOOLEAN_FIELDS = arrayOf("stop_warning", "technical_only")
        private val CAUSE_TEXT_FIELDS = arrayOf("id", "title", "entry_id", "severity")
        private val CAUSE_GUIDANCE_FIELDS = arrayOf("reason", "recommended_action")

        private fun validId(value: String): Boolean = try {
            UUID.fromString(value).toString() == value
        } catch (_: IllegalArgumentException) { false }

        private fun labelStrength(mechanic: Boolean) = if (mechanic) "self_reported_mechanic" else "weak_user_label"

        private fun validateVersion(value: String) {
            validateText(value, MAX_VERSION_CHARS, nonblank = true)
            require(value == value.trim() && value.none { it.isISOControl() }) { "feedback.invalid_consent_version" }
        }

        private fun validateText(value: String, maximum: Int, nonblank: Boolean = false) {
            require(value.length <= maximum && (!nonblank || value.isNotBlank())) { "feedback.invalid_text" }
        }

        private fun text(json: JSONObject, key: String, maximum: Int, nonblank: Boolean = false): String {
            val value = json.get(key)
            require(value is String) { "feedback.invalid_document" }
            validateText(value, maximum, nonblank)
            return value
        }

        private fun boolean(json: JSONObject, key: String): Boolean {
            val value = json.get(key)
            require(value is Boolean) { "feedback.invalid_document" }
            return value
        }

        private fun integer(json: JSONObject, key: String): Long {
            val value = json.get(key)
            require(value is Int || value is Long) { "feedback.invalid_document" }
            return (value as Number).toLong()
        }

        private fun nullableTime(json: JSONObject, key: String, updatedAt: Long): Long? {
            require(json.has(key)) { "feedback.invalid_consent" }
            if (json.isNull(key)) return null
            val value = integer(json, key)
            require(value > 0 && value <= updatedAt) { "feedback.invalid_time" }
            return value
        }

        private fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

        private fun hex(bytes: ByteArray): String {
            val chars = CharArray(bytes.size * 2)
            for (index in bytes.indices) {
                val value = bytes[index].toInt() and 0xff
                chars[index * 2] = HEX[value ushr 4]
                chars[index * 2 + 1] = HEX[value and 0xf]
            }
            return String(chars)
        }

        private fun fileManifest(path: String, hash: String, bytes: Long) = JSONObject()
            .put("path", path).put("sha256", hash).put("bytes", bytes)

        private fun startEntry(zip: ZipOutputStream, path: String) {
            zip.putNextEntry(ZipEntry(path).apply { time = 0L })
        }

        private fun writeEntry(zip: ZipOutputStream, path: String, bytes: ByteArray) {
            startEntry(zip, path)
            zip.write(bytes)
            zip.closeEntry()
        }
    }
}

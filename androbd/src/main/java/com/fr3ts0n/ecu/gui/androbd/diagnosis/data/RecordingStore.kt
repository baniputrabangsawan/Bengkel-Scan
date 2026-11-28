/* (C) 2026 Bengkel Scan contributors. GPL-2.0-or-later. */
package com.fr3ts0n.ecu.gui.androbd.diagnosis.data

import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.AudioQuality
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.PcmFormat
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.QualityMetrics
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.RecordingConfig
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.VehicleProfile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** Vehicle facts only: never pass/persist the spoken complaint, customer, plate or VIN here. */
data class RecordingContext(
    val profile: VehicleProfile,
    val temperature: String = "",
    val triggers: Set<String> = emptySet(),
    val location: String = "",
    val safetyBlocked: Boolean = false,
) {
    fun json(): JSONObject = JSONObject().put("make", profile.make).put("model", profile.model)
        .put("year", profile.year.toIntOrNull() ?: JSONObject.NULL).put("fuel_type", profile.fuelType)
        .put("cylinder_count", profile.cylinderCount.toIntOrNull() ?: JSONObject.NULL)
        .put("transmission", profile.transmission).put("mileage_km", profile.mileageKm.toIntOrNull() ?: JSONObject.NULL)
        .put("fuel_system", profile.fuelSystem).put("power_steering_type", profile.powerSteeringType)
        .put("engine_temp_state", temperature).put("trigger_conditions", JSONArray(triggers.sorted()))
        .put("location", location).put("safety_blocked", safetyBlocked)

    companion object {
        const val EXTRA_CONTEXT = "recording.vehicle_context"
        fun parse(text: String): RecordingContext {
            require(text.length <= 16 * 1024) { "recording.invalid_context" }
            val json = JSONObject(text)
            fun optionalNumber(key: String) = if (json.isNull(key)) "" else json.getInt(key).toString()
            val profile = VehicleProfile(json.getString("make"), json.getString("model"), optionalNumber("year"),
                json.getString("fuel_type"), optionalNumber("cylinder_count"), json.getString("transmission"),
                optionalNumber("mileage_km"), json.getString("fuel_system"), json.getString("power_steering_type"))
            require(profile.invalidFields().isEmpty() && profile.make.length <= 100 && profile.model.length <= 100)
            val triggers = json.getJSONArray("trigger_conditions")
            require(triggers.length() <= 6)
            val selected = (0 until triggers.length()).mapTo(linkedSetOf()) { triggers.getString(it) }
            require(selected.all { it in setOf("cold", "warm", "idle", "throttle", "braking", "turning") })
            val temperature = json.getString("engine_temp_state")
            val location = json.getString("location")
            require(temperature in setOf("", "cold", "warm"))
            require(location in setOf("", "front", "rear", "wheel", "under_hood", "cabin"))
            return RecordingContext(profile, temperature, selected, location, json.getBoolean("safety_blocked"))
        }
    }
}

data class RecordingDevice(val manufacturer: String, val model: String, val sdk: Int, val appVersion: String)
data class SavedRecording(val id: String, val condition: String, val createdAtMs: Long)
data class RecordingAnalysisInput(val file: File, val context: RecordingContext)

/** Private no-backup root supplied by the owner. Call every operation on an IO dispatcher. */
class RecordingStore(private val root: File, private val device: RecordingDevice) {
    @Synchronized
    fun save(context: RecordingContext, condition: String, capture: PcmCapture, reference: PcmCapture,
             config: RecordingConfig, consentAtMs: Long, vehicleGroupId: String): SavedRecording {
        require(!context.safetyBlocked && context.profile.invalidFields().isEmpty())
        require(condition in CONDITIONS && consentAtMs > 0 && vehicleGroupId.matches(GROUP_ID))
        require(compatible(capture.diagnostics, reference.diagnostics)) { "recording.source_changed" }
        require(capture.startedAtMs >= reference.startedAtMs &&
            capture.startedAtMs - reference.startedAtMs <= config.referenceMaxAgeSeconds * 1000L) { "recording.reference_expired" }
        val quality = AudioQuality.evaluate(capture.samples, reference.samples, config)
        require(quality.accepted) { "recording.rejected:${quality.reasons.joinToString()}" }
        ensureRoot()
        val id = UUID.randomUUID().toString()
        val staging = File(root, ".pending-$id")
        val destination = File(root, id)
        if (!staging.mkdir()) throw IOException("recording.storage_unavailable")
        try {
            writeWav(File(staging, "audio.wav"), capture.samples)
            writeWav(File(staging, "background.wav"), reference.samples)
            val metadata = JSONObject().put("schema_version", 1).put("recording_id", id)
                .put("protocol_revision", "phase3-pcm-v1").put("created_at_ms", capture.startedAtMs)
                .put("condition", condition).put("vehicle", context.json()).put("vehicle_group_id", vehicleGroupId)
                .put("device", JSONObject().put("manufacturer", device.manufacturer).put("model", device.model)
                    .put("android_sdk", device.sdk).put("device_group_id", deviceGroupId()))
                .put("app_version", device.appVersion)
                .put("audio", diagnostics(capture.diagnostics).put("file", "audio.wav").put("byte_order", "little_endian")
                    .put("sample_count", capture.samples.size).put("started_at_ms", capture.startedAtMs))
                .put("background", diagnostics(reference.diagnostics).put("file", "background.wav")
                    .put("sample_count", reference.samples.size).put("started_at_ms", reference.startedAtMs))
                .put("quality", metrics(quality.metrics).put("accepted", true).put("rejection_reasons", JSONArray())
                    .put("snr_method", "10log10((target_power-background_power)/background_power); nonpositive signal uses finite -160dB floor")
                    .put("limitations", "Uncalibrated relative estimate; same position/gain/environment required. Not SPL or diagnostic accuracy."))
                .put("quality_config", configJson(config))
                .put("consent", JSONObject().put("audio_capture", true).put("local_storage", true)
                    .put("granted_at_ms", consentAtMs).put("training", false).put("upload", false))
                .put("label", JSONObject().put("mechanic_verified", false).put("cause_id", JSONObject.NULL))
            FileOutputStream(File(staging, "metadata.json")).use { stream ->
                stream.write(metadata.toString(2).toByteArray(Charsets.UTF_8)); stream.fd.sync()
            }
            if (!staging.renameTo(destination)) throw IOException("recording.commit_failed")
            return SavedRecording(id, condition, capture.startedAtMs)
        } finally {
            if (staging.exists() && !staging.deleteRecursively()) throw IOException("recording.cleanup_failed")
        }
    }

    @Synchronized
    fun list(): List<SavedRecording> {
        if (!root.exists()) return emptyList()
        val children = root.listFiles() ?: throw IOException("recording.storage_unavailable")
        return children.filter { it.isDirectory && validId(it.name) }.map { directory ->
            val file = File(directory, "metadata.json")
            if (!file.isFile || file.length() > 64 * 1024) throw IOException("recording.invalid_metadata")
            val json = JSONObject(file.readText(Charsets.UTF_8))
            SavedRecording(directory.name, json.getString("condition"), json.getLong("created_at_ms"))
        }.sortedByDescending { it.createdAtMs }
    }

    /** Read the original saved context and require unrevoked local-storage consent. */
    @Synchronized
    fun loadForAnalysis(id: String): RecordingAnalysisInput {
        require(validId(id)) { "recording.invalid_id" }
        val directory = File(root, id).canonicalFile
        require(directory.parentFile == root.canonicalFile) { "recording.invalid_path" }
        val metadata = File(directory, "metadata.json").canonicalFile
        val audio = File(directory, "audio.wav").canonicalFile
        require(metadata.parentFile == directory && audio.parentFile == directory &&
            metadata.isFile && metadata.length() <= 65536 && audio.isFile) { "recording.invalid_metadata" }
        val json = JSONObject(metadata.readText(Charsets.UTF_8))
        require(json.getInt("schema_version") == 1 && json.getString("recording_id") == id &&
            json.getJSONObject("consent").getBoolean("local_storage") &&
            json.getJSONObject("quality").getBoolean("accepted")) { "recording.analysis_not_allowed" }
        return RecordingAnalysisInput(audio, RecordingContext.parse(json.getJSONObject("vehicle").toString()))
    }

    @Synchronized
    fun delete(id: String) {
        require(validId(id))
        val directory = File(root, id)
        require(directory.canonicalFile.parentFile == root.canonicalFile)
        if (directory.exists() && !directory.deleteRecursively()) throw IOException("recording.delete_failed")
    }

    private fun ensureRoot() {
        if (!root.isDirectory && !root.mkdirs()) throw IOException("recording.storage_unavailable")
    }

    private fun deviceGroupId(): String {
        val file = File(root, ".device-group")
        if (file.exists()) {
            val id = file.readText(Charsets.US_ASCII)
            if (!validId(id)) throw IOException("recording.invalid_device_group")
            return id
        }
        val id = UUID.randomUUID().toString()
        val temporary = File.createTempFile(".device-", ".tmp", root)
        try {
            FileOutputStream(temporary).use { it.write(id.toByteArray(Charsets.US_ASCII)); it.fd.sync() }
            if (!temporary.renameTo(file)) throw IOException("recording.device_group_failed")
        } finally { if (temporary.exists()) temporary.delete() }
        return id
    }

    companion object {
        val CONDITIONS = setOf("cold_start", "idle", "gradual_rev", "driving_braking")
        private val GROUP_ID = Regex("[A-Za-z0-9_-]{1,64}")
        private fun validId(value: String) = try { UUID.fromString(value).toString() == value } catch (_: IllegalArgumentException) { false }

        fun compatible(a: CaptureDiagnostics, b: CaptureDiagnostics): Boolean =
            a.sampleRate == PcmFormat.SAMPLE_RATE && b.sampleRate == PcmFormat.SAMPLE_RATE &&
                a.channels == PcmFormat.CHANNELS && b.channels == PcmFormat.CHANNELS &&
                a.encoding == "PCM_16BIT" && b.encoding == "PCM_16BIT" && a.actualSource == b.actualSource &&
                a.routedDeviceId != null && a.routedDeviceId == b.routedDeviceId &&
                a.routedDeviceType == b.routedDeviceType &&
                a.hardwareSource == b.hardwareSource && a.hardwareSampleRate == b.hardwareSampleRate &&
                a.clientSilenced != true && b.clientSilenced != true &&
                a.effects.map { it.name to it.after } == b.effects.map { it.name to it.after }

        fun metrics(value: QualityMetrics): JSONObject = JSONObject().put("sample_count", value.sampleCount)
            .put("duration_seconds", value.durationSeconds).put("rms", value.rms).put("rms_dbfs", value.rmsDbfs)
            .put("peak", value.peak).put("peak_dbfs", value.peakDbfs).put("clipping_ratio", value.clippingRatio)
            .put("noise_rms", value.noiseRms ?: JSONObject.NULL).put("snr_estimate_db", value.snrEstimateDb ?: JSONObject.NULL)

        private fun diagnostics(value: CaptureDiagnostics) = JSONObject().put("requested_source", value.requestedSource)
            .put("actual_source", value.actualSource).put("source_name", value.sourceName)
            .put("fallback_reason", value.fallbackReason ?: JSONObject.NULL).put("sample_rate_hz", value.sampleRate)
            .put("channels", value.channels).put("encoding", value.encoding).put("session_id", value.sessionId)
            .put("routed_device_id", value.routedDeviceId ?: JSONObject.NULL).put("routed_device_type", value.routedDeviceType ?: JSONObject.NULL)
            .put("route_name", value.routeName ?: JSONObject.NULL).put("hardware_sample_rate_hz", value.hardwareSampleRate ?: JSONObject.NULL)
            .put("hardware_source", value.hardwareSource ?: JSONObject.NULL)
            .put("client_silenced", value.clientSilenced ?: JSONObject.NULL)
            .put("effects", JSONArray(value.effects.map { effect -> JSONObject().put("name", effect.name)
                .put("available", effect.available ?: JSONObject.NULL).put("before", effect.before ?: JSONObject.NULL)
                .put("after", effect.after ?: JSONObject.NULL).put("disable_status", effect.disableStatus ?: JSONObject.NULL)
                .put("note", effect.note ?: JSONObject.NULL) }))

        fun configJson(config: RecordingConfig) = JSONObject().put("revision", config.revision)
            .put("countdown_seconds", config.countdownSeconds).put("recording_seconds", config.recordingSeconds)
            .put("reference_seconds", config.referenceSeconds).put("reference_max_age_seconds", config.referenceMaxAgeSeconds)
            .put("min_duration_seconds", config.minDurationSeconds).put("max_duration_seconds", config.maxDurationSeconds)
            .put("min_rms_dbfs", config.minRmsDbfs).put("max_rms_dbfs", config.maxRmsDbfs)
            .put("clipping_amplitude", config.clippingAmplitude).put("max_clipping_ratio", config.maxClippingRatio)
            .put("min_snr_db", config.minSnrDb).put("max_reference_rms_dbfs", config.maxReferenceRmsDbfs)

        /** Exact signed PCM16 little-endian samples; no resampling, gain, filtering or normalization. */
        fun writeWav(file: File, samples: ShortArray) {
            require(samples.size <= PcmFormat.SAMPLE_RATE * 30)
            val dataSize = samples.size * PcmFormat.BYTES_PER_SAMPLE
            val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataSize)
            header.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1.toShort()).putShort(1.toShort())
            header.putInt(PcmFormat.SAMPLE_RATE).putInt(PcmFormat.SAMPLE_RATE * 2).putShort(2.toShort()).putShort(16.toShort())
            header.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataSize)
            FileOutputStream(file).use { stream ->
                stream.write(header.array())
                val bytes = ByteArray(8192)
                var position = 0
                while (position < samples.size) {
                    val count = minOf(bytes.size / 2, samples.size - position)
                    for (i in 0 until count) {
                        val value = samples[position + i].toInt()
                        bytes[2 * i] = value.toByte(); bytes[2 * i + 1] = (value shr 8).toByte()
                    }
                    stream.write(bytes, 0, count * 2)
                    position += count
                }
                stream.fd.sync()
            }
        }
    }
}

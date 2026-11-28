/* (C) 2026 Bengkel Scan contributors. GPL-2.0-or-later. */
package com.fr3ts0n.ecu.gui.androbd.diagnosis.data

import android.content.res.AssetManager
import com.fr3ts0n.ecu.gui.androbd.BuildConfig
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.AudioPreprocessing
import org.json.JSONObject
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/** Bundled assets only. No download, system-runtime discovery, or model substitution. */
data class AudioModelMetadata(
    val modelId: String,
    val synthetic: Boolean,
    val classes: List<String>,
    val preprocessing: AudioPreprocessing,
    val temperature: Double,
    val rejectionThreshold: Double,
    val fusionAudioWeightMultiplier: Double,
    val sha256: String,
) {
    companion object {
        fun parse(text: String): AudioModelMetadata {
            require(text.toByteArray(Charsets.UTF_8).size <= 65536) { "audio.invalid_metadata" }
            val json = JSONObject(text)
            require(json.getInt("schema_version") == 1 && !json.getBoolean("release_eligible")) { "audio.release_not_authorized" }
            val purpose = json.getString("purpose")
            require(purpose in setOf("synthetic_test_only", "vehicle_research")) { "audio.invalid_purpose" }
            val synthetic = purpose == "synthetic_test_only"
            val id = json.getString("model_id")
            require(id == if (synthetic) "synthetic_test_model" else "vehicle_research_model") { "audio.invalid_model_id" }
            require(json.getString("unknown_label") == "unknown" && json.getString("output") == "logits" &&
                json.getString("aggregation") == "recording_mean_window_logits") { "audio.invalid_contract" }
            val names = json.getJSONArray("classes")
            require(names.length() in 2..64) { "audio.invalid_classes" }
            val classes = (0 until names.length()).map { names.getString(it) }
            require(classes.distinct().size == classes.size && classes.all { it.matches(Regex("[a-z][a-z0-9_]{0,63}")) } &&
                "unknown" !in classes && "background_noise" in classes && "normal_idle" in classes) { "audio.invalid_classes" }
            val p = json.getJSONObject("preprocessing")
            val prep = AudioPreprocessing(p.getInt("sample_rate"), p.getDouble("max_audio_seconds"),
                p.getInt("trim_frame_samples"), p.getInt("trim_hop_samples"), p.getDouble("trim_threshold_dbfs"),
                p.getInt("trim_margin_samples"), p.getDouble("normalize_peak"), p.getInt("window_samples"), p.getInt("hop_samples"))
            require(json.getInt("input_samples") == prep.windowSamples) { "audio.invalid_input_shape" }
            val calibration = json.getJSONObject("calibration")
            require(calibration.getString("unit") == "recording_mean_window_logits") { "audio.invalid_calibration_unit" }
            val temperature = calibration.getDouble("temperature")
            val threshold = calibration.getDouble("rejection_threshold")
            val weight = json.getDouble("fusion_audio_weight_multiplier")
            require(temperature.isFinite() && temperature > 0 && threshold.isFinite() && threshold in 0.0..1.0 &&
                weight.isFinite() && weight in 0.0..10.0) { "audio.invalid_calibration" }
            val sha = json.getString("model_sha256")
            require(sha.matches(Regex("[a-f0-9]{64}"))) { "audio.invalid_model_hash" }
            return AudioModelMetadata(id, synthetic, classes, prep, temperature, threshold, weight, sha)
        }
    }
}

internal class AudioModelBundle(private val assets: AssetManager, private val prefix: String) {
    fun metadata(): AudioModelMetadata {
        val metadata = assets.open("$prefix.json").use { stream ->
            val bytes = ByteArray(65537)
            var count = 0
            while (count < bytes.size) {
                val read = stream.read(bytes, count, bytes.size - count)
                if (read < 0) break
                require(read > 0) { "audio.invalid_metadata" }
                count += read
            }
            require(count <= 65536) { "audio.invalid_metadata" }
            AudioModelMetadata.parse(String(bytes, 0, count, Charsets.UTF_8))
        }
        require(BuildConfig.DEBUG || !metadata.synthetic) { "audio.synthetic_forbidden_in_release" }
        return metadata
    }

    fun map(metadata: AudioModelMetadata): ByteBuffer = assets.openFd("$prefix.tflite").use { descriptor ->
        require(descriptor.declaredLength in 1..32L * 1024 * 1024) { "audio.model_too_large" }
        FileInputStream(descriptor.fileDescriptor).use { stream ->
            val model = stream.channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(model.asReadOnlyBuffer())
            require(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == metadata.sha256) { "audio.model_hash_mismatch" }
            model
        }
    }
}

package com.fr3ts0n.ecu.gui.androbd.diagnosis.data

import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.Severity
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.VehicleContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AudioModelMetadataTest {
    private fun json(synthetic: Boolean = true) = JSONObject("""
        {"schema_version":1,"model_id":"${if (synthetic) "synthetic_test_model" else "vehicle_research_model"}",
        "purpose":"${if (synthetic) "synthetic_test_only" else "vehicle_research"}","release_eligible":false,
        "classes":["normal_idle","belt_squeal","background_noise"],"unknown_label":"unknown","input_samples":15600,
        "output":"logits","aggregation":"recording_mean_window_logits","fusion_audio_weight_multiplier":1.0,
        "model_sha256":"${"a".repeat(64)}","calibration":{"temperature":2.0,"rejection_threshold":0.6,"unit":"recording_mean_window_logits"},
        "preprocessing":{"sample_rate":16000,"max_audio_seconds":60,"trim_frame_samples":320,"trim_hop_samples":160,
        "trim_threshold_dbfs":-60,"trim_margin_samples":160,"normalize_peak":0.9,"window_samples":15600,"hop_samples":7800}}
    """.trimIndent())
    private fun knowledge() = ExpertKnowledgeLoader.load(requireNotNull(javaClass.classLoader)
        .getResourceAsStream("diagnosis/expert-knowledge.json")!!)

    @Test fun syntheticPurposeCannotYieldDiagnosisEvenWithDangerSignals() {
        val metadata = AudioModelMetadata.parse(json().toString())
        assertTrue(metadata.synthetic)
        assertNull(AudioExpertFusion.merge(metadata, knowledge(), VehicleContext(flags = setOf("heavy_engine_knock")),
            setOf("belt_squeal"), mapOf("belt_squeal" to 1.0), false))
    }

    @Test fun calibrationUnitsClassOrderAndArtifactPurposeCannotBeSilentlyReinterpreted() {
        val changes: List<(JSONObject) -> Unit> = listOf(
            { it.put("release_eligible", true) }, { it.put("purpose", "production") },
            { it.put("model_id", "renamed_model") }, { it.put("output", "probabilities") },
            { it.put("input_samples", 15360) }, { it.put("aggregation", "mean_probabilities") },
            { it.getJSONObject("calibration").put("unit", "per_window") },
            { it.getJSONObject("calibration").put("temperature", 0) },
            { it.getJSONObject("calibration").put("rejection_threshold", 1.01) },
            { it.put("fusion_audio_weight_multiplier", -1) }, { it.put("model_sha256", "unverified") },
            { it.put("classes", org.json.JSONArray(listOf("normal_idle", "normal_idle", "background_noise"))) },
            { it.put("classes", org.json.JSONArray(listOf("normal_idle", "background_noise", "unknown"))) },
        )
        changes.forEach { change ->
            val source = json(); change(source)
            assertThrows(IllegalArgumentException::class.java) { AudioModelMetadata.parse(source.toString()) }
        }
        assertEquals(listOf("normal_idle", "belt_squeal", "background_noise"), AudioModelMetadata.parse(json().toString()).classes)
    }

    @Test fun configuredAudioWeightChangesOnlyExplainableContributionNeverDangerSeverity() {
        val metadata = AudioModelMetadata.parse(json(false).toString())
        val context = VehicleContext(flags = setOf("heavy_engine_knock"))
        val distribution = mapOf("normal_idle" to 0.05, "belt_squeal" to 0.9, "background_noise" to 0.05)
        val first = AudioExpertFusion.merge(metadata, knowledge(), context, setOf("belt_squeal"), distribution, false)!!
        val second = AudioExpertFusion.merge(metadata.copy(fusionAudioWeightMultiplier = 0.5), knowledge(), context,
            setOf("belt_squeal"), distribution, false)!!
        assertEquals(Severity.STOP, first.severity)
        assertEquals(first.severity, second.severity)
        val before = first.causes.first().contributions.single { it.source == "audio" }.delta
        val after = second.causes.first().contributions.single { it.source == "audio" }.delta
        assertEquals(before * 0.5, after, 1e-12)
        assertEquals(first.safetyReasons, second.safetyReasons)
    }

    @Test fun rejectionKeepsManualEvidenceAndSafetyWhileUnsupportedPropulsionAbstains() {
        val metadata = AudioModelMetadata.parse(json(false).toString())
        val distribution = mapOf("normal_idle" to 0.05, "belt_squeal" to 0.9, "background_noise" to 0.05)
        val rejected = AudioExpertFusion.merge(metadata, knowledge(), VehicleContext(flags = setOf("brake_change")),
            setOf("belt_squeal"), distribution, true)!!
        assertEquals(Severity.STOP, rejected.severity)
        assertTrue(rejected.causes.isNotEmpty())
        assertTrue(rejected.causes.all { cause -> cause.contributions.none { it.source == "audio" } })
        val unsupported = AudioExpertFusion.merge(metadata, knowledge(), VehicleContext(fuelType = "electric", flags = setOf("brake_change")),
            setOf("belt_squeal"), distribution, false)!!
        assertEquals(Severity.STOP, unsupported.severity)
        assertTrue(unsupported.causes.isEmpty())
    }
}

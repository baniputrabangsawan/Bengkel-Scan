package com.fr3ts0n.ecu.gui.androbd.diagnosis.data

import android.os.Build
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.VehicleContext
import java.security.MessageDigest
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Generated PCM engineering fixtures, never an automotive accuracy evaluation. */
@RunWith(AndroidJUnit4::class)
class AudioParityInstrumentedTest {
    @Test fun twentyFileParityMustPassBeforeTwentySecondOfflineLatencyIsMeasured() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixtures = instrumentation.context.assets
        val expected = JSONObject(fixtures.open("audio-parity/expected.json").bufferedReader().use { it.readText() })
        assertEquals("synthetic_test_only", expected.getString("purpose"))
        val engine = AudioDiagnosticEngine(context)
        val metadata = engine.metadata()
        assertTrue(metadata.synthetic)
        assertEquals(expected.getString("model_sha256"), metadata.sha256)
        val knowledge = ExpertKnowledgeLoader.load(context)
        val files = expected.getJSONArray("files")
        assertEquals(20, files.length())
        val tolerance = expected.getDouble("probability_tolerance")
        assertEquals(0.0001, tolerance, 0.0)
        var maxError = 0.0
        for (index in 0 until files.length()) {
            val file = files.getJSONObject(index)
            val path = "audio-parity/${file.getString("file")}"
            val digest = MessageDigest.getInstance("SHA-256")
            fixtures.open(path).use { stream ->
                val buffer = ByteArray(8192)
                var count = stream.read(buffer)
                while (count != -1) { digest.update(buffer, 0, count); count = stream.read(buffer) }
            }
            assertEquals(file.getString("sha256"), digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
            val events = engine.analyze({ fixtures.open(path) }, knowledge, VehicleContext()).toList()
            assertTrue(events.first() is AudioInferenceState.Preparing)
            val result = (events.last() as AudioInferenceState.Complete).result
            assertEquals(file.getInt("trim_start"), result.trimStart)
            assertEquals(file.getInt("trim_end"), result.trimEnd)
            assertEquals(file.getInt("window_count"), result.windows)
            assertNull("Synthetic test model must never produce a diagnosis", result.triage)
            val probabilities = file.getJSONArray("probabilities")
            metadata.classes.forEachIndexed { column, label ->
                val error = abs(probabilities.getDouble(column) - result.probabilities.getValue(label))
                maxError = maxOf(maxError, error)
                assertTrue("${file.getString("file")}/$label absolute probability error=$error > $tolerance", error < tolerance)
            }
            assertEquals(file.getBoolean("rejected"), result.rejected)
        }
        println("AUDIO_PARITY files=20 max_abs_probability_error=$maxError tolerance=$tolerance synthetic_only=true")
        // Fail above before measuring latency or claiming technical acceptance.
        assertEquals("This proof must run with airplane mode enabled", 1,
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0))
        assertEquals(0, Settings.Global.getInt(context.contentResolver, "wifi_on", 0))
        val latency = engine.analyze({ fixtures.open("audio-parity/${expected.getString("latency_file")}") },
            knowledge, VehicleContext()).toList().last() as AudioInferenceState.Complete
        assertNull(latency.result.triage)
        println("AUDIO_LATENCY manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} sdk=${Build.VERSION.SDK_INT} " +
            "input_seconds=20 elapsed_ms=${latency.result.elapsedMs} windows=${latency.result.windows} cold_interpreter=true airplane_mode=true")
        assertTrue("20-second local inference took ${latency.result.elapsedMs}ms", latency.result.elapsedMs < 3000.0)
    }

    @Test fun cancellationClosesInterpreterAndMissingModelNeverSubstitutesFixture() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val knowledge = ExpertKnowledgeLoader.load(instrumentation.targetContext)
        val engine = AudioDiagnosticEngine(instrumentation.targetContext)
        val metadata = engine.metadata()
        val input = { instrumentation.targetContext.assets.open("diagnosis/synthetic_test_model.wav") }
        val interrupted = engine.analyze(input, knowledge, VehicleContext()).take(2).toList()
        assertTrue(interrupted.last() is AudioInferenceState.Running)
        val completed = engine.analyze(input, knowledge, VehicleContext()).toList().last() as AudioInferenceState.Complete
        assertEquals(metadata.modelId, completed.result.modelId)
        assertNull(completed.result.triage)
        var refused = false
        try {
            AudioDiagnosticEngine(instrumentation.targetContext, "diagnosis/absent_vehicle_model")
                .analyze(input, knowledge, VehicleContext()).toList()
        } catch (_: java.io.IOException) { refused = true }
        assertTrue("Missing real model must not silently use the synthetic model", refused)
    }
}

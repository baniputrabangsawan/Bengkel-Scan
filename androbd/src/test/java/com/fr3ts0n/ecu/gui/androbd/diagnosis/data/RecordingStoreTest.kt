package com.fr3ts0n.ecu.gui.androbd.diagnosis.data

import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.PcmFormat
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.VehicleProfile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class RecordingStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val config get() = RecordingConfigLoader.load(requireNotNull(javaClass.classLoader)
        .getResourceAsStream("diagnosis/recording-config.json")!!)
    private val diagnostic = CaptureDiagnostics(9, 9, "UNPROCESSED", null, 16000, 1, "PCM_16BIT", 17,
        4, 15, "audio_device_type_15", 48000, false,
        listOf(EffectState("AGC", true, true, false, 0, null)), 9)
    private val context = RecordingContext(VehicleProfile("Isuzu", "Panther", "2001", "diesel", "4", "manual", "120000"),
        "cold", setOf("idle", "cold"), "under_hood")
    private val reference get() = PcmCapture(tone(2, 0.002), diagnostic, 1000)
    private val capture get() = PcmCapture(tone(15, 0.2), diagnostic, 6000)
    private fun store(root: File) = RecordingStore(root, RecordingDevice("test", "fixture-device", 23, "fixture-version"))

    @Test fun wavPreservesSignedSamplesAndExactLittleEndianHeader() {
        val samples = shortArrayOf(Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE)
        val file = temporary.newFile("signal.wav")
        RecordingStore.writeWav(file, samples)
        val bytes = file.readBytes()
        val view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(bytes.size - 8, view.getInt(4))
        assertEquals("WAVEfmt ", String(bytes, 8, 8, Charsets.US_ASCII))
        assertEquals(1, view.getShort(20).toInt())
        assertEquals(1, view.getShort(22).toInt())
        assertEquals(16000, view.getInt(24))
        assertEquals(32000, view.getInt(28))
        assertEquals(16, view.getShort(34).toInt())
        assertEquals(samples.size * 2, view.getInt(40))
        samples.forEachIndexed { i, value -> assertEquals(value, view.getShort(44 + i * 2)) }
    }

    @Test fun acceptedBundleContainsReproducibleQualityProvenanceAndRevocableLocalConsent() {
        val root = temporary.newFolder("bundles")
        val store = store(root)
        val saved = store.save(context, "idle", capture, reference, config, 500, "vehicle-001")
        val directory = File(root, saved.id)
        val metadata = JSONObject(File(directory, "metadata.json").readText())
        assertEquals(listOf(saved), store.list())
        val input = store.loadForAnalysis(saved.id)
        assertEquals(context, input.context)
        assertEquals(File(directory, "audio.wav").canonicalFile, input.file)
        assertTrue(File(directory, "audio.wav").isFile)
        assertTrue(File(directory, "background.wav").isFile)
        assertEquals(16000 * 15 * 2 + 44L, File(directory, "audio.wav").length())
        assertEquals("vehicle-001", metadata.getString("vehicle_group_id"))
        assertEquals("fixture-device", metadata.getJSONObject("device").getString("model"))
        assertEquals("fixture-version", metadata.getString("app_version"))
        assertEquals("diesel", metadata.getJSONObject("vehicle").getString("fuel_type"))
        assertTrue(metadata.getJSONObject("quality").getBoolean("accepted"))
        assertEquals(15.0, metadata.getJSONObject("quality").getDouble("duration_seconds"), 0.0)
        assertEquals(9, metadata.getJSONObject("audio").getInt("hardware_source"))
        assertFalse(metadata.getJSONObject("audio").getJSONArray("effects").getJSONObject(0).getBoolean("after"))
        assertTrue(metadata.getJSONObject("consent").getBoolean("local_storage"))
        assertFalse(metadata.getJSONObject("consent").getBoolean("training"))
        assertFalse(metadata.getJSONObject("consent").getBoolean("upload"))
        assertFalse(metadata.getJSONObject("label").getBoolean("mechanic_verified"))
        assertEquals(config.revision, metadata.getJSONObject("quality_config").getString("revision"))
        val deviceId = metadata.getJSONObject("device").getString("device_group_id")
        val next = store.save(context, "idle", capture, reference, config, 500, "vehicle-001")
        assertEquals(deviceId, JSONObject(File(root, "${next.id}/metadata.json").readText())
            .getJSONObject("device").getString("device_group_id"))
        store.delete(saved.id)
        assertFalse(directory.exists())
        assertEquals(listOf(next), store.list())
    }

    @Test fun rejectedAudioUnsafeContextExpiredReferenceAndChangedSourceNeverReachDisk() {
        val root = File(temporary.root, "must-stay-absent")
        val store = store(root)
        fun reject(target: PcmCapture = capture, background: PcmCapture = reference, info: RecordingContext = context, consent: Long = 500) {
            assertThrows(IllegalArgumentException::class.java) { store.save(info, "idle", target, background, config, consent, "vehicle-001") }
            assertFalse(root.exists())
        }
        reject(capture.copy(samples = ShortArray(16000 * 15)))
        reject(capture.copy(samples = ShortArray(16000 * 15) { Short.MAX_VALUE }))
        reject(capture.copy(samples = tone(1, 0.2)))
        reject(capture.copy(startedAtMs = 200000))
        reject(capture.copy(diagnostics = diagnostic.copy(actualSource = 6)))
        reject(capture.copy(diagnostics = diagnostic.copy(hardwareSource = 6)))
        reject(capture.copy(diagnostics = diagnostic.copy(routedDeviceId = 7)))
        reject(capture.copy(diagnostics = diagnostic.copy(clientSilenced = true)))
        reject(capture.copy(diagnostics = diagnostic.copy(encoding = "FLOAT")))
        reject(info = context.copy(safetyBlocked = true))
        reject(consent = 0)
    }

    @Test fun nullRouteCannotMasqueradeAsMatchingMicrophonesAndDeletionIsConfined() {
        assertFalse(RecordingStore.compatible(diagnostic.copy(routedDeviceId = null), diagnostic.copy(routedDeviceId = null)))
        assertFalse(RecordingStore.compatible(diagnostic, diagnostic.copy(effects = listOf(EffectState("AGC", true, true, true, -1, null)))))
        val root = temporary.newFolder("confined")
        val outside = temporary.newFile("keep-me")
        assertThrows(IllegalArgumentException::class.java) { store(root).delete("../keep-me") }
        assertTrue(outside.exists())
    }

    @Test fun unknownVehicleFactsRemainUnknownAndContextExcludesTranscript() {
        val unknown = RecordingContext(VehicleProfile())
        val json = unknown.json()
        assertTrue(json.isNull("year"))
        assertTrue(json.isNull("cylinder_count"))
        assertFalse(json.has("transcript"))
        assertFalse(json.has("vin"))
        assertEquals(unknown, RecordingContext.parse(json.toString()))
        assertEquals(context, RecordingContext.parse(context.json().toString()))
    }

    @Test fun analysisRejectsRevokedConsentWrongIdentityAndPathEscapeWithoutChangingAudio() {
        val root = temporary.newFolder("analysis")
        val store = store(root)
        val saved = store.save(context, "idle", capture, reference, config, 500, "vehicle-001")
        val metadata = File(root, "${saved.id}/metadata.json")
        val original = metadata.readText()
        val revoked = JSONObject(original)
        revoked.getJSONObject("consent").put("local_storage", false)
        metadata.writeText(revoked.toString())
        assertThrows(IllegalArgumentException::class.java) { store.loadForAnalysis(saved.id) }
        metadata.writeText(JSONObject(original).put("recording_id", "wrong-id").toString())
        assertThrows(IllegalArgumentException::class.java) { store.loadForAnalysis(saved.id) }
        assertThrows(IllegalArgumentException::class.java) { store.loadForAnalysis("../elsewhere") }
        assertTrue(File(root, "${saved.id}/audio.wav").isFile)
        metadata.writeText(original)
        assertEquals(context, store.loadForAnalysis(saved.id).context)
    }

    private fun tone(seconds: Int, amplitude: Double) = ShortArray(PcmFormat.SAMPLE_RATE * seconds) {
        (sin(2 * PI * 400 * it / PcmFormat.SAMPLE_RATE) * 32768 * amplitude).toInt().toShort()
    }
}

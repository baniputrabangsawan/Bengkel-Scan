/* (C) 2026 Bengkel Scan contributors. GPL-2.0-or-later. */
package com.fr3ts0n.ecu.gui.androbd.diagnosis.data

import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.PcmFormat
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.VehicleProfile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class RecordingFeedbackStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val version = "feedback-export-id-v1"
    private val diagnostic = CaptureDiagnostics(9, 9, "UNPROCESSED", null, 16000, 1, "PCM_16BIT", 17,
        4, 15, "audio_device_type_15", 48000, false, emptyList(), 9)
    private val context = RecordingContext(
        VehicleProfile("Isuzu", "Panther", "2001", "diesel", "4", "manual", "120000"),
        "cold", setOf("idle", "cold"), "under_hood")

    @Test fun newFeedbackHasCompleteLinkedSchemaAndOnlyAWeakUserLabel() {
        val fixture = fixture()
        val item = save(fixture)
        val json = fixture.feedbackJson()
        assertEquals(setOf("schema_version", "feedback_id", "recording_id", "app_version", "knowledge_revision",
            "recording_metadata_sha256", "displayed_result", "choice", "actual_cause", "mechanic_verified",
            "label_strength", "consent_text_version", "created_at_ms", "updated_at_ms", "export_consent"), keys(json))
        assertEquals(1, json.getInt("schema_version"))
        assertEquals(UUID.fromString(item.id).toString(), item.id)
        assertEquals(item.id, json.getString("feedback_id"))
        assertEquals(fixture.id, item.recordingId)
        assertEquals(fixture.id, json.getString("recording_id"))
        assertEquals("test-app", json.getString("app_version"))
        assertEquals("knowledge-v1", json.getString("knowledge_revision"))
        assertEquals(sha256(fixture.metadata.readBytes()), json.getString("recording_metadata_sha256"))
        assertEquals(FeedbackChoice.CORRECT, item.choice)
        assertEquals("CORRECT", json.getString("choice"))
        assertNull(item.actualCause)
        assertTrue(json.isNull("actual_cause"))
        assertFalse(item.mechanicVerified)
        assertFalse(json.getBoolean("mechanic_verified"))
        assertEquals("weak_user_label", json.getString("label_strength"))
        assertFalse(item.consentGranted)
        assertEquals(version, item.consentTextVersion)
        assertEquals(1000L, item.createdAtMs)
        assertEquals(1000L, item.updatedAtMs)
        val consent = json.getJSONObject("export_consent")
        assertEquals(setOf("granted", "text_version", "granted_at_ms", "revoked_at_ms"), keys(consent))
        assertFalse(consent.getBoolean("granted"))
        assertEquals(version, consent.getString("text_version"))
        assertTrue(consent.isNull("granted_at_ms"))
        assertTrue(consent.isNull("revoked_at_ms"))
        assertEquals(item, RecordingFeedbackStore(fixture.root).load(fixture.id))
        assertFalse(File(fixture.directory, "export.zip").exists())
    }

    @Test fun editingOneItemKeepsIdentityAndCreationTimeButRevokesThePreviousGrant() {
        val fixture = fixture()
        val first = save(fixture)
        fixture.store.setExportConsent(fixture.id, true, version, 1100)
        val edited = save(fixture, FeedbackChoice.ACTUAL_CAUSE, "Bantalan alternator", true, 1200)
        assertEquals(first.id, edited.id)
        assertEquals(first.createdAtMs, edited.createdAtMs)
        assertEquals(1200L, edited.updatedAtMs)
        assertEquals(FeedbackChoice.ACTUAL_CAUSE, edited.choice)
        assertEquals("Bantalan alternator", edited.actualCause)
        assertTrue(edited.mechanicVerified)
        assertFalse(edited.consentGranted)
        assertEquals("self_reported_mechanic", fixture.feedbackJson().getString("label_strength"))
        assertEquals(1200L, fixture.feedbackJson().getJSONObject("export_consent").getLong("revoked_at_ms"))
        assertTrue(fixture.feedbackJson().getJSONObject("export_consent").isNull("granted_at_ms"))
        assertNoExport(fixture)
        assertEquals(listOf("feedback.json"), fixture.directory.listFiles()!!.filter {
            it.name.startsWith("feedback") || it.name.startsWith(".feedback-")
        }.map { it.name })

        fixture.store.setExportConsent(fixture.id, true, version, 1300)
        val unchanged = save(fixture, FeedbackChoice.ACTUAL_CAUSE, "Bantalan alternator", true, 1400)
        assertFalse(unchanged.consentGranted)
        assertNoExport(fixture)
        val corrected = save(fixture, FeedbackChoice.INCORRECT, now = 1500)
        assertNull(corrected.actualCause)
        assertFalse(corrected.mechanicVerified)
        assertEquals("weak_user_label", fixture.feedbackJson().getString("label_strength"))
    }

    @Test fun explicitConsentMustMatchTheCurrentVersionAndCanBeRevokedAcrossInstances() {
        val fixture = fixture()
        save(fixture)
        assertNoExport(fixture)
        val other = RecordingFeedbackStore(fixture.root)
        assertTrue(other.setExportConsent(fixture.id, true, version, 1100).consentGranted)
        assertEquals(1100L, fixture.feedbackJson().getJSONObject("export_consent").getLong("granted_at_ms"))
        assertNoExport(fixture, "feedback-export-id-v2")
        other.setExportConsent(fixture.id, true, "feedback-export-id-v2", 1200)
        assertNoExport(fixture, version)
        val exported = TrackingOutput()
        fixture.store.exportPackage(fixture.id, "feedback-export-id-v2", exported)
        assertTrue(exported.size() > 0)
        assertFalse(exported.closed)
        val revoked = other.setExportConsent(fixture.id, false, "feedback-export-id-v2", 1300)
        assertFalse(revoked.consentGranted)
        assertEquals(1300L, fixture.feedbackJson().getJSONObject("export_consent").getLong("revoked_at_ms"))
        assertNoExport(fixture, "feedback-export-id-v2")
        other.setExportConsent(fixture.id, true, "feedback-export-id-v2", 1400)
        val grantedAgain = TrackingOutput()
        fixture.store.exportPackage(fixture.id, "feedback-export-id-v2", grantedAgain)
        assertTrue(grantedAgain.size() > 0)
        assertTrue(fixture.feedbackJson().getJSONObject("export_consent").isNull("revoked_at_ms"))
    }

    @Test fun exportIsAnExactWhitelistWithLinkedRedactedMetadataAndVerifiedManifestDigests() {
        val fixture = fixture()
        val metadata = JSONObject(fixture.metadata.readText())
        metadata.put("plate", "SECRET_PLATE").put("vin", "SECRET_VIN")
            .put("transcript", "SECRET_TRANSCRIPT").put("location", "SECRET_GPS")
            .put("vehicle_group_id", "SECRET_VEHICLE_GROUP")
        metadata.getJSONObject("device").put("model", "SECRET_DEVICE_MODEL")
            .put("manufacturer", "SECRET_MANUFACTURER").put("device_group_id", "SECRET_DEVICE_GROUP")
        metadata.getJSONObject("audio").put("route_name", "SECRET_ROUTE")
        metadata.getJSONObject("vehicle").put("plate", "SECRET_PLATE").put("vin", "SECRET_VIN")
            .put("safety_location", "SECRET_SAFETY_LOCATION").put("device", JSONObject().put("model", "SECRET_DEVICE_MODEL"))
        fixture.metadata.writeText(metadata.toString())
        val result = displayedResult().put("transcript", "SECRET_TRANSCRIPT")
            .put("raw_user_description", "SECRET_DESCRIPTION").put("logs", "SECRET_LOGS")
        result.getJSONArray("causes").getJSONObject(0).put("raw_reason", "SECRET_REASON")
            .put("device_identity", "SECRET_DEVICE_IDENTITY")
        save(fixture, result = result)
        assertFalse(fixture.feedback.readText().contains("SECRET_"))
        val plantedFeedback = fixture.feedbackJson().put("hidden_metadata", metadata).put("transcript", "SECRET_TRANSCRIPT")
        plantedFeedback.getJSONObject("displayed_result").put("transcript", "SECRET_TRANSCRIPT")
        plantedFeedback.getJSONObject("displayed_result").getJSONArray("causes").getJSONObject(0)
            .put("raw_metadata", metadata)
        fixture.feedback.writeText(plantedFeedback.toString())
        fixture.store.setExportConsent(fixture.id, true, version, 1100)
        // Also verify export itself reconstructs the whitelist, not just save/setConsent.
        val afterConsent = fixture.feedbackJson().put("hidden_metadata", metadata)
        afterConsent.getJSONObject("displayed_result").put("transcript", "SECRET_TRANSCRIPT")
        fixture.feedback.writeText(afterConsent.toString())
        val output = TrackingOutput()
        fixture.store.exportPackage(fixture.id, version, output)
        assertFalse(output.closed)
        val entries = unzip(output.toByteArray())
        assertEquals(setOf("audio.wav", "vehicle-context.json", "feedback.json", "manifest.json"), entries.keys)
        assertArrayEquals(File(fixture.directory, "audio.wav").readBytes(), entries.getValue("audio.wav"))
        val exportedFeedback = JSONObject(entries.getValue("feedback.json").toString(Charsets.UTF_8))
        val vehicleContext = JSONObject(entries.getValue("vehicle-context.json").toString(Charsets.UTF_8))
        val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
        assertEquals(fixture.id, exportedFeedback.getString("recording_id"))
        assertEquals(fixture.id, vehicleContext.getString("recording_id"))
        assertEquals(fixture.id, manifest.getString("recording_id"))
        assertEquals(1, manifest.getInt("schema_version"))
        assertEquals(version, manifest.getString("consent_text_version"))
        assertFalse(manifest.getBoolean("mechanic_verified"))
        assertEquals("weak_user_label", manifest.getString("label_strength"))
        assertEquals("unverified_user_feedback", manifest.getString("verification_status"))
        assertFalse(exportedFeedback.has("recording_metadata_sha256"))
        assertEquals(keys(fixture.feedbackJson()) - setOf("recording_metadata_sha256", "hidden_metadata"), keys(exportedFeedback))
        assertEquals(setOf("make", "model", "year", "fuel_type", "cylinder_count", "transmission", "mileage_km",
            "fuel_system", "power_steering_type", "engine_temp_state", "trigger_conditions"), keys(vehicleContext.getJSONObject("vehicle")))
        assertEquals(setOf("outcome", "severity", "stop_warning", "knowledge_revision", "model_id", "technical_only", "causes",
            "safety_reasons", "disclaimer"),
            keys(exportedFeedback.getJSONObject("displayed_result")))
        assertEquals(setOf("id", "title", "entry_id", "severity", "probability", "reason", "recommended_action", "verification_tests"), keys(exportedFeedback
            .getJSONObject("displayed_result").getJSONArray("causes").getJSONObject(0)))
        assertTrue(exportedFeedback.getJSONObject("displayed_result").getJSONArray("causes").getJSONObject(0).isNull("probability"))
        val localSnapshot = fixture.feedbackJson().getJSONObject("displayed_result")
        val exportedSnapshot = exportedFeedback.getJSONObject("displayed_result")
        for (snapshot in listOf(localSnapshot, exportedSnapshot)) {
            assertEquals("Hasil ini hanya saran. Keputusan akhir ada pada mekanik.", snapshot.getString("disclaimer"))
            assertEquals(listOf("Hentikan mesin bila bunyi memburuk."), (0 until snapshot.getJSONArray("safety_reasons").length())
                .map { snapshot.getJSONArray("safety_reasons").getString(it) })
            val cause = snapshot.getJSONArray("causes").getJSONObject(0)
            assertEquals("Pola gejala sesuai panduan bantalan.", cause.getString("reason"))
            assertEquals("Minta mekanik memeriksa bantalan alternator.", cause.getString("recommended_action"))
            assertEquals(listOf("Periksa kelonggaran bantalan.", "Bandingkan bunyi saat beban berubah."),
                (0 until cause.getJSONArray("verification_tests").length()).map { cause.getJSONArray("verification_tests").getString(it) })
        }
        val text = entries.filterKeys { it != "audio.wav" }.values.joinToString { it.toString(Charsets.UTF_8) }
        for (forbidden in listOf("SECRET_", "under_hood", "recording_metadata_sha256", "routed_device_id", "android_sdk")) {
            assertFalse("Must not export $forbidden", text.contains(forbidden))
        }
        val localHash = sha256(fixture.metadata.readBytes())
        assertFalse(text.contains(localHash))
        assertEquals(sha256(entries.getValue("vehicle-context.json")), manifest.getString("redacted_metadata_sha256"))
        val files = manifest.getJSONArray("files")
        assertEquals(3, files.length()) // A manifest cannot include its own hash.
        val paths = mutableSetOf<String>()
        for (index in 0 until files.length()) {
            val file = files.getJSONObject(index)
            assertEquals(setOf("path", "sha256", "bytes"), keys(file))
            val path = file.getString("path")
            assertTrue(paths.add(path))
            assertEquals(entries.getValue(path).size.toLong(), file.getLong("bytes"))
            assertEquals(sha256(entries.getValue(path)), file.getString("sha256"))
        }
        assertEquals(entries.keys - "manifest.json", paths)
        output.write(7) // The completed ZIP did not take ownership of the caller's stream.
    }

    @Test fun absentModelAndOtherNullableOptionalCatalogStringsRemainEditableAndExportable() {
        val fixture = fixture()
        val result = displayedResult().put("model_id", JSONObject.NULL).put("knowledge_revision", JSONObject.NULL)
            .put("disclaimer", JSONObject.NULL)
        result.getJSONArray("causes").getJSONObject(0).put("reason", JSONObject.NULL).put("recommended_action", JSONObject.NULL)
        save(fixture, result = result)
        val saved = fixture.feedbackJson().getJSONObject("displayed_result")
        for (key in listOf("model_id", "knowledge_revision", "disclaimer")) {
            assertTrue(saved.has(key))
            assertTrue(saved.isNull(key))
        }
        assertTrue(saved.getJSONArray("causes").getJSONObject(0).isNull("reason"))
        assertTrue(saved.getJSONArray("causes").getJSONObject(0).isNull("recommended_action"))
        fixture.store.setExportConsent(fixture.id, true, version, 1100)
        val output = TrackingOutput()
        fixture.store.exportPackage(fixture.id, version, output)
        val exported = JSONObject(unzip(output.toByteArray()).getValue("feedback.json").toString(Charsets.UTF_8))
            .getJSONObject("displayed_result")
        assertTrue(exported.has("model_id"))
        assertTrue(exported.isNull("model_id"))
        result.remove("model_id")
        save(fixture, now = 1200, result = result)
        assertFalse(fixture.feedbackJson().getJSONObject("displayed_result").has("model_id"))
    }

    @Test fun catalogGuidanceSnapshotsRejectOversizedOrStructuredTextWithoutChangingFeedback() {
        val fixture = fixture()
        save(fixture)
        val original = fixture.feedback.readBytes()
        val invalid = listOf(
            displayedResult().put("disclaimer", "x".repeat(4097)),
            displayedResult().put("safety_reasons", JSONArray(List(33) { "reason" })),
            displayedResult().put("safety_reasons", JSONArray().put("x".repeat(2049))),
            displayedResult().put("safety_reasons", JSONArray().put(JSONObject().put("transcript", "private"))),
            displayedResult().apply { getJSONArray("causes").getJSONObject(0).put("reason", "x".repeat(4097)) },
            displayedResult().apply { getJSONArray("causes").getJSONObject(0).put("recommended_action", "x".repeat(4097)) },
            displayedResult().apply { getJSONArray("causes").getJSONObject(0).put("verification_tests", JSONArray(List(33) { "test" })) },
            displayedResult().apply { getJSONArray("causes").getJSONObject(0).put("verification_tests", JSONArray().put("x".repeat(2049))) },
            displayedResult().apply { getJSONArray("causes").getJSONObject(0).put("verification_tests", JSONArray().put(JSONObject())) })
        for (result in invalid) {
            assertThrows(Exception::class.java) { save(fixture, now = 1100, result = result) }
            assertArrayEquals(original, fixture.feedback.readBytes())
        }
    }

    @Test fun selfReportedMechanicIsExplicitlyNotIndependentVerificationAndFreeTextIsNotFalselyRedacted() {
        val fixture = fixture()
        val cause = "Bantalan alternator; label yang ditinjau pengguna"
        save(fixture, FeedbackChoice.ACTUAL_CAUSE, cause, true)
        fixture.store.setExportConsent(fixture.id, true, version, 1100)
        val output = TrackingOutput()
        fixture.store.exportPackage(fixture.id, version, output)
        val entries = unzip(output.toByteArray())
        val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
        assertTrue(manifest.getBoolean("mechanic_verified"))
        assertEquals("self_reported_mechanic", manifest.getString("label_strength"))
        assertEquals("self_reported_mechanic_not_independently_verified", manifest.getString("verification_status"))
        assertEquals(cause, JSONObject(entries.getValue("feedback.json").toString(Charsets.UTF_8)).getString("actual_cause"))
    }

    @Test fun metadataIdentityAcceptanceAndLocalStorageConsentAreRequiredForEveryOperation() {
        val fixture = fixture()
        save(fixture)
        fixture.store.setExportConsent(fixture.id, true, version, 1100)
        val original = fixture.metadata.readText()
        val invalidMetadata = listOf(
            JSONObject(original).put("recording_id", UUID.randomUUID().toString()),
            JSONObject(original).put("schema_version", 2),
            JSONObject(original).apply { getJSONObject("quality").put("accepted", false) },
            JSONObject(original).apply { getJSONObject("consent").put("local_storage", false) },
            JSONObject(original).apply { getJSONObject("consent").put("local_storage", "true") },
            JSONObject(original).apply { getJSONObject("audio").put("file", "../outside.wav") })
        for (invalid in invalidMetadata) {
            fixture.metadata.writeText(invalid.toString())
            assertThrows(Exception::class.java) { fixture.store.load(fixture.id) }
            assertThrows(Exception::class.java) { save(fixture, now = 1200) }
            assertThrows(Exception::class.java) { fixture.store.setExportConsent(fixture.id, false, version, 1200) }
            assertNoExport(fixture)
        }
        fixture.metadata.writeText(JSONObject(original).put("app_version", "changed-after-feedback").toString())
        assertThrows(Exception::class.java) { fixture.store.load(fixture.id) }
        assertNoExport(fixture)
        fixture.metadata.writeText(original)
        assertTrue(fixture.store.load(fixture.id)!!.consentGranted)
        assertTrue(fixture.metadata.delete())
        assertNoExport(fixture)
    }

    @Test fun missingMalformedOverlongAndOversizedWavNeverWriteAnyExportBytes() {
        val fixture = fixture()
        save(fixture)
        fixture.store.setExportConsent(fixture.id, true, version, 1100)
        val audio = File(fixture.directory, "audio.wav")
        val original = audio.readBytes()
        assertTrue(audio.delete())
        assertNoExport(fixture)
        audio.writeBytes(byteArrayOf(1, 2, 3))
        assertNoExport(fixture)
        audio.writeBytes(original.copyOf().apply { this[0] = 'X'.code.toByte() })
        assertNoExport(fixture)
        audio.writeBytes(original.copyOf(original.size - 2))
        assertNoExport(fixture)
        audio.writeBytes(original)
        RandomAccessFile(audio, "rw").use { it.setLength(2L * 1024 * 1024 + 1) }
        assertNoExport(fixture)
        audio.writeBytes(original)
        // A structurally consistent 61-second PCM file is still outside the allowed duration.
        RandomAccessFile(audio, "rw").use { file ->
            val bytes = 61 * PcmFormat.SAMPLE_RATE * 2
            file.setLength(44L + bytes)
            file.seek(4); file.write(intLe(36 + bytes))
            file.seek(40); file.write(intLe(bytes))
        }
        assertNoExport(fixture)
        audio.writeBytes(original)
        assertTrue(fixture.store.load(fixture.id)!!.consentGranted)
    }

    @Test fun boundedInputsAndFailedChangesLeaveTheExistingAtomicDocumentUntouched() {
        val fixture = fixture()
        save(fixture)
        fixture.store.setExportConsent(fixture.id, true, version, 1100)
        val original = fixture.feedback.readBytes()
        assertThrows(Exception::class.java) { save(fixture, FeedbackChoice.ACTUAL_CAUSE, "x".repeat(1025), now = 1200) }
        assertThrows(Exception::class.java) { save(fixture, FeedbackChoice.ACTUAL_CAUSE, " ", now = 1200) }
        assertThrows(Exception::class.java) { save(fixture, now = 999) }
        assertThrows(Exception::class.java) {
            fixture.store.setExportConsent(fixture.id, false, "", 1200)
        }
        assertThrows(Exception::class.java) {
            fixture.store.setExportConsent(fixture.id, false, "x".repeat(129), 1200)
        }
        val malformed = displayedResult().put("outcome", JSONObject().put("transcript", "private"))
        assertThrows(Exception::class.java) { save(fixture, now = 1200, result = malformed) }
        val tooManyCauses = displayedResult().put("causes", JSONArray(List(4) { JSONObject().put("id", "candidate-$it") }))
        assertThrows(Exception::class.java) { save(fixture, now = 1200, result = tooManyCauses) }
        assertArrayEquals(original, fixture.feedback.readBytes())
        assertFalse(fixture.directory.listFiles()!!.any { it.name.startsWith(".feedback-") })

        fixture.feedback.writeBytes(ByteArray(65537) { ' '.code.toByte() })
        assertThrows(Exception::class.java) { fixture.store.load(fixture.id) }
        assertNoExport(fixture)
        fixture.feedback.writeBytes(original)
        val originalMetadata = fixture.metadata.readBytes()
        fixture.metadata.writeBytes(ByteArray(65537) { ' '.code.toByte() })
        assertThrows(Exception::class.java) { fixture.store.load(fixture.id) }
        assertNoExport(fixture)
        fixture.metadata.writeBytes(originalMetadata)
        assertEquals("CORRECT", fixture.feedbackJson().getString("choice"))
    }

    @Test fun malformedFeedbackOrConsentCannotBeTreatedAsAnExplicitGrant() {
        val fixture = fixture()
        save(fixture)
        fixture.store.setExportConsent(fixture.id, true, version, 1100)
        val original = fixture.feedback.readText()
        val invalid = listOf(
            JSONObject(original).put("recording_id", UUID.randomUUID().toString()),
            JSONObject(original).put("feedback_id", "../feedback"),
            JSONObject(original).put("schema_version", 2),
            JSONObject(original).put("recording_metadata_sha256", "0".repeat(64)),
            JSONObject(original).put("mechanic_verified", "true"),
            JSONObject(original).put("label_strength", "independently_verified"),
            JSONObject(original).apply { getJSONObject("export_consent").put("granted", "true") },
            JSONObject(original).apply { getJSONObject("export_consent").put("granted_at_ms", JSONObject.NULL) },
            JSONObject(original).apply { getJSONObject("export_consent").put("revoked_at_ms", 1100) },
            JSONObject(original).apply { getJSONObject("export_consent").put("text_version", "older-version") })
        for (document in invalid) {
            fixture.feedback.writeText(document.toString())
            assertNoExport(fixture)
        }
        fixture.feedback.writeText(original)
        assertTrue(fixture.store.load(fixture.id)!!.consentGranted)
    }

    @Test fun canonicalPathsRejectTraversalRecordingLinksAndFileLinks() {
        val fixture = fixture()
        save(fixture)
        fixture.store.setExportConsent(fixture.id, true, version, 1100)
        val outside = temporary.newFolder("outside")
        val sentinel = File(outside, "sentinel").apply { writeText("do-not-touch") }
        for (id in listOf("../outside", fixture.id + "/../outside", "not-a-uuid", fixture.id.uppercase())) {
            assertThrows(Exception::class.java) { fixture.store.load(id) }
            assertThrows(Exception::class.java) {
                fixture.store.save(id, "app", "knowledge", displayedResult(), FeedbackChoice.CORRECT, null, false, version, 1200)
            }
            val output = TrackingOutput()
            assertThrows(Exception::class.java) { fixture.store.exportPackage(id, version, output) }
            assertEquals(0, output.size())
        }
        val linkedId = UUID.randomUUID().toString()
        val directoryLink = File(fixture.root, linkedId).toPath()
        Files.createSymbolicLink(directoryLink, outside.toPath())
        try {
            assertThrows(Exception::class.java) { fixture.store.load(linkedId) }
        } finally { Files.delete(directoryLink) }
        for (name in listOf("metadata.json", "audio.wav", "feedback.json")) {
            val file = File(fixture.directory, name)
            val bytes = file.readBytes()
            val target = File(outside, name).apply { writeBytes(bytes) }
            assertTrue(file.delete())
            Files.createSymbolicLink(file.toPath(), target.toPath())
            try {
                assertThrows(Exception::class.java) { fixture.store.load(fixture.id) }
                assertNoExport(fixture)
                assertArrayEquals(bytes, target.readBytes())
            } finally {
                Files.delete(file.toPath())
                file.writeBytes(bytes)
            }
        }
        assertEquals("do-not-touch", sentinel.readText())
    }

    @Test fun recordingDeletionCascadesWithoutRecreatingFeedbackOrLeavingExportableData() {
        val fixture = fixture()
        save(fixture)
        fixture.store.setExportConsent(fixture.id, true, version, 1100)
        fixture.recordings.delete(fixture.id)
        assertFalse(fixture.directory.exists())
        assertNull(fixture.store.load(fixture.id))
        assertNoExport(fixture)
        assertThrows(Exception::class.java) { save(fixture, now = 1200) }
        assertFalse(fixture.directory.exists())
        val missingRoot = File(temporary.root, "never-created")
        assertNull(RecordingFeedbackStore(missingRoot).load(UUID.randomUUID().toString()))
        assertFalse(missingRoot.exists())
    }

    @Test fun failedPersistenceDoesNotClaimThatConsentWasSaved() {
        val fixture = fixture()
        save(fixture)
        assertTrue(fixture.feedback.delete())
        assertTrue(fixture.feedback.mkdir())
        File(fixture.feedback, "must-remain").writeText("sentinel")
        assertThrows(Exception::class.java) { fixture.store.setExportConsent(fixture.id, true, version, 1100) }
        assertThrows(Exception::class.java) { fixture.store.setExportConsent(fixture.id, false, version, 1200) }
        assertEquals("sentinel", File(fixture.feedback, "must-remain").readText())
        assertNoExport(fixture)
        assertFalse(fixture.directory.listFiles()!!.any { it.name.startsWith(".feedback-") })
    }

    @Test fun exportFailurePreservesCallerOwnershipAndReleasesTheSharedLock() {
        val fixture = fixture()
        save(fixture)
        fixture.store.setExportConsent(fixture.id, true, version, 1100)
        var closed = false
        val failing = object : OutputStream() {
            override fun write(value: Int) { throw IOException("destination unavailable") }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { throw IOException("destination unavailable") }
            override fun close() { closed = true }
        }
        assertThrows(IOException::class.java) { fixture.store.exportPackage(fixture.id, version, failing) }
        assertFalse(closed)
        assertFalse(RecordingFeedbackStore(fixture.root).setExportConsent(fixture.id, false, version, 1200).consentGranted)
        assertNoExport(fixture)
    }

    @Test fun crossInstanceRevocationWaitsForAuthorizedExportThenBlocksAllFutureBytes() {
        val fixture = fixture()
        save(fixture)
        fixture.store.setExportConsent(fixture.id, true, version, 1100)
        val firstByte = CountDownLatch(1)
        val releaseExport = CountDownLatch(1)
        val revokeStarted = CountDownLatch(1)
        val revokeReturned = CountDownLatch(1)
        val output = object : TrackingOutput() {
            private var blocked = false
            @Synchronized override fun write(value: Int) {
                blockOnce()
                super.write(value)
            }
            @Synchronized override fun write(bytes: ByteArray, offset: Int, length: Int) {
                blockOnce()
                super.write(bytes, offset, length)
            }
            private fun blockOnce() {
                if (!blocked) {
                    blocked = true
                    firstByte.countDown()
                    check(releaseExport.await(5, TimeUnit.SECONDS))
                }
            }
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val export = executor.submit { fixture.store.exportPackage(fixture.id, version, output) }
            assertTrue(firstByte.await(5, TimeUnit.SECONDS))
            val revoke = executor.submit {
                revokeStarted.countDown()
                RecordingFeedbackStore(File(fixture.root, ".")).setExportConsent(fixture.id, false, version, 1200)
                revokeReturned.countDown()
            }
            assertTrue(revokeStarted.await(5, TimeUnit.SECONDS))
            assertFalse("Revocation must not return while an authorized export is writing", revokeReturned.await(100, TimeUnit.MILLISECONDS))
            releaseExport.countDown()
            export.get(5, TimeUnit.SECONDS)
            revoke.get(5, TimeUnit.SECONDS)
            assertEquals(0L, revokeReturned.count)
            assertFalse(output.closed)
            assertEquals(4, unzip(output.toByteArray()).size)
            assertNoExport(fixture)
        } finally {
            releaseExport.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun outputCallbacksCannotReentrantlyCompleteRevocationBeforeMoreExportBytesLeave() {
        val fixture = fixture()
        save(fixture)
        fixture.store.setExportConsent(fixture.id, true, version, 1100)
        var attempted = false
        val output = object : TrackingOutput() {
            override fun write(value: Int) {
                if (!attempted) {
                    attempted = true
                    assertThrows(IllegalStateException::class.java) {
                        RecordingFeedbackStore(fixture.root).setExportConsent(fixture.id, false, version, 1200)
                    }
                }
                super.write(value)
            }
        }
        fixture.store.exportPackage(fixture.id, version, output)
        assertTrue(attempted)
        assertTrue(fixture.store.load(fixture.id)!!.consentGranted)
        fixture.store.setExportConsent(fixture.id, false, version, 1200)
        assertNoExport(fixture)
    }

    private data class Fixture(val root: File, val id: String, val recordings: RecordingStore) {
        val store = RecordingFeedbackStore(root)
        val directory get() = File(root, id)
        val metadata get() = File(directory, "metadata.json")
        val feedback get() = File(directory, "feedback.json")
        fun feedbackJson() = JSONObject(feedback.readText())
    }

    private fun fixture(): Fixture {
        val root = temporary.newFolder()
        val recordings = RecordingStore(root, RecordingDevice("test", "fixture-device", 23, "fixture-app"))
        val config = RecordingConfigLoader.load(requireNotNull(javaClass.classLoader)
            .getResourceAsStream("diagnosis/recording-config.json")!!)
        fun samples(seconds: Int, amplitude: Int) = ShortArray(PcmFormat.SAMPLE_RATE * seconds) {
            (if (it % 40 < 20) amplitude else -amplitude).toShort()
        }
        val saved = recordings.save(context, "idle", PcmCapture(samples(15, 6000), diagnostic, 6000),
            PcmCapture(samples(2, 64), diagnostic, 1000), config, 500, "fixture-vehicle")
        return Fixture(root, saved.id, recordings)
    }

    private fun displayedResult() = JSONObject().put("outcome", "EXPERT_ONLY").put("severity", "CAUTION")
        .put("stop_warning", false).put("knowledge_revision", "knowledge-v1").put("model_id", "fixture-model")
        .put("technical_only", false).put("disclaimer", "Hasil ini hanya saran. Keputusan akhir ada pada mekanik.")
        .put("safety_reasons", JSONArray().put("Hentikan mesin bila bunyi memburuk."))
        .put("causes", JSONArray().put(JSONObject().put("id", "bearing")
            .put("title", "Bantalan alternator").put("entry_id", "alternator-bearing").put("severity", "CAUTION")
            .put("probability", JSONObject.NULL).put("reason", "Pola gejala sesuai panduan bantalan.")
            .put("recommended_action", "Minta mekanik memeriksa bantalan alternator.")
            .put("verification_tests", JSONArray().put("Periksa kelonggaran bantalan.").put("Bandingkan bunyi saat beban berubah."))))

    private fun save(fixture: Fixture, choice: FeedbackChoice = FeedbackChoice.CORRECT, cause: String? = null,
                     mechanic: Boolean = false, now: Long = 1000, result: JSONObject = displayedResult()) =
        fixture.store.save(fixture.id, "test-app", "knowledge-v1", result, choice, cause, mechanic, version, now)

    private fun assertNoExport(fixture: Fixture, consentVersion: String = version) {
        val output = TrackingOutput()
        assertThrows(Exception::class.java) { fixture.store.exportPackage(fixture.id, consentVersion, output) }
        assertEquals("No ZIP header or payload may leave without an eligible current grant", 0, output.size())
        assertFalse(output.closed)
    }

    private open class TrackingOutput : ByteArrayOutputStream() {
        var closed = false
        override fun close() { closed = true; super.close() }
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                assertFalse(entry.isDirectory)
                assertNull("Duplicate ZIP path", entries.put(entry.name, input.readBytes()))
                input.closeEntry()
            }
        }
        return entries
    }

    private fun keys(json: JSONObject): Set<String> = json.keys().asSequence().toSet()
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun intLe(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())
}

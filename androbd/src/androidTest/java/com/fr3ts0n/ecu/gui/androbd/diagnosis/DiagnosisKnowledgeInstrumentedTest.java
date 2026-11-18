package com.fr3ts0n.ecu.gui.androbd.diagnosis;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.net.Uri;

import androidx.core.content.FileProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisKnowledgeLoader;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Exercises platform JSON and real content-URI imports without changing user data. */
@RunWith(AndroidJUnit4.class)
public class DiagnosisKnowledgeInstrumentedTest {
    private Context target;
    private File directory;
    private DiagnosisKnowledgeLoader loader;
    private JSONObject bundled;

    @Before
    public void setUp() throws Exception {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File external = target.getExternalFilesDir(null);
        assertNotNull(external);
        directory = new File(external, "knowledge-test-" + UUID.randomUUID());
        assertTrue(directory.mkdirs());
        Context isolated = new ContextWrapper(target) {
            @Override public File getExternalFilesDir(String type) { return directory; }
        };
        loader = new DiagnosisKnowledgeLoader(isolated);
        bundled = new JSONObject(DiagnosisKnowledgeLoader.encode(loader.load()));
    }

    @After
    public void tearDown() {
        delete(directory);
    }

    @Test
    public void bundledDraftParsesAndRoundTrips() throws Exception {
        DiagnosisKnowledge knowledge = loader.load();
        assertEquals(1, knowledge.schemaVersion);
        assertEquals("", loader.warning());
        assertEquals(1, knowledge.complaints.size());
        assertNotNull(knowledge.findComplaint("cold_start"));
        assertNotNull(knowledge.findSound("slow_cranking"));
        assertNull(knowledge.findSound("slow_cranking").audioFile);
        assertEquals(3, knowledge.findComplaint("cold_start").questions.size());
        assertTrue(knowledge.findComplaint("cold_start").causes.size() >= 2);
        for (DiagnosisKnowledge.Cause cause : knowledge.findComplaint("cold_start").causes) {
            assertFalse(cause.verified);
        }
        DiagnosisKnowledge restored = DiagnosisKnowledgeLoader.parse(
                DiagnosisKnowledgeLoader.encode(knowledge));
        assertEquals(knowledge.revision, restored.revision);
        assertEquals(knowledge.findCause("cold_start.weak_starting_supply").soundIds,
                restored.findCause("cold_start.weak_starting_supply").soundIds);
        try {
            knowledge.complaints.clear();
            fail("Model lists must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected immutable snapshot.
        }
    }

    @Test
    public void validExtraOverridesWholeComplaintAndUsesBundledSound() throws Exception {
        JSONObject extra = new JSONObject(bundled.toString());
        extra.put("revision", "uji-pengganti");
        extra.getJSONArray("complaints").getJSONObject(0).put("title", "Keluhan pengganti");
        extra.put("sounds", new JSONArray());
        loader.importExtra(source(extra.toString()));
        DiagnosisKnowledge merged = loader.load();
        assertEquals(1, merged.complaints.size());
        assertEquals("Keluhan pengganti", merged.findComplaint("cold_start").title);
        assertNotNull(merged.findSound("slow_cranking"));
        assertTrue(merged.revision.contains("uji-pengganti"));
        assertEquals("", loader.warning());
    }

    @Test
    public void archiveMergeIsReadOnlyAndBundledLoadIgnoresInstalledExtra() throws Exception {
        JSONObject extra = new JSONObject(bundled.toString());
        complaint(extra).put("title", "Judul arsip");
        extra.put("sounds", new JSONArray());
        DiagnosisKnowledge merged = DiagnosisKnowledgeLoader.mergeExtra(
                loader.loadBundled(), extra.toString());
        assertEquals("Judul arsip", merged.findComplaint("cold_start").title);
        assertNotEquals("Judul arsip", loader.load().findComplaint("cold_start").title);
        assertFalse(new File(directory, "diagnosis/extra.json").exists());
        loader.importExtra(source(extra.toString()));
        assertEquals("Judul arsip", loader.load().findComplaint("cold_start").title);
        assertNotEquals("Judul arsip", loader.loadBundled().findComplaint("cold_start").title);
    }

    @Test
    public void validExtraAddsComplaintAndSound() throws Exception {
        JSONObject extra = new JSONObject(bundled.toString());
        JSONObject complaint = extra.getJSONArray("complaints").getJSONObject(0);
        complaint.put("id", "additional_start");
        JSONArray causes = complaint.getJSONArray("causes");
        for (int i = 0; i < causes.length(); i++) {
            JSONObject cause = causes.getJSONObject(i);
            cause.put("id", "additional_start." + i);
            cause.put("sound_ids", new JSONArray().put("additional_sound"));
        }
        JSONObject sound = extra.getJSONArray("sounds").getJSONObject(0);
        sound.put("id", "additional_sound");
        loader.importExtra(source(extra.toString()));
        DiagnosisKnowledge merged = loader.load();
        assertEquals(2, merged.complaints.size());
        assertEquals(2, merged.sounds.size());
        assertNotNull(merged.findComplaint("additional_start"));
        assertNotNull(merged.findSound("additional_sound"));
    }

    @Test
    public void soundOnlyExtraCanOverrideBundledEntry() throws Exception {
        JSONObject extra = new JSONObject(bundled.toString());
        extra.put("complaints", new JSONArray());
        extra.getJSONArray("sounds").getJSONObject(0).put("title", "Judul pengganti");
        loader.importExtra(source(extra.toString()));
        assertEquals("Judul pengganti", loader.load().findSound("slow_cranking").title);
        assertNotNull(loader.load().findComplaint("cold_start"));
    }

    @Test
    public void duplicateIdsAreRejectedWithinEveryScope() throws Exception {
        JSONObject duplicate = new JSONObject(bundled.toString());
        duplicate.getJSONArray("complaints").put(complaint(duplicate));
        assertInvalid(duplicate);
        duplicate = new JSONObject(bundled.toString());
        duplicate.getJSONArray("sounds").put(duplicate.getJSONArray("sounds").getJSONObject(0));
        assertInvalid(duplicate);
        duplicate = new JSONObject(bundled.toString());
        complaint(duplicate).getJSONArray("causes").put(cause(duplicate));
        assertInvalid(duplicate);
        duplicate = new JSONObject(bundled.toString());
        JSONArray questions = complaint(duplicate).getJSONArray("questions");
        questions.put(questions.getJSONObject(0));
        assertInvalid(duplicate);
        duplicate = new JSONObject(bundled.toString());
        JSONArray options = complaint(duplicate).getJSONArray("questions")
                .getJSONObject(0).getJSONArray("options");
        options.put(options.getJSONObject(0));
        assertInvalid(duplicate);
        duplicate = new JSONObject(bundled.toString());
        JSONArray rules = cause(duplicate).getJSONArray("score_rules");
        rules.put(rules.getJSONObject(0));
        assertInvalid(duplicate);
    }

    @Test
    public void invalidQuestionOptionAndSoundReferencesAreRejected() throws Exception {
        JSONObject invalid = new JSONObject(bundled.toString());
        cause(invalid).getJSONArray("score_rules").getJSONObject(0)
                .put("question_id", "missing_question");
        assertInvalid(invalid);
        invalid = new JSONObject(bundled.toString());
        cause(invalid).getJSONArray("score_rules").getJSONObject(0)
                .put("option_id", "missing_option");
        assertInvalid(invalid);
        invalid = new JSONObject(bundled.toString());
        cause(invalid).put("sound_ids", new JSONArray().put("missing_sound"));
        assertInvalid(invalid);
        try {
            loader.importExtra(source(invalid.toString()));
            fail("The merged sound reference must be checked");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void schemaMismatchAndWrongTypesAreRejected() throws Exception {
        JSONObject invalid = new JSONObject(bundled.toString()).put("schema_version", 2);
        assertInvalid(invalid);
        invalid = new JSONObject(bundled.toString()).put("schema_version", "1");
        assertInvalid(invalid);
        invalid = new JSONObject(bundled.toString());
        cause(invalid).remove("verified");
        assertInvalid(invalid);
        invalid = new JSONObject(bundled.toString());
        cause(invalid).put("verified", "false");
        assertInvalid(invalid);
        invalid = new JSONObject(bundled.toString());
        cause(invalid).put("base_weight", 1.5);
        assertInvalid(invalid);
    }

    @Test
    public void unsafeAudioPathsAreRejected() throws Exception {
        for (String path : new String[]{"../clip.wav", "/clip.wav", "https://contoh/clip.wav",
                "audio/../clip.wav", "audio\\clip.wav", "audio/%2e%2e/clip.wav"}) {
            JSONObject invalid = new JSONObject(bundled.toString());
            invalid.getJSONArray("sounds").getJSONObject(0).put("audio_file", path);
            assertInvalid(invalid);
        }
    }

    @Test
    public void invalidImportDoesNotReplaceValidFile() throws Exception {
        JSONObject valid = new JSONObject(bundled.toString()).put("revision", "uji-tetap");
        complaint(valid).put("title", "Judul tetap");
        loader.importExtra(source(valid.toString()));
        String before = DiagnosisKnowledgeLoader.encode(loader.load());
        JSONObject invalid = new JSONObject(valid.toString()).put("schema_version", 99);
        try {
            loader.importExtra(source(invalid.toString()));
            fail("Invalid import must fail");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
        assertEquals(before, DiagnosisKnowledgeLoader.encode(loader.load()));
        assertEquals("", loader.warning());
    }

    @Test
    public void malformedUtf8AndOversizedImportsPreserveExistingExtra() throws Exception {
        loader.importExtra(source(bundled.toString()));
        String before = DiagnosisKnowledgeLoader.encode(loader.load());
        assertInvalidImport(new byte[]{(byte) 0xc3, (byte) 0x28});
        assertInvalidImport(new byte[1024 * 1024 + 1]);
        assertEquals(before, DiagnosisKnowledgeLoader.encode(loader.load()));
    }

    @Test
    public void invalidOptionalExtraFallsBackWithVisibleWarning() throws Exception {
        File extra = new File(directory, "diagnosis/extra.json");
        assertTrue(extra.getParentFile().mkdirs());
        try (FileOutputStream out = new FileOutputStream(extra)) {
            out.write("{}".getBytes(StandardCharsets.UTF_8));
        }
        assertNotNull(loader.load().findComplaint("cold_start"));
        assertFalse(loader.warning().isEmpty());
    }

    private void assertInvalidImport(byte[] bytes) throws Exception {
        try {
            loader.importExtra(source(bytes));
            fail("Invalid bytes must fail");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private void assertInvalid(JSONObject json) throws Exception {
        try {
            DiagnosisKnowledgeLoader.parse(json.toString());
            fail("Invalid knowledge must fail");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private JSONObject complaint(JSONObject root) throws Exception {
        return root.getJSONArray("complaints").getJSONObject(0);
    }

    private JSONObject cause(JSONObject root) throws Exception {
        return complaint(root).getJSONArray("causes").getJSONObject(0);
    }

    private Uri source(String text) throws IOException {
        return source(text.getBytes(StandardCharsets.UTF_8));
    }

    private Uri source(byte[] bytes) throws IOException {
        File file = new File(directory, "import-" + UUID.randomUUID() + ".json");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
        return FileProvider.getUriForFile(target, target.getPackageName() + ".provider", file);
    }

    private void delete(File file) {
        if (file == null) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        assertTrue(file.delete());
    }
}

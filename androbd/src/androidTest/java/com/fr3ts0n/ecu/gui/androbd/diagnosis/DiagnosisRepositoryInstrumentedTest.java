package com.fr3ts0n.ecu.gui.androbd.diagnosis;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.CheckStatus;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisKnowledgeLoader;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisRepository;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopBackup;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DiagnosisRepositoryInstrumentedTest
{
    private Context context;
    private WorkshopDatabase db;
    private DiagnosisRepository repository;
    private DiagnosisKnowledge knowledge;
    private String name;
    private File backup, traversal;

    @Before public void setup() throws Exception
    {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        name = "guided-test-" + UUID.randomUUID() + ".db";
        db = new WorkshopDatabase(context, name);
        repository = new DiagnosisRepository(context, db);
        knowledge = new DiagnosisKnowledgeLoader(context).loadBundled();
        backup = new File(context.getCacheDir(), name + ".zip");
        traversal = new File(context.getCacheDir(), name + "-bad.zip");
    }

    @After public void teardown()
    {
        db.close();
        context.deleteDatabase(name);
        new File(context.getDatabasePath(name) + ".before-restore").delete();
        backup.delete(); traversal.delete();
    }

    private DiagnosisRepository.CaseRecord filled() throws Exception
    {
        DiagnosisRepository.CaseRecord record = repository.create(knowledge, knowledge.findComplaint("cold_start"), null);
        Map<String,String> answers = new LinkedHashMap<>();
        for (DiagnosisKnowledge.Question question : record.complaint.questions) answers.put(question.id, question.options.get(0).id);
        repository.saveProgress(record.id, answers, Collections.emptyMap());
        return repository.get(record.id);
    }

    @Test public void confirmationRequiresRecordedCheckAndKnowledgeDraftRemainsDraft() throws Exception
    {
        DiagnosisRepository.CaseRecord record = filled();
        String cause = record.complaint.causes.get(0).id;
        try { repository.saveFinal(record.id, cause, "Fixture assessment", true, ""); fail("Unproven confirmation accepted"); }
        catch (IllegalArgumentException expected) { }
        assertFalse(repository.get(record.id).mechanicConfirmed);
        Map<String,DiagnosisRepository.Check> checks = new LinkedHashMap<>();
        checks.put(cause, new DiagnosisRepository.Check(cause, CheckStatus.CONFIRMED, "Synthetic measurement matches manual reference"));
        repository.saveProgress(record.id, record.answers, checks);
        repository.saveFinal(record.id, cause, "Fixture assessment", true, "Evidence reviewed");
        record = repository.get(record.id);
        assertTrue(record.isFinal && record.mechanicConfirmed);
        assertFalse(record.complaint.findCause(cause).verified);
        assertEquals(1, repository.confirmedCases().size());
        checks.put(cause, new DiagnosisRepository.Check(cause, CheckStatus.RULED_OUT, "New measurement rules it out"));
        repository.saveProgress(record.id, record.answers, checks);
        assertFalse(repository.get(record.id).isFinal);
        assertTrue(repository.confirmedCases().isEmpty());
    }

    @Test public void archiveRestoresCaseAndRejectsTraversalWithoutChangingLiveData() throws Exception
    {
        DiagnosisRepository.CaseRecord record = filled();
        repository.saveFinal(record.id, "", "Custom provisional finding", false, "Needs vehicle-specific test");
        WorkshopBackup.export(db, backup);
        repository.saveFinal(record.id, "", "Changed later", false, "");
        WorkshopBackup.restore(db, backup);
        assertEquals("Custom provisional finding", repository.get(record.id).finalDiagnosis);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(traversal)))
        {
            zip.putNextEntry(new ZipEntry("media/photos/../../escape"));
            zip.write(1); zip.closeEntry();
        }
        try { WorkshopBackup.restore(db, traversal); fail("Unsafe archive accepted"); }
        catch (java.io.IOException expected) { }
        assertEquals("Custom provisional finding", repository.get(record.id).finalDiagnosis);
        assertFalse(new File(context.getCacheDir(), "escape").exists());
    }

    @Test public void snapshotDoesNotChangeWhenBundledKnowledgeIsReplaced() throws Exception
    {
        DiagnosisRepository.CaseRecord record = filled();
        String original = record.complaint.title;
        db.close();
        db = new WorkshopDatabase(context, name);
        repository = new DiagnosisRepository(context, db);
        record = repository.get(record.id);
        assertEquals(original, record.complaint.title);
        assertEquals(knowledge.revision, record.knowledgeRevision);
        assertNull(record.serviceId);
        assertEquals(record.complaint.questions.size(), record.answers.size());
    }

    @Test public void emptyConfirmationAndUnknownAnswersCannotEnterHistory() throws Exception
    {
        DiagnosisRepository.CaseRecord record = filled();
        Map<String,DiagnosisRepository.Check> checks = new LinkedHashMap<>();
        String cause = record.complaint.causes.get(0).id;
        checks.put(cause, new DiagnosisRepository.Check(cause, CheckStatus.CONFIRMED, " "));
        try { repository.saveProgress(record.id, record.answers, checks); fail("Empty evidence accepted"); }
        catch (IllegalArgumentException expected) { }
        Map<String,String> answers = new LinkedHashMap<>(record.answers);
        answers.put("invented-question", "invented-option");
        try { repository.saveProgress(record.id, answers, Collections.emptyMap()); fail("Unknown answer accepted"); }
        catch (IllegalArgumentException expected) { }
        repository.saveFinal(record.id, cause, "Provisional only", false, "");
        assertTrue(repository.confirmedCases().isEmpty());
        assertTrue(repository.get(record.id).checks.isEmpty());
    }

    @Test public void archiveCarriesPhotosAndV2UpgradeKeepsExistingRows() throws Exception
    {
        SQLiteDatabase writable = db.getWritableDatabase();
        writable.execSQL("INSERT INTO customers(name,created_at) VALUES('Migration fixture',1)");
        writable.execSQL("DROP TABLE diagnosis_photos");
        writable.execSQL("DROP TABLE diagnosis_checks");
        writable.execSQL("DROP TABLE diagnosis_cases");
        writable.setVersion(2);
        db.close();
        db = new WorkshopDatabase(context, name);
        repository = new DiagnosisRepository(context, db);
        assertEquals(3, db.getReadableDatabase().getVersion());
        try (android.database.Cursor rows = db.getReadableDatabase().rawQuery("SELECT name FROM customers", null))
        { assertTrue(rows.moveToFirst()); assertEquals("Migration fixture", rows.getString(0)); }
        DiagnosisRepository.CaseRecord record = filled();
        File photo = new File(context.getFilesDir(), "diagnosis/photos/test_" + UUID.randomUUID() + ".jpg");
        assertTrue(photo.getParentFile().isDirectory() || photo.getParentFile().mkdirs());
        try
        {
            try (FileOutputStream output = new FileOutputStream(photo)) { output.write(new byte[]{1,2,3,4}); }
            repository.addPhoto(record.id, "photos/" + photo.getName(), "Synthetic attachment");
            WorkshopBackup.export(db, backup);
            assertTrue(photo.delete());
            WorkshopBackup.restore(db, backup);
            assertTrue(photo.isFile());
            assertEquals(1, repository.get(record.id).photos.size());
            assertEquals(4, photo.length());
        }
        finally { photo.delete(); }
    }
}

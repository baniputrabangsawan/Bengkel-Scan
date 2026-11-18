package com.fr3ts0n.ecu.gui.androbd.diagnosis.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge;
import com.fr3ts0n.ecu.gui.androbd.workshop.EngineType;
import com.fr3ts0n.ecu.gui.androbd.workshop.EstimateItem;
import com.fr3ts0n.ecu.gui.androbd.workshop.ItemKind;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopDatabase;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopRepository;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Synthetic cases use a private helper, never the user's workshop database. */
@RunWith(AndroidJUnit4.class)
public class DiagnosisServiceLinkInstrumentedTest
{
    private Context context;
    private String databaseName;
    private WorkshopDatabase database;
    private WorkshopRepository workshop;
    private DiagnosisRepository cases;
    private DiagnosisServiceLink link;
    private long serviceId;
    private DiagnosisKnowledge knowledge;

    @Before public void setUp()
    {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        databaseName = "diagnosis-link-test-" + System.nanoTime() + ".db";
        database = new WorkshopDatabase(context, databaseName);
        workshop = new WorkshopRepository(database);
        cases = new DiagnosisRepository(context, database);
        link = new DiagnosisServiceLink(context, database);
        long customer = workshop.createCustomer("Pelanggan uji sintetis", "", "");
        long vehicle = workshop.createVehicle(customer, "UJI 8", "Model uji", 2020,
                EngineType.DIESEL, "", null);
        serviceId = workshop.createService(vehicle, "Keluhan sintetis");
        knowledge = syntheticKnowledge();
    }

    @After public void tearDown()
    {
        if (database != null) database.close();
        if (context != null && databaseName != null) context.deleteDatabase(databaseName);
    }

    @Test public void explicitEstimateIsLinkedOnceWithoutConfirmingCase()
    {
        long caseId = finalCase(serviceId);
        long itemId = link.addEstimate(caseId, ItemKind.LABOUR, " Pemeriksaan manual ", 1.5, 20000);
        assertEquals(itemId, cases.get(caseId).estimateItemId.longValue());
        assertFalse(cases.get(caseId).mechanicConfirmed);
        assertFalse(cases.get(caseId).complaint.causes.get(0).verified);
        EstimateItem item = workshop.estimateItems(serviceId).get(0);
        assertEquals("Pemeriksaan manual", item.description);
        assertEquals(1.5, item.quantity, 0);
        assertEquals(20000, item.unitPrice);
        assertEquals(ItemKind.LABOUR, item.kind);
        assertEquals(itemId, link.addEstimate(caseId, ItemKind.PART, "Tidak boleh menduplikasi", 2, 999));
        assertEquals(1, workshop.estimateItems(serviceId).size());
        assertEquals(30000, workshop.estimateTotal(serviceId));
    }

    @Test public void outerRollbackRevertsBothEstimateAndCaseLink()
    {
        long caseId = finalCase(serviceId);
        SQLiteDatabase db = database.getWritableDatabase();
        db.beginTransaction();
        try
        {
            long id = link.addEstimate(caseId, ItemKind.LABOUR, "Baris uji", 1, 0);
            assertEquals(id, cases.get(caseId).estimateItemId.longValue());
            assertEquals(id, link.addEstimate(caseId, ItemKind.LABOUR, "Klik ulang", 1, 0));
            assertEquals(1, workshop.estimateItems(serviceId).size());
            // Deliberately leave the outer shared-helper transaction unsuccessful.
        }
        finally { db.endTransaction(); }
        assertNull(cases.get(caseId).estimateItemId);
        assertTrue(workshop.estimateItems(serviceId).isEmpty());
    }

    @Test public void failedCaseUpdateRollsBackInsertedEstimate()
    {
        long caseId = finalCase(serviceId);
        SQLiteDatabase db = database.getWritableDatabase();
        db.execSQL("CREATE TRIGGER reject_diagnosis_estimate BEFORE UPDATE OF estimate_item_id ON diagnosis_cases "
                + "WHEN NEW.estimate_item_id IS NOT NULL BEGIN SELECT RAISE(ABORT,'synthetic rejection'); END");
        try
        {
            try
            {
                link.addEstimate(caseId, ItemKind.LABOUR, "Tidak tersimpan", 1, 100);
                fail("The synthetic trigger must reject the link");
            }
            catch (android.database.SQLException expected) { }
            assertNull(cases.get(caseId).estimateItemId);
            assertTrue(workshop.estimateItems(serviceId).isEmpty());
        }
        finally { db.execSQL("DROP TRIGGER reject_diagnosis_estimate"); }
    }

    @Test public void invalidAmountsAndDescriptionsDoNotMutate()
    {
        long caseId = finalCase(serviceId);
        for (double quantity : new double[]{Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, 0, -1})
            rejected(() -> link.addEstimate(caseId, ItemKind.LABOUR, "Uji", quantity, 1));
        rejected(() -> link.addEstimate(caseId, ItemKind.LABOUR, "Uji", 1, -1));
        rejected(() -> link.addEstimate(caseId, ItemKind.LABOUR, "  ", 1, 1));
        rejected(() -> link.addEstimate(caseId, ItemKind.LABOUR, null, 1, 1));
        rejected(() -> link.addEstimate(caseId, null, "Uji", 1, 1));
        assertNull(cases.get(caseId).estimateItemId);
        assertTrue(workshop.estimateItems(serviceId).isEmpty());
    }

    @Test public void draftUnlinkedAndMissingCasesCannotCreateItems()
    {
        long draft = cases.create(knowledge, knowledge.complaints.get(0), serviceId).id;
        long unlinked = finalCase(null);
        rejected(() -> link.addEstimate(draft, ItemKind.LABOUR, "Uji", 1, 0));
        rejected(() -> link.addEstimate(unlinked, ItemKind.LABOUR, "Uji", 1, 0));
        rejected(() -> link.addEstimate(Long.MAX_VALUE, ItemKind.LABOUR, "Uji", 1, 0));
        assertNull(cases.get(draft).estimateItemId);
        assertNull(cases.get(unlinked).estimateItemId);
        assertTrue(workshop.estimateItems(serviceId).isEmpty());
    }

    @Test public void linkingUsesRepositoryAndCannotMoveAnExistingEstimate()
    {
        long caseId = finalCase(null);
        link.linkService(caseId, serviceId);
        assertEquals(serviceId, cases.get(caseId).serviceId.longValue());
        assertEquals("Model uji", cases.get(caseId).vehicleModel);
        long item = link.addEstimate(caseId, ItemKind.LABOUR, "Uji", 1, 0);
        long second = workshop.createService(workshop.findService(serviceId).vehicleId, "Kunjungan lain");
        rejected(() -> link.linkService(caseId, second));
        assertEquals(serviceId, cases.get(caseId).serviceId.longValue());
        assertEquals(item, cases.get(caseId).estimateItemId.longValue());
        assertTrue(workshop.estimateItems(second).isEmpty());
        long another = finalCase(null);
        rejected(() -> link.linkService(another, Long.MAX_VALUE));
        assertNull(cases.get(another).serviceId);
    }

    @Test public void mismatchedExistingEstimateDoesNotCreateOrReassignItems()
    {
        long caseId = finalCase(serviceId);
        long second = workshop.createService(workshop.findService(serviceId).vehicleId, "Kunjungan lain");
        long foreignItem = workshop.addEstimateItem(second, ItemKind.PART, "Baris lain", 1, 1);
        database.getWritableDatabase().execSQL("UPDATE diagnosis_cases SET estimate_item_id=? WHERE id=?",
                new Object[]{foreignItem, caseId});
        rejected(() -> link.addEstimate(caseId, ItemKind.LABOUR, "Uji", 1, 0));
        assertTrue(workshop.estimateItems(serviceId).isEmpty());
        assertEquals(1, workshop.estimateItems(second).size());
        assertEquals(foreignItem, cases.get(caseId).estimateItemId.longValue());
    }

    @Test public void deletedServiceCannotLeaveAnOrphanEstimate()
    {
        long caseId = finalCase(serviceId);
        database.getWritableDatabase().delete("services", "id=?", new String[]{String.valueOf(serviceId)});
        rejected(() -> link.addEstimate(caseId, ItemKind.LABOUR, "Uji", 1, 0));
        try (Cursor rows = database.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM estimate_items", null))
        {
            assertTrue(rows.moveToFirst());
            assertEquals(0, rows.getInt(0));
        }
    }

    private long finalCase(Long service)
    {
        long id = cases.create(knowledge, knowledge.complaints.get(0), service).id;
        Map<String, String> answers = new LinkedHashMap<>();
        for (DiagnosisKnowledge.Question question : knowledge.complaints.get(0).questions)
            answers.put(question.id, "unknown");
        cases.saveProgress(id, answers, Collections.emptyMap());
        cases.saveFinal(id, "synthetic.cause", "Dugaan uji, belum dikonfirmasi", false, "");
        return id;
    }

    private static void rejected(Runnable action)
    {
        try { action.run(); fail("Expected explicit validation rejection"); }
        catch (IllegalArgumentException | IllegalStateException expected) { }
    }

    private static DiagnosisKnowledge syntheticKnowledge()
    {
        DiagnosisKnowledge.Cause cause = new DiagnosisKnowledge.Cause("synthetic.cause", "Penyebab sintetis",
                Collections.singletonList("Tanda uji"), Collections.singletonList("Periksa sesuai manual"),
                "LOW", "", "LABOR", "Pemeriksaan uji", 0, Collections.emptyList(), false, Collections.emptyList());
        java.util.List<DiagnosisKnowledge.Option> options = Collections.singletonList(
                new DiagnosisKnowledge.Option("unknown", "Belum diperiksa"));
        DiagnosisKnowledge.Complaint complaint = new DiagnosisKnowledge.Complaint("synthetic", "other", "Keluhan uji",
                Arrays.asList(new DiagnosisKnowledge.Question("q1", "Pertanyaan uji 1", options),
                        new DiagnosisKnowledge.Question("q2", "Pertanyaan uji 2", options),
                        new DiagnosisKnowledge.Question("q3", "Pertanyaan uji 3", options)), Collections.singletonList(cause));
        return new DiagnosisKnowledge(1, "synthetic-test", Collections.singletonList(complaint), Collections.emptyList());
    }
}

package com.fr3ts0n.ecu.gui.androbd.diagnosis;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisKnowledgeLoader;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisRepository;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.ui.DiagnosisResultsActivity;
import com.fr3ts0n.ecu.gui.androbd.workshop.EngineType;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopDatabase;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopRepository;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

/** The proposal does nothing until price/quantity are entered and Add is pressed. */
@RunWith(AndroidJUnit4.class)
public class DiagnosisEstimateUiInstrumentedTest
{
    @Test public void explicitEstimateFormAddsExactlyOnePricedServiceItem() throws Exception
    {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        WorkshopRepository workshop = new WorkshopRepository(WorkshopDatabase.getInstance(context));
        DiagnosisRepository repository = new DiagnosisRepository(context);
        long customer = workshop.createCustomer("Estimate UI fixture", "", "Synthetic test only");
        long vehicle = workshop.createVehicle(customer, "TEST ESTIMATE", "Fixture mechanical diesel", 1996, EngineType.DIESEL, "", null);
        long service = workshop.createService(vehicle, "Synthetic cold start");
        DiagnosisKnowledge knowledge = new DiagnosisKnowledgeLoader(context).loadBundled();
        DiagnosisRepository.CaseRecord record = repository.create(knowledge, knowledge.findComplaint("cold_start"), service);
        Map<String,String> answers = new LinkedHashMap<>();
        for (DiagnosisKnowledge.Question question : record.complaint.questions) answers.put(question.id, question.options.get(0).id);
        repository.saveProgress(record.id, answers, Collections.emptyMap());
        String cause = "cold_start.weak_starting_supply";
        repository.saveFinal(record.id, cause, record.complaint.findCause(cause).name, false, "Provisional test only");
        try (ActivityScenario<DiagnosisResultsActivity> scenario = ActivityScenario.launch(new Intent(context, DiagnosisResultsActivity.class).putExtra("diagnosis.case_id", record.id)))
        {
            assertTrue(workshop.estimateItems(service).isEmpty());
            click(awaitText(context.getString(R.string.diagnosis_link_estimate_action)));
            click(awaitText(context.getString(R.string.diagnosis_link_add)));
            assertTrue("Blank proposal must not create an item", workshop.estimateItems(service).isEmpty());
            setInput(context.getString(R.string.diagnosis_link_quantity), "1,5");
            setInput(context.getString(R.string.diagnosis_link_price), "100000");
            click(awaitText(context.getString(R.string.diagnosis_link_add)));
            long deadline = SystemClock.uptimeMillis() + 6000;
            while (workshop.estimateItems(service).isEmpty() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
            assertEquals(1, workshop.estimateItems(service).size());
            assertEquals(150000L, workshop.estimateTotal(service));
            assertNotNull(repository.get(record.id).estimateItemId);
        }
        finally { workshop.deleteCustomer(customer); }
    }

    private static AccessibilityNodeInfo root()
    {
        return InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();
    }

    private static AccessibilityNodeInfo awaitText(String text)
    {
        long deadline = SystemClock.uptimeMillis() + 6000;
        while (SystemClock.uptimeMillis() < deadline)
        {
            AccessibilityNodeInfo node = find(root(), text, false);
            if (node != null) return node;
            SystemClock.sleep(50);
        }
        throw new AssertionError("Visible estimate control unavailable: " + text);
    }

    private static void click(AccessibilityNodeInfo node)
    {
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK));
    }

    private static void setInput(String label, String value)
    {
        AccessibilityNodeInfo node = find(root(), label, true);
        assertNotNull("Labeled estimate input unavailable: " + label, node);
        Bundle args = new Bundle(); args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args));
    }

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo node, String text, boolean editable)
    {
        if (node == null) return null;
        String value = String.valueOf(node.getText());
        if ((!editable || node.isEditable()) && (text.equals(value) || (editable && value.contains(text)))) return node;
        for (int i = 0; i < node.getChildCount(); i++)
        {
            AccessibilityNodeInfo result = find(node.getChild(i), text, editable);
            if (result != null) return result;
        }
        return null;
    }
}

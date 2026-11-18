package com.fr3ts0n.ecu.gui.androbd.diagnosis;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisKnowledgeLoader;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.ui.DiagnosisQuestionsActivity;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.ui.DiagnosisResultsActivity;
import com.fr3ts0n.ecu.gui.androbd.workshop.EngineType;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopDatabase;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopRepository;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** An explicit answer is required; rotation must not discard observed symptoms. */
@RunWith(AndroidJUnit4.class)
public class DiagnosisFlowInstrumentedTest
{
    @Test public void explicitAnswersSurviveRecreationAndOpenSuggestedChecks() throws Exception
    {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        WorkshopRepository workshop = new WorkshopRepository(WorkshopDatabase.getInstance(context));
        long customer = workshop.createCustomer("Guided flow fixture", "", "Synthetic test data");
        long vehicle = workshop.createVehicle(customer, "TEST GUIDED", "Fixture diesel", 1998, EngineType.DIESEL, "", null);
        long service = workshop.createService(vehicle, "Synthetic cold-start complaint");
        DiagnosisKnowledge knowledge = new DiagnosisKnowledgeLoader(context).loadBundled();
        DiagnosisKnowledge.Complaint complaint = knowledge.findComplaint("cold_start");
        Intent intent = new Intent(context, DiagnosisQuestionsActivity.class)
                .putExtra("diagnosis.complaint_id", complaint.id)
                .putExtra("diagnosis.knowledge", DiagnosisKnowledgeLoader.encode(knowledge))
                .putExtra("workshop.service_id", service);
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(DiagnosisResultsActivity.class.getName(), null, false);
        Activity result = null;
        try (ActivityScenario<DiagnosisQuestionsActivity> scenario = ActivityScenario.launch(intent))
        {
            scenario.onActivity(activity -> {
                View root = activity.findViewById(android.R.id.content);
                text(root, activity.getString(R.string.diagnosis_show_results)).performClick();
                assertEquals(0, monitor.getHits());
                List<RadioGroup> groups = groups(root);
                assertEquals(complaint.questions.size(), groups.size());
                for (RadioGroup group : groups) group.check(group.getChildAt(0).getId());
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                View root = activity.findViewById(android.R.id.content);
                for (RadioGroup group : groups(root)) assertEquals(group.getChildAt(0).getId(), group.getCheckedRadioButtonId());
                text(root, activity.getString(R.string.diagnosis_show_results)).performClick();
            });
            result = instrumentation.waitForMonitorWithTimeout(monitor, 5000);
            assertNotNull("Ranked checks screen did not open", result);
            Activity shown = result;
            instrumentation.runOnMainSync(() -> {
                View root = shown.findViewById(android.R.id.content);
                assertNotNull(text(root, shown.getString(R.string.diagnosis_disclaimer)));
                assertNotNull(text(root, shown.getString(R.string.diagnosis_verify)));
                assertNotNull(text(root, shown.getString(R.string.diagnosis_draft)));
                assertNotNull(text(root, shown.getString(R.string.diagnosis_why)));
            });
        }
        finally
        {
            if (result != null) { Activity shown = result; instrumentation.runOnMainSync(shown::finish); }
            instrumentation.removeMonitor(monitor);
            workshop.deleteCustomer(customer);
        }
    }

    private static View text(View view, String value)
    {
        if (view instanceof TextView && value.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup)
        {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
            {
                View found = text(group.getChildAt(i), value);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static List<RadioGroup> groups(View root)
    {
        ArrayList<RadioGroup> result = new ArrayList<>();
        collect(root, result);
        return result;
    }

    private static void collect(View view, List<RadioGroup> result)
    {
        if (view instanceof RadioGroup) { result.add((RadioGroup) view); return; }
        if (view instanceof ViewGroup)
        {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), result);
        }
    }
}

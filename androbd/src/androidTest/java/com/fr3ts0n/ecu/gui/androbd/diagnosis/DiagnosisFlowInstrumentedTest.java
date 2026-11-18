package com.fr3ts0n.ecu.gui.androbd.diagnosis;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.CheckStatus;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisScorer;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisKnowledgeLoader;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisRepository;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.ui.DiagnosisCaseActivity;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.ui.DiagnosisQuestionsActivity;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.ui.DiagnosisResultsActivity;
import com.fr3ts0n.ecu.gui.androbd.workshop.EngineType;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopDatabase;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopRepository;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Synthetic cases are service-linked so teardown cannot touch real workshop data. */
@RunWith(AndroidJUnit4.class)
public class DiagnosisFlowInstrumentedTest
{
    @Test public void explicitAnswersSurviveRecreationAndOpenSuggestedChecks() throws Exception
    {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        try (Fixture fixture = new Fixture(false))
        {
            Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(DiagnosisResultsActivity.class.getName(), null, false);
            Activity result = null;
            try (ActivityScenario<DiagnosisQuestionsActivity> scenario = ActivityScenario.launch(fixture.intent(DiagnosisQuestionsActivity.class)))
            {
                scenario.onActivity(activity -> {
                    View root = activity.findViewById(android.R.id.content);
                    text(root, activity.getString(R.string.diagnosis_show_results)).performClick();
                    assertEquals(0, monitor.getHits());
                    List<RadioGroup> groups = groups(root);
                    assertEquals(fixture.complaint.questions.size(), groups.size());
                    for (RadioGroup group : groups) group.check(group.getChildAt(0).getId());
                    assertEquals(fixture.complaint.questions.size(), fixture.saved().answers.size());
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
                    assertEquals(fixture.caseId, shown.getIntent().getLongExtra("diagnosis.case_id", -1));
                    assertFalse(shown.getIntent().hasExtra("diagnosis.knowledge"));
                    assertFalse(shown.getIntent().hasExtra("diagnosis.answers"));
                    View root = shown.findViewById(android.R.id.content);
                    View disclaimer = text(root, shown.getString(R.string.diagnosis_disclaimer));
                    assertNotNull(disclaimer);
                    for (android.view.ViewParent parent = disclaimer.getParent(); parent != null; parent = parent.getParent())
                        assertFalse("Disclaimer must be outside scrolling results", parent instanceof ScrollView);
                    assertNotNull(text(root, shown.getString(R.string.diagnosis_verify)));
                    assertNotNull(text(root, shown.getString(R.string.diagnosis_draft)));
                    assertNotNull(text(root, shown.getString(R.string.diagnosis_why)));
                });
            }
            finally
            {
                if (result != null) { Activity shown = result; instrumentation.runOnMainSync(shown::finish); }
                instrumentation.removeMonitor(monitor);
            }
        }
    }

    @Test public void draftResumesFromDatabaseWithoutInstanceState() throws Exception
    {
        try (Fixture fixture = new Fixture(false))
        {
            try (ActivityScenario<DiagnosisQuestionsActivity> scenario = ActivityScenario.launch(fixture.intent(DiagnosisQuestionsActivity.class)))
            {
                scenario.onActivity(activity -> {
                    RadioGroup first = groups(activity.findViewById(android.R.id.content)).get(0);
                    first.check(first.getChildAt(0).getId());
                });
            }
            assertEquals(1, fixture.saved().answers.size());
            try (ActivityScenario<DiagnosisQuestionsActivity> scenario = ActivityScenario.launch(fixture.intent(DiagnosisQuestionsActivity.class)))
            {
                scenario.onActivity(activity -> {
                    List<RadioGroup> groups = groups(activity.findViewById(android.R.id.content));
                    assertEquals(groups.get(0).getChildAt(0).getId(), groups.get(0).getCheckedRadioButtonId());
                    assertEquals(-1, groups.get(1).getCheckedRadioButtonId());
                });
            }
        }
    }

    @Test public void confirmationNeedsEvidenceAndChangedChecksDemoteFinal() throws Exception
    {
        try (Fixture fixture = new Fixture(true);
             ActivityScenario<DiagnosisResultsActivity> scenario = ActivityScenario.launch(fixture.intent(DiagnosisResultsActivity.class)))
        {
            String causeId = DiagnosisScorer.rank(fixture.complaint, fixture.saved().answers,
                    Collections.<String, CheckStatus>emptyMap()).get(0).cause.id;
            scenario.onActivity(activity -> {
                text(activity.findViewById(android.R.id.content), activity.getString(R.string.diagnosis_check_action)).performClick();
                AlertDialog dialog = dialog(activity, "checkDialog");
                text(dialog.getWindow().getDecorView(), activity.getString(R.string.diagnosis_check_confirmed)).performClick();
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                assertTrue(dialog.isShowing());
                assertFalse(fixture.saved().checks.containsKey(causeId));
                EditText evidence = dialog.findViewById(R.id.diagnosis_check_evidence_input);
                assertNotNull(evidence.getError());
                evidence.setText("Synthetic measurement agrees with workshop manual inspection.");
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                AlertDialog dialog = dialog(activity, "checkDialog");
                EditText evidence = dialog.findViewById(R.id.diagnosis_check_evidence_input);
                assertEquals("Synthetic measurement agrees with workshop manual inspection.", evidence.getText().toString());
                RadioGroup choices = groups(dialog.getWindow().getDecorView()).get(0);
                assertEquals(CheckStatus.CONFIRMED, choices.findViewById(choices.getCheckedRadioButtonId()).getTag());
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            assertEquals(CheckStatus.CONFIRMED, fixture.saved().checks.get(causeId).status);
            assertFalse(fixture.saved().complaint.findCause(causeId).verified);
            assertFalse(fixture.saved().isFinal);
            fixture.repository.saveFinal(fixture.caseId, causeId, fixture.complaint.findCause(causeId).name, true, "");
            scenario.recreate();
            scenario.onActivity(activity -> {
                View root = activity.findViewById(android.R.id.content);
                assertNotNull(text(root, "Synthetic measurement agrees with workshop manual inspection."));
                text(root, activity.getString(R.string.diagnosis_check_action)).performClick();
                AlertDialog unchanged = dialog(activity, "checkDialog");
                unchanged.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                assertTrue("Saving identical evidence must not demote a final case", fixture.saved().isFinal);
                text(activity.findViewById(android.R.id.content), activity.getString(R.string.diagnosis_check_action)).performClick();
                AlertDialog changed = dialog(activity, "checkDialog");
                text(changed.getWindow().getDecorView(), activity.getString(R.string.diagnosis_check_ruled_out)).performClick();
                changed.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            assertFalse(fixture.saved().isFinal);
            assertFalse(fixture.saved().mechanicConfirmed);
            assertEquals(CheckStatus.RULED_OUT, fixture.saved().checks.get(causeId).status);
        }
    }

    @Test public void customFinalIsNotAutoConfirmedAndUnsavedFormSurvivesRotation() throws Exception
    {
        try (Fixture fixture = new Fixture(true);
             ActivityScenario<DiagnosisCaseActivity> scenario = ActivityScenario.launch(fixture.intent(DiagnosisCaseActivity.class)))
        {
            scenario.onActivity(activity -> {
                text(activity.findViewById(android.R.id.content), activity.getString(R.string.diagnosis_final_custom)).performClick();
                ((EditText) activity.findViewById(R.id.diagnosis_case_custom)).setText("Synthetic alternate assessment");
                ((EditText) activity.findViewById(R.id.diagnosis_case_notes)).setText("Synthetic inspection evidence");
                ((EditText) activity.findViewById(R.id.diagnosis_case_model)).setText("Synthetic changed model");
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertEquals("Synthetic alternate assessment", ((EditText) activity.findViewById(R.id.diagnosis_case_custom)).getText().toString());
                assertEquals("Synthetic inspection evidence", ((EditText) activity.findViewById(R.id.diagnosis_case_notes)).getText().toString());
                assertFalse(((CheckBox) activity.findViewById(R.id.diagnosis_case_confirmed)).isChecked());
                text(activity.findViewById(android.R.id.content), activity.getString(R.string.diagnosis_save)).performClick();
            });
            assertTrue(fixture.saved().isFinal);
            assertFalse(fixture.saved().mechanicConfirmed);
            assertEquals("Synthetic alternate assessment", fixture.saved().finalDiagnosis);
            assertEquals("Synthetic changed model", fixture.saved().vehicleModel);
            scenario.onActivity(activity -> {
                ((EditText) activity.findViewById(R.id.diagnosis_case_custom)).setText("Unsaved replacement");
                activity.onBackPressed();
                assertTrue(dialog(activity, "discardDialog").isShowing());
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertEquals("Unsaved replacement", ((EditText) activity.findViewById(R.id.diagnosis_case_custom)).getText().toString());
                AlertDialog discard = dialog(activity, "discardDialog");
                assertTrue(discard.isShowing());
                discard.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
                assertFalse(activity.isFinishing());
                activity.onBackPressed();
                dialog(activity, "discardDialog").getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            assertEquals("Synthetic alternate assessment", fixture.saved().finalDiagnosis);
        }
    }

    private static AlertDialog dialog(Activity activity, String name)
    {
        try
        {
            Field field = activity.getClass().getDeclaredField(name);
            field.setAccessible(true);
            AlertDialog dialog = (AlertDialog) field.get(activity);
            assertNotNull(dialog);
            return dialog;
        }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static final class Fixture implements AutoCloseable
    {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final WorkshopRepository workshop = new WorkshopRepository(WorkshopDatabase.getInstance(context));
        final DiagnosisRepository repository = new DiagnosisRepository(context);
        final long customer;
        final long caseId;
        final DiagnosisKnowledge.Complaint complaint;

        Fixture(boolean answered) throws Exception
        {
            customer = workshop.createCustomer("Guided flow fixture", "", "Synthetic test data");
            try
            {
                long vehicle = workshop.createVehicle(customer, "TEST GUIDED", "Fixture diesel", 1998, EngineType.DIESEL, "", null);
                long service = workshop.createService(vehicle, "Synthetic cold-start complaint");
                DiagnosisKnowledge knowledge = new DiagnosisKnowledgeLoader(context).loadBundled();
                complaint = knowledge.findComplaint("cold_start");
                caseId = repository.create(knowledge, complaint, service).id;
                if (answered)
                {
                    Map<String, String> answers = new LinkedHashMap<>();
                    for (DiagnosisKnowledge.Question question : complaint.questions) answers.put(question.id, question.options.get(0).id);
                    repository.saveProgress(caseId, answers, Collections.<String, DiagnosisRepository.Check>emptyMap());
                }
            }
            catch (Exception e) { workshop.deleteCustomer(customer); throw e; }
        }

        Intent intent(Class<?> activity) { return new Intent(context, activity).putExtra("diagnosis.case_id", caseId); }
        DiagnosisRepository.CaseRecord saved() { return repository.get(caseId); }
        @Override public void close() { workshop.deleteCustomer(customer); }
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

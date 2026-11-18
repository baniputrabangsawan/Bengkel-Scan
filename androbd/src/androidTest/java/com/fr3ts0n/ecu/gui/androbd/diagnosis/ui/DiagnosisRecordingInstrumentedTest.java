package com.fr3ts0n.ecu.gui.androbd.diagnosis.ui;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.CheckStatus;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisKnowledgeLoader;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisMediaStore;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisRepository;
import com.fr3ts0n.ecu.gui.androbd.workshop.EngineType;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopDatabase;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopRepository;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** A synthetic case records ambient test audio; neither fixture nor sound is a mechanical reference. */
@RunWith(AndroidJUnit4.class)
public class DiagnosisRecordingInstrumentedTest
{
    @Rule public GrantPermissionRule microphone = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO);

    @Test public void recordingStopsWhenLeavingScreenAndKeepsHumanCaseAssociation() throws Exception
    {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        WorkshopRepository workshop = new WorkshopRepository(WorkshopDatabase.getInstance(context));
        DiagnosisRepository repository = new DiagnosisRepository(context);
        DiagnosisMediaStore media = new DiagnosisMediaStore(context);
        long customer = workshop.createCustomer("Recording test fixture", "", "Synthetic test only");
        long vehicle = workshop.createVehicle(customer, "TEST AUDIO", "Fixture diesel", 1995, EngineType.DIESEL, "", null);
        long service = workshop.createService(vehicle, "Synthetic recording test");
        String label = "Test recording " + java.util.UUID.randomUUID();
        DiagnosisKnowledge knowledge = new DiagnosisKnowledgeLoader(context).loadBundled();
        DiagnosisRepository.CaseRecord record = repository.create(knowledge, knowledge.findComplaint("cold_start"), service);
        Map<String,String> answers = new LinkedHashMap<>();
        for (DiagnosisKnowledge.Question question : record.complaint.questions) answers.put(question.id, question.options.get(0).id);
        String causeId = "cold_start.weak_starting_supply";
        repository.saveProgress(record.id, answers, Collections.singletonMap(causeId,
                new DiagnosisRepository.Check(causeId, CheckStatus.CONFIRMED, "Synthetic confirmed test evidence")));
        repository.saveFinal(record.id, causeId, "Synthetic test diagnosis", true, "Not a real vehicle diagnosis");
        Intent intent = new Intent(context, SoundGuideActivity.class).putExtra("diagnosis.case_id", record.id).putExtra("diagnosis.sound_id", "slow_cranking");
        try (ActivityScenario<SoundGuideActivity> scenario = ActivityScenario.launch(intent))
        {
            long deadline = SystemClock.uptimeMillis() + 5000;
            AtomicReference<Boolean> ready = new AtomicReference<>(false);
            while (!ready.get() && SystemClock.uptimeMillis() < deadline)
            {
                scenario.onActivity(activity -> ready.set(activity.findViewById(R.id.diagnosis_sound_record).isEnabled()));
                SystemClock.sleep(50);
            }
            assertTrue("Sound guide did not load", ready.get());
            scenario.onActivity(activity -> activity.findViewById(R.id.diagnosis_sound_record).performClick());
            AccessibilityNodeInfo edit = awaitNode(instrumentation, "android.widget.EditText", null);
            Bundle value = new Bundle();
            value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, label);
            assertTrue(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, value));
            AccessibilityNodeInfo start = awaitNode(instrumentation, null, context.getString(R.string.diagnosis_sound_start));
            assertTrue(start.performAction(AccessibilityNodeInfo.ACTION_CLICK));
            AtomicReference<String> state = new AtomicReference<>("");
            deadline = SystemClock.uptimeMillis() + 6000;
            while (!context.getString(R.string.diagnosis_sound_recording).equals(state.get()) && SystemClock.uptimeMillis() < deadline)
            {
                scenario.onActivity(activity -> state.set(((TextView) activity.findViewById(R.id.diagnosis_sound_status)).getText().toString()));
                SystemClock.sleep(50);
            }
            assertEquals(context.getString(R.string.diagnosis_sound_recording), state.get());
            SystemClock.sleep(1500);
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
            DiagnosisMediaStore.Clip saved = null;
            deadline = SystemClock.uptimeMillis() + 6000;
            while (saved == null && SystemClock.uptimeMillis() < deadline)
            {
                for (DiagnosisMediaStore.Clip clip : media.clips("slow_cranking")) if (clip.label.equals(label)) saved = clip;
                if (saved == null) SystemClock.sleep(50);
            }
            assertNotNull("Recording was not saved on leaving screen", saved);
            assertEquals(record.id, saved.caseId);
            assertEquals(causeId, saved.causeId);
            assertEquals("Synthetic test diagnosis", saved.causeName);
            assertTrue(media.resolve(saved.relativePath).length() > 0);
        }
        finally
        {
            for (DiagnosisMediaStore.Clip clip : media.clips("slow_cranking"))
            {
                if (!clip.label.equals(label)) continue;
                File audio = media.resolve(clip.relativePath);
                new File(audio.getParentFile(), audio.getName().substring(0, audio.getName().lastIndexOf('.')) + ".json").delete();
                audio.delete();
            }
            workshop.deleteCustomer(customer);
        }
    }

    private static AccessibilityNodeInfo awaitNode(Instrumentation instrumentation, String className, String text)
    {
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (SystemClock.uptimeMillis() < deadline)
        {
            AccessibilityNodeInfo found = find(instrumentation.getUiAutomation().getRootInActiveWindow(), className, text);
            if (found != null) return found;
            SystemClock.sleep(50);
        }
        throw new AssertionError("Recording control unavailable: " + className + "/" + text);
    }

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo node, String className, String text)
    {
        if (node == null) return null;
        if ((className == null || className.equals(String.valueOf(node.getClassName())))
                && (text == null || text.equals(String.valueOf(node.getText())))) return node;
        for (int i = 0; i < node.getChildCount(); i++)
        {
            AccessibilityNodeInfo found = find(node.getChild(i), className, text);
            if (found != null) return found;
        }
        return null;
    }
}

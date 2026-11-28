package com.fr3ts0n.ecu.gui.androbd.diagnosis.ui;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.SettingsActivity;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisKnowledgeLoader;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisRepository;
import com.fr3ts0n.ecu.gui.androbd.workshop.ui.WorkshopUi;

import java.io.IOException;

/** Categories and saved complaints, independent of a scanner or service record. */
public class DiagnosisStartActivity extends AppCompatActivity
{
    private static final int IMPORT = 6101;
    private static final String[] CATEGORIES = {"noise", "smoke", "starting", "power", "overheating", "leaks", "vibration", "electrical", "brakes_steering", "other"};
    private static final int[] TITLES = {R.string.diagnosis_category_noise, R.string.diagnosis_category_smoke,
            R.string.diagnosis_category_starting, R.string.diagnosis_category_power, R.string.diagnosis_category_overheating,
            R.string.diagnosis_category_leaks, R.string.diagnosis_category_vibration, R.string.diagnosis_category_electrical,
            R.string.diagnosis_category_brakes_steering, R.string.diagnosis_category_other};
    private DiagnosisKnowledge knowledge;
    private DiagnosisKnowledgeLoader loader;
    private DiagnosisRepository repository;
    private String category;
    private boolean importing;

    @Override protected void onCreate(Bundle state)
    {
        SettingsActivity.applyLocale(this);
        super.onCreate(state);
        category = state == null ? null : state.getString("category");
        // Back from the question list must return to the categories first, and the
        // system back gesture only reaches a dispatcher callback on Android 13+.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true)
        {
            @Override public void handleOnBackPressed()
            {
                if (category != null) { category = null; render(); }
                else
                {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
        loader = new DiagnosisKnowledgeLoader(this);
        repository = new DiagnosisRepository(this);
        load();
        if (state == null && knowledge != null && getIntent().hasExtra(DiagnosisUi.COMPLAINT))
        {
            DiagnosisKnowledge.Complaint complaint = knowledge.findComplaint(getIntent().getStringExtra(DiagnosisUi.COMPLAINT));
            if (complaint != null)
            {
                String causeId = getIntent().getStringExtra("diagnosis.cause_id");
                if (causeId != null && complaint.findCause(causeId) != null)
                {
                    try
                    {
                        DiagnosisRepository.CaseRecord saved = repository.create(knowledge, complaint, null);
                        // The linked case has no answers yet, so the questions screen is the only
                        // place it can be completed. The cause the mechanic tapped is already the
                        // first row of the ranked list once the answers are in.
                        startActivity(new Intent(this, DiagnosisQuestionsActivity.class)
                                .putExtra(DiagnosisUi.CASE, saved.id));
                        finish();
                    }
                    catch (RuntimeException e)
                    {
                        Log.e("Diagnosis", "Sound-linked case creation failed", e);
                        Toast.makeText(this, R.string.diagnosis_case_save_failed, Toast.LENGTH_LONG).show();
                    }
                }
                else open(complaint);
            }
        }
    }

    @Override protected void onResume()
    {
        super.onResume();
        if (repository != null) render();
    }

    private void load()
    {
        try { knowledge = loader.load(); }
        catch (IOException | IllegalArgumentException e)
        {
            Log.e("Diagnosis", "Knowledge load failed", e);
            knowledge = null;
        }
        render();
    }

    private void render()
    {
        LinearLayout root = DiagnosisUi.screen(this, R.string.diagnosis_start_title);
        DiagnosisUi.text(this, root, getString(R.string.diagnosis_start_intro), false);
        DiagnosisUi.button(this, root, getString(R.string.context_entry), view -> {
            Intent intent = new Intent(this, VehicleContextActivity.class);
            DiagnosisUi.serviceExtra(getIntent(), intent);
            startActivity(intent);
        });
        renderCases(root);
        if (knowledge == null)
        {
            DiagnosisUi.text(this, root, getString(R.string.diagnosis_load_error), false);
            return;
        }
        if (!loader.warning().isEmpty()) DiagnosisUi.text(this, root, loader.warning(), false);
        if ("noise".equals(category))
            DiagnosisUi.button(this, root, getString(R.string.diagnosis_sound_title),
                    view -> startActivity(new Intent(this, SoundGuideActivity.class)));
        DiagnosisUi.text(this, root, getString(category == null ? R.string.diagnosis_choose_category : R.string.diagnosis_choose_complaint), true);
        if (category == null)
        {
            for (int i = 0; i < CATEGORIES.length; i++)
            {
                final String selected = CATEGORIES[i];
                DiagnosisUi.button(this, root, getString(TITLES[i]), view -> { category = selected; render(); });
            }
        }
        else
        {
            boolean found = false;
            for (DiagnosisKnowledge.Complaint complaint : knowledge.complaints)
            {
                if (!category.equals(complaint.category)) continue;
                found = true;
                DiagnosisUi.button(this, root, complaint.title, view -> open(complaint));
            }
            if (!found) DiagnosisUi.text(this, root, getString(R.string.diagnosis_empty_category), false);
        }
    }

    private void renderCases(LinearLayout root)
    {
        DiagnosisUi.text(this, root, getString(R.string.diagnosis_case_drafts), true);
        try
        {
            boolean found = false;
            for (DiagnosisRepository.CaseRecord saved : repository.cases())
            {
                if (getIntent().hasExtra(WorkshopUi.EXTRA_SERVICE_ID)
                        && (saved.serviceId == null || saved.serviceId != getIntent().getLongExtra(WorkshopUi.EXTRA_SERVICE_ID, -1))) continue;
                found = true;
                String status = getString(saved.isFinal ? R.string.diagnosis_case_final_state : R.string.diagnosis_case_draft_state);
                DiagnosisUi.button(this, root, getString(R.string.diagnosis_case_resume_label, saved.id, saved.complaintTitle, status), view -> resume(saved));
            }
            if (!found) DiagnosisUi.text(this, root, getString(R.string.diagnosis_case_no_records), false);
        }
        catch (RuntimeException e)
        {
            Log.e("Diagnosis", "Saved cases read failed", e);
            DiagnosisUi.text(this, root, getString(R.string.diagnosis_case_load_failed), false);
        }
    }

    private void resume(DiagnosisRepository.CaseRecord saved)
    {
        // Unanswered questions come first. Sending an incomplete case to the results screen
        // showed the "invalid case" notice and offered no way to finish it.
        Class<?> screen = saved.answers.size() < saved.complaint.questions.size()
                ? DiagnosisQuestionsActivity.class : DiagnosisResultsActivity.class;
        Intent intent = new Intent(this, screen).putExtra(DiagnosisUi.CASE, saved.id);
        if (saved.serviceId != null) intent.putExtra(WorkshopUi.EXTRA_SERVICE_ID, saved.serviceId.longValue());
        startActivity(intent);
    }

    private void open(DiagnosisKnowledge.Complaint complaint)
    {
        try
        {
            Long serviceId = getIntent().hasExtra(WorkshopUi.EXTRA_SERVICE_ID)
                    ? getIntent().getLongExtra(WorkshopUi.EXTRA_SERVICE_ID, -1) : null;
            resume(repository.create(knowledge, complaint, serviceId));
        }
        catch (RuntimeException e)
        {
            Log.e("Diagnosis", "Case creation failed", e);
            Toast.makeText(this, R.string.diagnosis_case_save_failed, Toast.LENGTH_LONG).show();
        }
    }

    @Override public boolean onCreateOptionsMenu(Menu menu)
    {
        menu.add(0, IMPORT, 0, R.string.diagnosis_import);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item)
    {
        if (item.getItemId() == android.R.id.home) { onBackPressed(); return true; }
        if (item.getItemId() == IMPORT)
        {
            if (importing) return true;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, IMPORT);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override protected void onActivityResult(int request, int result, Intent data)
    {
        super.onActivityResult(request, result, data);
        if (request != IMPORT || result != RESULT_OK || data == null || data.getData() == null) return;
        if (importing) return;
        importing = true;
        new Thread(() -> {
            DiagnosisKnowledgeLoader imported = new DiagnosisKnowledgeLoader(getApplicationContext());
            try
            {
                imported.importExtra(data.getData());
                DiagnosisKnowledge loaded = imported.load();
                runOnUiThread(() -> {
                    importing = false;
                    if (isFinishing() || isDestroyed()) return;
                    loader = imported;
                    knowledge = loaded;
                    Toast.makeText(this, R.string.diagnosis_import_ok, Toast.LENGTH_LONG).show();
                    render();
                });
            }
            catch (IOException | RuntimeException e)
            {
                Log.e("Diagnosis", "Knowledge import failed", e);
                runOnUiThread(() -> {
                    importing = false;
                    if (!isFinishing() && !isDestroyed())
                        Toast.makeText(this, R.string.diagnosis_import_error, Toast.LENGTH_LONG).show();
                });
            }
        }, "DiagnosisKnowledgeImport").start();
    }

    @Override protected void onSaveInstanceState(Bundle out)
    {
        super.onSaveInstanceState(out);
        out.putString("category", category);
    }


}

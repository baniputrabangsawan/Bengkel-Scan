package com.fr3ts0n.ecu.gui.androbd.diagnosis.ui;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.SettingsActivity;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisKnowledgeLoader;

import java.io.IOException;

/** Categories and complaints, usable without a scanner or a service record. */
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
    private String category;

    @Override protected void onCreate(Bundle state)
    {
        SettingsActivity.applyLocale(this);
        super.onCreate(state);
        category = state == null ? null : state.getString("category");
        loader = new DiagnosisKnowledgeLoader(this);
        load();
        if (state == null && knowledge != null && getIntent().hasExtra(DiagnosisUi.COMPLAINT))
        {
            DiagnosisKnowledge.Complaint complaint = knowledge.findComplaint(getIntent().getStringExtra(DiagnosisUi.COMPLAINT));
            if (complaint != null) open(complaint);
        }
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
        if (knowledge == null)
        {
            DiagnosisUi.text(this, root, getString(R.string.diagnosis_load_error), false);
            return;
        }
        if (!loader.warning().isEmpty()) DiagnosisUi.text(this, root, loader.warning(), false);
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

    private void open(DiagnosisKnowledge.Complaint complaint)
    {
        Intent intent = new Intent(this, DiagnosisQuestionsActivity.class)
                .putExtra(DiagnosisUi.COMPLAINT, complaint.id)
                .putExtra(DiagnosisUi.KNOWLEDGE, DiagnosisKnowledgeLoader.encode(DiagnosisUi.subset(knowledge, complaint)));
        DiagnosisUi.serviceExtra(getIntent(), intent);
        startActivity(intent);
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
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                    .addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, IMPORT);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override protected void onActivityResult(int request, int result, Intent data)
    {
        super.onActivityResult(request, result, data);
        if (request != IMPORT || result != RESULT_OK || data == null || data.getData() == null) return;
        try
        {
            loader.importExtra(data.getData());
            Toast.makeText(this, R.string.diagnosis_import_ok, Toast.LENGTH_LONG).show();
            load();
        }
        catch (IOException | IllegalArgumentException e)
        {
            Log.e("Diagnosis", "Knowledge import failed", e);
            Toast.makeText(this, R.string.diagnosis_import_error, Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onSaveInstanceState(Bundle out)
    {
        super.onSaveInstanceState(out);
        out.putString("category", category);
    }

    @Override public void onBackPressed()
    {
        if (category != null) { category = null; render(); }
        else super.onBackPressed();
    }
}

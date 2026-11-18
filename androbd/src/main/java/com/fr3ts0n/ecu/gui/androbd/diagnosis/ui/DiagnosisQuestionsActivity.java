package com.fr3ts0n.ecu.gui.androbd.diagnosis.ui;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatRadioButton;

import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.SettingsActivity;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisRepository;

import java.util.LinkedHashMap;
import java.util.Map;

/** Every explicit choice is persisted before another screen can consume it. */
public class DiagnosisQuestionsActivity extends AppCompatActivity
{
    private final Map<String, String> answers = new LinkedHashMap<>();
    private DiagnosisRepository repository;
    private DiagnosisRepository.CaseRecord record;
    private boolean restoringChoice;

    @Override protected void onCreate(Bundle state)
    {
        SettingsActivity.applyLocale(this);
        super.onCreate(state);
        repository = new DiagnosisRepository(this);
        render();
    }

    @Override protected void onRestart()
    {
        super.onRestart();
        render();
    }

    private void render()
    {
        LinearLayout root = DiagnosisUi.screen(this, R.string.diagnosis_questions_title);
        try
        {
            record = repository.get(getIntent().getLongExtra(DiagnosisUi.CASE, -1));
            if (record == null) throw new IllegalArgumentException("Missing case");
            answers.clear();
            answers.putAll(record.answers);
        }
        catch (RuntimeException e)
        {
            Log.e("Diagnosis", "Question state invalid", e);
            DiagnosisUi.text(this, root, getString(R.string.diagnosis_invalid_case), false);
            return;
        }
        DiagnosisUi.text(this, root, record.complaintTitle, true);
        if (record.isFinal) DiagnosisUi.text(this, root, getString(R.string.diagnosis_case_change_warning), false);
        for (DiagnosisKnowledge.Question question : record.complaint.questions)
        {
            DiagnosisUi.text(this, root, question.text, true);
            RadioGroup group = new RadioGroup(this);
            group.setOrientation(RadioGroup.VERTICAL);
            root.addView(group, new LinearLayout.LayoutParams(-1, -2));
            for (DiagnosisKnowledge.Option option : question.options)
            {
                AppCompatRadioButton radio = new AppCompatRadioButton(this);
                radio.setId(View.generateViewId());
                radio.setText(option.text);
                radio.setTextSize(17);
                radio.setMinHeight(DiagnosisUi.dp(this, 56));
                radio.setTag(option.id);
                group.addView(radio, new RadioGroup.LayoutParams(-1, -2));
                if (option.id.equals(answers.get(question.id))) group.check(radio.getId());
            }
            group.setOnCheckedChangeListener((view, id) -> {
                View selected = view.findViewById(id);
                if (restoringChoice || selected == null) return;
                String option = (String) selected.getTag();
                if (option.equals(answers.get(question.id))) return;
                Map<String, String> changed = new LinkedHashMap<>(answers);
                changed.put(question.id, option);
                try
                {
                    DiagnosisRepository.CaseRecord latest = repository.get(record.id);
                    if (latest == null) throw new IllegalArgumentException("Missing case");
                    repository.saveProgress(record.id, changed, latest.checks);
                    answers.clear();
                    answers.putAll(changed);
                }
                catch (RuntimeException e)
                {
                    Log.e("Diagnosis", "Draft save failed", e);
                    Toast.makeText(this, R.string.diagnosis_case_save_failed, Toast.LENGTH_LONG).show();
                    restoringChoice = true;
                    group.clearCheck();
                    for (int i = 0; i < group.getChildCount(); i++)
                    {
                        View previous = group.getChildAt(i);
                        if (previous.getTag().equals(answers.get(question.id))) group.check(previous.getId());
                    }
                    restoringChoice = false;
                }
            });
        }
        DiagnosisUi.button(this, root, getString(R.string.diagnosis_show_results), view -> {
            if (answers.size() != record.complaint.questions.size())
            {
                Toast.makeText(this, R.string.diagnosis_answer_required, Toast.LENGTH_LONG).show();
                return;
            }
            Intent intent = new Intent(this, DiagnosisResultsActivity.class).putExtra(DiagnosisUi.CASE, record.id);
            DiagnosisUi.serviceExtra(getIntent(), intent);
            startActivity(intent);
        });
    }

    @Override public boolean onOptionsItemSelected(MenuItem item)
    {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}

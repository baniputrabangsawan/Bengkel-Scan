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
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisKnowledgeLoader;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit unknown choices are preserved; unanswered is never interpreted as normal. */
public class DiagnosisQuestionsActivity extends AppCompatActivity
{
    private final Map<String, String> answers = new LinkedHashMap<>();
    private DiagnosisKnowledge knowledge;
    private DiagnosisKnowledge.Complaint complaint;
    private String snapshot;

    @Override protected void onCreate(Bundle state)
    {
        SettingsActivity.applyLocale(this);
        super.onCreate(state);
        LinearLayout root = DiagnosisUi.screen(this, R.string.diagnosis_questions_title);
        try
        {
            snapshot = state == null ? getIntent().getStringExtra(DiagnosisUi.KNOWLEDGE) : state.getString(DiagnosisUi.KNOWLEDGE);
            knowledge = DiagnosisKnowledgeLoader.parse(snapshot);
            complaint = knowledge.findComplaint(getIntent().getStringExtra(DiagnosisUi.COMPLAINT));
            if (complaint == null) throw new IllegalArgumentException("Missing complaint");
            if (state != null) answers.putAll(DiagnosisUi.answers(state.getString(DiagnosisUi.ANSWERS)));
        }
        catch (Exception e)
        {
            Log.e("Diagnosis", "Question state invalid", e);
            DiagnosisUi.text(this, root, getString(R.string.diagnosis_invalid_case), false);
            return;
        }
        DiagnosisUi.text(this, root, complaint.title, true);
        for (DiagnosisKnowledge.Question question : complaint.questions)
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
                if (selected != null) answers.put(question.id, (String) selected.getTag());
            });
        }
        DiagnosisUi.button(this, root, getString(R.string.diagnosis_show_results), view -> {
            if (answers.size() != complaint.questions.size())
            {
                Toast.makeText(this, R.string.diagnosis_answer_required, Toast.LENGTH_LONG).show();
                return;
            }
            Intent intent = new Intent(this, DiagnosisResultsActivity.class)
                    .putExtra(DiagnosisUi.COMPLAINT, complaint.id)
                    .putExtra(DiagnosisUi.KNOWLEDGE, snapshot)
                    .putExtra(DiagnosisUi.ANSWERS, new JSONObject(answers).toString());
            DiagnosisUi.serviceExtra(getIntent(), intent);
            startActivity(intent);
        });
    }

    @Override protected void onSaveInstanceState(Bundle out)
    {
        super.onSaveInstanceState(out);
        out.putString(DiagnosisUi.KNOWLEDGE, snapshot);
        out.putString(DiagnosisUi.ANSWERS, new JSONObject(answers).toString());
    }

    @Override public boolean onOptionsItemSelected(MenuItem item)
    {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}

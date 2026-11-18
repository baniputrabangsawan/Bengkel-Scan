package com.fr3ts0n.ecu.gui.androbd.diagnosis.ui;

import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;

import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.SettingsActivity;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.CheckStatus;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisScorer;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisKnowledgeLoader;

import java.util.Collections;
import java.util.Map;

/** Suggested checks are not an automatic diagnosis or a replacement recommendation. */
public class DiagnosisResultsActivity extends AppCompatActivity
{
    protected DiagnosisKnowledge knowledge;
    protected DiagnosisKnowledge.Complaint complaint;
    protected Map<String, String> answers;

    @Override protected void onCreate(Bundle state)
    {
        SettingsActivity.applyLocale(this);
        super.onCreate(state);
        LinearLayout root = DiagnosisUi.resultsScreen(this);
        try
        {
            knowledge = DiagnosisKnowledgeLoader.parse(getIntent().getStringExtra(DiagnosisUi.KNOWLEDGE));
            complaint = knowledge.findComplaint(getIntent().getStringExtra(DiagnosisUi.COMPLAINT));
            if (complaint == null) throw new IllegalArgumentException("Missing complaint");
            answers = DiagnosisUi.answers(getIntent().getStringExtra(DiagnosisUi.ANSWERS));
            render(root);
        }
        catch (Exception e)
        {
            Log.e("Diagnosis", "Result state invalid", e);
            root.removeAllViews();
            DiagnosisUi.text(this, root, getString(R.string.diagnosis_invalid_case), false);
        }
    }

    protected void render(LinearLayout root)
    {
        DiagnosisUi.text(this, root, complaint.title, true);
        for (DiagnosisScorer.RankedCause ranked : DiagnosisScorer.rank(complaint, answers, Collections.<String, CheckStatus>emptyMap()))
        {
            DiagnosisKnowledge.Cause cause = ranked.cause;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            int padding = DiagnosisUi.dp(this, 14);
            card.setPadding(padding, padding, padding, padding);
            card.setBackgroundResource(R.drawable.result_row_surface);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.topMargin = DiagnosisUi.dp(this, 12);
            root.addView(card, params);
            DiagnosisUi.text(this, card, cause.name, true);
            if (!cause.verified) DiagnosisUi.text(this, card, getString(R.string.diagnosis_draft), false);
            int danger = "HIGH".equals(cause.dangerLevel) ? R.string.diagnosis_danger_high
                    : "MEDIUM".equals(cause.dangerLevel) ? R.string.diagnosis_danger_medium : R.string.diagnosis_danger_low;
            DiagnosisUi.text(this, card, getString(danger), true);
            if ("HIGH".equals(cause.dangerLevel))
            {
                android.widget.TextView warning = DiagnosisUi.text(this, card, getString(R.string.diagnosis_danger_warning), true);
                warning.setTextColor(getColor(R.color.workshop_warning_text));
                warning.setBackgroundColor(getColor(R.color.workshop_warning_surface));
            }
            if (!cause.warning.isEmpty()) DiagnosisUi.text(this, card, cause.warning, false);
            DiagnosisUi.text(this, card, getString(R.string.diagnosis_signs), true);
            for (String sign : cause.signs) DiagnosisUi.text(this, card, "• " + sign, false);
            DiagnosisUi.text(this, card, getString(R.string.diagnosis_verify), true);
            for (int i = 0; i < cause.verificationSteps.size(); i++)
                DiagnosisUi.text(this, card, (i + 1) + ". " + cause.verificationSteps.get(i), false);
            DiagnosisUi.text(this, card, getString(R.string.diagnosis_why), true);
            if (ranked.reasons.isEmpty()) DiagnosisUi.text(this, card, getString(R.string.diagnosis_no_matching_rules), false);
            for (String reason : ranked.reasons) DiagnosisUi.text(this, card, "• " + reason, false);
            DiagnosisUi.text(this, card, getString(R.string.diagnosis_work, cause.workDescription), false);
        }
    }

    @Override public boolean onOptionsItemSelected(MenuItem item)
    {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}

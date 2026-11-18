package com.fr3ts0n.ecu.gui.androbd.diagnosis.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatRadioButton;

import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.SettingsActivity;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.CheckStatus;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisScorer;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisRepository;

import java.util.LinkedHashMap;
import java.util.Map;

/** Scores suggest checks; only explicit mechanic evidence can confirm a cause. */
public class DiagnosisResultsActivity extends AppCompatActivity
{
    protected DiagnosisKnowledge knowledge;
    protected DiagnosisKnowledge.Complaint complaint;
    protected Map<String, String> answers;
    protected DiagnosisRepository repository;
    protected DiagnosisRepository.CaseRecord record;
    private LinearLayout root;
    private String editingCause;
    private CheckStatus editingStatus;
    private EditText evidence;
    private AlertDialog checkDialog;

    @Override protected void onCreate(Bundle state)
    {
        SettingsActivity.applyLocale(this);
        super.onCreate(state);
        repository = new DiagnosisRepository(this);
        root = DiagnosisUi.resultsScreen(this);
        if (state != null)
        {
            editingCause = state.getString("check_cause");
            if (editingCause != null)
                editingStatus = CheckStatus.valueOf(state.getString("check_status", CheckStatus.NOT_TESTED.name()));
        }
        if (load() && editingCause != null)
        {
            DiagnosisKnowledge.Cause cause = complaint.findCause(editingCause);
            if (cause != null) showCheck(cause, editingStatus, state.getString("check_evidence", ""));
        }
    }

    @Override protected void onResume()
    {
        super.onResume();
        if (repository != null) load();
    }

    private boolean load()
    {
        root.removeAllViews();
        try
        {
            record = repository.get(getIntent().getLongExtra(DiagnosisUi.CASE, -1));
            if (record == null || record.answers.size() != record.complaint.questions.size())
                throw new IllegalArgumentException("Missing or incomplete case");
            knowledge = record.knowledge;
            complaint = record.complaint;
            answers = record.answers;
            render(root);
            return true;
        }
        catch (RuntimeException e)
        {
            Log.e("Diagnosis", "Result state invalid", e);
            root.removeAllViews();
            DiagnosisUi.text(this, root, getString(R.string.diagnosis_invalid_case), false);
            return false;
        }
    }

    protected void render(LinearLayout root)
    {
        DiagnosisUi.text(this, root, complaint.title, true);
        DiagnosisUi.text(this, root, getString(R.string.diagnosis_confirmed_note), false);
        if (record.isFinal) DiagnosisUi.text(this, root, getString(R.string.diagnosis_case_change_warning), false);
        DiagnosisUi.button(this, root, getString(R.string.diagnosis_final_action), view ->
                startActivity(new Intent(this, DiagnosisCaseActivity.class).putExtra(DiagnosisUi.CASE, record.id)));
        DiagnosisUi.button(this, root, getString(R.string.diagnosis_edit_answers), view ->
                startActivity(new Intent(this, DiagnosisQuestionsActivity.class).putExtra(DiagnosisUi.CASE, record.id)));
        Map<String, CheckStatus> checks = new LinkedHashMap<>();
        for (DiagnosisRepository.Check check : record.checks.values()) checks.put(check.causeId, check.status);
        for (DiagnosisScorer.RankedCause ranked : DiagnosisScorer.rank(complaint, answers, checks))
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
            DiagnosisRepository.Check check = record.checks.get(cause.id);
            DiagnosisUi.text(this, card, getString(statusLabel(check == null ? CheckStatus.NOT_TESTED : check.status)), true);
            if (check != null && !check.evidence.isEmpty()) DiagnosisUi.text(this, card, check.evidence, false);
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
            DiagnosisUi.button(this, card, getString(R.string.diagnosis_check_action), view -> {
                DiagnosisRepository.Check current = record.checks.get(cause.id);
                showCheck(cause, current == null ? CheckStatus.NOT_TESTED : current.status, current == null ? "" : current.evidence);
            });
        }
    }

    private static int statusLabel(CheckStatus status)
    {
        return status == CheckStatus.CONFIRMED ? R.string.diagnosis_check_confirmed
                : status == CheckStatus.RULED_OUT ? R.string.diagnosis_check_ruled_out : R.string.diagnosis_check_not_tested;
    }

    private void showCheck(DiagnosisKnowledge.Cause cause, CheckStatus status, String savedEvidence)
    {
        editingCause = cause.id;
        editingStatus = status;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = DiagnosisUi.dp(this, 16);
        form.setPadding(padding, 0, padding, 0);
        RadioGroup choices = new RadioGroup(this);
        form.addView(choices, new LinearLayout.LayoutParams(-1, -2));
        for (CheckStatus choice : CheckStatus.values())
        {
            AppCompatRadioButton radio = new AppCompatRadioButton(this);
            radio.setId(View.generateViewId());
            radio.setTag(choice);
            radio.setText(statusLabel(choice));
            radio.setMinHeight(DiagnosisUi.dp(this, 56));
            choices.addView(radio, new RadioGroup.LayoutParams(-1, -2));
            if (choice == status) choices.check(radio.getId());
        }
        choices.setOnCheckedChangeListener((group, id) -> {
            View selected = group.findViewById(id);
            if (selected != null) editingStatus = (CheckStatus) selected.getTag();
        });
        android.widget.TextView caption = DiagnosisUi.text(this, form, getString(R.string.diagnosis_check_evidence), true);
        evidence = new EditText(this);
        evidence.setId(R.id.diagnosis_check_evidence_input);
        caption.setLabelFor(evidence.getId());
        evidence.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        evidence.setMinLines(2);
        evidence.setText(savedEvidence);
        form.addView(evidence, new LinearLayout.LayoutParams(-1, -2));
        DiagnosisUi.text(this, form, getString(R.string.diagnosis_check_evidence_required), false);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(cause.name).setView(scroll)
                .setPositiveButton(R.string.diagnosis_save, null).setNegativeButton(R.string.diagnosis_cancel, null).create();
        checkDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (checkDialog != dialog) return;
            editingCause = null;
            evidence = null;
            checkDialog = null;
        });
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> saveCheck(cause.id));
    }

    private void saveCheck(String causeId)
    {
        String finding = evidence.getText().toString().trim();
        if (editingStatus == CheckStatus.CONFIRMED && finding.isEmpty())
        {
            evidence.setError(getString(R.string.diagnosis_check_evidence_required));
            return;
        }
        try
        {
            DiagnosisRepository.CaseRecord latest = repository.get(record.id);
            if (latest == null) throw new IllegalArgumentException("Missing case");
            DiagnosisRepository.Check previous = latest.checks.get(causeId);
            boolean changed = previous == null ? editingStatus != CheckStatus.NOT_TESTED || !finding.isEmpty()
                    : previous.status != editingStatus || !previous.evidence.equals(finding);
            if (changed)
            {
                Map<String, DiagnosisRepository.Check> checks = new LinkedHashMap<>(latest.checks);
                checks.put(causeId, new DiagnosisRepository.Check(causeId, editingStatus, finding));
                repository.saveProgress(latest.id, latest.answers, checks);
            }
            checkDialog.dismiss();
            load();
        }
        catch (RuntimeException e)
        {
            Log.e("Diagnosis", "Check save failed", e);
            Toast.makeText(this, R.string.diagnosis_case_save_failed, Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onSaveInstanceState(Bundle out)
    {
        super.onSaveInstanceState(out);
        if (editingCause == null || evidence == null) return;
        out.putString("check_cause", editingCause);
        out.putString("check_status", editingStatus.name());
        out.putString("check_evidence", evidence.getText().toString());
    }

    @Override protected void onDestroy()
    {
        if (checkDialog != null) checkDialog.dismiss();
        super.onDestroy();
    }

    @Override public boolean onOptionsItemSelected(MenuItem item)
    {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}

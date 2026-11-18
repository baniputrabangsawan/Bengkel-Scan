package com.fr3ts0n.ecu.gui.androbd.diagnosis.ui;

import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisCaseHistory;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisRepository;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Read-only local history shared by result and case screens. */
public final class DiagnosisHistoryPanel
{
    private DiagnosisHistoryPanel() { }

    public static void render(AppCompatActivity activity, LinearLayout container,
            DiagnosisRepository repository, DiagnosisRepository.CaseRecord current)
    {
        text(activity, container, activity.getString(R.string.diagnosis_history_title));
        text(activity, container, activity.getString(R.string.diagnosis_history_criteria));
        if (current == null)
        {
            text(activity, container, activity.getString(R.string.diagnosis_history_missing));
            return;
        }
        List<DiagnosisCaseHistory.Entry> entries = new ArrayList<>();
        for (DiagnosisRepository.CaseRecord record : repository.confirmedCases()) entries.add(entry(record));
        DiagnosisCaseHistory.Summary summary = DiagnosisCaseHistory.summarize(entry(current), entries);
        if (!summary.identityComplete)
        {
            text(activity, container, activity.getString(R.string.diagnosis_history_missing));
            return;
        }
        text(activity, container, activity.getString(R.string.diagnosis_history_vehicle,
                current.complaintTitle, current.vehicleModel, current.vehicleYear, current.engineType));
        text(activity, container, activity.getString(R.string.diagnosis_history_total, summary.count));
        if (summary.count == 0) text(activity, container, activity.getString(R.string.diagnosis_history_empty));
        DateFormat dates = android.text.format.DateFormat.getDateFormat(activity);
        for (DiagnosisCaseHistory.Group group : summary.groups)
        {
            text(activity, container, activity.getString(R.string.diagnosis_history_group,
                    group.diagnosis, group.count, dates.format(new Date(group.firstConfirmedAt)),
                    dates.format(new Date(group.lastConfirmedAt))));
        }
        text(activity, container, activity.getString(R.string.diagnosis_history_caution));
    }

    private static DiagnosisCaseHistory.Entry entry(DiagnosisRepository.CaseRecord record)
    {
        return new DiagnosisCaseHistory.Entry(record.id, record.complaintId, record.vehicleModel,
                record.vehicleYear, record.engineType, record.isFinal, record.mechanicConfirmed,
                record.finalCauseId, record.finalDiagnosis, record.updatedAt);
    }

    private static void text(AppCompatActivity activity, LinearLayout container, String value)
    {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(16);
        view.setTextColor(activity.getColor(R.color.workshop_on_surface));
        view.setPadding(0, 8, 0, 8);
        container.addView(view);
    }
}

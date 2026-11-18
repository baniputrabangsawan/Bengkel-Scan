package com.fr3ts0n.ecu.gui.androbd.diagnosis.ui;

import android.content.Intent;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.fr3ts0n.ecu.gui.androbd.ModernUiUtils;
import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge;
import com.fr3ts0n.ecu.gui.androbd.workshop.ui.WorkshopUi;
import com.google.android.material.button.MaterialButton;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Small view helpers for the offline guided flow; no diagnostic decisions. */
final class DiagnosisUi
{
    static final String COMPLAINT = "diagnosis.complaint_id";
    static final String ANSWERS = "diagnosis.answers";
    static final String KNOWLEDGE = "diagnosis.knowledge";
    static final String CASE = "diagnosis.case_id";

    private DiagnosisUi() { }

    static int dp(AppCompatActivity activity, int value)
    {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    static LinearLayout screen(AppCompatActivity activity, int title)
    {
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(activity);

        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(activity, 16);
        root.setPadding(padding, padding, padding, padding);
        scroll.addView(root);
        activity.setContentView(scroll);
        ModernUiUtils.applyScreenInsets(activity);
        WorkshopUi.prepare(activity, activity.getString(title));
        return root;
    }
    static LinearLayout resultsScreen(AppCompatActivity activity)
    {
        LinearLayout frame = new LinearLayout(activity);
        frame.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(activity, 16);
        frame.setPadding(padding, padding, padding, 0);
        text(activity, frame, activity.getString(R.string.diagnosis_disclaimer), false);
        ScrollView scroll = new ScrollView(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, 0, 0, padding);
        scroll.addView(root);
        frame.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        activity.setContentView(frame);
        ModernUiUtils.applyScreenInsets(activity);
        WorkshopUi.prepare(activity, activity.getString(R.string.diagnosis_results_title));
        return root;
    }

    static TextView text(AppCompatActivity activity, LinearLayout root, CharSequence value, boolean heading)
    {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextSize(heading ? 20 : 16);
        if (heading) text.setTypeface(text.getTypeface(), Typeface.BOLD);
        text.setTextColor(activity.getColor(R.color.workshop_on_surface));
        text.setPadding(0, dp(activity, 10), 0, dp(activity, 8));
        text.setTextIsSelectable(!heading);
        root.addView(text, new LinearLayout.LayoutParams(-1, -2));
        return text;
    }

    static MaterialButton button(AppCompatActivity activity, LinearLayout root, CharSequence label, View.OnClickListener listener)
    {
        MaterialButton button = new MaterialButton(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinHeight(dp(activity, 56));
        button.setOnClickListener(listener);
        root.addView(button, new LinearLayout.LayoutParams(-1, -2));
        return button;
    }

    static Map<String, String> answers(String json) throws JSONException
    {
        Map<String, String> result = new LinkedHashMap<>();
        JSONObject object = new JSONObject(json == null ? "{}" : json);
        Iterator<String> keys = object.keys();
        while (keys.hasNext())
        {
            String key = keys.next();
            Object value = object.get(key);
            if (!(value instanceof String)) throw new JSONException("Invalid answer value");
            result.put(key, (String) value);
        }
        return result;
    }

    static DiagnosisKnowledge subset(DiagnosisKnowledge source, DiagnosisKnowledge.Complaint complaint)
    {
        Set<String> referenced = new HashSet<>();
        for (DiagnosisKnowledge.Cause cause : complaint.causes) referenced.addAll(cause.soundIds);
        ArrayList<DiagnosisKnowledge.Sound> sounds = new ArrayList<>();
        for (DiagnosisKnowledge.Sound sound : source.sounds)
            if (referenced.contains(sound.id)) sounds.add(sound);
        return new DiagnosisKnowledge(source.schemaVersion, source.revision,
                java.util.Collections.singletonList(complaint), sounds);
    }

    static void serviceExtra(Intent from, Intent to)
    {
        if (from.hasExtra(WorkshopUi.EXTRA_SERVICE_ID))
            to.putExtra(WorkshopUi.EXTRA_SERVICE_ID, from.getLongExtra(WorkshopUi.EXTRA_SERVICE_ID, -1));
    }
}

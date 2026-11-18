package com.fr3ts0n.ecu.gui.androbd.diagnosis.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatRadioButton;

import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.SettingsActivity;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisMediaStore;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisRepository;

import java.io.File;
import java.io.IOException;

/** Final assessment is entered by the mechanic, never inferred from a score. */
public class DiagnosisCaseActivity extends AppCompatActivity
{
    private static final int PHOTO = 6201;
    private DiagnosisRepository repository;
    private DiagnosisRepository.CaseRecord record;
    private DiagnosisMediaStore media;
    private LinearLayout root;
    private EditText custom, notes, model, year, engine;
    private CheckBox confirmed;
    private String selected;
    private boolean dirty;
    private AlertDialog discardDialog;

    @Override protected void onCreate(Bundle state)
    {
        SettingsActivity.applyLocale(this);
        super.onCreate(state);
        root = DiagnosisUi.screen(this, R.string.diagnosis_case_title);
        repository = new DiagnosisRepository(this);
        media = new DiagnosisMediaStore(this);
        try { record = repository.get(getIntent().getLongExtra(DiagnosisUi.CASE, -1)); }
        catch (RuntimeException e) { Log.e("Diagnosis", "Case read failed", e); }
        if (record == null)
        {
            DiagnosisUi.text(this, root, getString(R.string.diagnosis_invalid_case), false);
            return;
        }
        render(state);
    }

    private EditText field(int id, int label, String value, boolean multiline)
    {
        android.widget.TextView caption = DiagnosisUi.text(this, root, getString(label), false);
        EditText edit = new EditText(this);
        edit.setId(id);
        edit.setSaveEnabled(false);
        caption.setLabelFor(edit.getId());
        edit.setText(value);
        edit.setTextSize(17);
        edit.setMinHeight(DiagnosisUi.dp(this, 56));
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        if (multiline) edit.setMinLines(2);
        root.addView(edit, new LinearLayout.LayoutParams(-1, -2));
        return edit;
    }

    private void render(Bundle state)
    {
        DiagnosisUi.text(this, root, getString(R.string.diagnosis_disclaimer), true);
        DiagnosisUi.text(this, root, record.complaintTitle, true);
        DiagnosisUi.text(this, root, getString(R.string.diagnosis_final_choose), true);
        selected = state == null ? (record.finalDiagnosis.isEmpty() ? null : record.finalCauseId) : state.getString("selected");
        RadioGroup choices = new RadioGroup(this);
        root.addView(choices, new LinearLayout.LayoutParams(-1, -2));
        for (DiagnosisKnowledge.Cause cause : record.complaint.causes) addChoice(choices, cause.id, cause.name);
        addChoice(choices, "", getString(R.string.diagnosis_final_custom));
        choices.setOnCheckedChangeListener((group, id) -> {
            View choice = group.findViewById(id);
            if (choice != null)
            {
                selected = (String) choice.getTag();
                if (confirmed != null) confirmed.setChecked(false);
                dirty = true;
            }
        });
        custom = field(R.id.diagnosis_case_custom, R.string.diagnosis_final_text, state == null ? (record.finalCauseId.isEmpty() ? record.finalDiagnosis : "") : state.getString("custom", ""), true);
        notes = field(R.id.diagnosis_case_notes, R.string.diagnosis_case_notes, state == null ? record.notes : state.getString("notes", ""), true);
        confirmed = new CheckBox(this);
        confirmed.setId(R.id.diagnosis_case_confirmed);
        confirmed.setText(R.string.diagnosis_final_confirmed);
        confirmed.setTextSize(17);
        confirmed.setMinHeight(DiagnosisUi.dp(this, 56));
        confirmed.setChecked(state == null ? record.mechanicConfirmed : state.getBoolean("confirmed"));
        root.addView(confirmed, new LinearLayout.LayoutParams(-1, -2));
        DiagnosisUi.text(this, root, getString(R.string.diagnosis_final_unconfirmed), false);
        DiagnosisUi.text(this, root, getString(R.string.diagnosis_case_vehicle), true);
        model = field(R.id.diagnosis_case_model, R.string.diagnosis_case_model, state == null ? record.vehicleModel : state.getString("model", ""), false);
        year = field(R.id.diagnosis_case_year, R.string.diagnosis_case_year, state == null ? (record.vehicleYear == null ? "" : String.valueOf(record.vehicleYear)) : state.getString("year", ""), false);
        year.setInputType(InputType.TYPE_CLASS_NUMBER);
        engine = field(R.id.diagnosis_case_engine, R.string.diagnosis_case_engine, state == null ? record.engineType : state.getString("engine", ""), false);
        DiagnosisUi.button(this, root, getString(R.string.diagnosis_save), view -> save());
        DiagnosisUi.button(this, root, getString(R.string.diagnosis_photo_add), view -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, PHOTO);
        });
        renderPhotos();
        DiagnosisUi.button(this, root, getString(R.string.diagnosis_sound_title),
                view -> startActivity(new Intent(this, SoundGuideActivity.class)
                        .putExtra(DiagnosisUi.CASE, record.id)));
        dirty = state != null && state.getBoolean("dirty");
        TextWatcher changes = new TextWatcher()
        {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { dirty = true; }
            @Override public void afterTextChanged(Editable text) { }
        };
        for (EditText field : new EditText[]{custom, notes, model, year, engine}) field.addTextChangedListener(changes);
        confirmed.setOnCheckedChangeListener((button, checked) -> dirty = true);
        confirmed.setSaveEnabled(false);
        if (state != null && state.getBoolean("discard_dialog")) confirmDiscard();
    }

    private void addChoice(RadioGroup group, String id, String label)
    {
        AppCompatRadioButton radio = new AppCompatRadioButton(this);
        radio.setId(View.generateViewId());
        radio.setTag(id);
        radio.setText(label);
        radio.setTextSize(17);
        radio.setMinHeight(DiagnosisUi.dp(this, 56));
        group.addView(radio, new RadioGroup.LayoutParams(-1, -2));
        if (id.equals(selected)) group.check(radio.getId());
    }

    private void save()
    {
        if (selected == null || (selected.isEmpty() && custom.getText().toString().trim().isEmpty()))
        {
            Toast.makeText(this, R.string.diagnosis_final_required, Toast.LENGTH_LONG).show();
            return;
        }
        Integer parsedYear = null;
        try
        {
            if (!year.getText().toString().trim().isEmpty())
            {
                parsedYear = Integer.valueOf(year.getText().toString().trim());
                if (parsedYear <= 0) throw new NumberFormatException();
            }
        }
        catch (NumberFormatException e) { year.setError(getString(R.string.diagnosis_year_invalid)); return; }
        try
        {
            record = repository.get(record.id);
            if (record == null) throw new IllegalArgumentException("Missing case");
            String diagnosis = selected.isEmpty() ? custom.getText().toString().trim() : record.complaint.findCause(selected).name;
            if (confirmed.isChecked())
            {
                DiagnosisRepository.Check check = record.checks.get(selected);
                if (selected.isEmpty() ? notes.getText().toString().trim().isEmpty()
                        : check == null || check.status != com.fr3ts0n.ecu.gui.androbd.diagnosis.core.CheckStatus.CONFIRMED || check.evidence.isEmpty())
                {
                    Toast.makeText(this, R.string.diagnosis_final_evidence_missing, Toast.LENGTH_LONG).show();
                    return;
                }
            }
            repository.updateVehicle(record.id, model.getText().toString(), parsedYear, engine.getText().toString());
            repository.saveFinal(record.id, selected, diagnosis, confirmed.isChecked(), notes.getText().toString());
            record = repository.get(record.id);
            dirty = false;
            Toast.makeText(this, R.string.diagnosis_case_saved, Toast.LENGTH_LONG).show();
        }
        catch (RuntimeException e)
        {
            Log.e("Diagnosis", "Final save failed", e);
            Toast.makeText(this, R.string.diagnosis_case_save_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void renderPhotos()
    {
        for (int i = 0; i < record.photos.size(); i++)
        {
            DiagnosisRepository.Photo photo = record.photos.get(i);
            DiagnosisUi.button(this, root, getString(R.string.diagnosis_photo_label, i + 1), view -> openPhoto(photo));
        }
    }

    private void openPhoto(DiagnosisRepository.Photo photo)
    {
        try
        {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 2;
            Bitmap bitmap = BitmapFactory.decodeFile(media.resolve(photo.relativePath).getPath(), options);
            if (bitmap == null) throw new IOException("Unreadable photograph");
            ImageView image = new ImageView(this);
            image.setAdjustViewBounds(true);
            image.setImageBitmap(bitmap);
            image.setContentDescription(photo.caption.isEmpty() ? getString(R.string.diagnosis_photo_label, photo.id) : photo.caption);
            AlertDialog dialog = new AlertDialog.Builder(this).setView(image).setPositiveButton(R.string.diagnosis_photo_close, null).create();
            dialog.setOnDismissListener(ignored -> { image.setImageDrawable(null); bitmap.recycle(); });
            dialog.show();
        }
        catch (IOException | RuntimeException e)
        {
            Log.e("Diagnosis", "Photo display failed", e);
            Toast.makeText(this, R.string.diagnosis_photo_error, Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data)
    {
        super.onActivityResult(request, result, data);
        if (request != PHOTO || result != RESULT_OK || data == null || data.getData() == null) return;
        File photo = null;
        boolean attached = false;
        try
        {
            photo = media.importPhoto(data.getData());
            repository.addPhoto(record.id, media.relativePath(photo), "");
            attached = true;
            record = repository.get(record.id);
            final DiagnosisRepository.Photo savedPhoto = record.photos.get(record.photos.size() - 1);
            DiagnosisUi.button(this, root, getString(R.string.diagnosis_photo_label, record.photos.size()), view -> openPhoto(savedPhoto));
        }
        catch (IOException | RuntimeException e)
        {
            if (photo != null && !attached) photo.delete();
            Log.e("Diagnosis", "Photo import failed", e);
            Toast.makeText(this, R.string.diagnosis_photo_error, Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onSaveInstanceState(Bundle out)
    {
        super.onSaveInstanceState(out);
        if (custom == null) return;
        out.putString("selected", selected);
        out.putString("custom", custom.getText().toString());
        out.putString("notes", notes.getText().toString());
        out.putBoolean("confirmed", confirmed.isChecked());
        out.putString("model", model.getText().toString());
        out.putString("year", year.getText().toString());
        out.putString("engine", engine.getText().toString());
        out.putBoolean("dirty", dirty);
        out.putBoolean("discard_dialog", discardDialog != null && discardDialog.isShowing());
    }

    @Override public void onBackPressed()
    {
        if (dirty) confirmDiscard();
        else super.onBackPressed();
    }

    private void confirmDiscard()
    {
        if (discardDialog != null && discardDialog.isShowing()) return;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.diagnosis_case_discard_title)
                .setMessage(R.string.diagnosis_case_discard_message)
                .setPositiveButton(R.string.diagnosis_case_discard, (ignored, which) -> finish())
                .setNegativeButton(R.string.diagnosis_cancel, null).create();
        discardDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (discardDialog == dialog) discardDialog = null;
        });
        discardDialog.show();
    }

    @Override protected void onDestroy()
    {
        if (discardDialog != null) discardDialog.dismiss();
        super.onDestroy();
    }

    @Override public boolean onOptionsItemSelected(MenuItem item)
    {
        if (item.getItemId() == android.R.id.home) { onBackPressed(); return true; }
        return super.onOptionsItemSelected(item);
    }
}

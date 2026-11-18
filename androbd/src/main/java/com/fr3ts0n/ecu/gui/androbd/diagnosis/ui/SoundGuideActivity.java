/* (C) 2026 Bengkel Scan contributors. GPL-2.0-or-later. */
package com.fr3ts0n.ecu.gui.androbd.diagnosis.ui;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import com.fr3ts0n.ecu.gui.androbd.ModernUiUtils;
import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.SettingsActivity;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisKnowledgeLoader;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisMediaStore;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisRepository;
import com.fr3ts0n.ecu.gui.androbd.workshop.ui.WorkshopUi;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Offline sound catalogue with explicitly unverified imports and case-backed workshop recordings. */
public class SoundGuideActivity extends AppCompatActivity implements DiagnosisAudioPlayer.Listener {
    public static final String EXTRA_SOUND_ID = "diagnosis.sound_id";
    public static final String EXTRA_CASE_ID = "diagnosis.case_id";
    private static final int MAX_RECORDING_MS = 15000;
    private static final String STATE_IMPORT_SOUND = "sound.import_sound";
    private static final String STATE_IMPORT_LABEL = "sound.import_label";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable recordingLimit = () -> finishRecording(true);

    private DiagnosisMediaStore media;
    private DiagnosisAudioPlayer player;
    private DiagnosisKnowledge knowledge;
    private DiagnosisKnowledge.Sound selected;
    private Spinner selector;
    private LinearLayout clipRows, relatedRows;
    private TextView notice, status, description, when;
    private MaterialButton importButton, recordButton, activePlayButton;
    private MediaRecorder recorder;
    private File recordingFile;
    private RecordingRequest pendingRecording, activeRecording;
    private boolean foreground, destroyed, busy;
    private int clipGeneration;
    private String importSoundId, importLabel;
    private long preferredCaseId;
    private final ActivityResultLauncher<String[]> documentPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::importSelectedAudio);
    private final ActivityResultLauncher<String> microphonePermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted) {
                    pendingRecording = null;
                    showNotice(R.string.diagnosis_sound_permission_denied);
                    updateControls();
                } else if (pendingRecording != null && foreground) {
                    verifyAndStartRecording();
                } else {
                    pendingRecording = null;
                    updateControls();
                }
            });

    @Override protected void onCreate(Bundle state) {
        SettingsActivity.applyLocale(this);
        super.onCreate(state);
        setContentView(R.layout.diagnosis_sound_screen);
        ModernUiUtils.applyScreenInsets(this);
        WorkshopUi.prepare(this, getString(R.string.diagnosis_sound_title));
        media = new DiagnosisMediaStore(this);
        player = new DiagnosisAudioPlayer(this, media, this);
        selector = findViewById(R.id.diagnosis_sound_selector);
        clipRows = findViewById(R.id.diagnosis_sound_clips);
        relatedRows = findViewById(R.id.diagnosis_sound_related);
        notice = findViewById(R.id.diagnosis_sound_notice);
        status = findViewById(R.id.diagnosis_sound_status);
        description = findViewById(R.id.diagnosis_sound_description);
        when = findViewById(R.id.diagnosis_sound_when);
        importButton = findViewById(R.id.diagnosis_sound_import);
        recordButton = findViewById(R.id.diagnosis_sound_record);
        importButton.setOnClickListener(view -> showImportDialog());
        recordButton.setOnClickListener(view -> {
            if (recorder != null) finishRecording(true);
            else loadConfirmedCases();
        });
        preferredCaseId = state == null ? getIntent().getLongExtra(EXTRA_CASE_ID, 0)
                : state.getLong(EXTRA_CASE_ID, 0);
        if (state != null) {
            importSoundId = state.getString(STATE_IMPORT_SOUND);
            importLabel = state.getString(STATE_IMPORT_LABEL);
        }
        String initial = state == null ? getIntent().getStringExtra(EXTRA_SOUND_ID)
                : state.getString(EXTRA_SOUND_ID);
        loadKnowledge(initial);
    }

    private void loadKnowledge(String initialId) {
        worker.execute(() -> {
            try {
                DiagnosisKnowledgeLoader loader = new DiagnosisKnowledgeLoader(getApplicationContext());
                DiagnosisKnowledge loaded = loader.load();
                String warning = loader.warning();
                post(() -> {
                    knowledge = loaded;
                    if (warning != null && !warning.isEmpty()) notice.setText(warning);
                    List<String> titles = new ArrayList<>();
                    int initialPosition = 0;
                    for (int index = 0; index < knowledge.sounds.size(); index++) {
                        DiagnosisKnowledge.Sound sound = knowledge.sounds.get(index);
                        titles.add(sound.title);
                        if (sound.id.equals(initialId)) initialPosition = index;
                    }
                    selector.setAdapter(adapter(titles));
                    selector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                        @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                            selectSound(knowledge.sounds.get(position));
                        }
                        @Override public void onNothingSelected(AdapterView<?> parent) { }
                    });
                    if (titles.isEmpty()) status.setText(R.string.diagnosis_sound_empty);
                    else {
                        selector.setSelection(initialPosition);
                        selectSound(knowledge.sounds.get(initialPosition));
                    }
                    updateControls();
                });
            } catch (IOException | RuntimeException error) {
                post(() -> status.setText(R.string.diagnosis_sound_load_error));
            }
        });
    }

    private void selectSound(DiagnosisKnowledge.Sound sound) {
        if (selected != null && selected.id.equals(sound.id)) return;
        player.release();
        selected = sound;
        description.setText(sound.description);
        when.setText(getString(R.string.diagnosis_sound_when, sound.when));
        relatedRows.removeAllViews();
        for (DiagnosisKnowledge.Complaint complaint : knowledge.complaints) {
            for (DiagnosisKnowledge.Cause cause : complaint.causes) {
                if (!cause.soundIds.contains(sound.id)) continue;
                MaterialButton button = button(getString(R.string.diagnosis_sound_cause, cause.name, complaint.title));
                button.setOnClickListener(view -> startActivity(new Intent(this, DiagnosisStartActivity.class)
                        .putExtra("diagnosis.complaint_id", complaint.id)
                        .putExtra("diagnosis.cause_id", cause.id)));
                relatedRows.addView(button);
            }
        }
        if (relatedRows.getChildCount() == 0) addText(relatedRows, getString(R.string.diagnosis_sound_no_related));
        refreshClips();
        updateControls();
    }

    private void refreshClips() {
        if (selected == null) return;
        DiagnosisKnowledge.Sound sound = selected;
        int generation = ++clipGeneration;
        worker.execute(() -> {
            try {
                List<DiagnosisMediaStore.Clip> clips = media.clips(sound.id);
                post(() -> {
                    if (generation != clipGeneration || selected == null || !sound.id.equals(selected.id)) return;
                    player.release();
                    activePlayButton = null;
                    clipRows.removeAllViews();
                    if (sound.audioFile != null && !sound.audioFile.isEmpty()) {
                        addAudioRow(getString(R.string.diagnosis_sound_reference), sound.audioFile, true);
                    }
                    for (DiagnosisMediaStore.Clip clip : clips) {
                        String label = clip.caseId > 0
                                ? getString(R.string.diagnosis_sound_confirmed, clip.label, clip.caseId, clip.causeName)
                                : getString(R.string.diagnosis_sound_unverified, clip.label);
                        addAudioRow(label, clip.relativePath, false);
                    }
                    if (clips.isEmpty()) addText(clipRows, getString(sound.audioFile == null || sound.audioFile.isEmpty()
                            ? R.string.diagnosis_sound_missing : R.string.diagnosis_sound_no_clips));
                    updateControls();
                });
            } catch (IOException | RuntimeException error) {
                post(() -> {
                    if (generation != clipGeneration) return;
                    player.release();
                    activePlayButton = null;
                    clipRows.removeAllViews();
                    addText(clipRows, getString(R.string.diagnosis_sound_clips_error));
                });
            }
        });
    }

    private void addAudioRow(String label, String path, boolean bundled) {
        addText(clipRows, label);
        MaterialButton play = button(getString(R.string.diagnosis_sound_play));
        play.setContentDescription(getString(R.string.diagnosis_sound_play) + ": " + label);
        play.setTag(label);
        play.setOnClickListener(view -> {
            if (busy || recorder != null || pendingRecording != null) return;
            if (activePlayButton != play) {
                player.release();
                activePlayButton = play;
            }
            if (bundled) player.toggleReference(path);
            else player.togglePrivate(path);
        });
        clipRows.addView(play);
    }

    private void showImportDialog() {
        if (selected == null || busy || recorder != null || pendingRecording != null) return;
        String soundId = selected.id;
        LinearLayout fields = dialogFields(getString(R.string.diagnosis_sound_import_notice));
        EditText label = labelField(fields);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.diagnosis_sound_import).setView(fields)
                .setNegativeButton(R.string.diagnosis_sound_cancel, null)
                .setPositiveButton(R.string.diagnosis_sound_pick, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String text = validLabel(label);
            if (text == null) return;
            importSoundId = soundId;
            importLabel = text;
            dialog.dismiss();
            try {
                player.release();
                documentPicker.launch(new String[]{"audio/*"});
            } catch (ActivityNotFoundException error) {
                importSoundId = null;
                importLabel = null;
                showNotice(R.string.diagnosis_sound_picker_missing);
            }
        }));
        dialog.show();
    }

    private void importSelectedAudio(Uri uri) {
        String soundId = importSoundId;
        String label = importLabel;
        importSoundId = null;
        importLabel = null;
        if (uri == null) return;
        if (soundId == null || label == null) {
            showNotice(R.string.diagnosis_sound_import_error);
            return;
        }
        setBusy(true);
        worker.execute(() -> {
            File imported = null;
            try {
                imported = media.importAudio(uri);
                media.saveClip(new DiagnosisMediaStore.Clip(media.relativePath(imported), soundId, label,
                        "", "", 0, System.currentTimeMillis()));
                post(() -> {
                    setBusy(false);
                    showNotice(R.string.diagnosis_sound_imported);
                    refreshClips();
                });
            } catch (IOException | RuntimeException error) {
                boolean cleaned = discard(imported);
                post(() -> {
                    setBusy(false);
                    showNotice(cleaned ? R.string.diagnosis_sound_import_error : R.string.diagnosis_sound_cleanup_error);
                });
            }
        });
    }

    private void loadConfirmedCases() {
        if (selected == null || busy || pendingRecording != null) return;
        setBusy(true);
        worker.execute(() -> {
            try {
                List<DiagnosisRepository.CaseRecord> cases = new DiagnosisRepository(getApplicationContext()).confirmedCases();
                post(() -> {
                    setBusy(false);
                    if (!foreground) return;
                    if (cases.isEmpty()) showNotice(R.string.diagnosis_sound_no_cases);
                    else showRecordingDialog(cases);
                });
            } catch (RuntimeException error) {
                post(() -> {
                    setBusy(false);
                    showNotice(R.string.diagnosis_sound_case_error);
                });
            }
        });
    }

    private void showRecordingDialog(List<DiagnosisRepository.CaseRecord> cases) {
        if (selected == null) return;
        String soundId = selected.id;
        LinearLayout fields = dialogFields(getString(R.string.diagnosis_sound_record_notice));
        EditText label = labelField(fields);
        TextView caseLabel = addText(fields, getString(R.string.diagnosis_sound_case));
        Spinner caseSelector = new Spinner(this);
        caseSelector.setId(ViewCompat.generateViewId());
        caseLabel.setLabelFor(caseSelector.getId());
        List<String> names = new ArrayList<>();
        int preferred = 0;
        for (int index = 0; index < cases.size(); index++) {
            DiagnosisRepository.CaseRecord record = cases.get(index);
            names.add(getString(R.string.diagnosis_sound_case_option, record.id, record.finalDiagnosis));
            if (record.id == preferredCaseId) preferred = index;
        }
        caseSelector.setAdapter(adapter(names));
        caseSelector.setSelection(preferred);
        fields.addView(caseSelector);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.diagnosis_sound_record).setView(fields)
                .setNegativeButton(R.string.diagnosis_sound_cancel, null)
                .setPositiveButton(R.string.diagnosis_sound_start, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String text = validLabel(label);
            if (text == null) return;
            preferredCaseId = cases.get(caseSelector.getSelectedItemPosition()).id;
            pendingRecording = new RecordingRequest(soundId, text, preferredCaseId, "", "");
            dialog.dismiss();
            player.release();
            updateControls();
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                verifyAndStartRecording();
            } else {
                microphonePermission.launch(Manifest.permission.RECORD_AUDIO);
            }
        }));
        dialog.show();
    }

    private void verifyAndStartRecording() {
        RecordingRequest request = pendingRecording;
        if (request == null || !foreground) return;
        setBusy(true);
        worker.execute(() -> {
            try {
                DiagnosisRepository.CaseRecord record = new DiagnosisRepository(getApplicationContext()).get(request.caseId);
                if (record == null || !record.mechanicConfirmed || record.finalDiagnosis == null
                        || record.finalDiagnosis.trim().isEmpty()) throw new IOException("Kasus belum dikonfirmasi.");
                RecordingRequest verified = new RecordingRequest(request.soundId, request.label, request.caseId,
                        record.finalCauseId == null ? "" : record.finalCauseId, record.finalDiagnosis);
                post(() -> {
                    setBusy(false);
                    if (!foreground || pendingRecording != request) return;
                    pendingRecording = null;
                    startRecording(verified);
                });
            } catch (IOException | RuntimeException error) {
                post(() -> {
                    setBusy(false);
                    pendingRecording = null;
                    showNotice(R.string.diagnosis_sound_case_error);
                    updateControls();
                });
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void startRecording(RecordingRequest request) {
        if (!foreground || ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            showNotice(R.string.diagnosis_sound_permission_denied);
            updateControls();
            return;
        }
        player.release();
        MediaRecorder next;
        try {
            next = new MediaRecorder();
        } catch (RuntimeException error) {
            showNotice(R.string.diagnosis_sound_record_error);
            updateControls();
            return;
        }
        recorder = next;
        activeRecording = request;
        try {
            recordingFile = media.createRecordingFile();
            next.setAudioSource(MediaRecorder.AudioSource.MIC);
            next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            next.setAudioSamplingRate(44100);
            next.setAudioEncodingBitRate(96000);
            next.setMaxDuration(MAX_RECORDING_MS);
            next.setOutputFile(recordingFile.getPath());
            next.setOnInfoListener((source, what, extra) -> {
                if (source == recorder && what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) finishRecording(true);
            });
            next.setOnErrorListener((source, what, extra) -> {
                if (source == recorder) finishRecording(false);
            });
            next.prepare();
            next.start();
            status.setText(R.string.diagnosis_sound_recording);
            handler.postDelayed(recordingLimit, MAX_RECORDING_MS);
            updateControls();
        } catch (IOException | RuntimeException error) {
            finishRecording(false);
        }
    }

    private void finishRecording(boolean save) {
        MediaRecorder previous = recorder;
        if (previous == null) return;
        recorder = null;
        handler.removeCallbacks(recordingLimit);
        File file = recordingFile;
        RecordingRequest request = activeRecording;
        recordingFile = null;
        activeRecording = null;
        boolean stopped = false;
        try {
            previous.setOnInfoListener(null);
            previous.setOnErrorListener(null);
            previous.stop();
            stopped = true;
        } catch (RuntimeException error) {
            save = false;
        } finally {
            previous.release();
        }
        if (!save || !stopped || file == null || request == null) {
            boolean cleaned = discard(file);
            showNotice(cleaned ? R.string.diagnosis_sound_record_error : R.string.diagnosis_sound_cleanup_error);
            status.setText(R.string.diagnosis_sound_ready);
            updateControls();
            return;
        }
        setBusy(true);
        worker.execute(() -> {
            try {
                DiagnosisRepository.CaseRecord record = new DiagnosisRepository(getApplicationContext()).get(request.caseId);
                if (record == null || !record.mechanicConfirmed
                        || !request.causeId.equals(record.finalCauseId == null ? "" : record.finalCauseId)
                        || !request.causeName.equals(record.finalDiagnosis)) {
                    throw new IOException("Kasus terkait belum dikonfirmasi atau diagnosis akhirnya berubah.");
                }
                media.saveClip(new DiagnosisMediaStore.Clip(media.relativePath(file), request.soundId, request.label,
                        request.causeId, request.causeName, request.caseId, System.currentTimeMillis()));
                post(() -> {
                    setBusy(false);
                    showNotice(R.string.diagnosis_sound_saved);
                    refreshClips();
                });
            } catch (IOException | RuntimeException error) {
                boolean cleaned = discard(file);
                post(() -> {
                    setBusy(false);
                    showNotice(cleaned ? R.string.diagnosis_sound_save_error : R.string.diagnosis_sound_cleanup_error);
                });
            }
        });
    }

    private LinearLayout dialogFields(String explanation) {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        fields.setPadding(padding, 0, padding, 0);
        addText(fields, explanation);
        return fields;
    }

    private EditText labelField(LinearLayout parent) {
        TextView title = addText(parent, getString(R.string.diagnosis_sound_label));
        EditText field = new EditText(this);
        field.setId(ViewCompat.generateViewId());
        title.setLabelFor(field.getId());
        field.setHint(R.string.diagnosis_sound_label);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setSingleLine(true);
        parent.addView(field);
        return field;
    }

    private String validLabel(EditText label) {
        String value = label.getText().toString().trim();
        if (value.isEmpty() || value.length() > 200) {
            label.setError(getString(R.string.diagnosis_sound_label_required));
            return null;
        }
        return value;
    }

    private ArrayAdapter<String> adapter(List<String> entries) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, entries);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private MaterialButton button(String text) {
        MaterialButton button = new MaterialButton(this);
        button.setText(text);
        button.setMinHeight((int) (56 * getResources().getDisplayMetrics().density));
        button.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return button;
    }

    private TextView addText(LinearLayout parent, String text) {
        TextView view = new TextView(this);
        view.setTextAppearance(R.style.WorkshopHomeHelper);
        view.setText(text);
        view.setTextIsSelectable(true);
        parent.addView(view);
        return view;
    }

    private void showNotice(int message) {
        notice.setText(message);
    }

    private void setBusy(boolean value) {
        busy = value;
        if (value) status.setText(R.string.diagnosis_sound_busy);
        else if (recorder == null) status.setText(R.string.diagnosis_sound_ready);
        updateControls();
    }

    private void updateControls() {
        boolean ready = selected != null && !busy && recorder == null && pendingRecording == null;
        selector.setEnabled(!busy && recorder == null && pendingRecording == null);
        importButton.setEnabled(ready);
        recordButton.setEnabled(recorder != null || ready);
        recordButton.setText(recorder == null ? R.string.diagnosis_sound_record : R.string.diagnosis_sound_stop);
        for (int index = 0; index < clipRows.getChildCount(); index++) {
            View child = clipRows.getChildAt(index);
            if (child instanceof MaterialButton) child.setEnabled(ready);
        }
        for (int index = 0; index < relatedRows.getChildCount(); index++) relatedRows.getChildAt(index).setEnabled(ready);
    }

    @Override public void onStateChanged(DiagnosisAudioPlayer.State state) {
        if (status == null || busy || recorder != null) return;
        int message;
        switch (state) {
            case PREPARING: message = R.string.diagnosis_sound_preparing; break;
            case PLAYING: message = R.string.diagnosis_sound_playing; break;
            case PAUSED: message = R.string.diagnosis_sound_paused; break;
            default: message = R.string.diagnosis_sound_ready;
        }
        status.setText(message);
        if (activePlayButton != null) {
            int action = state == DiagnosisAudioPlayer.State.PLAYING ? R.string.diagnosis_sound_pause
                    : state == DiagnosisAudioPlayer.State.PAUSED ? R.string.diagnosis_sound_resume
                    : R.string.diagnosis_sound_play;
            activePlayButton.setText(action);
            activePlayButton.setContentDescription(getString(action) + ": " + activePlayButton.getTag());
        }
    }

    @Override public void onError() {
        showNotice(R.string.diagnosis_sound_playback_error);
    }

    private void post(Runnable action) {
        handler.post(() -> {
            if (!destroyed) action.run();
        });
    }

    private static boolean discard(File file) {
        return file == null || !file.exists() || file.delete();
    }

    @Override protected void onStart() {
        super.onStart();
        foreground = true;
    }

    @Override protected void onStop() {
        foreground = false;
        pendingRecording = null;
        finishRecording(true);
        player.release();
        updateControls();
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (selected != null) state.putString(EXTRA_SOUND_ID, selected.id);
        state.putLong(EXTRA_CASE_ID, preferredCaseId);
        state.putString(STATE_IMPORT_SOUND, importSoundId);
        state.putString(STATE_IMPORT_LABEL, importLabel);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacks(recordingLimit);
        player.release();
        worker.shutdown();
        super.onDestroy();
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private static final class RecordingRequest {
        final String soundId, label, causeId, causeName;
        final long caseId;

        RecordingRequest(String soundId, String label, long caseId, String causeId, String causeName) {
            this.soundId = soundId;
            this.label = label;
            this.caseId = caseId;
            this.causeId = causeId;
            this.causeName = causeName;
        }
    }
}

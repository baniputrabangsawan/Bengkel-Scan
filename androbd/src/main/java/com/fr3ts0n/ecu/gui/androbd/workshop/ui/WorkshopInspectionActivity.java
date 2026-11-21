/*
 * (C) Copyright 2026 by the Bengkel Scan contributors.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */
package com.fr3ts0n.ecu.gui.androbd.workshop.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.fr3ts0n.ecu.gui.androbd.ModernUiUtils;
import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.SettingsActivity;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisMediaStore;
import com.fr3ts0n.ecu.gui.androbd.workshop.InspectionItem;
import com.fr3ts0n.ecu.gui.androbd.workshop.InspectionRepository;
import com.fr3ts0n.ecu.gui.androbd.workshop.InspectionState;
import com.fr3ts0n.ecu.gui.androbd.workshop.PhotoOwner;
import com.fr3ts0n.ecu.gui.androbd.workshop.PhotoRepository;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopRepository;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Inspected parts of one service: name, condition, note and photos.
 *
 * <p>A photo is taken by the camera app and copied into private storage before
 * it is referenced, so a later deletion from the gallery cannot break the PDF.
 * Only files inside the app's own folders are ever exposed to the camera.</p>
 */
public class WorkshopInspectionActivity extends AppCompatActivity
{
	private static final String TAG = "WorkshopInspection";
	private static final String EXTRA_SERVICE = WorkshopUi.EXTRA_SERVICE_ID;

	private WorkshopRepository repository;
	private InspectionRepository inspections;
	private PhotoRepository photos;
	private DiagnosisMediaStore media;
	private WorkshopRowAdapter adapter;
	private TextView empty;
	private long serviceId;
	/** Item whose photo button was pressed, restored after the camera returns. */
	private long pendingPhotoItem = -1;
	private String pendingPhotoPath;

	private final VoiceNoteRecorder voice = new VoiceNoteRecorder();
	private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
	private long pendingVoiceItem = -1;
	private com.fr3ts0n.ecu.gui.androbd.diagnosis.ui.DiagnosisAudioPlayer player;

	private final androidx.activity.result.ActivityResultLauncher<Uri> takePicture =
			registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts
					.TakePicture(), saved ->
			{
				if (saved) attachPendingPhoto(Uri.fromFile(new File(pendingPhotoPath)));
				else clearPendingPhoto();
			});

	private final androidx.activity.result.ActivityResultLauncher<String[]> pickPicture =
			registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts
					.OpenDocument(), uri ->
			{
				if (uri != null) attachPendingPhoto(uri);
			});

	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		SettingsActivity.applyLocale(this);
		super.onCreate(savedInstanceState);
		serviceId = getIntent().getLongExtra(EXTRA_SERVICE, -1);
		repository = WorkshopUi.repository(this);
		if (repository.findService(serviceId) == null)
		{
			finish();
			return;
		}
		setContentView(R.layout.workshop_inspection);
		ModernUiUtils.applyScreenInsets(this);
		WorkshopUi.prepare(this, getString(R.string.workshop_inspection_title));
		inspections = new InspectionRepository(repository.database());
		photos = new PhotoRepository(repository.database());
		media = new DiagnosisMediaStore(this);
		player = new com.fr3ts0n.ecu.gui.androbd.diagnosis.ui.DiagnosisAudioPlayer(this, media,
				new com.fr3ts0n.ecu.gui.androbd.diagnosis.ui.DiagnosisAudioPlayer.Listener()
				{
					@Override
					public void onStateChanged(
							com.fr3ts0n.ecu.gui.androbd.diagnosis.ui.DiagnosisAudioPlayer.State state)
					{
						if (state == com.fr3ts0n.ecu.gui.androbd.diagnosis.ui
								.DiagnosisAudioPlayer.State.IDLE)
						{
							// Playback finished on its own.
						}
					}

					@Override
					public void onError()
					{
						Toast.makeText(WorkshopInspectionActivity.this,
								R.string.workshop_inspection_voice_missing, Toast.LENGTH_SHORT).show();
					}
				});

		empty = findViewById(R.id.workshop_empty);
		ListView list = findViewById(R.id.workshop_list);
		adapter = new WorkshopRowAdapter(this);
		list.setAdapter(adapter);
		list.setOnItemClickListener((parent, view, position, id) ->
				openItem(adapter.getItem(position).id));
		list.setOnItemLongClickListener((parent, view, position, id) ->
		{
			confirmDelete(adapter.getItem(position));
			return true;
		});
		findViewById(R.id.workshop_add).setOnClickListener(view -> addItem());
	}

	@Override
	protected void onResume()
	{
		super.onResume();
		if (repository != null) load();
	}

	@Override
	protected void onStop()
	{
		super.onStop();
		if (voice.recording()) finishVoice(false);
		player.release();
	}

	private void load()
	{
		List<InspectionItem> items = inspections.forService(serviceId);
		List<WorkshopRow> rows = new ArrayList<>(items.size());
		for (InspectionItem item : items)
		{
			int photoCount = photos.forOwner(PhotoOwner.INSPECTION, item.id).size();
			StringBuilder subtitle = new StringBuilder(getString(stateLabel(item.state)));
			if (photoCount > 0) subtitle.append(" · ")
					.append(getString(R.string.workshop_inspection_photo_count, photoCount));
			if (!item.note.isEmpty()) subtitle.append(" · ").append(item.note);
			rows.add(new WorkshopRow(item.id, item.name, subtitle.toString(), null));
		}
		adapter.submit(rows);
		empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
	}

	private static int stateLabel(InspectionState state)
	{
		switch (state)
		{
			case ATTENTION:
				return R.string.workshop_inspection_state_attention;
			case BROKEN:
				return R.string.workshop_inspection_state_broken;
			case GOOD:
			default:
				return R.string.workshop_inspection_state_good;
		}
	}

	private void addItem()
	{
		View form = getLayoutInflater().inflate(R.layout.dialog_inspection, null);
		TextInputEditText name = form.findViewById(R.id.workshop_inspection_name);
		TextInputEditText note = form.findViewById(R.id.workshop_inspection_note);
		Spinner state = form.findViewById(R.id.workshop_inspection_state);
		state.setAdapter(states());

		AlertDialog dialog = new AlertDialog.Builder(this)
				.setTitle(R.string.workshop_inspection_add)
				.setView(form)
				.setNegativeButton(R.string.workshop_action_cancel, null)
				.setPositiveButton(R.string.workshop_action_save, null)
				.create();
		dialog.setOnShowListener(ignored ->
				dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view ->
				{
					String part = textOf(name);
					if (part.isEmpty())
					{
						name.setError(getString(R.string.workshop_inspection_error_name));
						name.requestFocus();
						return;
					}
					long id = inspections.create(serviceId, part, state(state), textOf(note));
					WorkshopUi.hideKeyboard(this);
					dialog.dismiss();
					load();
					openItem(id);
				}));
		dialog.show();
	}

	private void openItem(long itemId)
	{
		InspectionItem item = inspections.find(itemId);
		if (item == null)
		{
			load();
			return;
		}
		View form = getLayoutInflater().inflate(R.layout.dialog_inspection, null);
		TextInputEditText name = form.findViewById(R.id.workshop_inspection_name);
		TextInputEditText note = form.findViewById(R.id.workshop_inspection_note);
		Spinner state = form.findViewById(R.id.workshop_inspection_state);
		state.setAdapter(states());
		name.setText(item.name);
		note.setText(item.note);
		state.setSelection(item.state.ordinal());

		int photoCount = photos.forOwner(PhotoOwner.INSPECTION, item.id).size();
		AlertDialog dialog = new AlertDialog.Builder(this)
				.setTitle(R.string.workshop_inspection_add)
				.setView(form)
				.setNeutralButton(photoCount > 0
						? getString(R.string.workshop_inspection_photo_count, photoCount)
						: getString(R.string.workshop_inspection_photo_add), null)
				.setNegativeButton(R.string.workshop_action_cancel, null)
				.setPositiveButton(R.string.workshop_action_save, null)
				.create();
		dialog.setOnShowListener(ignored ->
		{
			dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view ->
			{
				dialog.dismiss();
				choosePhotoSource(itemId);
			});
			dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText(
					R.string.workshop_inspection_voice_button);
			dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view ->
			{
				dialog.dismiss();
				voiceActions(itemId);
			});
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view ->
			{
				String part = textOf(name);
				if (part.isEmpty())
				{
					name.setError(getString(R.string.workshop_inspection_error_name));
					name.requestFocus();
					return;
				}
				inspections.update(itemId, part, state(state), textOf(note));
				WorkshopUi.hideKeyboard(this);
				dialog.dismiss();
				load();
				Toast.makeText(this, R.string.workshop_inspection_saved, Toast.LENGTH_SHORT).show();
			});
		});
		dialog.show();
	}

	/** Voice note for an inspected part: record, play back, or delete. */
	void voiceActions(final long itemId)
	{
		InspectionItem item = inspections.find(itemId);
		if (item == null) return;
		boolean hasNote = item.voiceNote();
		String[] labels = new String[]{getString(R.string.workshop_inspection_voice_record),
				hasNote ? getString(R.string.workshop_inspection_voice_play)
						: getString(R.string.workshop_inspection_voice_missing),
				getString(R.string.workshop_inspection_voice_delete)};
		new AlertDialog.Builder(this)
				.setTitle(R.string.workshop_inspection_voice_title)
				.setItems(labels, (dialog, which) ->
				{
					if (which == 0) recordVoice(itemId);
					else if (which == 1 && hasNote) playVoice(item);
					else if (which == 2 && hasNote) clearVoice(itemId);
				})
				.setNegativeButton(R.string.workshop_action_cancel, null)
				.show();
	}

	private void recordVoice(final long itemId)
	{
		if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
				!= android.content.pm.PackageManager.PERMISSION_GRANTED)
		{
			requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 1);
			pendingVoiceItem = itemId;
			return;
		}
		try
		{
			File target = media.createRecordingFile();
			pendingVoiceItem = itemId;
			voice.start(target, () -> finishVoice(true), () -> finishVoice(false));
			handler.postDelayed(voiceLimit, VoiceNoteRecorder.MAX_DURATION_MS);
			Toast.makeText(this, R.string.workshop_inspection_voice_recording, Toast.LENGTH_SHORT)
					.show();
		}
		catch (IOException error)
		{
			Log.e(TAG, "Voice note failed to start", error);
			Toast.makeText(this, R.string.workshop_inspection_photo_failed, Toast.LENGTH_LONG).show();
		}
	}

	private final Runnable voiceLimit = () -> finishVoice(true);

	private void finishVoice(boolean keep)
	{
		handler.removeCallbacks(voiceLimit);
		long itemId = pendingVoiceItem;
		if (!voice.recording())
		{
			pendingVoiceItem = -1;
			return;
		}
		File written = keep ? voice.stopKeeping() : null;
		if (!keep) voice.stopAndDiscard();
		pendingVoiceItem = -1;
		if (written == null || itemId < 0)
		{
			if (written != null) written.delete();
			return;
		}
		try
		{
			inspections.setVoicePath(itemId, media.relativePath(written));
			load();
			Toast.makeText(this, R.string.workshop_inspection_voice_saved, Toast.LENGTH_SHORT).show();
		}
		catch (IOException error)
		{
			Log.e(TAG, "Voice note could not be stored", error);
			written.delete();
		}
	}

	private void playVoice(InspectionItem item)
	{
		// The player reports failures through its listener; a missing file simply
		// produces onError there instead of an exception here.
		player.togglePrivate(item.voicePath);
	}

	private void clearVoice(long itemId)
	{
		InspectionItem item = inspections.find(itemId);
		if (item != null && item.voiceNote())
		{
			try
			{
				File stored = media.resolve(item.voicePath);
				if (stored.isFile()) stored.delete();
			}
			catch (IOException ignored)
			{
				// The reference is cleared below regardless; a stray file is harmless.
			}
		}
		inspections.setVoicePath(itemId, "");
		load();
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results)
	{
		super.onRequestPermissionsResult(requestCode, permissions, results);
		if (requestCode == 1 && results.length > 0
				&& results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
				&& pendingVoiceItem > 0)
		{
			long item = pendingVoiceItem;
			pendingVoiceItem = -1;
			recordVoice(item);
		}
	}

	/** Camera first, gallery second: both are one tap from the item dialog. */
	private void choosePhotoSource(long itemId)
	{
		new AlertDialog.Builder(this)
				.setTitle(R.string.workshop_inspection_menu)
				.setPositiveButton(R.string.workshop_inspection_photo_add, (dialog, which) ->
						capture(itemId))
				.setNegativeButton(R.string.workshop_inspection_photo_pick, (dialog, which) ->
						pick(itemId))
				.show();
	}

	private void capture(long itemId)
	{
		if (getPackageManager().resolveActivity(
				new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE), 0) == null)
		{
			Toast.makeText(this, R.string.workshop_inspection_camera_missing, Toast.LENGTH_LONG).show();
			return;
		}
		try
		{
			File folder = new File(getCacheDir(), "photos");
			if (!folder.isDirectory() && !folder.mkdirs())
			{
				throw new IOException("photo cache");
			}
			File target = new File(folder, "capture-" + System.currentTimeMillis() + ".jpg");
			pendingPhotoItem = itemId;
			pendingPhotoPath = target.getAbsolutePath();
			Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", target);
			takePicture.launch(uri);
		}
		catch (IOException | IllegalArgumentException error)
		{
			Log.e(TAG, "Camera target failed", error);
			clearPendingPhoto();
			Toast.makeText(this, R.string.workshop_inspection_photo_failed, Toast.LENGTH_LONG).show();
		}
	}

	private void pick(long itemId)
	{
		pendingPhotoItem = itemId;
		pendingPhotoPath = null;
		pickPicture.launch(new String[]{"image/*"});
	}

	/**
	 * Copy the photo into private storage, then reference it.
	 *
	 * <p>Runs on a worker thread: the import re-encodes a full camera image (up to 20 MB), and
	 * doing that in the activity-result callback froze the screen for seconds.</p>
	 */
	private void attachPendingPhoto(Uri source)
	{
		final long itemId = pendingPhotoItem;
		if (itemId < 0)
		{
			clearPendingPhoto();
			return;
		}
		// Capture the pending file now: a second pick before this worker finishes replaces the
		// field, and the cleanup below must delete the file this job used, not the next one's.
		final String pendingPath = pendingPhotoPath;
		final Uri pendingUri = source;
		new Thread(() ->
		{
			try
			{
				File stored = media.importPhoto(pendingUri);
				photos.add(PhotoOwner.INSPECTION, itemId, media.relativePath(stored), "");
				runOnUiThread(() ->
				{
					if (isFinishing() || isDestroyed()) return;
					load();
				});
			}
			catch (IOException | RuntimeException error)
			{
				Log.e(TAG, "Photo import failed", error);
				runOnUiThread(() ->
				{
					if (isFinishing() || isDestroyed()) return;
					Toast.makeText(this, R.string.workshop_inspection_photo_unsafe,
							Toast.LENGTH_LONG).show();
				});
			}
			finally
			{
				if (pendingPath != null)
				{
					File pending = new File(pendingPath);
					if (pending.exists()) pending.delete();
				}
				runOnUiThread(this::clearPendingPhoto);
			}
		}, "inspection-photo").start();
	}

	private void clearPendingPhoto()
	{
		if (pendingPhotoPath != null)
		{
			File pending = new File(pendingPhotoPath);
			if (pending.exists()) pending.delete();
		}
		pendingPhotoPath = null;
		pendingPhotoItem = -1;
	}

	private void confirmDelete(WorkshopRow row)
	{
		new AlertDialog.Builder(this)
				.setTitle(R.string.workshop_inspection_delete_title)
				.setMessage(getString(R.string.workshop_inspection_delete_message, row.title))
				.setNegativeButton(R.string.workshop_action_cancel, null)
				.setPositiveButton(R.string.workshop_action_delete, (dialog, which) ->
				{
					inspections.delete(row.id);
					load();
				})
				.show();
	}

	private ArrayAdapter<String> states()
	{
		ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
				android.R.layout.simple_spinner_item,
				new String[]{getString(R.string.workshop_inspection_state_good),
						getString(R.string.workshop_inspection_state_attention),
						getString(R.string.workshop_inspection_state_broken)});
		adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		return adapter;
	}

	private static InspectionState state(Spinner spinner)
	{
		int position = spinner.getSelectedItemPosition();
		return position == 1 ? InspectionState.ATTENTION
				: position == 2 ? InspectionState.BROKEN : InspectionState.GOOD;
	}

	private static String textOf(TextInputEditText field)
	{
		return field.getText() == null ? "" : field.getText().toString().trim();
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item)
	{
		if (item.getItemId() == android.R.id.home)
		{
			finish();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}
}

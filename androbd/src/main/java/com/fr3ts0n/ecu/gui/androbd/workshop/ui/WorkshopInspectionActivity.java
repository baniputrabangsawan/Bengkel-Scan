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

	/** Copy the photo into private storage, then reference it. */
	private void attachPendingPhoto(Uri source)
	{
		long itemId = pendingPhotoItem;
		if (itemId < 0)
		{
			clearPendingPhoto();
			return;
		}
		try
		{
			File stored = media.importPhoto(source);
			photos.add(PhotoOwner.INSPECTION, itemId, media.relativePath(stored), "");
			load();
		}
		catch (IOException | RuntimeException error)
		{
			Log.e(TAG, "Photo import failed", error);
			Toast.makeText(this, R.string.workshop_inspection_photo_unsafe, Toast.LENGTH_LONG).show();
		}
		finally
		{
			clearPendingPhoto();
		}
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

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
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.fr3ts0n.ecu.gui.androbd.ModernUiUtils;
import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.SettingsActivity;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisMediaStore;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopProfile;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopSettingsRepository;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.io.IOException;

/**
 * Workshop identity printed on reports and invoices: name, address, phone,
 * closing note and an optional logo.
 *
 * <p>The logo is copied into the same private folder as diagnosis photos, so a
 * backup carries it and no external file path is stored.</p>
 */
public class WorkshopProfileActivity extends AppCompatActivity
{
	private static final String TAG = "WorkshopProfile";

	private WorkshopSettingsRepository settings;
	private DiagnosisMediaStore media;
	private TextInputEditText name;
	private TextInputEditText address;
	private TextInputEditText phone;
	private TextInputEditText footer;
	private TextView logoState;
	private String logoPath = "";

	private final ActivityResultLauncher<String[]> pickLogo =
			registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri ->
			{
				if (uri != null) importLogo(uri);
			});

	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		SettingsActivity.applyLocale(this);
		super.onCreate(savedInstanceState);
		setContentView(R.layout.workshop_profile);
		ModernUiUtils.applyScreenInsets(this);
		WorkshopUi.prepare(this, getString(R.string.workshop_profile_title));

		settings = new WorkshopSettingsRepository(WorkshopUi.repository(this).database());
		media = new DiagnosisMediaStore(this);
		name = findViewById(R.id.workshop_profile_name);
		address = findViewById(R.id.workshop_profile_address);
		phone = findViewById(R.id.workshop_profile_phone);
		footer = findViewById(R.id.workshop_profile_footer);
		logoState = findViewById(R.id.workshop_profile_logo_state);

		WorkshopProfile profile = settings.profile();
		name.setText(profile.name);
		address.setText(profile.address);
		phone.setText(profile.phone);
		footer.setText(profile.footer);
		logoPath = profile.logoPath;
		showLogoState();

		findViewById(R.id.workshop_profile_logo).setOnClickListener(view ->
				pickLogo.launch(new String[]{"image/*"}));
		findViewById(R.id.workshop_profile_save).setOnClickListener(view -> save());
	}

	private void showLogoState()
	{
		if (logoPath == null || logoPath.isEmpty())
		{
			logoState.setText(R.string.workshop_profile_logo_missing);
			return;
		}
		try
		{
			logoState.setText(media.resolve(logoPath).getName());
		}
		catch (IOException error)
		{
			logoState.setText(R.string.workshop_profile_logo_unsafe);
		}
	}

	private void importLogo(Uri uri)
	{
		try
		{
			File stored = media.importPhoto(uri);
			logoPath = media.relativePath(stored);
			showLogoState();
		}
		catch (IOException | RuntimeException error)
		{
			Log.e(TAG, "Logo import failed", error);
			Toast.makeText(this, R.string.workshop_profile_logo_unsafe, Toast.LENGTH_LONG).show();
		}
	}

	private void save()
	{
		String workshopName = textOf(name);
		if (workshopName.isEmpty())
		{
			name.setError(getString(R.string.workshop_profile_error_name));
			name.requestFocus();
			return;
		}
		settings.saveProfile(workshopName, textOf(address), textOf(phone), textOf(footer), logoPath);
		WorkshopUi.hideKeyboard(this);
		Toast.makeText(this, R.string.workshop_profile_saved, Toast.LENGTH_SHORT).show();
		finish();
	}

	private static String textOf(TextView view)
	{
		return view.getText() == null ? "" : view.getText().toString().trim();
	}
}

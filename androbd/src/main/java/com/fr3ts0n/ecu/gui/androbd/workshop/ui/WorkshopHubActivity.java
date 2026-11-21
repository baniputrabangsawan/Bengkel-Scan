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

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.fr3ts0n.ecu.gui.androbd.ModernUiUtils;
import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.SettingsActivity;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopRepository;

/**
 * "Data bengkel": the workshop tools that used to sit in an overflow menu, as visible cards.
 *
 * <p>Nothing here is new functionality — profile, due reminders, job packages, export, backup
 * and restore all existed. The hub only makes them findable, because a beginner does not open
 * a three-dot menu. The overflow entry itself stays for the two actions that belong to the
 * customer list (this hub and settings are reachable from home).</p>
 */
public class WorkshopHubActivity extends AppCompatActivity
{
	private WorkshopRepository repository;

	private final ActivityResultLauncher<String[]> openBackup =
			registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri ->
			{
				if (uri != null) WorkshopDataActions.confirmRestore(this, repository, uri);
			});

	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		SettingsActivity.applyLocale(this);
		super.onCreate(savedInstanceState);
		setContentView(R.layout.workshop_hub);
		ModernUiUtils.applyScreenInsets(this);
		repository = WorkshopUi.repository(this);
		WorkshopUi.prepare(this, getString(R.string.workshop_data_title));

		findViewById(R.id.hub_profile).setOnClickListener(view ->
				startActivity(new Intent(this, WorkshopProfileActivity.class)));
		findViewById(R.id.hub_due).setOnClickListener(view ->
				startActivity(new Intent(this, WorkshopDueActivity.class)));
		findViewById(R.id.hub_canned).setOnClickListener(view ->
				startActivity(new Intent(this, WorkshopCannedJobsActivity.class)));
		findViewById(R.id.hub_export).setOnClickListener(view ->
				WorkshopDataActions.chooseExport(this, repository));
		findViewById(R.id.hub_backup).setOnClickListener(view ->
				WorkshopDataActions.backup(this, repository));
		findViewById(R.id.hub_restore).setOnClickListener(view ->
				openBackup.launch(new String[]{"*/*"}));
	}

	@Override
	public boolean onSupportNavigateUp()
	{
		finish();
		return true;
	}
}

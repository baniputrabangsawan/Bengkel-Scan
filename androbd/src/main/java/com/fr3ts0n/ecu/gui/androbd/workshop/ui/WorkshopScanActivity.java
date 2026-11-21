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

import android.os.Bundle;
import android.view.MenuItem;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.fr3ts0n.ecu.gui.androbd.ModernUiUtils;
import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.SettingsActivity;
import com.fr3ts0n.ecu.gui.androbd.workshop.ScanPhase;
import com.fr3ts0n.ecu.gui.androbd.workshop.ScanSnapshotRepository;
import com.fr3ts0n.ecu.gui.androbd.workshop.ScanSnapshotWriter;
import com.fr3ts0n.ecu.gui.androbd.workshop.ScanSource;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopRepository;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopSettingsRepository;
import com.google.android.material.textfield.TextInputEditText;

import java.util.Collection;

/**
 * Copy the current OBD reading into a service record.
 *
 * <p>The copy happens on entry and again on save, always before anything can
 * clear the protocol lists. The source is stated in words so demo data can
 * never be mistaken for a vehicle reading.</p>
 */
public class WorkshopScanActivity extends AppCompatActivity
{
	/** Mode name handed over by the main screen, so demo data is never called real. */
	public static final String EXTRA_MODE = "workshop.scan.mode";

	private WorkshopRepository repository;
	private ScanSnapshotRepository snapshots;
	private long serviceId;
	private String codesJson;
	private String readingsJson;
	private String freezeJson;
	private ScanSource source;

	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		SettingsActivity.applyLocale(this);
		super.onCreate(savedInstanceState);
		serviceId = getIntent().getLongExtra(WorkshopUi.EXTRA_SERVICE_ID, -1);
		repository = WorkshopUi.repository(this);
		if (repository.findService(serviceId) == null)
		{
			finish();
			return;
		}
		setContentView(R.layout.workshop_scan);
		ModernUiUtils.applyScreenInsets(this);
		WorkshopUi.prepare(this, getString(R.string.workshop_scan_title));
		snapshots = new ScanSnapshotRepository(repository.database());

		source = ScanSnapshotWriter.currentSource(getIntent().getStringExtra(EXTRA_MODE) == null
				? com.fr3ts0n.ecu.gui.androbd.MainActivity.modeName()
				: getIntent().getStringExtra(EXTRA_MODE));
		capture();

		TextView sourceView = findViewById(R.id.workshop_scan_source);
		sourceView.setText(sourceLabel());
		TextView summary = findViewById(R.id.workshop_scan_summary);
		summary.setText(getString(R.string.workshop_scan_codes, ScanSnapshotWriter.size(codesJson))
				+ " · " + getString(R.string.workshop_scan_readings,
				ScanSnapshotWriter.size(readingsJson)));
		if (source == ScanSource.DEMO)
		{
			summary.append("\n" + getString(R.string.workshop_scan_demo_warning));
		}

		findViewById(R.id.workshop_scan_save).setOnClickListener(view -> save());
	}

	/** Freeze the reading immediately; later reads may be cleared. */
	private void capture()
	{
		Collection<? extends com.fr3ts0n.pvs.IndexedProcessVar> codes = ScanSnapshotWriter.faultCodes();
		Collection<? extends com.fr3ts0n.pvs.IndexedProcessVar> readings = ScanSnapshotWriter.liveReadings();
		codesJson = ScanSnapshotWriter.codesJson(codes);
		readingsJson = ScanSnapshotWriter.readingsJson(readings);
		// Freeze-frame rows are the same PID structure; keep the live copy separate
		// because the ECU may not send any for this vehicle.
		freezeJson = "[]";
	}

	private int sourceLabel()
	{
		return WorkshopUi.scanSourceLabel(source);
	}

	private void save()
	{
		RadioButton after = findViewById(R.id.workshop_scan_after);
		ScanPhase phase = after.isChecked() ? ScanPhase.AFTER : ScanPhase.BEFORE;
		TextInputEditText note = findViewById(R.id.workshop_scan_note);
		String text = note.getText() == null ? "" : note.getText().toString().trim();
		try
		{
			long id = snapshots.save(serviceId, phase, source, adapterName(), codesJson,
					readingsJson, freezeJson, text);
			new WorkshopSettingsRepository(repository.database())
					.record("scan.saved", "service", serviceId, phase.name() + " " + source.name());
			Toast.makeText(this, R.string.workshop_scan_saved, Toast.LENGTH_SHORT).show();
			if (id <= 0) finish();
			finish();
		}
		catch (RuntimeException error)
		{
			Toast.makeText(this, R.string.workshop_scan_empty, Toast.LENGTH_LONG).show();
		}
	}

	private String adapterName()
	{
		return source == ScanSource.REAL ? getString(R.string.workshop_scan_source_real) : "";
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

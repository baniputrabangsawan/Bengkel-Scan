/*
 * Copyright 2026 Bengkel Scan contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.fr3ts0n.ecu.gui.androbd.workshop.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.fr3ts0n.ecu.gui.androbd.ModernUiUtils;
import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.SettingsActivity;
import com.fr3ts0n.ecu.gui.androbd.workshop.DiagnosticModule;
import com.fr3ts0n.ecu.gui.androbd.workshop.DiagnosticReadStatus;
import com.fr3ts0n.ecu.gui.androbd.workshop.DiagnosticRecord;
import com.fr3ts0n.ecu.gui.androbd.workshop.DiagnosticRepository;
import com.fr3ts0n.ecu.gui.androbd.workshop.ServiceRecord;
import com.fr3ts0n.ecu.gui.androbd.workshop.Vehicle;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopDatabase;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopRepository;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** A missing record is always uninspected, never a healthy module. */
public class WorkshopDiagnosticsActivity extends AppCompatActivity
{
	private long serviceId;
	private DiagnosticRepository diagnostics;
	private WorkshopRepository workshop;
	private WorkshopRowAdapter adapter;
	private final DiagnosticModule[] modules = DiagnosticModule.values();

	@Override
	protected void onCreate(Bundle state)
	{
		SettingsActivity.applyLocale(this);
		super.onCreate(state);
		serviceId = getIntent().getLongExtra(WorkshopUi.EXTRA_SERVICE_ID, -1);
		workshop = WorkshopUi.repository(this);
		if (workshop.findService(serviceId) == null)
		{
			finish();
			return;
		}
		diagnostics = new DiagnosticRepository(WorkshopDatabase.getInstance(this));
		setContentView(R.layout.workshop_diagnostics);
		ModernUiUtils.applyScreenInsets(this);
		WorkshopUi.prepare(this, getString(R.string.diagnostic_title));
		adapter = new WorkshopRowAdapter(this);
		ListView list = findViewById(R.id.diagnostic_modules);
		list.setAdapter(adapter);
		list.setOnItemClickListener((parent, view, position, id) ->
				startActivity(new Intent(this, WorkshopDiagnosticEditActivity.class)
						.putExtra(WorkshopUi.EXTRA_SERVICE_ID, serviceId)
						.putExtra(DiagnosticUi.EXTRA_MODULE, modules[position].name())));
		findViewById(R.id.diagnostic_open_guide).setOnClickListener(view ->
				startActivity(new Intent(this, WorkshopGuideActivity.class)
						.putExtra(WorkshopUi.EXTRA_SERVICE_ID, serviceId)));
	}

	@Override
	protected void onResume()
	{
		super.onResume();
		if (adapter == null) return;
		ServiceRecord service = workshop.findService(serviceId);
		if (service == null)
		{
			finish();
			return;
		}
		Vehicle vehicle = workshop.findVehicle(service.vehicleId);
		if (vehicle == null)
		{
			finish();
			return;
		}
		((TextView) findViewById(R.id.diagnostic_context)).setText(getString(
				R.string.diagnostic_summary, vehicle.plate, vehicle.model, service.complaint));
		String[] reads = getResources().getStringArray(R.array.diagnostic_read_states);
		String[] conclusions = getResources().getStringArray(R.array.diagnostic_conclusions);
		Map<DiagnosticModule, DiagnosticRecord> records = new EnumMap<>(DiagnosticModule.class);
		for (DiagnosticRecord record : diagnostics.forService(serviceId)) records.put(record.module, record);
		List<WorkshopRow> rows = new ArrayList<>(modules.length);
		for (DiagnosticModule module : modules)
		{
			DiagnosticRecord record = records.get(module);
			String description = record == null ? reads[DiagnosticReadStatus.NOT_CHECKED.ordinal()]
					+ "\n" + getString(R.string.diagnostic_no_record)
					: getString(R.string.diagnostic_record_summary,
							reads[record.readStatus.ordinal()],
							record.readStatus == DiagnosticReadStatus.READ
									? conclusions[record.conclusion.ordinal()]
									: getString(R.string.diagnostic_unresolved),
							WorkshopUi.dateTime(record.updatedAt));
			rows.add(new WorkshopRow(module.ordinal(), getString(DiagnosticUi.moduleLabel(module)),
					description, null));
		}
		adapter.submit(rows);
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

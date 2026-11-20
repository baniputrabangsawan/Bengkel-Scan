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
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.fr3ts0n.ecu.gui.androbd.ModernUiUtils;
import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.SettingsActivity;
import com.fr3ts0n.ecu.gui.androbd.workshop.PaymentStatus;
import com.fr3ts0n.ecu.gui.androbd.workshop.ServiceRecord;
import com.fr3ts0n.ecu.gui.androbd.workshop.Vehicle;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopRepository;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Every service job, filtered by open, today or unpaid.
 *
 * <p>Filters are plain queries, not a separate stored state. A job appears in
 * "today" by its creation date and in "open" while it is not done.</p>
 */
public class WorkshopJobsActivity extends AppCompatActivity
{
	private enum Filter
	{
		ALL,
		OPEN,
		TODAY,
		UNPAID
	}

	private WorkshopRepository repository;
	private WorkshopRowAdapter adapter;
	private TextView empty;
	private Filter filter = Filter.OPEN;
	private MaterialButton all;
	private MaterialButton open;
	private MaterialButton today;
	private MaterialButton unpaid;

	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		SettingsActivity.applyLocale(this);
		super.onCreate(savedInstanceState);
		setContentView(R.layout.workshop_jobs);
		ModernUiUtils.applyScreenInsets(this);
		repository = WorkshopUi.repository(this);
		WorkshopUi.prepare(this, getString(R.string.workshop_jobs_title));

		all = findViewById(R.id.workshop_jobs_all);
		open = findViewById(R.id.workshop_jobs_open);
		today = findViewById(R.id.workshop_jobs_today);
		unpaid = findViewById(R.id.workshop_jobs_unpaid);
		empty = findViewById(R.id.workshop_empty);
		ListView list = findViewById(R.id.workshop_list);
		adapter = new WorkshopRowAdapter(this);
		list.setAdapter(adapter);
		list.setOnItemClickListener((parent, view, position, id) ->
		{
			WorkshopRow row = adapter.getItem(position);
			startActivity(new Intent(this, WorkshopServiceActivity.class)
					.putExtra(WorkshopUi.EXTRA_SERVICE_ID, row.id));
		});

		all.setOnClickListener(view -> select(Filter.ALL));
		open.setOnClickListener(view -> select(Filter.OPEN));
		today.setOnClickListener(view -> select(Filter.TODAY));
		unpaid.setOnClickListener(view -> select(Filter.UNPAID));
	}

	@Override
	protected void onResume()
	{
		super.onResume();
		if (repository != null) load();
	}

	private void select(Filter next)
	{
		filter = next;
		load();
	}

	private void load()
	{
		List<ServiceRecord> services = switch (filter)
		{
			case ALL -> repository.allServices();
			case TODAY -> repository.servicesForDay(dayStart(), dayStart() + 24L * 60 * 60 * 1000);
			case UNPAID -> repository.unpaidServices();
			case OPEN -> repository.openServices();
		};
		List<WorkshopRow> rows = new ArrayList<>(services.size());
		for (ServiceRecord service : services)
		{
			Vehicle vehicle = repository.findVehicle(service.vehicleId);
			String plate = vehicle == null ? "" : vehicle.plate;
			rows.add(new WorkshopRow(service.id, plate.isEmpty()
					? service.complaint : plate + " · " + service.complaint,
					WorkshopUi.dateTime(service.createdAt),
					getString(WorkshopUi.statusLabel(service.status))));
		}
		adapter.submit(rows);
		empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
		highlight();
	}

	private void highlight()
	{
		all.setEnabled(filter != Filter.ALL);
		open.setEnabled(filter != Filter.OPEN);
		today.setEnabled(filter != Filter.TODAY);
		unpaid.setEnabled(filter != Filter.UNPAID);
	}

	/** Midnight of the current local day. */
	private static long dayStart()
	{
		Calendar calendar = Calendar.getInstance();
		calendar.set(Calendar.HOUR_OF_DAY, 0);
		calendar.set(Calendar.MINUTE, 0);
		calendar.set(Calendar.SECOND, 0);
		calendar.set(Calendar.MILLISECOND, 0);
		return calendar.getTimeInMillis();
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

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
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.fr3ts0n.ecu.gui.androbd.ModernUiUtils;
import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.SettingsActivity;
import com.fr3ts0n.ecu.gui.androbd.workshop.Customer;
import com.fr3ts0n.ecu.gui.androbd.workshop.ServiceRecord;
import com.fr3ts0n.ecu.gui.androbd.workshop.Vehicle;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopRepository;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

/** One vehicle with its service history. */
public class WorkshopVehicleActivity extends AppCompatActivity
{
	private WorkshopRepository repository;
	private WorkshopRowAdapter adapter;
	private Vehicle vehicle;
	private TextView empty;

	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		SettingsActivity.applyLocale(this);
		super.onCreate(savedInstanceState);
		long vehicleId = getIntent().getLongExtra(WorkshopUi.EXTRA_VEHICLE_ID, -1);
		repository = WorkshopUi.repository(this);
		vehicle = repository.findVehicle(vehicleId);
		if (vehicle == null)
		{
			finish();
			return;
		}
		setContentView(R.layout.workshop_vehicle);
		ModernUiUtils.applyScreenInsets(this);
		WorkshopUi.prepare(this, vehicle.plate);

		ListView list = findViewById(R.id.workshop_list);
		empty = findViewById(R.id.workshop_empty);
		adapter = new WorkshopRowAdapter(this);
		list.setAdapter(adapter);
		list.setOnItemClickListener((parent, view, position, id) ->
		{
			WorkshopRow row = adapter.getItem(position);
			startActivity(new Intent(this, WorkshopServiceActivity.class)
					.putExtra(WorkshopUi.EXTRA_SERVICE_ID, row.id));
		});

		findViewById(R.id.workshop_add).setOnClickListener(view -> addService());
		renderSummary();
	}

	@Override
	protected void onResume()
	{
		super.onResume();
		if (vehicle != null) loadServices();
	}

	private void renderSummary()
	{
		StringBuilder text = new StringBuilder();
		if (!vehicle.model.isEmpty())
		{
			text.append(vehicle.year == null ? vehicle.model
					: getString(R.string.workshop_vehicle_summary, vehicle.model, vehicle.year));
			text.append('\n');
		}
		Customer customer = repository.findCustomer(vehicle.customerId);
		if (customer != null) text.append(customer.name);
		((TextView) findViewById(R.id.workshop_summary)).setText(text);
	}

	private void loadServices()
	{
		List<ServiceRecord> services = repository.servicesForVehicle(vehicle.id);
		List<WorkshopRow> rows = new ArrayList<>(services.size());
		for (ServiceRecord service : services)
		{
			rows.add(new WorkshopRow(service.id, service.complaint,
					WorkshopUi.dateTime(service.createdAt),
					getString(WorkshopUi.statusLabel(service.status))));
		}
		adapter.submit(rows);
		empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
	}

	private void addService()
	{
		View form = getLayoutInflater().inflate(R.layout.dialog_text, null);
		TextInputEditText complaint = form.findViewById(R.id.workshop_text);

		AlertDialog dialog = new AlertDialog.Builder(this)
				.setTitle(R.string.workshop_service_add)
				.setView(form)
				.setNegativeButton(R.string.workshop_action_cancel, null)
				.setPositiveButton(R.string.workshop_action_save, null)
				.create();
		dialog.setOnShowListener(ignored ->
				dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view ->
				{
					String text = complaint.getText() == null
							? "" : complaint.getText().toString().trim();
					if (text.isEmpty())
					{
						complaint.setError(getString(R.string.workshop_error_complaint));
						complaint.requestFocus();
						return;
					}
					long serviceId = repository.createService(vehicle.id, text);
					WorkshopUi.hideKeyboard(this);
					dialog.dismiss();
					loadServices();
					startActivity(new Intent(this, WorkshopServiceActivity.class)
							.putExtra(WorkshopUi.EXTRA_SERVICE_ID, serviceId));
				}));
		dialog.show();
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
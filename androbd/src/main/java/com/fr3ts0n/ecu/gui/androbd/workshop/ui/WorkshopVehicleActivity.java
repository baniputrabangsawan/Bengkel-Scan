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
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.fr3ts0n.ecu.gui.androbd.ModernUiUtils;
import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.SettingsActivity;
import com.fr3ts0n.ecu.gui.androbd.workshop.Customer;
import com.fr3ts0n.ecu.gui.androbd.workshop.EngineType;
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
		text.append('\n');
		text.append(vehicle.engineType == EngineType.PETROL
				? getString(R.string.workshop_engine_petrol)
				: getString(R.string.workshop_engine_diesel));
		if (!vehicle.vin.isEmpty()) text.append(" · ").append(vehicle.vin);
		if (vehicle.odometerKm != null)
		{
			text.append(" · ").append(vehicle.odometerKm).append(" km");
		}
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
	public boolean onCreateOptionsMenu(Menu menu)
	{
		getMenuInflater().inflate(R.menu.workshop_edit, menu);
		menu.findItem(R.id.workshop_edit).setTitle(R.string.workshop_vehicle_edit_menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item)
	{
		if (item.getItemId() == android.R.id.home)
		{
			finish();
			return true;
		}
		if (item.getItemId() == R.id.workshop_edit)
		{
			editVehicle();
			return true;
		}
		if (item.getItemId() == R.id.workshop_due)
		{
			setReminder();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	/** Set the next service date and/or odometer for this vehicle. */
	private void setReminder()
	{
		View form = getLayoutInflater().inflate(R.layout.dialog_estimate_item, null);
		form.findViewById(R.id.workshop_kind).setVisibility(View.GONE);
		form.findViewById(R.id.workshop_description).setVisibility(View.GONE);
		form.findViewById(R.id.workshop_quantity).setVisibility(View.GONE);
		form.findViewById(R.id.workshop_price).setVisibility(View.GONE);
		form.findViewById(R.id.workshop_discount).setVisibility(View.GONE);
		form.findViewById(R.id.workshop_inspection_link).setVisibility(View.GONE);
		TextInputEditText day = form.findViewById(R.id.workshop_reminder_day);
		TextInputEditText odometer = form.findViewById(R.id.workshop_reminder_odometer);
		day.setVisibility(View.VISIBLE);
		odometer.setVisibility(View.VISIBLE);
		odometer.setText(vehicle.odometerKm == null ? "" : String.valueOf(vehicle.odometerKm));

		AlertDialog dialog = new AlertDialog.Builder(this)
				.setTitle(R.string.workshop_due_remind)
				.setView(form)
				.setNegativeButton(R.string.workshop_action_cancel, null)
				.setPositiveButton(R.string.workshop_action_save, null)
				.create();
		dialog.setOnShowListener(ignored ->
				dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view ->
				{
					Integer km = parseOdometer(textOf(odometer));
					Long dueDate = parseDate(textOf(day));
					if (km == null && dueDate == null)
					{
						day.setError(getString(R.string.workshop_due_need_target));
						return;
					}
					new com.fr3ts0n.ecu.gui.androbd.workshop.ReminderRepository(
							repository.database())
							.create(vehicle.id, dueDate, km, "");
					dialog.dismiss();
					Toast.makeText(this, R.string.workshop_due_saved, Toast.LENGTH_SHORT).show();
				}));
		dialog.show();
	}

	/** {@code dd-MM-yyyy} as the mechanic types it, or null when blank/invalid. */
	public static Long parseDate(String value)
	{
		if (value == null || value.trim().isEmpty()) return null;
		String[] parts = value.trim().split("[-/.]");
		if (parts.length != 3) return null;
		try
		{
			int day = Integer.parseInt(parts[0]);
			int month = Integer.parseInt(parts[1]);
			int year = Integer.parseInt(parts[2]);
			java.util.Calendar calendar = java.util.Calendar.getInstance();
			calendar.set(year, month - 1, day, 8, 0, 0);
			calendar.set(java.util.Calendar.MILLISECOND, 0);
			return calendar.getTimeInMillis();
		}
		catch (NumberFormatException | java.lang.ArrayIndexOutOfBoundsException invalid)
		{
			return null;
		}
	}

	/** Change plate, model, year, fuel type, identity number and odometer in place. */
	private void editVehicle()
	{
		View form = getLayoutInflater().inflate(R.layout.dialog_vehicle, null);
		TextInputEditText plate = form.findViewById(R.id.workshop_plate);
		TextInputEditText model = form.findViewById(R.id.workshop_model);
		TextInputEditText year = form.findViewById(R.id.workshop_year);
		TextInputEditText vin = form.findViewById(R.id.workshop_vin);
		TextInputEditText odometer = form.findViewById(R.id.workshop_odometer);
		Spinner engine = form.findViewById(R.id.workshop_engine);
		ArrayAdapter<String> engines = new ArrayAdapter<>(this,
				android.R.layout.simple_spinner_item,
				new String[]{getString(R.string.workshop_engine_diesel),
						getString(R.string.workshop_engine_petrol)});
		engines.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		engine.setAdapter(engines);

		plate.setText(vehicle.plate);
		model.setText(vehicle.model);
		year.setText(vehicle.year == null ? "" : String.valueOf(vehicle.year));
		vin.setText(vehicle.vin);
		odometer.setText(vehicle.odometerKm == null ? "" : String.valueOf(vehicle.odometerKm));
		engine.setSelection(vehicle.engineType == EngineType.PETROL ? 1 : 0);

		AlertDialog dialog = new AlertDialog.Builder(this)
				.setTitle(R.string.workshop_vehicle_update_title)
				.setView(form)
				.setNegativeButton(R.string.workshop_action_cancel, null)
				.setPositiveButton(R.string.workshop_action_save, null)
				.create();
		dialog.setOnShowListener(ignored ->
				dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view ->
				{
					String plateNumber = textOf(plate);
					if (plateNumber.isEmpty())
					{
						plate.setError(getString(R.string.workshop_error_plate));
						plate.requestFocus();
						return;
					}
					Integer odometerKm = parseOdometer(textOf(odometer));
					if (odometerKm == null && !textOf(odometer).isEmpty())
					{
						odometer.setError(getString(R.string.workshop_error_odometer));
						odometer.requestFocus();
						return;
					}
					EngineType type = engine.getSelectedItemPosition() == 1
							? EngineType.PETROL : EngineType.DIESEL;
					repository.updateVehicle(vehicle.id, plateNumber, textOf(model),
							parseYear(textOf(year)), type, textOf(vin), odometerKm);
					vehicle = repository.findVehicle(vehicle.id);
					WorkshopUi.hideKeyboard(this);
					dialog.dismiss();
					WorkshopUi.prepare(this, vehicle.plate);
					renderSummary();
					Toast.makeText(this, R.string.workshop_saved, Toast.LENGTH_SHORT).show();
				}));
		dialog.show();
	}

	private static Integer parseYear(String value)
	{
		if (value.isEmpty()) return null;
		try
		{
			int year = Integer.parseInt(value);
			return year > 0 && year < 3000 ? Integer.valueOf(year) : null;
		}
		catch (NumberFormatException ignored)
		{
			return null;
		}
	}

	private static Integer parseOdometer(String value)
	{
		if (value.isEmpty()) return null;
		try
		{
			int km = Integer.parseInt(value);
			return km >= 0 && km < 10_000_000 ? Integer.valueOf(km) : null;
		}
		catch (NumberFormatException ignored)
		{
			return null;
		}
	}

	private static String textOf(TextInputEditText field)
	{
		return field.getText() == null ? "" : field.getText().toString().trim();
	}
}
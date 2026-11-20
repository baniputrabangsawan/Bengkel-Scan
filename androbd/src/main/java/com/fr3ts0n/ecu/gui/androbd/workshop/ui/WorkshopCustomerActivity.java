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
import com.fr3ts0n.ecu.gui.androbd.workshop.Vehicle;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopRepository;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

/** One customer with the vehicle list; vehicles open their service history. */
public class WorkshopCustomerActivity extends AppCompatActivity
{
	private WorkshopRepository repository;
	private WorkshopRowAdapter adapter;
	private Customer customer;
	private TextView summary;
	private TextView empty;

	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		SettingsActivity.applyLocale(this);
		super.onCreate(savedInstanceState);
		long customerId = getIntent().getLongExtra(WorkshopUi.EXTRA_CUSTOMER_ID, -1);
		repository = WorkshopUi.repository(this);
		customer = repository.findCustomer(customerId);
		if (customer == null)
		{
			finish();
			return;
		}
		setContentView(R.layout.workshop_customer);
		ModernUiUtils.applyScreenInsets(this);
		WorkshopUi.prepare(this, customer.name);

		summary = findViewById(R.id.workshop_summary);
		empty = findViewById(R.id.workshop_empty);
		ListView list = findViewById(R.id.workshop_list);
		adapter = new WorkshopRowAdapter(this);
		list.setAdapter(adapter);
		list.setOnItemClickListener((parent, view, position, id) ->
		{
			WorkshopRow row = adapter.getItem(position);
			startActivity(new Intent(this, WorkshopVehicleActivity.class)
					.putExtra(WorkshopUi.EXTRA_VEHICLE_ID, row.id));
		});

		findViewById(R.id.workshop_add).setOnClickListener(view -> addVehicle());
		renderCustomer();
	}

	@Override
	protected void onResume()
	{
		super.onResume();
		if (customer != null) loadVehicles();
	}

	private void renderCustomer()
	{
		StringBuilder text = new StringBuilder(
				WorkshopUi.orFallback(customer.phone, getString(R.string.workshop_no_phone)));
		if (!customer.notes.isEmpty())
		{
			text.append('\n').append(customer.notes);
		}
		summary.setText(text);
	}

	private void loadVehicles()
	{
		List<Vehicle> vehicles = repository.vehiclesForCustomer(customer.id);
		List<WorkshopRow> rows = new ArrayList<>(vehicles.size());
		for (Vehicle vehicle : vehicles)
		{
			String subtitle = vehicle.model.isEmpty()
					? getString(R.string.workshop_no_model)
					: (vehicle.year == null
					? vehicle.model
					: getString(R.string.workshop_vehicle_summary, vehicle.model, vehicle.year));
			rows.add(new WorkshopRow(vehicle.id, vehicle.plate, subtitle, null));
		}
		adapter.submit(rows);
		empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
	}

	private void addVehicle()
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
		engine.setSelection(0);

		AlertDialog dialog = new AlertDialog.Builder(this)
				.setTitle(R.string.workshop_vehicle_add)
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
					Integer modelYear = parseYear(textOf(year));
					Integer odometerKm = parseOdometer(textOf(odometer));
					if (odometerKm == null && !textOf(odometer).isEmpty())
					{
						odometer.setError(getString(R.string.workshop_error_odometer));
						odometer.requestFocus();
						return;
					}
					EngineType type = engine.getSelectedItemPosition() == 1
							? EngineType.PETROL : EngineType.DIESEL;
					long vehicleId = repository.createVehicle(customer.id, plateNumber,
							textOf(model), modelYear, type, textOf(vin), odometerKm);
					WorkshopUi.hideKeyboard(this);
					dialog.dismiss();
					loadVehicles();
					startActivity(new Intent(this, WorkshopVehicleActivity.class)
							.putExtra(WorkshopUi.EXTRA_VEHICLE_ID, vehicleId));
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

	/** Whole kilometres, or {@code null} when the field is blank or not a number. */
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

	@Override
	public boolean onCreateOptionsMenu(Menu menu)
	{
		getMenuInflater().inflate(R.menu.workshop_edit, menu);
		menu.findItem(R.id.workshop_edit).setTitle(R.string.workshop_customer_edit_menu);
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
			editCustomer();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	/** Change name, phone and notes; the row keeps its id so vehicles stay attached. */
	private void editCustomer()
	{
		View form = getLayoutInflater().inflate(R.layout.dialog_customer, null);
		TextInputEditText name = form.findViewById(R.id.workshop_name);
		TextInputEditText phone = form.findViewById(R.id.workshop_phone);
		TextInputEditText notes = form.findViewById(R.id.workshop_notes);
		name.setText(customer.name);
		phone.setText(customer.phone);
		notes.setText(customer.notes);

		AlertDialog dialog = new AlertDialog.Builder(this)
				.setTitle(R.string.workshop_customer_update_title)
				.setView(form)
				.setNegativeButton(R.string.workshop_action_cancel, null)
				.setPositiveButton(R.string.workshop_action_save, null)
				.create();
		dialog.setOnShowListener(ignored ->
				dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view ->
				{
					String customerName = textOf(name);
					if (customerName.isEmpty())
					{
						name.setError(getString(R.string.workshop_error_name));
						name.requestFocus();
						return;
					}
					repository.updateCustomer(customer.id, customerName, textOf(phone), textOf(notes));
					customer = repository.findCustomer(customer.id);
					WorkshopUi.hideKeyboard(this);
					dialog.dismiss();
					WorkshopUi.prepare(this, customer.name);
					renderCustomer();
					Toast.makeText(this, R.string.workshop_saved, Toast.LENGTH_SHORT).show();
				}));
		dialog.show();
	}
}
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
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.fr3ts0n.ecu.gui.androbd.ModernUiUtils;
import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.SettingsActivity;
import com.fr3ts0n.ecu.gui.androbd.workshop.EstimateItem;
import com.fr3ts0n.ecu.gui.androbd.workshop.ItemKind;
import com.fr3ts0n.ecu.gui.androbd.workshop.PaymentStatus;
import com.fr3ts0n.ecu.gui.androbd.workshop.ServiceRecord;
import com.fr3ts0n.ecu.gui.androbd.workshop.ServiceStatus;
import com.fr3ts0n.ecu.gui.androbd.workshop.Vehicle;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopRepository;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

/** One service: status, payment, notes and the cost estimate. */
public class WorkshopServiceActivity extends AppCompatActivity
{
	private WorkshopRepository repository;
	private WorkshopRowAdapter adapter;
	private ServiceRecord service;
	private Spinner status;
	private MaterialSwitch paid;
	private EditText notes;
	private TextView total;
	private TextView empty;
	private boolean binding;

	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		SettingsActivity.applyLocale(this);
		super.onCreate(savedInstanceState);
		long serviceId = getIntent().getLongExtra(WorkshopUi.EXTRA_SERVICE_ID, -1);
		repository = WorkshopUi.repository(this);
		service = repository.findService(serviceId);
		if (service == null)
		{
			finish();
			return;
		}
		setContentView(R.layout.workshop_service);
		ModernUiUtils.applyScreenInsets(this);

		Vehicle vehicle = repository.findVehicle(service.vehicleId);
		WorkshopUi.prepare(this, vehicle == null ? getString(R.string.workshop_services_title)
				: vehicle.plate);
		((TextView) findViewById(R.id.workshop_summary)).setText(
				service.complaint + "\n" + WorkshopUi.dateTime(service.createdAt));

		status = findViewById(R.id.workshop_status);
		ArrayAdapter<String> statuses = new ArrayAdapter<>(this,
				android.R.layout.simple_spinner_item,
				new String[]{getString(R.string.workshop_status_waiting),
						getString(R.string.workshop_status_in_progress),
						getString(R.string.workshop_status_done)});
		statuses.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		status.setAdapter(statuses);
		status.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener()
		{
			@Override
			public void onItemSelected(AdapterView<?> parent, View view, int position, long id)
			{
				if (binding || service == null) return;
				ServiceStatus next = position == 1 ? ServiceStatus.IN_PROGRESS
						: position == 2 ? ServiceStatus.DONE : ServiceStatus.WAITING;
				if (next == service.status) return;
				repository.updateServiceStatus(service.id, next);
				service = repository.findService(service.id);
			}

			@Override
			public void onNothingSelected(AdapterView<?> parent)
			{
			}
		});

		paid = findViewById(R.id.workshop_paid);
		notes = findViewById(R.id.workshop_notes);
		total = findViewById(R.id.workshop_total);

		ListView list = findViewById(R.id.workshop_list);
		empty = findViewById(R.id.workshop_empty);
		adapter = new WorkshopRowAdapter(this);
		list.setAdapter(adapter);
		list.setOnItemLongClickListener(this::confirmDeleteItem);

		findViewById(R.id.workshop_add).setOnClickListener(view -> addEstimateItem());
		findViewById(R.id.workshop_save_notes).setOnClickListener(view ->
		{
			repository.updateServiceNotes(service.id, notes.getText().toString().trim());
			WorkshopUi.hideKeyboard(this);
			Toast.makeText(this, R.string.workshop_service_notes_saved, Toast.LENGTH_SHORT).show();
		});
	}

	@Override
	protected void onResume()
	{
		super.onResume();
		if (service == null) return;
		service = repository.findService(service.id);
		if (service == null)
		{
			finish();
			return;
		}
		bind();
	}

	private void bind()
	{
		binding = true;
		status.setSelection(service.status == ServiceStatus.IN_PROGRESS ? 1
				: service.status == ServiceStatus.DONE ? 2 : 0);
		binding = false;

		paid.setOnCheckedChangeListener(null);
		paid.setChecked(service.paymentStatus == PaymentStatus.PAID);
		paid.setOnCheckedChangeListener((button, checked) ->
				repository.setPaymentStatus(service.id,
						checked ? PaymentStatus.PAID : PaymentStatus.UNPAID));

		if (!notes.hasFocus())
		{
			notes.setText(service.notes);
		}
		loadItems();
	}

	private void loadItems()
	{
		List<EstimateItem> items = repository.estimateItems(service.id);
		DecimalFormat quantity = new DecimalFormat("0.##");
		List<WorkshopRow> rows = new ArrayList<>(items.size());
		for (EstimateItem item : items)
		{
			rows.add(new WorkshopRow(item.id, item.description,
					getString(R.string.workshop_item_line,
							quantity.format(item.quantity), WorkshopUi.money(item.unitPrice)),
					WorkshopUi.money(item.lineTotal())));
		}
		adapter.submit(rows);
		empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
		total.setText(WorkshopUi.money(repository.estimateTotal(service.id)));
	}

	private boolean confirmDeleteItem(AdapterView<?> parent, View view, int position, long id)
	{
		WorkshopRow row = adapter.getItem(position);
		new AlertDialog.Builder(this)
				.setTitle(R.string.workshop_delete_item_title)
				.setMessage(getString(R.string.workshop_delete_item_message, row.title))
				.setNegativeButton(R.string.workshop_action_cancel, null)
				.setPositiveButton(R.string.workshop_action_delete, (dialog, which) ->
				{
					repository.deleteEstimateItem(row.id);
					loadItems();
				})
				.show();
		return true;
	}

	private void addEstimateItem()
	{
		View form = getLayoutInflater().inflate(R.layout.dialog_estimate_item, null);
		Spinner kind = form.findViewById(R.id.workshop_kind);
		TextInputEditText description = form.findViewById(R.id.workshop_description);
		TextInputEditText quantity = form.findViewById(R.id.workshop_quantity);
		TextInputEditText price = form.findViewById(R.id.workshop_price);

		ArrayAdapter<String> kinds = new ArrayAdapter<>(this,
				android.R.layout.simple_spinner_item,
				new String[]{getString(R.string.workshop_item_labour),
						getString(R.string.workshop_item_part)});
		kinds.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		kind.setAdapter(kinds);

		AlertDialog dialog = new AlertDialog.Builder(this)
				.setTitle(R.string.workshop_estimate_add)
				.setView(form)
				.setNegativeButton(R.string.workshop_action_cancel, null)
				.setPositiveButton(R.string.workshop_action_save, null)
				.create();
		dialog.setOnShowListener(ignored ->
				dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view ->
				{
					String text = description.getText() == null
							? "" : description.getText().toString().trim();
					if (text.isEmpty())
					{
						description.setError(getString(R.string.workshop_item_description));
						description.requestFocus();
						return;
					}
					double amount;
					try
					{
						amount = Double.parseDouble(quantity.getText().toString()
								.trim().replace(',', '.'));
					}
					catch (NumberFormatException invalid)
					{
						amount = 0;
					}
					if (amount <= 0)
					{
						quantity.setError(getString(R.string.workshop_error_quantity));
						quantity.requestFocus();
						return;
					}
					long unitPrice;
					try
					{
						unitPrice = Long.parseLong(price.getText().toString()
								.trim().replaceAll("[^0-9]", ""));
					}
					catch (NumberFormatException invalid)
					{
						unitPrice = -1;
					}
					if (unitPrice < 0)
					{
						price.setError(getString(R.string.workshop_error_price));
						price.requestFocus();
						return;
					}
					repository.addEstimateItem(service.id,
							kind.getSelectedItemPosition() == 0
									? ItemKind.LABOUR : ItemKind.PART,
							text, amount, unitPrice);
					WorkshopUi.hideKeyboard(this);
					dialog.dismiss();
					loadItems();
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
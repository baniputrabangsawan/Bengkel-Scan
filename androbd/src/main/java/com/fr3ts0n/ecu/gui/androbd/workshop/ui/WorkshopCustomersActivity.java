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
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.fr3ts0n.ecu.gui.androbd.ModernUiUtils;
import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.SettingsActivity;
import com.fr3ts0n.ecu.gui.androbd.workshop.Customer;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopRepository;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

/** Customer list: search, add, open a customer, back up and restore. */
public class WorkshopCustomersActivity extends AppCompatActivity
{
	private WorkshopRepository repository;
	private WorkshopRowAdapter adapter;
	private EditText search;
	private TextView empty;

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
		setContentView(R.layout.workshop_customers);
		ModernUiUtils.applyScreenInsets(this);
		repository = WorkshopUi.repository(this);
		WorkshopUi.prepare(this, getString(R.string.workshop_customers_title));

		search = findViewById(R.id.workshop_search);
		empty = findViewById(R.id.workshop_empty);
		ListView list = findViewById(R.id.workshop_list);
		adapter = new WorkshopRowAdapter(this);
		list.setAdapter(adapter);
		list.setOnItemClickListener((parent, view, position, id) ->
		{
			WorkshopRow row = adapter.getItem(position);
			startActivity(new Intent(this, WorkshopCustomerActivity.class)
					.putExtra(WorkshopUi.EXTRA_CUSTOMER_ID, row.id));
		});

		search.addTextChangedListener(new TextWatcher()
		{
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after)
			{
			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count)
			{
			}

			@Override
			public void afterTextChanged(Editable s)
			{
				loadCustomers();
			}
		});

		findViewById(R.id.workshop_add).setOnClickListener(view -> addCustomer());
		findViewById(R.id.workshop_backup).setOnClickListener(view ->
				WorkshopDataActions.backup(this, repository));
		WorkshopDataActions.maybeNudgeBackup(this, repository,
				() -> WorkshopDataActions.backup(this, repository));
	}

	@Override
	protected void onResume()
	{
		super.onResume();
		if (repository != null) loadCustomers();
	}

	private void loadCustomers()
	{
		String query = search.getText().toString().trim();
		List<Customer> customers = query.isEmpty()
				? repository.listCustomers()
				: repository.searchCustomers(query);
		List<WorkshopRow> rows = new ArrayList<>(customers.size());
		for (Customer customer : customers)
		{
			rows.add(new WorkshopRow(customer.id, customer.name,
					WorkshopUi.orFallback(customer.phone,
							getString(R.string.workshop_no_phone)), null));
		}
		adapter.submit(rows);
		empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
	}

	private void addCustomer()
	{
		View form = getLayoutInflater().inflate(R.layout.dialog_customer, null);
		TextInputEditText name = form.findViewById(R.id.workshop_name);
		TextInputEditText phone = form.findViewById(R.id.workshop_phone);
		TextInputEditText notes = form.findViewById(R.id.workshop_notes);

		AlertDialog dialog = new AlertDialog.Builder(this)
				.setTitle(R.string.workshop_customers_add)
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
					repository.createCustomer(customerName, textOf(phone), textOf(notes));
					WorkshopUi.hideKeyboard(this);
					dialog.dismiss();
					loadCustomers();
				}));
		dialog.show();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu)
	{
		getMenuInflater().inflate(R.menu.workshop_customers, menu);
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
		if (item.getItemId() == R.id.workshop_hub_menu)
		{
			startActivity(new Intent(this, WorkshopHubActivity.class));
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private static String textOf(EditText field)
	{
		return field.getText() == null ? "" : field.getText().toString().trim();
	}
}
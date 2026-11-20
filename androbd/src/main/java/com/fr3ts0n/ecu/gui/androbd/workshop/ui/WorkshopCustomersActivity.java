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
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.fr3ts0n.ecu.gui.androbd.ModernUiUtils;
import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.SettingsActivity;
import com.fr3ts0n.ecu.gui.androbd.workshop.Customer;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopBackup;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopRepository;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
				if (uri != null) confirmRestore(uri);
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
		findViewById(R.id.workshop_backup).setOnClickListener(view -> backup());
		maybeNudgeBackup();
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

	/** Settings key: when the last successful backup happened. */
	private static final String KEY_LAST_BACKUP = "backup.last_at";
	/** Settings key: when the nudge was last shown, so it appears once a day. */
	private static final String KEY_LAST_NUDGE = "backup.last_nudge";

	/**
	 * Gentle reminder after the configured number of days without a backup.
	 * Shown at most once a day, and dismissed for the day if the mechanic says later.
	 */
	private void maybeNudgeBackup()
	{
		com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopSettingsRepository settings =
				new com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopSettingsRepository(
						repository.database());
		long days = Math.max(1, settings.getLong(
				com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopSettingsRepository.KEY_BACKUP_REMINDER_DAYS,
				7));
		long now = System.currentTimeMillis();
		long lastNudge = settings.getLong(KEY_LAST_NUDGE, 0);
		if (lastNudge > 0 && now - lastNudge < 24L * 60 * 60 * 1000) return;

		long lastBackup = settings.getLong(KEY_LAST_BACKUP, 0);
		long silentSince = lastBackup > 0 ? now - lastBackup : -1;
		if (lastBackup > 0 && silentSince < days * 24L * 60 * 60 * 1000) return;

		settings.put(KEY_LAST_NUDGE, String.valueOf(now));
		String message = lastBackup > 0
				? getString(R.string.workshop_backup_nudge_message, (int) (silentSince
						/ (24L * 60 * 60 * 1000)))
				: getString(R.string.workshop_backup_never);
		new AlertDialog.Builder(this)
				.setTitle(R.string.workshop_backup_nudge_title)
				.setMessage(message)
				.setNegativeButton(R.string.workshop_backup_nudge_later, null)
				.setPositiveButton(R.string.workshop_backup_now, (dialog, which) -> backup())
				.show();
	}

	/** Pick which table to write, then share the file like a backup. */
	private void chooseExport()
	{
		new AlertDialog.Builder(this)
				.setTitle(R.string.workshop_export_csv)
				.setItems(new String[]{getString(R.string.workshop_export_customers),
						getString(R.string.workshop_export_services),
						getString(R.string.workshop_export_unpaid)}, (dialog, which) -> export(which))
				.setNegativeButton(R.string.workshop_action_cancel, null)
				.show();
	}

	private void export(int which)
	{
		try
		{
			File folder = new File(getExternalFilesDir(null), "exports");
			if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("exports folder");
			String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
					.format(new java.util.Date());
			String name = which == 0 ? "pelanggan-" : which == 1 ? "servis-" : "belum-lunas-";
			File target = new File(folder, name + stamp + ".csv");
			try (java.io.Writer out = new java.io.OutputStreamWriter(
					new java.io.FileOutputStream(target), java.nio.charset.StandardCharsets.UTF_8))
			{
				// BOM so Excel opens Indonesian text correctly.
				out.write('\uFEFF');
				if (which == 0) com.fr3ts0n.ecu.gui.androbd.workshop.CsvExporter
						.writeCustomers(repository, out);
				else if (which == 1) com.fr3ts0n.ecu.gui.androbd.workshop.CsvExporter
						.writeServices(repository, out);
				else com.fr3ts0n.ecu.gui.androbd.workshop.CsvExporter.writeUnpaid(repository, out);
			}
			Toast.makeText(this, getString(R.string.workshop_export_done, target.getName()),
					Toast.LENGTH_LONG).show();
		}
		catch (IOException | RuntimeException error)
		{
			showError(R.string.workshop_export_failed, error);
		}
	}

	private void backup()
	{
		try
		{
			File target = WorkshopBackup.defaultExportFile(this);
			WorkshopBackup.export(repository.database(), target);
			new com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopSettingsRepository(repository.database())
					.put(KEY_LAST_BACKUP, String.valueOf(System.currentTimeMillis()));
			new AlertDialog.Builder(this)
					.setTitle(R.string.workshop_backup_title)
					.setMessage(getString(R.string.workshop_backup_done, target.getAbsolutePath()))
					.setPositiveButton(android.R.string.ok, null)
					.setNeutralButton(R.string.workshop_backup_share,
							(dialog, which) -> shareBackup(target))
					.show();
		}
		catch (IOException | RuntimeException error)
		{
			showError(R.string.workshop_backup_failed, error);
		}
	}

	private void shareBackup(File file)
	{
		Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", file);
		Intent send = new Intent(Intent.ACTION_SEND)
				.setType(file.getName().endsWith(".zip") ? "application/zip" : "application/octet-stream")
				.putExtra(Intent.EXTRA_STREAM, uri)
				.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.workshop_backup_subject))
				.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		startActivity(Intent.createChooser(send, getString(R.string.workshop_backup_share)));
	}

	private void confirmRestore(Uri uri)
	{
		new AlertDialog.Builder(this)
				.setTitle(R.string.workshop_restore_title)
				.setMessage(R.string.workshop_restore_message)
				.setNegativeButton(R.string.workshop_action_cancel, null)
				.setPositiveButton(R.string.workshop_restore_action, (dialog, which) -> restore(uri))
				.show();
	}

	private void restore(Uri uri)
	{
		new Thread(() ->
		{
			String failure = null;
			File temp = new File(getCacheDir(), "restore-" + System.currentTimeMillis() + ".db");
			try (InputStream in = getContentResolver().openInputStream(uri);
				 OutputStream out = new FileOutputStream(temp))
			{
				if (in == null) throw new IOException("Cannot read the selected file");
				byte[] buffer = new byte[8192];
				int read;
				long total = 0;
				while ((read = in.read(buffer)) != -1)
				{
					total += read;
					if (total > 512L * 1024 * 1024) throw new IOException("Backup is too large");
					out.write(buffer, 0, read);
				}
				out.flush();
				WorkshopBackup.restore(repository.database(), temp);
			}
			catch (IOException | RuntimeException error)
			{
				failure = error.getMessage() == null ? error.toString() : error.getMessage();
			}
			finally
			{
				if (temp.exists() && !temp.delete()) temp.deleteOnExit();
			}

			String result = failure;
			runOnUiThread(() ->
			{
				if (result == null)
				{
					Toast.makeText(this, R.string.workshop_restore_done, Toast.LENGTH_LONG).show();
					loadCustomers();
				}
				else
				{
					new AlertDialog.Builder(this)
							.setTitle(R.string.workshop_restore_title)
							.setMessage(getString(R.string.workshop_restore_failed, result))
							.setPositiveButton(android.R.string.ok, null)
							.show();
				}
			});
		}, "workshop-restore").start();
	}

	private void showError(int format, Exception error)
	{
		String detail = error.getMessage() == null ? error.toString() : error.getMessage();
		new AlertDialog.Builder(this)
				.setTitle(R.string.workshop_data_title)
				.setMessage(getString(format, detail))
				.setPositiveButton(android.R.string.ok, null)
				.show();
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
		if (item.getItemId() == R.id.workshop_export_csv)
		{
			chooseExport();
			return true;
		}
		if (item.getItemId() == R.id.workshop_due_menu)
		{
			startActivity(new Intent(this, WorkshopDueActivity.class));
			return true;
		}
		if (item.getItemId() == R.id.workshop_canned_menu)
		{
			startActivity(new Intent(this, WorkshopCannedJobsActivity.class));
			return true;
		}
		if (item.getItemId() == R.id.workshop_profile_menu)
		{
			startActivity(new Intent(this, WorkshopProfileActivity.class));
			return true;
		}
		if (item.getItemId() == R.id.workshop_restore)
		{
			openBackup.launch(new String[]{"*/*"});
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private static String textOf(EditText field)
	{
		return field.getText() == null ? "" : field.getText().toString().trim();
	}
}
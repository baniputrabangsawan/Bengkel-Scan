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
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.fr3ts0n.ecu.gui.androbd.ModernUiUtils;
import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.SettingsActivity;
import com.fr3ts0n.ecu.gui.androbd.workshop.ApprovalState;
import com.fr3ts0n.ecu.gui.androbd.workshop.CannedJob;
import com.fr3ts0n.ecu.gui.androbd.workshop.CannedJobRepository;
import com.fr3ts0n.ecu.gui.androbd.workshop.EstimateItem;
import com.fr3ts0n.ecu.gui.androbd.workshop.ItemKind;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopRepository;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

/**
 * Job packages: reusable estimate lines for work the workshop repeats.
 *
 * <p>Applying a package copies its lines into the estimate. The package is
 * never linked to the estimate, so later edits touch only the copy.</p>
 */
public class WorkshopCannedJobsActivity extends AppCompatActivity
{
	private CannedJobRepository jobs;
	private WorkshopRepository repository;
	private WorkshopRowAdapter adapter;
	private TextView empty;
	private long applyToService = -1;

	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		SettingsActivity.applyLocale(this);
		super.onCreate(savedInstanceState);
		setContentView(R.layout.workshop_canned);
		ModernUiUtils.applyScreenInsets(this);
		serviceIdExtra();
		repository = WorkshopUi.repository(this);
		jobs = new CannedJobRepository(repository.database());
		WorkshopUi.prepare(this, getString(R.string.workshop_canned_title));

		empty = findViewById(R.id.workshop_empty);
		ListView list = findViewById(R.id.workshop_list);
		adapter = new WorkshopRowAdapter(this);
		list.setAdapter(adapter);
		list.setOnItemClickListener((parent, view, position, id) ->
		{
			WorkshopRow row = adapter.getItem(position);
			if (applyToService > 0) apply(row.id);
			else edit(row.id);
		});
		list.setOnItemLongClickListener((parent, view, position, id) ->
		{
			confirmDelete(adapter.getItem(position));
			return true;
		});
		findViewById(R.id.workshop_add).setOnClickListener(view -> edit(-1));
	}

	private void serviceIdExtra()
	{
		applyToService = getIntent().getLongExtra(WorkshopUi.EXTRA_SERVICE_ID, -1);
		if (applyToService > 0 && new WorkshopRepository(
				com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopDatabase.getInstance(this))
				.findService(applyToService) == null)
		{
			applyToService = -1;
		}
	}

	@Override
	protected void onResume()
	{
		super.onResume();
		if (jobs != null) load();
	}

	private void load()
	{
		// Seed once per database; the marker note makes the examples obvious.
		com.fr3ts0n.ecu.gui.androbd.workshop.CannedJobSeeds.seedIfEmpty(jobs,
				getResources().getStringArray(R.array.workshop_canned_seed_names),
				getString(R.string.workshop_canned_seed_note));
		List<CannedJob> all = jobs.list();
		List<WorkshopRow> rows = new ArrayList<>(all.size());
		for (CannedJob job : all)
		{
			StringBuilder subtitle = new StringBuilder(
					getString(R.string.workshop_canned_lines, job.items.size()));
			if (!job.note.isEmpty()) subtitle.append(" · ").append(job.note);
			rows.add(new WorkshopRow(job.id, job.name, subtitle.toString(), null));
		}
		adapter.submit(rows);
		empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
	}

	/** Create (id {@code < 0}) or rename a package. */
	private void edit(long id)
	{
		CannedJob existing = id > 0 ? jobs.find(id) : null;
		View form = getLayoutInflater().inflate(R.layout.dialog_canned_job, null);
		TextInputEditText name = form.findViewById(R.id.workshop_canned_name);
		TextInputEditText note = form.findViewById(R.id.workshop_canned_note);
		if (existing != null)
		{
			name.setText(existing.name);
			note.setText(existing.note);
		}
		AlertDialog dialog = new AlertDialog.Builder(this)
				.setTitle(existing == null ? R.string.workshop_canned_add : R.string.workshop_canned_title)
				.setView(form)
				.setNegativeButton(R.string.workshop_action_cancel, null)
				.setPositiveButton(R.string.workshop_action_save, null)
				.create();
		dialog.setOnShowListener(ignored ->
				dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view ->
				{
					String packageName = textOf(name);
					if (packageName.isEmpty())
					{
						name.setError(getString(R.string.workshop_canned_error_name));
						name.requestFocus();
						return;
					}
					long jobId;
					if (existing == null)
					{
						jobId = jobs.create(packageName, textOf(note));
						seedStarters(jobId);
					}
					else
					{
						jobs.rename(existing.id, packageName, textOf(note));
						jobId = existing.id;
					}
					WorkshopUi.hideKeyboard(this);
					dialog.dismiss();
					load();
					Toast.makeText(this, R.string.workshop_canned_saved, Toast.LENGTH_SHORT).show();
					manageLines(jobId);
				}));
		dialog.show();
	}

	/** A new package starts with one example line so the list is never empty. */
	private void seedStarters(long jobId)
	{
		jobs.addItem(jobId, ItemKind.LABOUR, getString(R.string.workshop_item_labour), 1, 0);
	}

	private void manageLines(long jobId)
	{
		CannedJob job = jobs.find(jobId);
		if (job == null) return;
		String[] labels = new String[job.items.size() + 1];
		labels[0] = getString(R.string.workshop_canned_item_add);
		for (int i = 0; i < job.items.size(); i++)
		{
			EstimateItem item = job.items.get(i);
			labels[i + 1] = item.description + " · " + WorkshopUi.money(item.unitPrice);
		}
		new AlertDialog.Builder(this)
				.setTitle(job.name)
				.setItems(labels, (dialog, which) ->
				{
					if (which == 0) addLine(jobId);
					else removeLine(job.items.get(which - 1));
				})
				.setNegativeButton(R.string.workshop_action_cancel, null)
				.show();
	}

	private void addLine(long jobId)
	{
		View form = getLayoutInflater().inflate(R.layout.dialog_text, null);
		TextInputEditText text = form.findViewById(R.id.workshop_text);
		AlertDialog dialog = new AlertDialog.Builder(this)
				.setTitle(R.string.workshop_canned_item_add)
				.setView(form)
				.setNegativeButton(R.string.workshop_action_cancel, null)
				.setPositiveButton(R.string.workshop_action_save, null)
				.create();
		dialog.setOnShowListener(ignored ->
				dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view ->
				{
					String line = textOf(text);
					if (line.isEmpty())
					{
						text.setError(getString(R.string.workshop_item_description));
						text.requestFocus();
						return;
					}
					jobs.addItem(jobId, ItemKind.PART, line, 1, 0);
					dialog.dismiss();
					load();
					manageLines(jobId);
				}));
		dialog.show();
	}

	private void removeLine(EstimateItem item)
	{
		new AlertDialog.Builder(this)
				.setTitle(R.string.workshop_delete_item_title)
				.setMessage(getString(R.string.workshop_delete_item_message, item.description))
				.setNegativeButton(R.string.workshop_action_cancel, null)
				.setPositiveButton(R.string.workshop_action_delete, (dialog, which) ->
				{
					jobs.deleteItem(item.id);
					load();
				})
				.show();
	}

	private void apply(long jobId)
	{
		int added = jobs.applyTo(jobId, applyToService, repository).size();
		Toast.makeText(this, getString(R.string.workshop_canned_applied, added),
				Toast.LENGTH_SHORT).show();
		finish();
	}

	private void confirmDelete(WorkshopRow row)
	{
		new AlertDialog.Builder(this)
				.setTitle(R.string.workshop_canned_delete_title)
				.setMessage(getString(R.string.workshop_canned_delete_message, row.title))
				.setNegativeButton(R.string.workshop_action_cancel, null)
				.setPositiveButton(R.string.workshop_action_delete, (dialog, which) ->
				{
					jobs.delete(row.id);
					load();
				})
				.show();
	}

	private static String textOf(TextInputEditText field)
	{
		return field.getText() == null ? "" : field.getText().toString().trim();
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

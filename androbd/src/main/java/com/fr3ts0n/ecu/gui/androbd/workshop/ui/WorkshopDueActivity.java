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
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.fr3ts0n.ecu.gui.androbd.ModernUiUtils;
import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.SettingsActivity;
import com.fr3ts0n.ecu.gui.androbd.workshop.Customer;
import com.fr3ts0n.ecu.gui.androbd.workshop.Reminder;
import com.fr3ts0n.ecu.gui.androbd.workshop.ReminderRepository;
import com.fr3ts0n.ecu.gui.androbd.workshop.ReminderStatus;
import com.fr3ts0n.ecu.gui.androbd.workshop.Vehicle;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopRepository;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Vehicles whose service is due or overdue.
 *
 * <p>Nothing is sent automatically and no notification is posted: the mechanic
 * opens this screen and can send a prefilled message himself.</p>
 */
public class WorkshopDueActivity extends AppCompatActivity
{
	private WorkshopRepository repository;
	private ReminderRepository reminders;
	private WorkshopRowAdapter adapter;
	private TextView empty;
	private final List<Reminder> shown = new ArrayList<>();

	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		SettingsActivity.applyLocale(this);
		super.onCreate(savedInstanceState);
		setContentView(R.layout.workshop_due);
		ModernUiUtils.applyScreenInsets(this);
		repository = WorkshopUi.repository(this);
		reminders = new ReminderRepository(repository.database());
		WorkshopUi.prepare(this, getString(R.string.workshop_due_title));

		empty = findViewById(R.id.workshop_empty);
		ListView list = findViewById(R.id.workshop_list);
		adapter = new WorkshopRowAdapter(this);
		list.setAdapter(adapter);
		list.setOnItemClickListener((parent, view, position, id) ->
				actions(shown.get(position)));
	}

	@Override
	protected void onResume()
	{
		super.onResume();
		if (repository != null) load();
	}

	private void load()
	{
		long now = System.currentTimeMillis();
		shown.clear();
		List<WorkshopRow> rows = new ArrayList<>();
		for (Reminder reminder : reminders.open())
		{
			Vehicle vehicle = repository.findVehicle(reminder.vehicleId);
			if (vehicle == null) continue;
			ReminderStatus.State state =
					ReminderStatus.evaluate(reminder, vehicle.odometerKm, now);
			boolean soon = state == ReminderStatus.State.NOT_DUE
					&& ReminderStatus.dueWithinDays(reminder, now, 14);
			if (state == ReminderStatus.State.NOT_DUE && !soon) continue;
			shown.add(reminder);
			StringBuilder subtitle = new StringBuilder();
			if (reminder.dueDate != null)
			{
				subtitle.append(getString(R.string.workshop_due_by_date,
						android.text.format.DateFormat.getDateFormat(this)
								.format(new Date(reminder.dueDate))));
			}
			if (reminder.dueOdometerKm != null)
			{
				if (subtitle.length() > 0) subtitle.append(" · ");
				subtitle.append(getString(R.string.workshop_due_by_km, reminder.dueOdometerKm));
			}
			if (!reminder.note.isEmpty()) subtitle.append(" · ").append(reminder.note);
			rows.add(new WorkshopRow(reminder.id, vehicle.plate, subtitle.toString(),
					getString(label(state)), tone(state)));
		}
		adapter.submit(rows);
		empty.setVisibility(rows.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
	}

	private static int label(ReminderStatus.State state)
	{
		switch (state)
		{
			case OVERDUE:
				return R.string.workshop_due_overdue;
			case TODAY:
				return R.string.workshop_due_today;
			case NOT_DUE:
			default:
				return R.string.workshop_due_soon;
		}
	}

	/** Overdue is the only red state here; a reminder that is merely near is a warning. */
	private static RowTone tone(ReminderStatus.State state)
	{
		return state == ReminderStatus.State.OVERDUE ? RowTone.DANGER : RowTone.WARNING;
	}

	/** Offer the two useful actions: send the message, or mark it handled. */
	private void actions(final Reminder reminder)
	{
		Vehicle vehicle = repository.findVehicle(reminder.vehicleId);
		if (vehicle == null) return;
		new AlertDialog.Builder(this)
				.setTitle(vehicle.plate)
				.setItems(new String[]{getString(R.string.workshop_due_send),
						getString(R.string.workshop_due_dismiss)}, (dialog, which) ->
				{
					if (which == 0) sendMessage(reminder, vehicle);
					else
					{
						reminders.dismiss(reminder.id);
						load();
					}
				})
				.setNegativeButton(R.string.workshop_action_cancel, null)
				.show();
	}

	private void sendMessage(Reminder reminder, Vehicle vehicle)
	{
		Customer customer = repository.findCustomer(vehicle.customerId);
		StringBuilder detail = new StringBuilder();
		if (reminder.dueDate != null)
		{
			detail.append(getString(R.string.workshop_due_message_date,
					android.text.format.DateFormat.getDateFormat(this)
							.format(new Date(reminder.dueDate))));
		}
		if (reminder.dueOdometerKm != null)
		{
			detail.append(getString(R.string.workshop_due_message_km, reminder.dueOdometerKm));
		}
		String body = getString(R.string.workshop_due_message,
				customer == null ? "" : customer.name, vehicle.plate, detail.toString());
		Intent send = new Intent(Intent.ACTION_SEND)
				.setType("text/plain")
				.putExtra(Intent.EXTRA_TEXT, body);
		startActivity(Intent.createChooser(send, getString(R.string.workshop_due_send)));
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

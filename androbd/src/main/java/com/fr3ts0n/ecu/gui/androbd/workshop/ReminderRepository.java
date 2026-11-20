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
package com.fr3ts0n.ecu.gui.androbd.workshop;

import android.content.ContentValues;
import android.database.Cursor;

import java.util.ArrayList;
import java.util.List;

/**
 * Service reminders per vehicle: a due date and/or a due odometer.
 *
 * <p>Nothing here schedules background work. A reminder is data the mechanic
 * sees on the "Jatuh tempo" screen; the app never posts a notification.</p>
 */
public final class ReminderRepository
{
	private static final String[] COLUMNS = {"id", "vehicle_id", "due_date", "due_odometer_km",
			"note", "dismissed_at", "created_at"};

	private final WorkshopDatabase database;

	public ReminderRepository(WorkshopDatabase database)
	{
		this.database = database;
	}

	public long create(long vehicleId, Long dueDate, Integer dueOdometerKm, String note)
	{
		if (dueDate == null && dueOdometerKm == null)
		{
			throw new IllegalArgumentException("reminder.target.required");
		}
		if (dueOdometerKm != null && dueOdometerKm < 0)
		{
			throw new IllegalArgumentException("reminder.odometer.invalid");
		}
		ContentValues values = new ContentValues();
		values.put("vehicle_id", vehicleId);
		if (dueDate == null) values.putNull("due_date");
		else values.put("due_date", dueDate);
		if (dueOdometerKm == null) values.putNull("due_odometer_km");
		else values.put("due_odometer_km", dueOdometerKm);
		values.put("note", note == null ? "" : note.trim());
		values.putNull("dismissed_at");
		values.put("created_at", System.currentTimeMillis());
		return database.getWritableDatabase().insertOrThrow("reminders", null, values);
	}

	/** Open reminders only; dismissed rows stay for history but are not shown. */
	public List<Reminder> open()
	{
		return read("dismissed_at IS NULL", null, "due_date IS NULL, due_date ASC, id ASC");
	}

	public List<Reminder> forVehicle(long vehicleId)
	{
		return read("vehicle_id = ?", new String[]{String.valueOf(vehicleId)}, "id DESC");
	}

	public boolean dismiss(long id)
	{
		ContentValues values = new ContentValues();
		values.put("dismissed_at", System.currentTimeMillis());
		return database.getWritableDatabase().update("reminders", values,
				"id = ? AND dismissed_at IS NULL", new String[]{String.valueOf(id)}) > 0;
	}

	public boolean update(long id, Long dueDate, Integer dueOdometerKm, String note)
	{
		if (dueDate == null && dueOdometerKm == null)
		{
			throw new IllegalArgumentException("reminder.target.required");
		}
		ContentValues values = new ContentValues();
		if (dueDate == null) values.putNull("due_date");
		else values.put("due_date", dueDate);
		if (dueOdometerKm == null) values.putNull("due_odometer_km");
		else values.put("due_odometer_km", dueOdometerKm);
		values.put("note", note == null ? "" : note.trim());
		return database.getWritableDatabase().update("reminders", values,
				"id = ?", new String[]{String.valueOf(id)}) > 0;
	}

	public boolean delete(long id)
	{
		return database.getWritableDatabase().delete("reminders",
				"id = ?", new String[]{String.valueOf(id)}) > 0;
	}

	private List<Reminder> read(String selection, String[] args, String orderBy)
	{
		List<Reminder> reminders = new ArrayList<>();
		try (Cursor cursor = database.getReadableDatabase().query("reminders", COLUMNS,
				selection, args, null, null, orderBy))
		{
			while (cursor.moveToNext())
			{
				reminders.add(new Reminder(cursor.getLong(0), cursor.getLong(1),
						cursor.isNull(2) ? null : cursor.getLong(2),
						cursor.isNull(3) ? null : cursor.getInt(3), cursor.getString(4),
						cursor.isNull(5) ? null : cursor.getLong(5), cursor.getLong(6)));
			}
		}
		return reminders;
	}
}

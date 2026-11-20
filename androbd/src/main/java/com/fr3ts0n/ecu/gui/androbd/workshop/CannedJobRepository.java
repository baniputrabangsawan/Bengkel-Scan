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
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

/**
 * Reusable packages of estimate lines ("paket pekerjaan").
 *
 * <p>Applying a package copies its lines into the estimate. The package itself
 * never changes when a copy is edited, and an estimate is never linked to a
 * package, so deleting a package cannot alter an existing service.</p>
 */
public final class CannedJobRepository
{
	private final WorkshopDatabase database;

	public CannedJobRepository(WorkshopDatabase database)
	{
		this.database = database;
	}

	public long create(String name, String note)
	{
		long now = System.currentTimeMillis();
		ContentValues values = new ContentValues();
		values.put("name", requireText(name, "canned.name"));
		values.put("note", note == null ? "" : note.trim());
		values.put("created_at", now);
		values.put("updated_at", now);
		return database.getWritableDatabase().insertOrThrow("canned_jobs", null, values);
	}

	public List<CannedJob> list()
	{
		List<CannedJob> jobs = new ArrayList<>();
		try (Cursor cursor = database.getReadableDatabase().query("canned_jobs",
				new String[]{"id", "name", "note"}, null, null, null, null, "name COLLATE NOCASE ASC"))
		{
			while (cursor.moveToNext())
			{
				long id = cursor.getLong(0);
				jobs.add(new CannedJob(id, cursor.getString(1), cursor.getString(2), items(id)));
			}
		}
		return jobs;
	}

	public CannedJob find(long id)
	{
		try (Cursor cursor = database.getReadableDatabase().query("canned_jobs",
				new String[]{"name", "note"}, "id = ?", new String[]{String.valueOf(id)},
				null, null, null))
		{
			return cursor.moveToFirst()
					? new CannedJob(id, cursor.getString(0), cursor.getString(1), items(id))
					: null;
		}
	}

	public List<EstimateItem> items(long jobId)
	{
		List<EstimateItem> items = new ArrayList<>();
		try (Cursor cursor = database.getReadableDatabase().query("canned_job_items",
				new String[]{"id", "job_id", "kind", "description", "quantity", "unit_price", "position"},
				"job_id = ?", new String[]{String.valueOf(jobId)},
				null, null, "position ASC, id ASC"))
		{
			while (cursor.moveToNext())
			{
				items.add(new EstimateItem(cursor.getLong(0), cursor.getLong(1),
						ItemKind.fromStored(cursor.getString(2)), cursor.getString(3),
						cursor.getDouble(4), cursor.getLong(5), 0, ApprovalState.PENDING, null,
						cursor.getInt(6)));
			}
		}
		return items;
	}

	public long addItem(long jobId, ItemKind kind, String description, double quantity, long unitPrice)
	{
		if (quantity <= 0) throw new IllegalArgumentException("canned.quantity");
		if (unitPrice < 0) throw new IllegalArgumentException("canned.unit_price");
		ContentValues values = new ContentValues();
		values.put("job_id", jobId);
		values.put("kind", (kind == null ? ItemKind.PART : kind).name());
		values.put("description", requireText(description, "canned.description"));
		values.put("quantity", quantity);
		values.put("unit_price", unitPrice);
		values.put("position", nextPosition(jobId));
		return database.getWritableDatabase().insertOrThrow("canned_job_items", null, values);
	}

	public boolean deleteItem(long itemId)
	{
		return database.getWritableDatabase().delete("canned_job_items",
				"id = ?", new String[]{String.valueOf(itemId)}) > 0;
	}

	public boolean rename(long id, String name, String note)
	{
		ContentValues values = new ContentValues();
		values.put("name", requireText(name, "canned.name"));
		values.put("note", note == null ? "" : note.trim());
		values.put("updated_at", System.currentTimeMillis());
		return database.getWritableDatabase().update("canned_jobs", values,
				"id = ?", new String[]{String.valueOf(id)}) > 0;
	}

	public boolean delete(long id)
	{
		return database.getWritableDatabase().delete("canned_jobs",
				"id = ?", new String[]{String.valueOf(id)}) > 0;
	}

	/** Copy a package into one service estimate. */
	public List<Long> applyTo(long jobId, long serviceId, WorkshopRepository estimates)
	{
		List<Long> created = new ArrayList<>();
		for (EstimateItem item : items(jobId))
		{
			created.add(estimates.addEstimateItem(serviceId, item.kind, item.description,
					item.quantity, item.unitPrice));
		}
		return created;
	}

	private int nextPosition(long jobId)
	{
		try (Cursor cursor = database.getReadableDatabase().rawQuery(
				"SELECT COALESCE(MAX(position), -1) + 1 FROM canned_job_items WHERE job_id = ?",
				new String[]{String.valueOf(jobId)}))
		{
			return cursor.moveToFirst() ? cursor.getInt(0) : 0;
		}
	}

	private static String requireText(String value, String reason)
	{
		if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(reason);
		return value.trim();
	}
}

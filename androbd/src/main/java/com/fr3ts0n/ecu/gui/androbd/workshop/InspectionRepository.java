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
 * Inspection items for one service: name, state, note and optional voice note.
 *
 * <p>Deletion is soft ({@code deleted_at}) so a printed report stays
 * reproducible after the mechanic removes a line by mistake.</p>
 */
public final class InspectionRepository
{
	private static final String[] COLUMNS = {"id", "service_id", "name", "state", "note",
			"voice_path", "position", "created_at", "updated_at"};

	private final WorkshopDatabase database;

	public InspectionRepository(WorkshopDatabase database)
	{
		this.database = database;
	}

	public long create(long serviceId, String name, InspectionState state, String note)
	{
		long now = System.currentTimeMillis();
		ContentValues values = new ContentValues();
		values.put("service_id", serviceId);
		values.put("name", requireText(name, "inspection.name"));
		values.put("state", (state == null ? InspectionState.GOOD : state).name());
		values.put("note", textOrEmpty(note));
		values.put("position", nextPosition(serviceId));
		values.put("created_at", now);
		values.put("updated_at", now);
		return database.getWritableDatabase().insertOrThrow("inspection_items", null, values);
	}

	/** Live items only, in mechanic order. */
	public List<InspectionItem> forService(long serviceId)
	{
		List<InspectionItem> items = new ArrayList<>();
		try (Cursor cursor = database.getReadableDatabase().query("inspection_items", COLUMNS,
				"service_id = ? AND deleted_at IS NULL", new String[]{String.valueOf(serviceId)},
				null, null, "position ASC, id ASC"))
		{
			while (cursor.moveToNext()) items.add(read(cursor));
		}
		return items;
	}

	public InspectionItem find(long id)
	{
		try (Cursor cursor = database.getReadableDatabase().query("inspection_items", COLUMNS,
				"id = ? AND deleted_at IS NULL", new String[]{String.valueOf(id)}, null, null, null))
		{
			return cursor.moveToFirst() ? read(cursor) : null;
		}
	}

	/**
	 * Update a live item.
	 *
	 * @throws IllegalArgumentException when the row is missing or already deleted,
	 *                                  so a stale screen cannot resurrect it
	 */
	public boolean update(long id, String name, InspectionState state, String note)
	{
		ContentValues values = new ContentValues();
		values.put("name", requireText(name, "inspection.name"));
		values.put("state", (state == null ? InspectionState.GOOD : state).name());
		values.put("note", textOrEmpty(note));
		values.put("updated_at", System.currentTimeMillis());
		return database.getWritableDatabase().update("inspection_items", values,
				"id = ? AND deleted_at IS NULL", new String[]{String.valueOf(id)}) > 0;
	}

	/** Attach or clear the voice note path of a live item. */
	public boolean setVoicePath(long id, String relativePath)
	{
		ContentValues values = new ContentValues();
		values.put("voice_path", textOrEmpty(relativePath));
		values.put("updated_at", System.currentTimeMillis());
		return database.getWritableDatabase().update("inspection_items", values,
				"id = ? AND deleted_at IS NULL", new String[]{String.valueOf(id)}) > 0;
	}

	public boolean delete(long id)
	{
		ContentValues values = new ContentValues();
		values.put("deleted_at", System.currentTimeMillis());
		return database.getWritableDatabase().update("inspection_items", values,
				"id = ? AND deleted_at IS NULL", new String[]{String.valueOf(id)}) > 0;
	}

	private int nextPosition(long serviceId)
	{
		try (Cursor cursor = database.getReadableDatabase().rawQuery(
				"SELECT COALESCE(MAX(position), -1) + 1 FROM inspection_items WHERE service_id = ?",
				new String[]{String.valueOf(serviceId)}))
		{
			return cursor.moveToFirst() ? cursor.getInt(0) : 0;
		}
	}

	private static InspectionItem read(Cursor c)
	{
		return new InspectionItem(c.getLong(0), c.getLong(1), c.getString(2),
				InspectionState.fromStored(c.getString(3)), c.getString(4), c.getString(5),
				c.getInt(6), c.getLong(7), c.getLong(8));
	}

	private static String requireText(String value, String reason)
	{
		if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(reason);
		return value.trim();
	}

	private static String textOrEmpty(String value)
	{
		return value == null ? "" : value.trim();
	}
}

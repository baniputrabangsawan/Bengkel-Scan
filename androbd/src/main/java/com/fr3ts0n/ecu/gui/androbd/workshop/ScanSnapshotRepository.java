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

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/**
 * Frozen OBD readings linked to a service, kept as JSON so a later app version
 * can still show what the adapter reported.
 *
 * <p>A snapshot is written once. Callers must save it while the reading is in
 * memory: switching OBD service or reconnecting can clear the live lists.</p>
 */
public final class ScanSnapshotRepository
{
	private static final String[] COLUMNS = {"id", "service_id", "phase", "source", "adapter",
			"codes_json", "readings_json", "freeze_json", "note", "created_at"};

	private final WorkshopDatabase database;

	public ScanSnapshotRepository(WorkshopDatabase database)
	{
		this.database = database;
	}

	/**
	 * Save one snapshot.
	 *
	 * @param codesJson    JSON array of fault codes; must parse
	 * @param readingsJson JSON array of live readings; must parse
	 * @param freezeJson   JSON array of freeze-frame rows; must parse
	 */
	public long save(long serviceId, ScanPhase phase, ScanSource source, String adapter,
			String codesJson, String readingsJson, String freezeJson, String note)
	{
		if (phase == null) throw new IllegalArgumentException("scan.phase.required");
		if (source == null) throw new IllegalArgumentException("scan.source.required");
		requireArray(codesJson, "scan.codes.invalid");
		requireArray(readingsJson, "scan.readings.invalid");
		requireArray(freezeJson, "scan.freeze.invalid");
		ContentValues values = new ContentValues();
		values.put("service_id", serviceId);
		values.put("phase", phase.name());
		values.put("source", source.name());
		values.put("adapter", textOrEmpty(adapter));
		values.put("codes_json", codesJson);
		values.put("readings_json", readingsJson);
		values.put("freeze_json", freezeJson);
		values.put("note", textOrEmpty(note));
		values.put("created_at", System.currentTimeMillis());
		return database.getWritableDatabase().insertOrThrow("scan_snapshots", null, values);
	}

	public List<ScanSnapshot> forService(long serviceId)
	{
		List<ScanSnapshot> snapshots = new ArrayList<>();
		try (Cursor cursor = database.getReadableDatabase().query("scan_snapshots", COLUMNS,
				"service_id = ?", new String[]{String.valueOf(serviceId)},
				null, null, "created_at ASC, id ASC"))
		{
			while (cursor.moveToNext()) snapshots.add(read(cursor));
		}
		return snapshots;
	}

	/** The newest snapshot of one phase, or {@code null} when the mechanic never saved one. */
	public ScanSnapshot latest(long serviceId, ScanPhase phase)
	{
		try (Cursor cursor = database.getReadableDatabase().query("scan_snapshots", COLUMNS,
				"service_id = ? AND phase = ?",
				new String[]{String.valueOf(serviceId), phase.name()},
				null, null, "created_at DESC, id DESC", "1"))
		{
			return cursor.moveToFirst() ? read(cursor) : null;
		}
	}

	public boolean delete(long id)
	{
		return database.getWritableDatabase().delete("scan_snapshots",
				"id = ?", new String[]{String.valueOf(id)}) > 0;
	}

	private static ScanSnapshot read(Cursor c)
	{
		return new ScanSnapshot(c.getLong(0), c.getLong(1), ScanPhase.fromStored(c.getString(2)),
				ScanSource.fromStored(c.getString(3)), c.getString(4), c.getString(5),
				c.getString(6), c.getString(7), c.getString(8), c.getLong(9));
	}

	private static void requireArray(String json, String reason)
	{
		if (json == null) throw new IllegalArgumentException(reason);
		try
		{
			new JSONArray(json);
		}
		catch (JSONException ignored)
		{
			throw new IllegalArgumentException(reason);
		}
	}

	private static String textOrEmpty(String value)
	{
		return value == null ? "" : value.trim();
	}
}

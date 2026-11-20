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
 * Workshop profile, settings rows and audit trail.
 *
 * <p>These are process-wide app data, not per-service records: the profile is a
 * single row, settings are key/value strings, and the audit log is append-only.
 * Call these off the UI thread.</p>
 */
public final class WorkshopSettingsRepository
{
	/** Setting key: agreed PDF footer text. */
	public static final String KEY_PDF_FOOTER = "pdf.footer";
	/** Setting key: how often to nudge the mechanic to back up, in days. */
	public static final String KEY_BACKUP_REMINDER_DAYS = "backup.reminder_days";

	private final WorkshopDatabase database;

	public WorkshopSettingsRepository(WorkshopDatabase database)
	{
		this.database = database;
	}

	public WorkshopDatabase database()
	{
		return database;
	}

	// ------------------------------------------------------------------ profile

	/** The saved profile, or a blank one when the mechanic has not filled it in. */
	public WorkshopProfile profile()
	{
		try (Cursor cursor = database.getReadableDatabase().query("workshop_profile",
				new String[]{"name", "address", "phone", "footer", "logo_path", "updated_at"},
				null, null, null, null, null))
		{
			if (cursor.moveToFirst())
			{
				return new WorkshopProfile(cursor.getString(0), cursor.getString(1),
						cursor.getString(2), cursor.getString(3), cursor.getString(4), cursor.getLong(5));
			}
		}
		return new WorkshopProfile("", "", "", "", "", 0);
	}

	public boolean saveProfile(String name, String address, String phone, String footer, String logoPath)
	{
		ContentValues values = new ContentValues();
		values.put("id", 1);
		values.put("name", textOrEmpty(name));
		values.put("address", textOrEmpty(address));
		values.put("phone", textOrEmpty(phone));
		values.put("footer", textOrEmpty(footer));
		values.put("logo_path", textOrEmpty(logoPath));
		values.put("updated_at", System.currentTimeMillis());
		SQLiteDatabase db = database.getWritableDatabase();
		if (db.update("workshop_profile", values, "id = 1", null) > 0) return true;
		return db.insertOrThrow("workshop_profile", null, values) > 0;
	}

	// ----------------------------------------------------------------- settings

	public String get(String key, String fallback)
	{
		try (Cursor cursor = database.getReadableDatabase().query("settings",
				new String[]{"value"}, "key = ?", new String[]{key}, null, null, null))
		{
			return cursor.moveToFirst() ? cursor.getString(0) : fallback;
		}
	}

	public long getLong(String key, long fallback)
	{
		String stored = get(key, null);
		if (stored == null || stored.trim().isEmpty()) return fallback;
		try
		{
			return Long.parseLong(stored.trim());
		}
		catch (NumberFormatException ignored)
		{
			return fallback;
		}
	}

	public boolean put(String key, String value)
	{
		if (key == null || key.trim().isEmpty()) throw new IllegalArgumentException("settings.key");
		ContentValues values = new ContentValues();
		values.put("key", key.trim());
		values.put("value", textOrEmpty(value));
		values.put("updated_at", System.currentTimeMillis());
		return database.getWritableDatabase().insertWithOnConflict("settings", null, values,
				SQLiteDatabase.CONFLICT_REPLACE) > 0;
	}

	// ---------------------------------------------------------------- audit log

	/** Append an action. The log has no update or delete path. */
	public long record(String action, String targetKind, Long targetId, String detail)
	{
		if (action == null || action.trim().isEmpty()) throw new IllegalArgumentException("audit.action");
		ContentValues values = new ContentValues();
		values.put("action", action.trim());
		values.put("target_kind", textOrEmpty(targetKind));
		if (targetId == null) values.putNull("target_id");
		else values.put("target_id", targetId);
		values.put("detail", textOrEmpty(detail));
		values.put("created_at", System.currentTimeMillis());
		return database.getWritableDatabase().insertOrThrow("audit_log", null, values);
	}

	public List<AuditEntry> recent(int limit)
	{
		List<AuditEntry> entries = new ArrayList<>();
		try (Cursor cursor = database.getReadableDatabase().query("audit_log",
				new String[]{"id", "action", "target_kind", "target_id", "detail", "created_at"},
				null, null, null, null, "created_at DESC, id DESC", String.valueOf(Math.max(1, limit))))
		{
			while (cursor.moveToNext())
			{
				entries.add(new AuditEntry(cursor.getLong(0), cursor.getString(1), cursor.getString(2),
						cursor.isNull(3) ? null : cursor.getLong(3), cursor.getString(4),
						cursor.getLong(5)));
			}
		}
		return entries;
	}

	private static String textOrEmpty(String value)
	{
		return value == null ? "" : value.trim();
	}
}

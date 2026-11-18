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

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Whole-file backup and restore of the workshop database.
 *
 * <p>Backup is a physical copy of the SQLite file, not a partial export, so
 * customer, vehicle, service, estimate and diagnostic data stay consistent. Callers must
 * not write concurrently: export checkpoints the WAL and then copies the main
 * file, which is only safe while the app holds the UI (single writer).</p>
 *
 * <p>Restore validates and migrates a private candidate copy, keeps a
 * {@code workshop.db.before-restore} copy of the current data, and rolls back
 * automatically if replacement fails. The caller's backup is never modified.</p>
 */
public final class WorkshopBackup
{
	private WorkshopBackup()
	{
	}

	/** Timestamped destination under the app's external files directory. */
	public static File defaultExportFile(Context context)
	{
		File directory = new File(context.getExternalFilesDir(null), "backup");
		if (!directory.exists() && !directory.mkdirs())
		{
			throw new IllegalStateException("Cannot create backup directory " + directory);
		}
		String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
		return new File(directory, "workshop-" + stamp + ".db");
	}

	/** Copy the live database to {@code target}, replacing it if present. */
	public static File export(WorkshopDatabase database, File target) throws IOException
	{
		SQLiteDatabase db = database.getWritableDatabase();
		checkpoint(db);
		File source = new File(db.getPath());
		if (!source.isFile())
		{
			throw new IOException("Database file missing: " + source);
		}
		File parent = target.getParentFile();
		if (parent != null && !parent.exists() && !parent.mkdirs())
		{
			throw new IOException("Cannot create directory " + parent);
		}
		copy(source, target);
		return target;
	}

	/**
	 * Replace the current database with a previously exported file.
	 *
	 * @throws IOException when the backup has an unsupported or invalid schema,
	 *                     or when the restored file fails its checks
	 */
	public static void restore(WorkshopDatabase database, File source) throws IOException
	{
		if (source == null || !source.isFile())
		{
			throw new IOException("Backup file not found");
		}

		SQLiteDatabase db = database.getWritableDatabase();
		File current = new File(db.getPath());
		if (current.getCanonicalFile().equals(source.getCanonicalFile()))
		{
			throw new IOException("Backup source is the live database");
		}

		// Validate a copy so the caller's backup file is never opened, truncated
		// or deleted by SQLite when it turns out to be damaged.
		File candidate = File.createTempFile("workshop-restore-", ".db",
				current.getParentFile());
		try
		{
			copy(source, candidate);
			validateAndMigrate(database, candidate);
			checkpoint(db);

			File safety = new File(current.getPath() + ".before-restore");
			copy(current, safety);

			database.close();
			// Sidecar deletion belongs inside the rollback boundary too.

			try
			{
				deleteSidecars(current);
				copy(candidate, current);
				verifyUsage(database.getWritableDatabase());
			}
			catch (IOException | RuntimeException failure)
			{
				database.close();
				try
				{
					// Failed candidate WAL/journals must never replay onto the old file.
					deleteSidecars(current);
					copy(safety, current);
					verifyUsage(database.getWritableDatabase());
				}
				catch (IOException | RuntimeException rollbackFailure)
				{
					failure.addSuppressed(rollbackFailure);
					throw new IOException("Restore failed and rollback failed; copy "
							+ safety + " back manually", failure);
				}
				throw new IOException("Restore failed; previous database was kept", failure);
			}
		}
		finally
		{
			deleteQuietly(candidate);
			deleteQuietly(new File(candidate.getPath() + "-wal"));
			deleteQuietly(new File(candidate.getPath() + "-shm"));
			deleteQuietly(new File(candidate.getPath() + "-journal"));
		}
	}

	private static void validateAndMigrate(WorkshopDatabase database, File candidate) throws IOException
	{
		try (SQLiteDatabase check = SQLiteDatabase.openDatabase(candidate.getPath(), null,
				SQLiteDatabase.OPEN_READWRITE))
		{
			check.setForeignKeyConstraintsEnabled(true);
			int version = check.getVersion();
			if (version < 1 || version > WorkshopDatabase.SCHEMA_VERSION)
			{
				throw new IOException("Unsupported backup schema version " + version);
			}
			validateSchema(check, version);
			if (version < WorkshopDatabase.SCHEMA_VERSION)
			{
				check.beginTransaction();
				try
				{
					database.onUpgrade(check, version, WorkshopDatabase.SCHEMA_VERSION);
					check.setVersion(WorkshopDatabase.SCHEMA_VERSION);
					check.setTransactionSuccessful();
				}
				finally
				{
					check.endTransaction();
				}
			}
			verifyUsage(check);
			checkpoint(check);
		}
		catch (SQLiteException | IllegalArgumentException e)
		{
			throw new IOException("Not a valid workshop backup", e);
		}
	}

	private static void verifyUsage(SQLiteDatabase db) throws IOException
	{
		if (db.getVersion() != WorkshopDatabase.SCHEMA_VERSION)
		{
			throw new IOException("Restored database has an unexpected schema version");
		}
		validateSchema(db, WorkshopDatabase.SCHEMA_VERSION);
	}

	/** Exercise every column used by repositories, including empty tables. */
	private static void validateSchema(SQLiteDatabase db, int version) throws IOException
	{
		String[] queries = {
				"SELECT id,name,phone,notes,created_at FROM customers LIMIT 0",
				"SELECT id,customer_id,plate,plate_key,model,year,engine_type,vin,odometer_km,created_at FROM vehicles LIMIT 0",
				"SELECT id,vehicle_id,complaint,status,payment_status,notes,created_at,updated_at,completed_at FROM services LIMIT 0",
				"SELECT id,service_id,kind,description,quantity,unit_price,position FROM estimate_items LIMIT 0",
				"SELECT s.id FROM services s JOIN vehicles v ON v.id=s.vehicle_id JOIN customers c ON c.id=v.customer_id LEFT JOIN estimate_items i ON i.service_id=s.id LIMIT 0"
		};
		for (String query : queries)
		{
			try (Cursor cursor = db.rawQuery(query, null))
			{
				cursor.moveToFirst();
			}
		}
		if (version >= 2)
		{
			// Strict parsing also rejects unknown enums and fabricated incomplete proof.
			try (Cursor cursor = db.query("diagnostics", DiagnosticRepository.COLUMNS,
					null, null, null, null, null))
			{
				while (cursor.moveToNext()) DiagnosticRepository.read(cursor);
			}
		}
		try (Cursor cursor = db.rawQuery("PRAGMA integrity_check", null))
		{
			if (!cursor.moveToFirst() || !"ok".equalsIgnoreCase(cursor.getString(0))
					|| cursor.moveToNext())
			{
				throw new IOException("Backup failed SQLite integrity check");
			}
		}
		try (Cursor cursor = db.rawQuery("PRAGMA foreign_key_check", null))
		{
			if (cursor.moveToFirst())
			{
				throw new IOException("Backup failed SQLite foreign key check");
			}
		}
	}

	private static void deleteSidecars(File database) throws IOException
	{
		for (String suffix : new String[]{"-wal", "-shm", "-journal"})
		{
			deleteIfExists(new File(database.getPath() + suffix));
		}
	}

	private static void checkpoint(SQLiteDatabase db) throws IOException
	{
		try (Cursor cursor = db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null))
		{
			if (cursor.moveToFirst() && cursor.getInt(0) != 0)
			{
				throw new IOException("Database busy; close other screens and retry the backup");
			}
		}
	}

	private static void copy(File source, File target) throws IOException
	{
		if (source.getCanonicalFile().equals(target.getCanonicalFile()))
		{
			throw new IOException("Cannot copy a database onto itself");
		}
		try (InputStream in = new FileInputStream(source);
			 OutputStream out = new FileOutputStream(target))
		{
			byte[] buffer = new byte[8192];
			int read;
			while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
			out.flush();
		}
	}

	private static void deleteIfExists(File file) throws IOException
	{
		if (file.exists() && !file.delete())
		{
			throw new IOException("Cannot delete " + file);
		}
	}

	private static void deleteQuietly(File file)
	{
		if (file != null && file.exists()) file.delete();
	}
}

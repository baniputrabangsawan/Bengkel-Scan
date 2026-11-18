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
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * Local workshop database.
 *
 * <p>Uses the platform SQLite helper instead of Room: the schema is small and
 * stable, no annotation processor is needed, and the app stays dependency-light
 * on a single offline phone.</p>
 *
 * <p>All app code and schema use Rupiah integers. Status values are stored as
 * enum names so future states can be added without renumbering rows.</p>
 */
public class WorkshopDatabase extends SQLiteOpenHelper
{
	public static final String DEFAULT_NAME = "workshop.db";
	static final int SCHEMA_VERSION = 1;

	private static WorkshopDatabase instance;

	public WorkshopDatabase(Context context, String name)
	{
		super(context.getApplicationContext(), name, null, SCHEMA_VERSION);
	}

	/** Shared connection for the running app. */
	public static synchronized WorkshopDatabase getInstance(Context context)
	{
		if (instance == null)
		{
			instance = new WorkshopDatabase(context, DEFAULT_NAME);
		}
		return instance;
	}

	/** Close the shared connection so a restored file can replace it. */
	public static synchronized void resetInstance()
	{
		if (instance != null)
		{
			instance.close();
			instance = null;
		}
	}

	@Override
	public void onConfigure(SQLiteDatabase db)
	{
		super.onConfigure(db);
		// Cascades must work on every connection, including reopened ones.
		db.setForeignKeyConstraintsEnabled(true);
	}

	@Override
	public void onCreate(SQLiteDatabase db)
	{
		db.execSQL("CREATE TABLE customers ("
				+ "id INTEGER PRIMARY KEY AUTOINCREMENT,"
				+ "name TEXT NOT NULL,"
				+ "phone TEXT NOT NULL DEFAULT '',"
				+ "notes TEXT NOT NULL DEFAULT '',"
				+ "created_at INTEGER NOT NULL)");
		db.execSQL("CREATE INDEX idx_customers_name ON customers(name COLLATE NOCASE)");

		db.execSQL("CREATE TABLE vehicles ("
				+ "id INTEGER PRIMARY KEY AUTOINCREMENT,"
				+ "customer_id INTEGER NOT NULL REFERENCES customers(id) ON DELETE CASCADE,"
				+ "plate TEXT NOT NULL,"
				+ "plate_key TEXT NOT NULL DEFAULT '',"
				+ "model TEXT NOT NULL DEFAULT '',"
				+ "year INTEGER,"
				+ "engine_type TEXT NOT NULL DEFAULT 'DIESEL',"
				+ "vin TEXT NOT NULL DEFAULT '',"
				+ "odometer_km INTEGER,"
				+ "created_at INTEGER NOT NULL)");
		db.execSQL("CREATE INDEX idx_vehicles_customer ON vehicles(customer_id)");
		db.execSQL("CREATE INDEX idx_vehicles_plate ON vehicles(plate COLLATE NOCASE)");
		db.execSQL("CREATE INDEX idx_vehicles_plate_key ON vehicles(plate_key)");

		db.execSQL("CREATE TABLE services ("
				+ "id INTEGER PRIMARY KEY AUTOINCREMENT,"
				+ "vehicle_id INTEGER NOT NULL REFERENCES vehicles(id) ON DELETE CASCADE,"
				+ "complaint TEXT NOT NULL DEFAULT '',"
				+ "status TEXT NOT NULL DEFAULT 'WAITING',"
				+ "payment_status TEXT NOT NULL DEFAULT 'UNPAID',"
				+ "notes TEXT NOT NULL DEFAULT '',"
				+ "created_at INTEGER NOT NULL,"
				+ "updated_at INTEGER NOT NULL,"
				+ "completed_at INTEGER)");
		db.execSQL("CREATE INDEX idx_services_vehicle ON services(vehicle_id)");
		db.execSQL("CREATE INDEX idx_services_status ON services(status)");

		db.execSQL("CREATE TABLE estimate_items ("
				+ "id INTEGER PRIMARY KEY AUTOINCREMENT,"
				+ "service_id INTEGER NOT NULL REFERENCES services(id) ON DELETE CASCADE,"
				+ "kind TEXT NOT NULL DEFAULT 'PART',"
				+ "description TEXT NOT NULL,"
				+ "quantity REAL NOT NULL DEFAULT 1,"
				+ "unit_price INTEGER NOT NULL DEFAULT 0,"
				+ "position INTEGER NOT NULL DEFAULT 0)");
		db.execSQL("CREATE INDEX idx_estimate_items_service ON estimate_items(service_id)");
	}

	@Override
	public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion)
	{
		// Forward-only migrations. Add "if (oldVersion < N)" blocks with the
		// matching SCHEMA_VERSION bump; never edit history in place.
	}
}

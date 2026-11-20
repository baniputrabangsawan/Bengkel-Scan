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
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisRepository;

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
	static final int SCHEMA_VERSION = 5;
	final Context appContext;

	private static WorkshopDatabase instance;

	public WorkshopDatabase(Context context, String name)
	{
		super(context.getApplicationContext(), name, null, SCHEMA_VERSION);
		appContext = context.getApplicationContext();
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
				+ "odometer_km INTEGER,"
				+ "payment_method TEXT,"
				+ "paid_at INTEGER,"
				+ "paid_amount INTEGER NOT NULL DEFAULT 0,"
				+ "created_at INTEGER NOT NULL,"
				+ "updated_at INTEGER NOT NULL,"
				+ "completed_at INTEGER)");
		db.execSQL("CREATE INDEX idx_services_vehicle ON services(vehicle_id)");
		db.execSQL("CREATE INDEX idx_services_status ON services(status)");

		createWorkshopTables(db);

		db.execSQL("CREATE TABLE estimate_items ("
				+ "id INTEGER PRIMARY KEY AUTOINCREMENT,"
				+ "service_id INTEGER NOT NULL REFERENCES services(id) ON DELETE CASCADE,"
				+ "kind TEXT NOT NULL DEFAULT 'PART',"
				+ "description TEXT NOT NULL,"
				+ "quantity REAL NOT NULL DEFAULT 1,"
				+ "unit_price INTEGER NOT NULL DEFAULT 0,"
				+ "discount INTEGER NOT NULL DEFAULT 0,"
				+ "approval TEXT NOT NULL DEFAULT 'PENDING',"
				+ "inspection_item_id INTEGER REFERENCES inspection_items(id) ON DELETE SET NULL,"
				+ "position INTEGER NOT NULL DEFAULT 0)");
		db.execSQL("CREATE INDEX idx_estimate_items_service ON estimate_items(service_id)");
		createApprovalTable(db);
		createDiagnostics(db);
		DiagnosisRepository.createTables(db);
	}

	@Override
	public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion)
	{
		// Additive migration: earlier workshop records remain untouched.
		if (oldVersion < 2 && newVersion >= 2) createDiagnostics(db);
		if (oldVersion < 3 && newVersion >= 3) DiagnosisRepository.createTables(db);
		if (oldVersion < 4 && newVersion >= 4) upgradeToV4(db);
		if (oldVersion < 5 && newVersion >= 5)
		{
			// Version 5 records how much was received, so a partial payment is a fact.
			addColumn(db, "services", "paid_amount", "INTEGER NOT NULL DEFAULT 0");
		}
	}

	/**
	 * Version 4 adds the workshop profile, inspection items, photos, scan
	 * snapshots, canned jobs, reminders, audit log and settings, and extends
	 * existing rows in place. No row is dropped or recreated.
	 */
	private static void upgradeToV4(SQLiteDatabase db)
	{
		createWorkshopTables(db);
		addColumn(db, "services", "odometer_km", "INTEGER");
		addColumn(db, "services", "payment_method", "TEXT");
		addColumn(db, "services", "paid_at", "INTEGER");
		addColumn(db, "estimate_items", "discount", "INTEGER NOT NULL DEFAULT 0");
		addColumn(db, "estimate_items", "approval", "TEXT NOT NULL DEFAULT 'PENDING'");
		addColumn(db, "estimate_items", "inspection_item_id", "INTEGER");
		createApprovalTable(db);
	}

	/**
	 * Add a column only when it is missing, so a database whose version number
	 * was rewound (a restored backup, a test fixture) upgrades instead of failing.
	 */
	private static void addColumn(SQLiteDatabase db, String table, String column, String type)
	{
		try (android.database.Cursor cursor = db.rawQuery(
				"SELECT COUNT(*) FROM pragma_table_info(?) WHERE name = ?",
				new String[]{table, column}))
		{
			if (cursor.moveToFirst() && cursor.getInt(0) > 0) return;
		}
		db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
	}

	private static void createWorkshopTables(SQLiteDatabase db)
	{
		db.execSQL("CREATE TABLE IF NOT EXISTS inspection_items ("
				+ "id INTEGER PRIMARY KEY AUTOINCREMENT,"
				+ "service_id INTEGER NOT NULL REFERENCES services(id) ON DELETE CASCADE,"
				+ "name TEXT NOT NULL,"
				+ "state TEXT NOT NULL DEFAULT 'GOOD'"
				+ " CHECK(state IN ('GOOD','ATTENTION','BROKEN')),"
				+ "note TEXT NOT NULL DEFAULT '',"
				+ "voice_path TEXT NOT NULL DEFAULT '',"
				+ "position INTEGER NOT NULL DEFAULT 0,"
				+ "created_at INTEGER NOT NULL,"
				+ "updated_at INTEGER NOT NULL,"
				+ "deleted_at INTEGER)");
		db.execSQL("CREATE INDEX IF NOT EXISTS idx_inspection_service ON inspection_items(service_id)");

		db.execSQL("CREATE TABLE IF NOT EXISTS photos ("
				+ "id INTEGER PRIMARY KEY AUTOINCREMENT,"
				+ "owner_kind TEXT NOT NULL CHECK(owner_kind IN ('INSPECTION','DIAGNOSIS','SERVICE')),"
				+ "owner_id INTEGER NOT NULL,"
				+ "relative_path TEXT NOT NULL,"
				+ "caption TEXT NOT NULL DEFAULT '',"
				+ "created_at INTEGER NOT NULL)");
		db.execSQL("CREATE INDEX IF NOT EXISTS idx_photos_owner ON photos(owner_kind, owner_id)");

		db.execSQL("CREATE TABLE IF NOT EXISTS scan_snapshots ("
				+ "id INTEGER PRIMARY KEY AUTOINCREMENT,"
				+ "service_id INTEGER NOT NULL REFERENCES services(id) ON DELETE CASCADE,"
				+ "phase TEXT NOT NULL DEFAULT 'BEFORE'"
				+ " CHECK(phase IN ('BEFORE','AFTER')),"
				+ "source TEXT NOT NULL DEFAULT 'REAL'"
				+ " CHECK(source IN ('REAL','DEMO','FILE')),"
				+ "adapter TEXT NOT NULL DEFAULT '',"
				+ "codes_json TEXT NOT NULL DEFAULT '[]',"
				+ "readings_json TEXT NOT NULL DEFAULT '[]',"
				+ "freeze_json TEXT NOT NULL DEFAULT '[]',"
				+ "note TEXT NOT NULL DEFAULT '',"
				+ "created_at INTEGER NOT NULL)");
		db.execSQL("CREATE INDEX IF NOT EXISTS idx_scan_snapshots_service"
				+ " ON scan_snapshots(service_id, phase)");

		db.execSQL("CREATE TABLE IF NOT EXISTS canned_jobs ("
				+ "id INTEGER PRIMARY KEY AUTOINCREMENT,"
				+ "name TEXT NOT NULL UNIQUE,"
				+ "note TEXT NOT NULL DEFAULT '',"
				+ "created_at INTEGER NOT NULL,"
				+ "updated_at INTEGER NOT NULL)");

		db.execSQL("CREATE TABLE IF NOT EXISTS canned_job_items ("
				+ "id INTEGER PRIMARY KEY AUTOINCREMENT,"
				+ "job_id INTEGER NOT NULL REFERENCES canned_jobs(id) ON DELETE CASCADE,"
				+ "kind TEXT NOT NULL DEFAULT 'PART',"
				+ "description TEXT NOT NULL,"
				+ "quantity REAL NOT NULL DEFAULT 1,"
				+ "unit_price INTEGER NOT NULL DEFAULT 0,"
				+ "position INTEGER NOT NULL DEFAULT 0)");
		db.execSQL("CREATE INDEX IF NOT EXISTS idx_canned_job_items_job ON canned_job_items(job_id)");

		db.execSQL("CREATE TABLE IF NOT EXISTS reminders ("
				+ "id INTEGER PRIMARY KEY AUTOINCREMENT,"
				+ "vehicle_id INTEGER NOT NULL REFERENCES vehicles(id) ON DELETE CASCADE,"
				+ "due_date INTEGER,"
				+ "due_odometer_km INTEGER,"
				+ "note TEXT NOT NULL DEFAULT '',"
				+ "dismissed_at INTEGER,"
				+ "created_at INTEGER NOT NULL)");
		db.execSQL("CREATE INDEX IF NOT EXISTS idx_reminders_vehicle ON reminders(vehicle_id)");

		db.execSQL("CREATE TABLE IF NOT EXISTS audit_log ("
				+ "id INTEGER PRIMARY KEY AUTOINCREMENT,"
				+ "action TEXT NOT NULL,"
				+ "target_kind TEXT NOT NULL,"
				+ "target_id INTEGER,"
				+ "detail TEXT NOT NULL DEFAULT '',"
				+ "created_at INTEGER NOT NULL)");
		db.execSQL("CREATE INDEX IF NOT EXISTS idx_audit_log_created ON audit_log(created_at)");

		db.execSQL("CREATE TABLE IF NOT EXISTS workshop_profile ("
				+ "id INTEGER PRIMARY KEY CHECK(id = 1),"
				+ "name TEXT NOT NULL DEFAULT '',"
				+ "address TEXT NOT NULL DEFAULT '',"
				+ "phone TEXT NOT NULL DEFAULT '',"
				+ "footer TEXT NOT NULL DEFAULT '',"
				+ "logo_path TEXT NOT NULL DEFAULT '',"
				+ "updated_at INTEGER NOT NULL)");

		db.execSQL("CREATE TABLE IF NOT EXISTS settings ("
				+ "key TEXT PRIMARY KEY,"
				+ "value TEXT NOT NULL DEFAULT '',"
				+ "updated_at INTEGER NOT NULL)");
	}

	private static void createApprovalTable(SQLiteDatabase db)
	{
		db.execSQL("CREATE TABLE IF NOT EXISTS estimate_approvals ("
				+ "id INTEGER PRIMARY KEY AUTOINCREMENT,"
				+ "estimate_item_id INTEGER NOT NULL REFERENCES estimate_items(id) ON DELETE CASCADE,"
				+ "state TEXT NOT NULL CHECK(state IN ('PENDING','APPROVED','DECLINED')),"
				+ "note TEXT NOT NULL DEFAULT '',"
				+ "changed_at INTEGER NOT NULL)");
		db.execSQL("CREATE INDEX IF NOT EXISTS idx_estimate_approvals_item"
				+ " ON estimate_approvals(estimate_item_id, changed_at)");
	}


	private static void createDiagnostics(SQLiteDatabase db)
	{
		db.execSQL("CREATE TABLE diagnostics ("
				+ "service_id INTEGER NOT NULL REFERENCES services(id) ON DELETE CASCADE,"
				+ "module TEXT NOT NULL CHECK(module IN ('ENGINE','SRS','TRANSMISSION','ABS','BODY','OTHER')),"
				+ "read_status TEXT NOT NULL CHECK(read_status IN ('NOT_CHECKED','NOT_SUPPORTED','READ','READ_FAILED')),"
				+ "conclusion TEXT NOT NULL CHECK(conclusion IN ('ECU_INDICATION','HYPOTHESIS','CONFIRMED')),"
				+ "scanner TEXT NOT NULL DEFAULT '',"
				+ "codes TEXT NOT NULL DEFAULT '',"
				+ "symptoms TEXT NOT NULL DEFAULT '',"
				+ "observations TEXT NOT NULL DEFAULT '',"
				+ "test_procedure TEXT NOT NULL DEFAULT '',"
				+ "test_result TEXT NOT NULL DEFAULT '',"
				+ "reference TEXT NOT NULL DEFAULT '',"
				+ "diagnosis TEXT NOT NULL DEFAULT '',"
				+ "repair TEXT NOT NULL DEFAULT '',"
				+ "verification TEXT NOT NULL DEFAULT '',"
				+ "updated_at INTEGER NOT NULL,"
				+ "PRIMARY KEY(service_id, module),"
				+ "CHECK(read_status != 'READ' OR length(trim(scanner)) > 0),"
				+ "CHECK(conclusion != 'CONFIRMED' OR (read_status = 'READ'"
				+ " AND length(trim(diagnosis)) > 0 AND length(trim(test_procedure)) > 0"
				+ " AND length(trim(test_result)) > 0 AND length(trim(reference)) > 0)))");
	}
}

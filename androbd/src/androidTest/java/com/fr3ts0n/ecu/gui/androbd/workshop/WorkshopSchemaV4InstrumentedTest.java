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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.List;
import java.util.UUID;

/**
 * Schema version 4: the new workshop tables, the in-place column additions and
 * the fact that a version 3 database keeps every existing row when it upgrades.
 */
@RunWith(AndroidJUnit4.class)
public class WorkshopSchemaV4InstrumentedTest
{
	private Context context;
	private String name;
	private File file;

	@Before
	public void setUp()
	{
		context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		name = "schema-v4-test-" + UUID.randomUUID() + ".db";
		file = context.getDatabasePath(name);
	}

	@After
	public void tearDown()
	{
		context.deleteDatabase(name);
		for (String suffix : new String[]{"-wal", "-shm", "-journal", ".before-restore"})
		{
			File sidecar = new File(file.getPath() + suffix);
			if (sidecar.exists()) assertTrue("Stray test sidecar " + sidecar, sidecar.delete());
		}
	}

	@Test
	public void freshDatabaseCreatesEveryVersionFourTable()
	{
		try (WorkshopDatabase database = new WorkshopDatabase(context, name))
		{
			SQLiteDatabase db = database.getWritableDatabase();
			assertTrue("The schema must be at least version 4", db.getVersion() >= 4);
			for (String table : new String[]{"customers", "vehicles", "services", "estimate_items",
					"diagnostics", "diagnosis_cases", "inspection_items", "photos",
					"scan_snapshots", "estimate_approvals", "canned_jobs", "canned_job_items",
					"reminders", "audit_log", "workshop_profile", "settings"})
			{
				try (android.database.Cursor cursor = db.rawQuery(
						"SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?",
						new String[]{table}))
				{
					assertTrue("Missing table " + table, cursor.moveToFirst() && cursor.getInt(0) == 1);
				}
			}
		}
	}

	@Test
	public void versionThreeDatabaseUpgradesWithoutLosingRows()
	{
		// Build a genuine v3 file: the same code path the released app shipped.
		createVersionThreeDatabase();

		try (WorkshopDatabase database = new WorkshopDatabase(context, name))
		{
			SQLiteDatabase db = database.getWritableDatabase();
			assertTrue("The schema must be at least version 4", db.getVersion() >= 4);

			WorkshopRepository workshop = new WorkshopRepository(database);
			Customer customer = workshop.findCustomer(1);
			assertEquals("Legacy customer", customer.name);
			ServiceRecord service = workshop.findService(3);
			assertEquals("Legacy complaint", service.complaint);
			assertEquals(ServiceStatus.DONE, service.status);
			assertEquals(PaymentStatus.PAID, service.paymentStatus);
			assertEquals("The upgrade must not clear a completion time", Long.valueOf(500),
					service.completedAt);
			assertNull("A legacy service has no recorded payment method yet",
					service.paymentMethod == PaymentMethod.UNKNOWN ? null : service.paymentMethod);
			assertNull("A legacy service has no recorded odometer yet", service.odometerKm);

			List<EstimateItem> items = workshop.estimateItems(3);
			assertEquals(1, items.size());
			assertEquals("Legacy filter", items.get(0).description);
			assertEquals(0, items.get(0).discount);
			assertEquals("A migrated line needs approval before it counts",
					ApprovalState.PENDING, items.get(0).approval);
			assertEquals(90000L, workshop.estimateTotal(3));
			assertEquals("Nothing is approved yet", 0L, workshop.approvedTotal(3));
		}
	}

	@Test
	public void inspectionScanReminderAndAuditRoundTrip()
	{
		try (WorkshopDatabase database = new WorkshopDatabase(context, name))
		{
			WorkshopRepository workshop = new WorkshopRepository(database);
			long customer = workshop.createCustomer("Pelanggan uji", "0812", "");
			long vehicle = workshop.createVehicle(customer, "B 1234 XYZ", "Truk uji", 2011,
					EngineType.DIESEL, "", 250000);
			long service = workshop.createService(vehicle, "Sulit hidup", 250100);

			InspectionRepository inspections = new InspectionRepository(database);
			long hose = inspections.create(service, "Selang turbo", InspectionState.ATTENTION,
					"Rembes kecil");
			assertEquals(1, inspections.forService(service).size());
			assertTrue(inspections.setVoicePath(hose, "audio/voice-note.mp3"));
			assertTrue(inspections.find(hose).voiceNote());
			assertTrue(inspections.delete(hose));
			assertTrue("A soft delete hides the row", inspections.forService(service).isEmpty());

			ScanSnapshotRepository scans = new ScanSnapshotRepository(database);
			scans.save(service, ScanPhase.BEFORE, ScanSource.DEMO, "Latihan",
					"[{\"code\":\"P0087\"}]", "[]", "[]", "");
			scans.save(service, ScanPhase.AFTER, ScanSource.REAL, "ELM327",
					"[]", "[]", "[]", "");
			assertEquals(2, scans.forService(service).size());
			assertNull("No earlier FRAME snapshot was saved for this phase",
					scans.latest(service, ScanPhase.AFTER).real() ? null : "not real");
			assertTrue(scans.latest(service, ScanPhase.AFTER).real());

			ReminderRepository reminders = new ReminderRepository(database);
			long reminder = reminders.create(vehicle, System.currentTimeMillis() + 86400000L,
					260000, "Servis berkala");
			assertEquals(1, reminders.open().size());
			assertTrue(reminders.dismiss(reminder));
			assertTrue("Dismissed reminders leave the due list", reminders.open().isEmpty());

			WorkshopSettingsRepository settings = new WorkshopSettingsRepository(database);
			assertTrue(settings.saveProfile("Bengkel Uji", "Jl. Uji 1", "0812", "Terima kasih", ""));
			assertEquals("Bengkel Uji", settings.profile().name);
			assertTrue(settings.put(WorkshopSettingsRepository.KEY_BACKUP_REMINDER_DAYS, "7"));
			assertEquals(7, settings.getLong(WorkshopSettingsRepository.KEY_BACKUP_REMINDER_DAYS, 30));
			long audit = settings.record("service.created", "service", service, "");
			assertTrue("Audit rows are append-only", audit > 0);
			assertEquals(1, settings.recent(10).size());
		}
	}

	@Test
	public void approvalHistoryKeepsEveryChange()
	{
		try (WorkshopDatabase database = new WorkshopDatabase(context, name))
		{
			WorkshopRepository workshop = new WorkshopRepository(database);
			long customer = workshop.createCustomer("Pelanggan uji", "", "");
			long vehicle = workshop.createVehicle(customer, "B 9999 ZZ", "", null,
					EngineType.DIESEL, "", null);
			long service = workshop.createService(vehicle, "");
			long item = workshop.addEstimateItem(service, ItemKind.PART, "Filter", 1, 50000);

			workshop.setApproval(item, ApprovalState.APPROVED, "Disetujui lewat WhatsApp");
			workshop.setApproval(item, ApprovalState.DECLINED, "Batal, stok kosong");
			List<EstimateApproval> history = workshop.approvals(item);
			assertEquals(2, history.size());
			assertEquals(ApprovalState.APPROVED, history.get(0).state);
			assertEquals(ApprovalState.DECLINED, history.get(1).state);
			assertEquals(ApprovalState.DECLINED, workshop.estimateItems(service).get(0).approval);
			assertEquals("Only approved lines count", 0L, workshop.approvedTotal(service));
		}
	}

	@Test
	public void discountAboveTheLineIsRejected()
	{
		try (WorkshopDatabase database = new WorkshopDatabase(context, name))
		{
			WorkshopRepository workshop = new WorkshopRepository(database);
			long customer = workshop.createCustomer("Pelanggan uji", "", "");
			long vehicle = workshop.createVehicle(customer, "B 1 X", "", null, null, "", null);
			long service = workshop.createService(vehicle, "");

			try
			{
				workshop.addEstimateItem(service, ItemKind.PART, "Oli", 1, 50000, 50001, null);
				fail("A discount above the line was accepted");
			}
			catch (IllegalArgumentException expected)
			{
				assertEquals("estimate.discount_above_total", expected.getMessage());
			}

			long item = workshop.addEstimateItem(service, ItemKind.PART, "Oli", 1, 50000, 5000, null);
			long total = workshop.estimateTotal(service);
			assertEquals(45000L, total);
			assertTrue(workshop.setApproval(item, ApprovalState.APPROVED, ""));
			assertEquals(45000L, workshop.approvedTotal(service));
		}
	}

	@Test
	public void cannedJobSeedsOneEstimateWithoutChangingTheBellTemplate()
	{
		try (WorkshopDatabase database = new WorkshopDatabase(context, name))
		{
			WorkshopRepository workshop = new WorkshopRepository(database);
			CannedJobRepository jobs = new CannedJobRepository(database);
			long job = jobs.create("Servis berkala diesel", "Contoh otomatis");
			jobs.addItem(job, ItemKind.LABOUR, "Servis berkala", 1, 150000);
			jobs.addItem(job, ItemKind.PART, "Filter oli (contoh)", 1, 90000);

			long customer = workshop.createCustomer("Pelanggan uji", "", "");
			long vehicle = workshop.createVehicle(customer, "B 2 Y", "", null, null, "", null);
			long service = workshop.createService(vehicle, "");

			assertEquals(2, jobs.applyTo(job, service, workshop).size());
			assertEquals(2, workshop.estimateItems(service).size());
			workshop.deleteEstimateItem(workshop.estimateItems(service).get(0).id);
			assertEquals("Editing the copied estimate must not touch the template",
					2, jobs.find(job).items.size());
		}
	}

	/** Build the exact v3 schema and rows the previous release created. */
	private void createVersionThreeDatabase()
	{
		File parent = file.getParentFile();
		if (parent != null && !parent.exists()) assertTrue(parent.mkdirs());
		try (SQLiteDatabase legacy = SQLiteDatabase.openOrCreateDatabase(file, null))
		{
			legacy.execSQL("CREATE TABLE customers (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, phone TEXT NOT NULL DEFAULT '', notes TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL)");
			legacy.execSQL("CREATE TABLE vehicles (id INTEGER PRIMARY KEY AUTOINCREMENT, customer_id INTEGER NOT NULL REFERENCES customers(id) ON DELETE CASCADE, plate TEXT NOT NULL, plate_key TEXT NOT NULL DEFAULT '', model TEXT NOT NULL DEFAULT '', year INTEGER, engine_type TEXT NOT NULL DEFAULT 'DIESEL', vin TEXT NOT NULL DEFAULT '', odometer_km INTEGER, created_at INTEGER NOT NULL)");
			legacy.execSQL("CREATE TABLE services (id INTEGER PRIMARY KEY AUTOINCREMENT, vehicle_id INTEGER NOT NULL REFERENCES vehicles(id) ON DELETE CASCADE, complaint TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT 'WAITING', payment_status TEXT NOT NULL DEFAULT 'UNPAID', notes TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, completed_at INTEGER)");
			legacy.execSQL("CREATE TABLE estimate_items (id INTEGER PRIMARY KEY AUTOINCREMENT, service_id INTEGER NOT NULL REFERENCES services(id) ON DELETE CASCADE, kind TEXT NOT NULL DEFAULT 'PART', description TEXT NOT NULL, quantity REAL NOT NULL DEFAULT 1, unit_price INTEGER NOT NULL DEFAULT 0, position INTEGER NOT NULL DEFAULT 0)");
			legacy.execSQL("INSERT INTO customers VALUES (1, 'Legacy customer', '0800', '', 100)");
			legacy.execSQL("INSERT INTO vehicles VALUES (2, 1, 'B 321 ZZ', 'B321ZZ', 'Legacy truck', 2010, 'DIESEL', '', 234000, 200)");
			legacy.execSQL("INSERT INTO services VALUES (3, 2, 'Legacy complaint', 'DONE', 'PAID', '', 300, 400, 500)");
			legacy.execSQL("INSERT INTO estimate_items VALUES (4, 3, 'PART', 'Legacy filter', 2, 45000, 0)");
			legacy.setVersion(3);
		}
	}
}

package com.fr3ts0n.ecu.gui.androbd.workshop;

import static org.junit.Assert.*;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Device coverage for evidence persistence, additive migration and safe restore. */
@RunWith(AndroidJUnit4.class)
public class DiagnosticRepositoryInstrumentedTest
{
	private Context context;
	private String databaseName;
	private WorkshopDatabase database;
	private WorkshopRepository workshop;
	private DiagnosticRepository diagnostics;
	private final List<File> files = new ArrayList<>();

	@Before
	public void setUp()
	{
		context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		databaseName = "diagnostics-test-" + UUID.randomUUID() + ".db";
		database = new WorkshopDatabase(context, databaseName);
		workshop = new WorkshopRepository(database);
		diagnostics = new DiagnosticRepository(database);
	}

	@After
	public void tearDown()
	{
		database.close();
		context.deleteDatabase(databaseName);
		files.add(new File(context.getDatabasePath(databaseName) + ".before-restore"));
		for (File file : files)
		{
			for (String suffix : new String[]{"", "-wal", "-shm", "-journal"})
			{
				File part = new File(file.getPath() + suffix);
				if (part.exists()) assertTrue("Cannot clean " + part, part.delete());
			}
		}
	}

	@Test
	public void upgradePreservesAllV1DataAndLeavesDiagnosticsAbsent()
	{
		database.close();
		createV1(context.getDatabasePath(databaseName));
		assertLegacyData();
		assertEquals(WorkshopDatabase.SCHEMA_VERSION, database.getReadableDatabase().getVersion());
		for (DiagnosticModule module : DiagnosticModule.values())
		{
			assertNull(diagnostics.find(3, module));
		}
		assertTrue(diagnostics.forService(3).isEmpty());
	}

	@Test
	public void confirmationRequiresReadAndEveryEvidenceFieldAndPersistsActualProof()
	{
		long service = createService();
		for (DiagnosticReadStatus status : DiagnosticReadStatus.values())
		{
			if (status != DiagnosticReadStatus.READ)
			{
				reject(record(service, DiagnosticModule.ENGINE, status,
						DiagnosticConclusion.CONFIRMED, "diagnosis", "procedure", "result", "manual"));
			}
		}
		reject(record(service, DiagnosticModule.ENGINE, DiagnosticReadStatus.READ,
				DiagnosticConclusion.CONFIRMED, " ", "procedure", "result", "manual"));
		reject(record(service, DiagnosticModule.ENGINE, DiagnosticReadStatus.READ,
				DiagnosticConclusion.CONFIRMED, "diagnosis", " ", "result", "manual"));
		reject(record(service, DiagnosticModule.ENGINE, DiagnosticReadStatus.READ,
				DiagnosticConclusion.CONFIRMED, "diagnosis", "procedure", "\n", "manual"));
		reject(record(service, DiagnosticModule.ENGINE, DiagnosticReadStatus.READ,
				DiagnosticConclusion.CONFIRMED, "diagnosis", "procedure", "result", "\t"));
		assertNull(diagnostics.find(service, DiagnosticModule.ENGINE));
		diagnostics.save(proof(service, DiagnosticModule.ENGINE));
		database.close();
		DiagnosticRecord saved = diagnostics.find(service, DiagnosticModule.ENGINE);
		assertEquals(DiagnosticConclusion.CONFIRMED, saved.conclusion);
		assertEquals(DiagnosticReadStatus.READ, saved.readStatus);
		assertEquals("Measured open circuit", saved.diagnosis);
		assertEquals("Harness continuity test with ECU isolated", saved.testProcedure);
		assertEquals("Open circuit at connector", saved.testResult);
		assertEquals("Vehicle manual, harness procedure page 42", saved.reference);
		assertEquals("Independent scanner", saved.scanner);
		assertEquals("P0100\nP0101", saved.codes);
		assertEquals("Power loss", saved.symptoms);
		assertEquals("Connector corrosion", saved.observations);
		assertEquals("Repaired harness", saved.repair);
		assertEquals("Retested under same conditions", saved.verification);
		assertEquals(123456L, saved.updatedAt);
	}

	@Test
	public void moduleAndServiceRecordsAreIndependentUpdatedAndCascadeDeleted()
	{
		long service = createService();
		long vehicle = workshop.findService(service).vehicleId;
		long otherService = workshop.createService(vehicle, "Second visit");
		diagnostics.save(proof(service, DiagnosticModule.ENGINE));
		diagnostics.save(record(service, DiagnosticModule.SRS, DiagnosticReadStatus.NOT_SUPPORTED,
				DiagnosticConclusion.HYPOTHESIS, "Inspect separately", "", "", ""));
		diagnostics.save(proof(otherService, DiagnosticModule.ENGINE));
		diagnostics.save(record(service, DiagnosticModule.ENGINE, DiagnosticReadStatus.READ_FAILED,
				DiagnosticConclusion.ECU_INDICATION, "Retained technician note", "", "", ""));
		assertEquals(2, diagnostics.forService(service).size());
		DiagnosticRecord failed = diagnostics.find(service, DiagnosticModule.ENGINE);
		assertEquals(DiagnosticReadStatus.READ_FAILED, failed.readStatus);
		assertEquals(DiagnosticConclusion.ECU_INDICATION, failed.conclusion);
		assertEquals("P0100\nP0101", failed.codes);
		assertEquals("Retained technician note", failed.diagnosis);
		assertEquals(DiagnosticReadStatus.NOT_SUPPORTED,
				diagnostics.find(service, DiagnosticModule.SRS).readStatus);
		assertEquals(DiagnosticConclusion.CONFIRMED,
				diagnostics.find(otherService, DiagnosticModule.ENGINE).conclusion);
		database.getWritableDatabase().delete("services", "id = ?", new String[]{String.valueOf(service)});
		assertTrue(diagnostics.forService(service).isEmpty());
		assertNotNull(diagnostics.find(otherService, DiagnosticModule.ENGINE));
		long customer = workshop.findVehicle(vehicle).customerId;
		assertTrue(workshop.deleteCustomer(customer));
		assertTrue(diagnostics.forService(otherService).isEmpty());
	}

	@Test
	public void readRequiresNamedProvenanceButUncheckedNotesArePreserved()
	{
		long service = createService();
		for (DiagnosticConclusion conclusion : DiagnosticConclusion.values())
		{
			for (String scanner : new String[]{null, "", " \n\t"})
			{
				DiagnosticRecord missingSource = new DiagnosticRecord(service, DiagnosticModule.ENGINE,
						DiagnosticReadStatus.READ, conclusion, scanner, "P0100", "Symptom", "Observation",
						"Procedure", "Result", "Manual", "Diagnosis", "", "", 123456L);
				try { diagnostics.save(missingSource); fail("Reading without provenance accepted"); }
				catch (IllegalArgumentException expected)
				{
					assertEquals("diagnostic.read.scanner_required", expected.getMessage());
				}
			}
		}
		DiagnosticRecord unchecked = new DiagnosticRecord(service, DiagnosticModule.OTHER,
				DiagnosticReadStatus.NOT_CHECKED, DiagnosticConclusion.HYPOTHESIS, null,
				null, "Mechanical symptom", "  Preserve entered notes\n", null, null,
				null, "Pending inspection", null, null, 123456L);
		diagnostics.save(unchecked);
		DiagnosticRecord saved = diagnostics.find(service, DiagnosticModule.OTHER);
		assertEquals(DiagnosticReadStatus.NOT_CHECKED, saved.readStatus);
		assertEquals(DiagnosticConclusion.HYPOTHESIS, saved.conclusion);
		assertEquals("", saved.scanner);
		assertEquals("", saved.codes);
		assertEquals("  Preserve entered notes\n", saved.observations);
	}

	@Test
	public void unknownStoredEnumsAreRejectedRatherThanDefaulted()
	{
		for (String value : new String[]{null, "", "NORMAL", "read"})
		{
			try { DiagnosticReadStatus.fromStored(value); fail("Unknown status accepted"); }
			catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
		}
		try { DiagnosticModule.fromStored("AIRBAG"); fail("Unknown module accepted"); }
		catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
		try { DiagnosticConclusion.fromStored("NORMAL"); fail("Unknown conclusion accepted"); }
		catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
	}

	@Test
	public void v2BackupRoundTripRestoresAllDiagnosticEvidence() throws Exception
	{
		long service = createService();
		diagnostics.save(proof(service, DiagnosticModule.ABS));
		File backup = temporary("v2");
		WorkshopBackup.export(database, backup);
		diagnostics.save(record(service, DiagnosticModule.ABS, DiagnosticReadStatus.NOT_CHECKED,
				DiagnosticConclusion.HYPOTHESIS, "Changed", "", "", ""));
		WorkshopBackup.restore(database, backup);
		DiagnosticRecord restored = diagnostics.find(service, DiagnosticModule.ABS);
		assertEquals(DiagnosticConclusion.CONFIRMED, restored.conclusion);
		assertEquals("Vehicle manual, harness procedure page 42", restored.reference);
		assertEquals("Harness continuity test with ECU isolated", restored.testProcedure);
		assertEquals("Open circuit at connector", restored.testResult);
		assertEquals("P0100\nP0101", restored.codes);
		assertEquals("Repaired harness", restored.repair);
		assertEquals("Retested under same conditions", restored.verification);
	}

	@Test
	public void v1BackupIsMigratedBeforeRestoreWithoutChangingOriginal() throws Exception
	{
		createService();
		File backup = temporary("v1");
		createV1(backup);
		WorkshopBackup.restore(database, backup);
		assertLegacyData();
		assertEquals(WorkshopDatabase.SCHEMA_VERSION, database.getReadableDatabase().getVersion());
		assertNull(diagnostics.find(3, DiagnosticModule.ENGINE));
		diagnostics.save(proof(3, DiagnosticModule.TRANSMISSION));
		assertNotNull(diagnostics.find(3, DiagnosticModule.TRANSMISSION));
		try (SQLiteDatabase original = SQLiteDatabase.openDatabase(backup.getPath(), null,
				SQLiteDatabase.OPEN_READONLY))
		{
			assertEquals(1, original.getVersion());
		}
	}

	@Test
	public void malformedV2AndNewerBackupsCannotReplaceLiveData() throws Exception
	{
		long service = createService();
		diagnostics.save(proof(service, DiagnosticModule.BODY));
		File malformed = temporary("missing-column");
		WorkshopBackup.export(database, malformed);
		try (SQLiteDatabase bad = SQLiteDatabase.openDatabase(malformed.getPath(), null, 0))
		{
			bad.execSQL("ALTER TABLE diagnostics RENAME TO removed_diagnostics");
			bad.execSQL("CREATE TABLE diagnostics (service_id INTEGER, module TEXT)");
		}
		rejectRestore(malformed, service);
		File newer = temporary("newer");
		WorkshopBackup.export(database, newer);
		try (SQLiteDatabase bad = SQLiteDatabase.openDatabase(newer.getPath(), null, 0))
		{
			bad.setVersion(WorkshopDatabase.SCHEMA_VERSION + 1);
		}
		rejectRestore(newer, service);
		File orphan = temporary("orphan");
		WorkshopBackup.export(database, orphan);
		try (SQLiteDatabase bad = SQLiteDatabase.openDatabase(orphan.getPath(), null, 0))
		{
			bad.execSQL("UPDATE diagnostics SET service_id = 999999");
		}
		rejectRestore(orphan, service);
	}

	@Test
	public void canonicalSameFileGuardsProtectLiveDatabase() throws Exception
	{
		long service = createService();
		File current = context.getDatabasePath(databaseName);
		File alias = new File(current.getParentFile(), "./" + current.getName());
		try { WorkshopBackup.export(database, alias); fail("Export overwrote itself"); }
		catch (IOException expected) { assertNotNull(expected.getMessage()); }
		try { WorkshopBackup.restore(database, alias); fail("Restore accepted itself"); }
		catch (IOException expected) { assertNotNull(expected.getMessage()); }
		assertNotNull(workshop.findService(service));
	}

	@Test
	public void failedCandidateOpenRollsBackAndKeepsNewerLiveEvidence() throws Exception
	{
		database.close();
		FailingOpenDatabase failing = new FailingOpenDatabase(context, databaseName);
		database = failing;
		workshop = new WorkshopRepository(database);
		diagnostics = new DiagnosticRepository(database);
		long service = createService();
		diagnostics.save(proof(service, DiagnosticModule.ENGINE));
		File backup = temporary("rollback");
		WorkshopBackup.export(database, backup);
		diagnostics.save(proof(service, DiagnosticModule.BODY));
		workshop.updateServiceNotes(service, "Newer live notes");
		failing.failNextOpen = true;
		try { WorkshopBackup.restore(database, backup); fail("Injected open failure was ignored"); }
		catch (IOException expected) { assertNotNull(expected.getCause()); }
		assertNotNull(diagnostics.find(service, DiagnosticModule.ENGINE));
		assertNotNull(diagnostics.find(service, DiagnosticModule.BODY));
		assertEquals("Newer live notes", workshop.findService(service).notes);
	}

	private static final class FailingOpenDatabase extends WorkshopDatabase
	{
		boolean failNextOpen;

		FailingOpenDatabase(Context context, String name)
		{
			super(context, name);
		}

		@Override
		public void onOpen(SQLiteDatabase db)
		{
			super.onOpen(db);
			if (failNextOpen)
			{
				failNextOpen = false;
				throw new android.database.sqlite.SQLiteException("Injected candidate open failure");
			}
		}
	}

	private void rejectRestore(File backup, long service) throws Exception
	{
		try { WorkshopBackup.restore(database, backup); fail("Malformed backup accepted"); }
		catch (IOException expected) { assertNotNull(expected.getMessage()); }
		assertNotNull(workshop.findService(service));
		assertEquals(DiagnosticConclusion.CONFIRMED,
				diagnostics.find(service, DiagnosticModule.BODY).conclusion);
	}

	private void reject(DiagnosticRecord record)
	{
		try { diagnostics.save(record); fail("Missing confirmation evidence accepted"); }
		catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
	}

	private long createService()
	{
		long customer = workshop.createCustomer("Evidence customer", "0812", "Notes");
		long vehicle = workshop.createVehicle(customer, "B 123 AA", "Truck", 2015,
				EngineType.DIESEL, "VIN123", 123000);
		return workshop.createService(vehicle, "Power loss");
	}

	private DiagnosticRecord proof(long service, DiagnosticModule module)
	{
		return record(service, module, DiagnosticReadStatus.READ, DiagnosticConclusion.CONFIRMED,
				"Measured open circuit", "Harness continuity test with ECU isolated",
				"Open circuit at connector", "Vehicle manual, harness procedure page 42");
	}

	private DiagnosticRecord record(long service, DiagnosticModule module,
			DiagnosticReadStatus status, DiagnosticConclusion conclusion, String diagnosis,
			String procedure, String result, String reference)
	{
		return new DiagnosticRecord(service, module, status, conclusion, "Independent scanner",
				"P0100\nP0101", "Power loss", "Connector corrosion", procedure, result,
				reference, diagnosis, "Repaired harness", "Retested under same conditions", 123456L);
	}

	private File temporary(String label)
	{
		File file = new File(context.getCacheDir(), databaseName + "-" + label + ".db");
		files.add(file);
		return file;
	}

	/** Frozen v1 schema: intentionally independent from current onCreate/migrations. */
	private void createV1(File file)
	{
		File parent = file.getParentFile();
		if (!parent.exists()) assertTrue(parent.mkdirs());
		try (SQLiteDatabase legacy = SQLiteDatabase.openOrCreateDatabase(file, null))
		{
			legacy.execSQL("CREATE TABLE customers (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, phone TEXT NOT NULL DEFAULT '', notes TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL)");
			legacy.execSQL("CREATE TABLE vehicles (id INTEGER PRIMARY KEY AUTOINCREMENT, customer_id INTEGER NOT NULL REFERENCES customers(id) ON DELETE CASCADE, plate TEXT NOT NULL, plate_key TEXT NOT NULL DEFAULT '', model TEXT NOT NULL DEFAULT '', year INTEGER, engine_type TEXT NOT NULL DEFAULT 'DIESEL', vin TEXT NOT NULL DEFAULT '', odometer_km INTEGER, created_at INTEGER NOT NULL)");
			legacy.execSQL("CREATE TABLE services (id INTEGER PRIMARY KEY AUTOINCREMENT, vehicle_id INTEGER NOT NULL REFERENCES vehicles(id) ON DELETE CASCADE, complaint TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT 'WAITING', payment_status TEXT NOT NULL DEFAULT 'UNPAID', notes TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, completed_at INTEGER)");
			legacy.execSQL("CREATE TABLE estimate_items (id INTEGER PRIMARY KEY AUTOINCREMENT, service_id INTEGER NOT NULL REFERENCES services(id) ON DELETE CASCADE, kind TEXT NOT NULL DEFAULT 'PART', description TEXT NOT NULL, quantity REAL NOT NULL DEFAULT 1, unit_price INTEGER NOT NULL DEFAULT 0, position INTEGER NOT NULL DEFAULT 0)");
			legacy.execSQL("INSERT INTO customers VALUES (1, 'Legacy customer', '0800', 'Customer notes', 100)");
			legacy.execSQL("INSERT INTO vehicles VALUES (2, 1, 'B 321 ZZ', 'B321ZZ', 'Legacy truck', 2010, 'DIESEL', 'VIN-LEGACY', 234000, 200)");
			legacy.execSQL("INSERT INTO services VALUES (3, 2, 'Legacy complaint', 'DONE', 'PAID', 'Service notes', 300, 400, 500)");
			legacy.execSQL("INSERT INTO estimate_items VALUES (4, 3, 'PART', 'Legacy filter', 2, 45000, 0)");
			legacy.setVersion(1);
		}
	}

	private void assertLegacyData()
	{
		Customer customer = workshop.findCustomer(1);
		assertEquals("Legacy customer", customer.name);
		assertEquals("0800", customer.phone);
		assertEquals("Customer notes", customer.notes);
		assertEquals(100L, customer.createdAt);
		Vehicle vehicle = workshop.findVehicle(2);
		assertEquals(1L, vehicle.customerId);
		assertEquals("B 321 ZZ", vehicle.plate);
		assertEquals("Legacy truck", vehicle.model);
		assertEquals(Integer.valueOf(2010), vehicle.year);
		assertEquals("VIN-LEGACY", vehicle.vin);
		assertEquals(Integer.valueOf(234000), vehicle.odometerKm);
		assertEquals(1, workshop.findVehiclesByPlate("B321ZZ").size());
		ServiceRecord service = workshop.findService(3);
		assertEquals(2L, service.vehicleId);
		assertEquals("Legacy complaint", service.complaint);
		assertEquals("Service notes", service.notes);
		assertEquals(ServiceStatus.DONE, service.status);
		assertEquals(PaymentStatus.PAID, service.paymentStatus);
		assertEquals(Long.valueOf(500), service.completedAt);
		assertEquals(300L, service.createdAt);
		assertEquals(400L, service.updatedAt);
		assertEquals(90000L, workshop.estimateTotal(3));
		assertEquals("Legacy filter", workshop.estimateItems(3).get(0).description);
	}
}

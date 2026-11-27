package com.fr3ts0n.ecu.gui.androbd.workshop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/**
 * A v4 database that already recorded a job as paid must keep that meaning after the upgrade:
 * the received amount becomes the job's total, so the balance reads zero.
 */
@RunWith(AndroidJUnit4.class)
public class UpgradeInstrumentedTest
{
	@Test
	public void v4DatabaseWithPaidJobSurvivesUpgrade()
	{
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		String name = "upgrade-" + System.nanoTime() + ".db";
		File path = context.getDatabasePath(name);
		//noinspection ResultOfMethodCallIgnored
		path.getParentFile().mkdirs();
		for (String suffix : new String[]{"", "-wal", "-shm", "-journal"})
		{
			new File(path.getPath() + suffix).delete();
		}

		SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(path, null);
		db.execSQL("CREATE TABLE customers (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, phone TEXT, notes TEXT, created_at INTEGER NOT NULL)");
		db.execSQL("CREATE TABLE vehicles (id INTEGER PRIMARY KEY AUTOINCREMENT, customer_id INTEGER NOT NULL, plate TEXT NOT NULL, plate_key TEXT NOT NULL, model TEXT, year INTEGER, engine_type TEXT, vin TEXT, odometer_km INTEGER, created_at INTEGER NOT NULL)");
		db.execSQL("CREATE TABLE services (id INTEGER PRIMARY KEY AUTOINCREMENT, vehicle_id INTEGER NOT NULL, complaint TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'WAITING', payment_status TEXT NOT NULL DEFAULT 'UNPAID', notes TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, completed_at INTEGER, odometer_km INTEGER, payment_method TEXT, paid_at INTEGER)");
		db.execSQL("CREATE TABLE estimate_items (id INTEGER PRIMARY KEY AUTOINCREMENT, service_id INTEGER NOT NULL, kind TEXT NOT NULL, description TEXT NOT NULL, quantity REAL NOT NULL DEFAULT 1, unit_price INTEGER NOT NULL DEFAULT 0, position INTEGER NOT NULL DEFAULT 0)");
		db.execSQL("INSERT INTO customers VALUES (1,'Pelanggan uji','','',1)");
		db.execSQL("INSERT INTO vehicles VALUES (1,1,'B 1 UP','B1UP','Truk',2010,'DIESEL','',1000,1)");
		db.execSQL("INSERT INTO services VALUES (1,1,'Servis lunas','DONE','PAID','',1,1,1,NULL,'CASH',1)");
		db.execSQL("INSERT INTO estimate_items (id,service_id,kind,description,quantity,unit_price,position) VALUES (1,1,'LABOUR','Jasa',1,100000,0)");
		// v4 already knows about approvals
		db.execSQL("ALTER TABLE estimate_items ADD COLUMN discount INTEGER NOT NULL DEFAULT 0");
		db.execSQL("ALTER TABLE estimate_items ADD COLUMN approval TEXT NOT NULL DEFAULT 'PENDING'");
		db.execSQL("ALTER TABLE estimate_items ADD COLUMN inspection_item_id INTEGER");
		db.execSQL("UPDATE estimate_items SET approval='APPROVED'");
		db.setVersion(4);
		db.close();

		String outcome;
		try
		{
			WorkshopDatabase upgraded = new WorkshopDatabase(context, name);
			WorkshopRepository repo = new WorkshopRepository(upgraded);
			ServiceRecord service = repo.findService(1);
			outcome = "upgrade=ok status=" + service.paymentStatus
					+ " paid=" + service.paidAmount
					+ " balance=" + repo.balanceDue(1);
			upgraded.close();
		}
		catch (RuntimeException e)
		{
			outcome = "upgrade=THREW " + e.getClass().getSimpleName() + ": " + e.getMessage();
		}

		for (String suffix : new String[]{"", "-wal", "-shm", "-journal"})
		{
			new File(path.getPath() + suffix).delete();
		}
		assertTrue(outcome, outcome.startsWith("upgrade=ok"));
		assertTrue(outcome, outcome.contains("status=PAID"));
		assertTrue("a settled job must owe nothing: " + outcome, outcome.endsWith("balance=0"));
	}
}

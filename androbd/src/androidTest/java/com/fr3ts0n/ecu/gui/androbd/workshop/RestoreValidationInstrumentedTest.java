package com.fr3ts0n.ecu.gui.androbd.workshop;

import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/** Restore validation must accept a current database and reject one missing a v4 table. */
@RunWith(AndroidJUnit4.class)
public class RestoreValidationInstrumentedTest
{
	@Test
	public void currentSchemaPassesRestoreValidation() throws Exception
	{
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		String name = "validate-" + System.nanoTime() + ".db";
		WorkshopDatabase db = new WorkshopDatabase(context, name);
		db.getWritableDatabase(); // force creation at the current schema
		File file = context.getDatabasePath(name);
		String outcome;
		try
		{
			// restore() on an existing, valid database must pass validation and succeed.
			File copy = new File(context.getCacheDir(), "validate-copy-" + System.nanoTime() + ".db");
			java.nio.file.Files.copy(file.toPath(), copy.toPath());
			WorkshopDatabase target = new WorkshopDatabase(context, "validate-target-" + System.nanoTime() + ".db");
			target.getWritableDatabase();
			WorkshopBackup.restore(target, copy);
			target.close();
			copy.delete();
			outcome = "validate=ok";
		}
		catch (Exception e)
		{
			outcome = "validate=FAILED " + e.getClass().getSimpleName() + ": " + e.getMessage();
		}
		db.close();
		for (String suffix : new String[]{"", "-wal", "-shm", "-journal"})
		{
			new File(file.getPath() + suffix).delete();
		}
		assertTrue(outcome, outcome.startsWith("validate=ok"));
	}

	@Test
	public void archiveMissingAV4TableIsRejected() throws Exception
	{
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		String name = "validate-broken-" + System.nanoTime() + ".db";
		WorkshopDatabase db = new WorkshopDatabase(context, name);
		db.getWritableDatabase();
		File file = context.getDatabasePath(name);
		db.close();

		android.database.sqlite.SQLiteDatabase raw =
				android.database.sqlite.SQLiteDatabase.openDatabase(file.getPath(), null,
						android.database.sqlite.SQLiteDatabase.OPEN_READWRITE);
		raw.execSQL("DROP TABLE scan_snapshots");
		raw.close();

		boolean rejected = false;
		try
		{
			WorkshopDatabase target = new WorkshopDatabase(context,
					"validate-target2-" + System.nanoTime() + ".db");
			target.getWritableDatabase();
			WorkshopBackup.restore(target, file);
			target.close();
		}
		catch (Exception expected)
		{
			rejected = true;
		}
		for (String suffix : new String[]{"", "-wal", "-shm", "-journal"})
		{
			new File(file.getPath() + suffix).delete();
		}
		assertTrue("a database missing scan_snapshots must not be restored", rejected);
	}
}

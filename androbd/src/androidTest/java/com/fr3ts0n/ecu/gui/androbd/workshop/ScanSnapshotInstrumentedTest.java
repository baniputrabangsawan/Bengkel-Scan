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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fr3ts0n.ecu.EcuCodeItem;
import com.fr3ts0n.ecu.prot.obd.ObdProt;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.Collections;
import java.util.UUID;

/**
 * Scan snapshots are copies: clearing the live protocol lists afterwards must
 * not change what was saved, and the audit log must keep the clearing itself.
 */
@RunWith(AndroidJUnit4.class)
public class ScanSnapshotInstrumentedTest
{
	private Context context;
	private String name;
	private File file;
	private WorkshopDatabase database;
	private WorkshopRepository workshop;
	private ScanSnapshotRepository snapshots;
	private long serviceId;

	@Before
	public void setUp()
	{
		context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		name = "scan-snapshot-test-" + UUID.randomUUID() + ".db";
		file = context.getDatabasePath(name);
		database = new WorkshopDatabase(context, name);
		workshop = new WorkshopRepository(database);
		snapshots = new ScanSnapshotRepository(database);
		long customer = workshop.createCustomer("Scan fixture", "", "");
		long vehicle = workshop.createVehicle(customer, "B 77 SCAN", "Truk uji", 2012,
				EngineType.DIESEL, "", 300000);
		serviceId = workshop.createService(vehicle, "Pindai sebelum perbaikan", 300100);
	}

	@After
	public void tearDown()
	{
		database.close();
		context.deleteDatabase(name);
		for (String suffix : new String[]{"-wal", "-shm", "-journal", ".before-restore"})
		{
			File sidecar = new File(file.getPath() + suffix);
			if (sidecar.exists()) assertTrue("Stray test sidecar " + sidecar, sidecar.delete());
		}
		ObdProt.tCodes.clear();
	}

	@Test
	public void clearingTheLiveListAfterwardsDoesNotChangeTheSavedSnapshot()
	{
		EcuCodeItem stored = new EcuCodeItem("P0087", "Tekanan rel terlalu rendah");
		stored.put(EcuCodeItem.FID_STATUS, Integer.valueOf(ObdProt.OBD_SVC_READ_CODES));
		ObdProt.tCodes.put(stored.getKeyAttribute(), stored);
		String json = ScanSnapshotWriter.codesJson(ScanSnapshotWriter.faultCodes());
		assertEquals("The live list had one code", 1, ScanSnapshotWriter.size(json));

		long id = snapshots.save(serviceId, ScanPhase.BEFORE, ScanSource.DEMO, "Latihan", json, "[]", "[]", "");

		// What the next service selection or reconnect does.
		ObdProt.tCodes.clear();
		assertEquals("The live list is empty now", 0, ScanSnapshotWriter.size(
				ScanSnapshotWriter.codesJson(ScanSnapshotWriter.faultCodes())));

		ScanSnapshot saved = snapshots.latest(serviceId, ScanPhase.BEFORE);
		assertNotNull(saved);
		assertEquals(id, saved.id);
		assertEquals("The saved copy must survive the live list being cleared",
				1, ScanSnapshotWriter.size(saved.codesJson));
		assertTrue(saved.codesJson.contains("P0087"));
		assertEquals(ScanSource.DEMO, saved.source);
		assertFalse("Demo data must never report itself as a real scan", saved.real());
	}

	@Test
	public void beforeAndAfterAreKeptSeparatelyAndComparingThemIsPossible()
	{
		snapshots.save(serviceId, ScanPhase.BEFORE, ScanSource.DEMO, "",
				"[{\"code\":\"P0087\"},{\"code\":\"P0102\"}]", "[]", "[]", "Awal");
		snapshots.save(serviceId, ScanPhase.AFTER, ScanSource.DEMO, "",
				"[{\"code\":\"P0102\"}]", "[]", "[]", "Akhir");
		assertEquals(2, snapshots.forService(serviceId).size());

		ScanSnapshot before = snapshots.latest(serviceId, ScanPhase.BEFORE);
		ScanSnapshot after = snapshots.latest(serviceId, ScanPhase.AFTER);
		assertNotNull(before);
		assertNotNull(after);
		assertEquals("Awal", before.note);
		assertEquals("Akhir", after.note);

		ScanComparison comparison = ScanComparison.compare(before.codesJson, after.codesJson);
		assertEquals("P0087 no longer reported", 1, comparison.removed);
		assertEquals(0, comparison.added);
		assertEquals(1, comparison.same);
	}

	@Test
	public void latestReturnsTheNewestOfEachPhaseOnly()
	{
		snapshots.save(serviceId, ScanPhase.BEFORE, ScanSource.DEMO, "", "[{\"code\":\"A\"}]", "[]", "[]", "lama");
		snapshots.save(serviceId, ScanPhase.BEFORE, ScanSource.DEMO, "", "[{\"code\":\"B\"}]", "[]", "[]", "baru");
		ScanSnapshot latest = snapshots.latest(serviceId, ScanPhase.BEFORE);
		assertNotNull(latest);
		assertEquals("baru", latest.note);
		assertNull("No after-scan was saved yet", snapshots.latest(serviceId, ScanPhase.AFTER));
	}

	@Test
	public void aMalformedSnapshotIsRefusedInsteadOfStoredEmpty()
	{
		try
		{
			snapshots.save(serviceId, ScanPhase.BEFORE, ScanSource.DEMO, "", "not json", "[]", "[]", "");
			org.junit.Assert.fail("Malformed codes were accepted");
		}
		catch (IllegalArgumentException expected)
		{
			assertEquals("scan.codes.invalid", expected.getMessage());
		}
		assertEquals(0, snapshots.forService(serviceId).size());
	}

	@Test
	public void clearingWritesAnAuditLineWithTheCodesThatWereReported()
	{
		WorkshopSettingsRepository settings = new WorkshopSettingsRepository(database);
		EcuCodeItem stored = new EcuCodeItem("P0299", "Tekanan turbo rendah");
		String destroyed = ScanSnapshotWriter.codesJson(Collections.singletonList(stored));
		settings.record("codes.cleared", "obd", null, ScanSource.REAL.name() + " " + destroyed);
		assertEquals(1, settings.recent(10).size());
		assertEquals("codes.cleared", settings.recent(10).get(0).action);
		assertTrue("The audit line must keep the codes that were destroyed",
				settings.recent(10).get(0).detail.contains("P0299"));
	}
}

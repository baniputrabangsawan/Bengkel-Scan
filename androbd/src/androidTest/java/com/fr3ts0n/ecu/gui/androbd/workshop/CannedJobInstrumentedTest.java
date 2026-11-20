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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.List;

/**
 * Job packages: applying copies lines, editing the copy never touches the
 * package, and the seeded examples are recognisable and price-free.
 */
@RunWith(AndroidJUnit4.class)
public class CannedJobInstrumentedTest
{
	private Context context;
	private WorkshopDatabase database;
	private WorkshopRepository workshop;
	private CannedJobRepository jobs;
	private long serviceId;

	@Before
	public void setUp()
	{
		context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		database = new WorkshopDatabase(context, "canned-test-" + System.nanoTime() + ".db");
		workshop = new WorkshopRepository(database);
		jobs = new CannedJobRepository(database);
		long customer = workshop.createCustomer("Canned fixture", "", "");
		long vehicle = workshop.createVehicle(customer, "B 66 PK", "Truk uji", 2015,
				EngineType.DIESEL, "", 330000);
		serviceId = workshop.createService(vehicle, "Servis berkala", 330100);
	}

	@After
	public void tearDown()
	{
		String name = database.getDatabaseName();
		database.close();
		context.deleteDatabase(name);
		for (String suffix : new String[]{"-wal", "-shm", "-journal"})
		{
			File sidecar = new File(context.getDatabasePath(name).getPath() + suffix);
			if (sidecar.exists()) sidecar.delete();
		}
	}

	@Test
	public void applyingAPackageCopiesItsLinesOnly()
	{
		long job = jobs.create("Servis berkala diesel", "Contoh");
		jobs.addItem(job, ItemKind.LABOUR, "Servis berkala", 1, 150000);
		jobs.addItem(job, ItemKind.PART, "Filter oli", 1, 90000);

		List<Long> created = jobs.applyTo(job, serviceId, workshop);
		assertEquals(2, created.size());
		assertEquals(2, workshop.estimateItems(serviceId).size());
		assertEquals("A copied line starts unapproved", ApprovalState.PENDING,
				workshop.estimateItems(serviceId).get(0).approval);

		workshop.deleteEstimateItem(created.get(0));
		assertEquals("Deleting a copied line must not touch the package",
				2, jobs.find(job).items.size());
		assertEquals(1, workshop.estimateItems(serviceId).size());
	}

	@Test
	public void packagesAreIndependentOfEachOther()
	{
		long first = jobs.create("Rem", "");
		long second = jobs.create("Filter", "");
		jobs.addItem(first, ItemKind.LABOUR, "Ganti kampas", 1, 200000);
		jobs.addItem(second, ItemKind.PART, "Filter bahan bakar", 1, 120000);

		assertEquals(2, jobs.list().size());
		assertEquals("Rem", jobs.find(first).name);
		jobs.delete(second);
		assertEquals(1, jobs.list().size());
		assertNotNull(jobs.find(first));
	}

	@Test
	public void seedsAreMarkedExamplesWithoutPrices()
	{
		String note = "Contoh otomatis — harga masih contoh";
		int created = CannedJobSeeds.seedIfEmpty(jobs,
				new String[]{"Servis berkala", "Periksa rem"}, note);
		assertEquals(2, created);
		for (CannedJob job : jobs.list())
		{
			assertTrue("A seeded package must be recognisable: " + job.name,
					job.name.contains(CannedJobSeeds.MARK));
			assertEquals(note, job.note);
			for (EstimateItem item : job.items)
			{
				assertEquals("A seeded line must not pretend to be a real price",
						0, item.unitPrice);
			}
		}

		assertEquals("Seeding twice must not duplicate packages", 0,
				CannedJobSeeds.seedIfEmpty(jobs, new String[]{"Servis berkala"}, note));
		assertEquals(2, jobs.list().size());
	}

	@Test
	public void blankNamesAndBadPricesAreRefused()
	{
		try
		{
			jobs.create("   ", "");
			org.junit.Assert.fail("Blank package name accepted");
		}
		catch (IllegalArgumentException expected)
		{
			assertEquals("canned.name", expected.getMessage());
		}
		long job = jobs.create("Uji", "");
		try
		{
			jobs.addItem(job, ItemKind.PART, "Baris", 0, 1000);
			org.junit.Assert.fail("Zero quantity accepted");
		}
		catch (IllegalArgumentException expected)
		{
			assertEquals("canned.quantity", expected.getMessage());
		}
		try
		{
			jobs.addItem(job, ItemKind.PART, "Baris", 1, -1);
			org.junit.Assert.fail("Negative price accepted");
		}
		catch (IllegalArgumentException expected)
		{
			assertEquals("canned.unit_price", expected.getMessage());
		}
		assertTrue(jobs.find(job).items.isEmpty());
	}
}

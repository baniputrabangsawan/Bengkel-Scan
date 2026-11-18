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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.List;

/** Device tests for the workshop schema, repository and file backup. */
@RunWith(AndroidJUnit4.class)
public class WorkshopRepositoryInstrumentedTest
{
	private Context context;
	private String databaseName;
	private WorkshopDatabase database;
	private WorkshopRepository repository;

	@Before
	public void setUp()
	{
		context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		databaseName = "workshop-test-" + System.nanoTime() + ".db";
		database = new WorkshopDatabase(context, databaseName);
		repository = new WorkshopRepository(database);
	}

	@After
	public void tearDown()
	{
		repository.close();
		context.deleteDatabase(databaseName);
	}

	@Test
	public void customerAndVehicleLookupIgnoresPlateSpacingAndCase()
	{
		long customerId = repository.createCustomer("Budi Santoso", "0812-1111-2222", "");
		long vehicleId = repository.createVehicle(customerId, "  b 1234   xyz ",
				"Isuzu Panther", 2011, EngineType.DIESEL, "", 210000);

		assertEquals("B 1234 XYZ", repository.findVehicle(vehicleId).plate);
		assertEquals(2011, (int) repository.findVehicle(vehicleId).year);

		List<Vehicle> byPlate = repository.findVehiclesByPlate("b1234");
		assertEquals(1, byPlate.size());
		assertEquals(vehicleId, byPlate.get(0).id);
		assertEquals(1, repository.findVehiclesByPlate("  b 1234 ").size());
		assertEquals(1, repository.findVehiclesByPlate("XYZ").size());
		assertEquals(0, repository.findVehiclesByPlate("ZZZ").size());

		assertEquals(1, repository.searchCustomers("budi").size());
		assertEquals(1, repository.searchCustomers("1111").size());
		assertEquals(0, repository.searchCustomers("tidak-ada").size());
	}

	@Test
	public void serviceLifecycleStampsAndClearsCompletion()
	{
		long customerId = repository.createCustomer("Sari", "", "");
		long vehicleId = repository.createVehicle(customerId, "D 99 AB", "Truk", 2015,
				EngineType.DIESEL, null, null);
		long serviceId = repository.createService(vehicleId, "Asap hitam, tenaga hilang");

		ServiceRecord created = repository.findService(serviceId);
		assertEquals(ServiceStatus.WAITING, created.status);
		assertEquals(PaymentStatus.UNPAID, created.paymentStatus);
		assertNull(created.completedAt);

		assertTrue(repository.updateServiceStatus(serviceId, ServiceStatus.IN_PROGRESS));
		assertNull(repository.findService(serviceId).completedAt);

		assertTrue(repository.updateServiceStatus(serviceId, ServiceStatus.DONE));
		ServiceRecord done = repository.findService(serviceId);
		assertEquals(ServiceStatus.DONE, done.status);
		assertNotNull(done.completedAt);
		assertTrue(done.completedAt >= done.createdAt);

		assertTrue(repository.updateServiceStatus(serviceId, ServiceStatus.IN_PROGRESS));
		assertNull(repository.findService(serviceId).completedAt);

		assertTrue(repository.setPaymentStatus(serviceId, PaymentStatus.PAID));
		assertEquals(PaymentStatus.PAID, repository.findService(serviceId).paymentStatus);

		assertEquals(1, repository.servicesForVehicle(vehicleId).size());
		assertEquals(1, repository.servicesWithStatus(ServiceStatus.IN_PROGRESS).size());
		assertEquals(0, repository.servicesWithStatus(ServiceStatus.DONE).size());
	}

	@Test
	public void estimateTotalAddsRoundedLinesAndTracksOrder()
	{
		long customerId = repository.createCustomer("Joko", "", "");
		long vehicleId = repository.createVehicle(customerId, "L 1 AA", "", null,
				EngineType.PETROL, "", null);
		long serviceId = repository.createService(vehicleId, "Servis rutin");

		long labour = repository.addEstimateItem(serviceId, ItemKind.LABOUR,
				"Jasa bongkar", 1.5, 100000);
		long part = repository.addEstimateItem(serviceId, ItemKind.PART,
				"Filter oli", 2, 45000);

		assertEquals(150000L + 90000L, repository.estimateTotal(serviceId));
		List<EstimateItem> items = repository.estimateItems(serviceId);
		assertEquals(labour, items.get(0).id);
		assertEquals(part, items.get(1).id);

		assertTrue(repository.deleteEstimateItem(labour));
		assertEquals(90000L, repository.estimateTotal(serviceId));
	}

	@Test
	public void deletingCustomerCascadesToVehiclesServicesAndItems()
	{
		long customerId = repository.createCustomer("Pelanggan Hapus", "", "");
		long vehicleId = repository.createVehicle(customerId, "B 2 BB", "", null,
				EngineType.DIESEL, "", null);
		long serviceId = repository.createService(vehicleId, "Keluhan");
		repository.addEstimateItem(serviceId, ItemKind.PART, "Oli", 1, 50000);

		assertTrue(repository.deleteCustomer(customerId));
		assertNull(repository.findCustomer(customerId));
		assertNull(repository.findVehicle(vehicleId));
		assertNull(repository.findService(serviceId));
		assertTrue(repository.estimateItems(serviceId).isEmpty());
	}

	@Test
	public void restoreRejectsInvalidFileAndKeepsCurrentData() throws Exception
	{
		long customerId = repository.createCustomer("Tetap Ada", "", "");
		File bogus = new File(context.getCacheDir(), databaseName + "-bogus.db");
		java.io.FileOutputStream out = new java.io.FileOutputStream(bogus);
		out.write("bukan database sqlite".getBytes("UTF-8"));
		out.close();

		try
		{
			WorkshopBackup.restore(database, bogus);
			org.junit.Assert.fail("restore should reject a non-database file");
		}
		catch (java.io.IOException expected)
		{
			// expected
		}

		assertEquals("Tetap Ada", repository.findCustomer(customerId).name);
		assertTrue("rejected backup file must be left untouched", bogus.isFile());
		assertTrue(bogus.delete());
	}

	@Test
	public void exportAndRestoreRoundTripKeepsWorkshopData() throws Exception
	{
		long customerId = repository.createCustomer("Andi", "0813", "Pelanggan lama");
		long vehicleId = repository.createVehicle(customerId, "B 777 CD", "L300", 2008,
				EngineType.DIESEL, "", 320000);
		long serviceId = repository.createService(vehicleId, "Mogok");
		repository.addEstimateItem(serviceId, ItemKind.PART, "Pompa tangan", 1, 350000);

		File backup = new File(context.getCacheDir(), databaseName + "-backup.db");
		WorkshopBackup.export(database, backup);
		assertTrue(backup.isFile());
		assertTrue(backup.length() > 0);

		assertTrue(repository.setPaymentStatus(serviceId, PaymentStatus.PAID));

		WorkshopBackup.restore(database, backup);

		assertEquals("Andi", repository.findCustomer(customerId).name);
		assertEquals("B 777 CD", repository.findVehicle(vehicleId).plate);
		assertEquals(PaymentStatus.UNPAID, repository.findService(serviceId).paymentStatus);
		assertEquals(350000L, repository.estimateTotal(serviceId));

		assertTrue(backup.delete());
		assertFalse(backup.exists());
	}
}

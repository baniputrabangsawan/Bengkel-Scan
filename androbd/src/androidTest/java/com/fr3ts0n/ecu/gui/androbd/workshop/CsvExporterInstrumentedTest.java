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
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;

/**
 * CSV for a spreadsheet: one header, stable columns, and quoted text so a name
 * with a separator cannot shift them.
 */
@RunWith(AndroidJUnit4.class)
public class CsvExporterInstrumentedTest
{
	private Context context;
	private WorkshopDatabase database;
	private WorkshopRepository workshop;

	@Before
	public void setUp()
	{
		context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		database = new WorkshopDatabase(context, "csv-test-" + System.nanoTime() + ".db");
		workshop = new WorkshopRepository(database);
	}

	@After
	public void tearDown()
	{
		String name = database.getDatabaseName();
		database.close();
		context.deleteDatabase(name);
	}

	private StringWriter exportCustomers()
	{
		StringWriter out = new StringWriter();
		try
		{
			CsvExporter.writeCustomers(workshop, out);
		}
		catch (IOException error)
		{
			throw new AssertionError(error);
		}
		return out;
	}

	@Test
	public void customersFileHasAHeaderAndOneRowPerCustomer()
	{
		long id = workshop.createCustomer("Budi; Santoso", "0812", "Catatan \"penting\"");
		workshop.createVehicle(id, "B 1 CSV", "Truk", 2010, EngineType.DIESEL, "", 100);
		String csv = exportCustomers().toString();
		String[] lines = csv.split("\n");
		assertEquals("A header plus one row", 2, lines.length);
		assertTrue(lines[0], lines[0].startsWith("nama;telepon"));
		assertTrue("A separator inside a name must stay quoted: " + lines[1],
				lines[1].contains("\"Budi; Santoso\""));
		assertTrue("An embedded quote is doubled: " + lines[1],
				lines[1].contains("\"Catatan \"\"penting\"\"\""));
		assertTrue(lines[1], lines[1].contains("B 1 CSV"));
	}

	@Test
	public void servicesFileCountsReceivedAndApprovedMoney()
	{
		long id = workshop.createCustomer("Pelanggan", "", "");
		long vehicle = workshop.createVehicle(id, "B 2 CSV", "", null, EngineType.DIESEL, "", null);
		long service = workshop.createService(vehicle, "Servis");
		long line = workshop.addEstimateItem(service, ItemKind.LABOUR, "Jasa", 1, 200000);
		workshop.setApproval(line, ApprovalState.APPROVED, "");
		workshop.setPayment(service, PaymentStatus.UNPAID, PaymentMethod.CASH, null, 50000L);

		StringWriter out = new StringWriter();
		try
		{
			CsvExporter.writeServices(workshop, out);
		}
		catch (IOException error)
		{
			throw new AssertionError(error);
		}
		String[] lines = out.toString().split("\n");
		assertEquals(2, lines.length);
		assertTrue(lines[1], lines[1].contains("200000"));
		assertTrue("Money already received must be in the row", lines[1].contains("50000"));
	}

	@Test
	public void unpaidFilePrintsTheRemainingBalance()
	{
		long id = workshop.createCustomer("Pelanggan Utang", "0813", "");
		long vehicle = workshop.createVehicle(id, "B 3 CSV", "", null, EngineType.DIESEL, "", null);
		long service = workshop.createService(vehicle, "Belum dibayar");
		long line = workshop.addEstimateItem(service, ItemKind.PART, "Sparepart", 1, 300000);
		workshop.setApproval(line, ApprovalState.APPROVED, "");
		workshop.setPayment(service, PaymentStatus.UNPAID, PaymentMethod.TRANSFER, null, 100000L);

		StringWriter out = new StringWriter();
		try
		{
			CsvExporter.writeUnpaid(workshop, out);
		}
		catch (IOException error)
		{
			throw new AssertionError(error);
		}
		String[] lines = out.toString().split("\n");
		assertEquals(2, lines.length);
		assertTrue("The remaining balance is printed: " + lines[1], lines[1].endsWith("200000"));
		assertTrue(lines[1], lines[1].contains("Pelanggan Utang"));
	}
}

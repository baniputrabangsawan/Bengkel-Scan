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

/**
 * Money received: a short payment stays unpaid with an outstanding balance, and
 * the amount, method and time are recorded together.
 */
@RunWith(AndroidJUnit4.class)
public class PaymentInstrumentedTest
{
	private Context context;
	private WorkshopDatabase database;
	private WorkshopRepository workshop;
	private long serviceId;

	@Before
	public void setUp()
	{
		context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		database = new WorkshopDatabase(context, "payment-test-" + System.nanoTime() + ".db");
		workshop = new WorkshopRepository(database);
		long customer = workshop.createCustomer("Payment fixture", "", "");
		long vehicle = workshop.createVehicle(customer, "B 90 BY", "Truk uji", 2017,
				EngineType.DIESEL, "", 350000);
		serviceId = workshop.createService(vehicle, "Ganti kampas rem", 350100);
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

	private long agreeTotal(long total)
	{
		long line = workshop.addEstimateItem(serviceId, ItemKind.LABOUR, "Jasa", 1, total);
		workshop.setApproval(line, ApprovalState.APPROVED, "");
		return workshop.approvedTotal(serviceId);
	}

	@Test
	public void partialPaymentKeepsTheBalanceVisible()
	{
		long agreed = agreeTotal(500000);
		assertEquals(500000, agreed);

		workshop.setPayment(serviceId, PaymentStatus.UNPAID, PaymentMethod.TRANSFER, null, 200000L);
		ServiceRecord service = workshop.findService(serviceId);
		assertEquals(200000, service.paidAmount);
		assertEquals("A short payment is not settled", PaymentStatus.UNPAID, service.paymentStatus);
		assertNull("Unpaid work has no paid time", service.paidAt);
		assertEquals("The remaining balance is the agreed total minus what arrived",
				300000, service.outstanding(agreed));
		assertTrue(service.partiallyPaid(agreed));
	}

	@Test
	public void fullPaymentStampsTimeAndMethod()
	{
		long agreed = agreeTotal(250000);
		workshop.setPayment(serviceId, PaymentStatus.PAID, PaymentMethod.CASH, null, agreed);
		ServiceRecord service = workshop.findService(serviceId);
		assertEquals(agreed, service.paidAmount);
		assertEquals(PaymentStatus.PAID, service.paymentStatus);
		assertEquals(PaymentMethod.CASH, service.paymentMethod);
		assertNotNull("A settled job records when it was paid", service.paidAt);
		assertEquals(0, service.outstanding(agreed));
		assertTrue("Nothing outstanding after settlement", !service.partiallyPaid(agreed));
	}

	@Test
	public void markingUnpaidClearsTheRecordedMoney()
	{
		agreeTotal(100000);
		workshop.setPayment(serviceId, PaymentStatus.PAID, PaymentMethod.CASH, null, 100000L);
		assertEquals(PaymentStatus.PAID, workshop.findService(serviceId).paymentStatus);

		workshop.setPayment(serviceId, PaymentStatus.UNPAID, PaymentMethod.CASH, null, null);
		ServiceRecord cleared = workshop.findService(serviceId);
		assertEquals(PaymentStatus.UNPAID, cleared.paymentStatus);
		assertEquals("Reverting a payment must not leave money behind", 0, cleared.paidAmount);
		assertNull(cleared.paidAt);
	}

	@Test
	public void paidWithZeroReceivedIsNeverSettled()
	{
		agreeTotal(100000);
		workshop.setPayment(serviceId, PaymentStatus.PAID, PaymentMethod.CASH, null, 0L);
		assertEquals("Zero received cannot be paid", PaymentStatus.UNPAID,
				workshop.findService(serviceId).paymentStatus);
	}

	@Test
	public void unpaidListIncludesPartiallyPaidJobs()
	{
		long agreed = agreeTotal(300000);
		workshop.setPayment(serviceId, PaymentStatus.UNPAID, PaymentMethod.TRANSFER, null, 100000L);
		ServiceRecord service = workshop.findService(serviceId);
		assertEquals(200000, service.outstanding(agreed));
		assertTrue("A short payment must stay on the unpaid list",
				workshop.unpaidServices().stream().anyMatch(row -> row.id == serviceId));
	}

	@Test
	public void outstandingNeverGoesNegative()
	{
		agreeTotal(100000);
		workshop.setPayment(serviceId, PaymentStatus.PAID, PaymentMethod.CASH, null, 150000L);
		assertEquals("Overpayment is not a credit the app invents", 0,
				workshop.findService(serviceId).outstanding(100000));
	}
}

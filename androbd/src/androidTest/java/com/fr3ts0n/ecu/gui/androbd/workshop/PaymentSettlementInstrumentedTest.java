package com.fr3ts0n.ecu.gui.androbd.workshop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Settling a job from the one-tap "Lunas" control must not leave the whole agreed total due,
 * and a job that received nothing must not carry a settlement time.
 */
@RunWith(AndroidJUnit4.class)
public class PaymentSettlementInstrumentedTest
{
	private Context context;
	private WorkshopDatabase database;
	private WorkshopRepository workshop;
	private long serviceId;

	@Before
	public void setUp()
	{
		context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		database = new WorkshopDatabase(context, "pay-settle-" + System.nanoTime() + ".db");
		workshop = new WorkshopRepository(database);
		long customer = workshop.createCustomer("Probe fixture", "", "");
		long vehicle = workshop.createVehicle(customer, "B 11 PR", "Truk uji", 2015,
				EngineType.DIESEL, "", 100000);
		serviceId = workshop.createService(vehicle, "Ganti oli", 100010);
		long line = workshop.addEstimateItem(serviceId, ItemKind.LABOUR, "Oli mesin",
				1, 500000);
		workshop.setApproval(line, ApprovalState.APPROVED, "");
	}

	@After
	public void tearDown()
	{
		String name = database.getDatabaseName();
		database.close();
		for (String suffix : new String[]{"", "-wal", "-shm", "-journal"})
		{
			new java.io.File(context.getDatabasePath(name).getPath() + suffix).delete();
		}
	}

	@Test
	public void oneTapPaidWithNoAmountLeavesFullBalanceDue()
	{
		// exactly what the "Lunas" spinner entry does: status PAID, no amount recorded
		workshop.setPayment(serviceId, PaymentStatus.PAID, null, null, null);
		ServiceRecord record = workshop.findService(serviceId);
		long agreed = workshop.approvedTotal(serviceId);
		assertEquals("one-tap paid must settle the balance", 0L, record.outstanding(agreed));
	}

	@Test
	public void unpaidWithZeroAmountKeepsNoSettlementTimestamp()
	{
		workshop.setPayment(serviceId, PaymentStatus.PAID, PaymentMethod.CASH, null, 0L);
		ServiceRecord record = workshop.findService(serviceId);
		assertEquals(PaymentStatus.UNPAID, record.paymentStatus);
		assertNull("an unpaid job must not carry a settlement time", record.paidAt);
	}

	@Test
	public void repricingAPaidLineBringsTheDebtBack()
	{
		// Settle at 500000, then the line is re-priced to 600000: 100000 is owed again.
		workshop.setPaymentStatus(serviceId, PaymentStatus.PAID);
		assertEquals(0L, workshop.balanceDue(serviceId));

		EstimateItem line = workshop.estimateItems(serviceId).get(0);
		workshop.updateEstimateItem(line.id, line.kind, line.description, line.quantity,
				600000, 0);
		workshop.reconcilePayment(serviceId);

		ServiceRecord record = workshop.findService(serviceId);
		assertEquals("a partially covered job must not stay marked paid",
				PaymentStatus.UNPAID, record.paymentStatus);
		assertEquals(100000L, workshop.balanceDue(serviceId));
	}
}

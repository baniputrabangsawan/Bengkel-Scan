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

/**
 * What the customer owes: the approved total when anything is approved, the whole estimate
 * otherwise. An unbilled job must never report a zero balance.
 */
@RunWith(AndroidJUnit4.class)
public class BalanceDueInstrumentedTest
{
	private Context context;
	private WorkshopDatabase database;
	private WorkshopRepository workshop;
	private long serviceId;

	@Before
	public void setUp()
	{
		context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		database = new WorkshopDatabase(context, "balance-" + System.nanoTime() + ".db");
		workshop = new WorkshopRepository(database);
		long customer = workshop.createCustomer("Balance fixture", "", "");
		long vehicle = workshop.createVehicle(customer, "B 33 BL", "Truk", 2014,
				EngineType.DIESEL, "", 5000);
		serviceId = workshop.createService(vehicle, "Servis besar", 5010);
		workshop.addEstimateItem(serviceId, ItemKind.LABOUR, "Jasa", 1, 500000);
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
	public void unapprovedJobStillReportsWhatTheCustomerOwes()
	{
		assertEquals("a 500000 job with no approval must still owe 500000", 500000L,
				workshop.balanceDue(serviceId));
	}

	@Test
	public void partPaymentReducesTheUnapprovedBalance()
	{
		workshop.setPayment(serviceId, PaymentStatus.UNPAID, PaymentMethod.CASH, null, 200000L);
		assertEquals(300000L, workshop.balanceDue(serviceId));
	}
}

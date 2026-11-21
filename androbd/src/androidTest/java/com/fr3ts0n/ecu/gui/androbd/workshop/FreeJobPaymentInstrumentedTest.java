package com.fr3ts0n.ecu.gui.androbd.workshop;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** A job that owes nothing (no estimate, or a free check) can still be marked paid in one tap. */
@RunWith(AndroidJUnit4.class)
public class FreeJobPaymentInstrumentedTest
{
	private Context context;
	private WorkshopDatabase database;
	private WorkshopRepository workshop;
	private long serviceId;

	@Before
	public void setUp()
	{
		context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		database = new WorkshopDatabase(context, "free-job-" + System.nanoTime() + ".db");
		workshop = new WorkshopRepository(database);
		long customer = workshop.createCustomer("Empty fixture", "", "");
		long vehicle = workshop.createVehicle(customer, "B 22 EE", "Truk", 2012,
				EngineType.DIESEL, "", 10);
		serviceId = workshop.createService(vehicle, "Cek gratis", 20);
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
	public void jobWithoutAnyEstimateCanStillBeMarkedPaid()
	{
		workshop.setPaymentStatus(serviceId, PaymentStatus.PAID);
		assertEquals("nothing owed, so one tap must settle it", PaymentStatus.PAID,
				workshop.findService(serviceId).paymentStatus);
	}
}

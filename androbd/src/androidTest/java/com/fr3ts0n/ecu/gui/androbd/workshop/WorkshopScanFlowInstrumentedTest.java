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

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fr3ts0n.ecu.EcuCodeItem;
import com.fr3ts0n.ecu.gui.androbd.MainActivity;
import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.workshop.ui.WorkshopScanActivity;
import com.fr3ts0n.ecu.gui.androbd.workshop.ui.WorkshopScanCompareActivity;
import com.fr3ts0n.ecu.gui.androbd.workshop.ui.WorkshopServiceActivity;
import com.fr3ts0n.ecu.gui.androbd.workshop.ui.WorkshopUi;
import com.fr3ts0n.ecu.prot.obd.ObdProt;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * F5 user journey on a device: launch the app, open the workshop screens through
 * the app, save a scan, leave, come back and compare before/after.
 *
 * <p>Internal activities stay {@code exported=false}; the test reaches them with
 * the same intent extras the mechanic's taps produce. Synthetic rows live in the
 * app database so the real screens can read them, and are deleted afterwards.</p>
 */
@RunWith(AndroidJUnit4.class)
public class WorkshopScanFlowInstrumentedTest
{
	private Context context;
	private WorkshopRepository repository;
	private long customerId;
	private long serviceId;

	@Before
	public void setUp()
	{
		context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		ObdProt.tCodes.clear();
		repository = new WorkshopRepository(WorkshopDatabase.getInstance(context));
		customerId = repository.createCustomer("Scan flow fixture " + System.nanoTime(), "", "");
		long vehicleId = repository.createVehicle(customerId, "B 42 F5", "Truk uji", 2013,
				EngineType.DIESEL, "", 310000);
		serviceId = repository.createService(vehicleId, "Keluhan uji alur pindai", 310100);
	}

	@After
	public void tearDown()
	{
		if (customerId > 0) repository.deleteCustomer(customerId);
		ObdProt.tCodes.clear();
	}

	@Test
	public void mechanicSavesScanReturnsAndComparesBeforeWithAfter()
	{
		// Demo-mode reading, so no adapter is required for this journey.
		EcuCodeItem code = new EcuCodeItem("P0087", "Tekanan rel terlalu rendah");
		code.put(EcuCodeItem.FID_STATUS, Integer.valueOf(ObdProt.OBD_SVC_READ_CODES));
		ObdProt.tCodes.put(code.getKeyAttribute(), code);

		// 1. Start at the real entry point.
		try (ActivityScenario<MainActivity> main = ActivityScenario.launch(MainActivity.class))
		{
			main.onActivity(activity -> assertNotNull("Launcher activity did not open", activity));
		}

		// 2. The service screen offers both buttons; they are the mechanic's route.
		try (ActivityScenario<WorkshopServiceActivity> opened =
					 ActivityScenario.launch(intentFor(WorkshopServiceActivity.class)))
		{
			opened.onActivity(activity ->
			{
				assertNotNull("Scan button missing on the service screen",
						activity.findViewById(R.id.workshop_scan));
				assertNotNull("Inspection button missing on the service screen",
						activity.findViewById(R.id.workshop_inspection));
				assertNotNull("Before/after button missing on the service screen",
						activity.findViewById(R.id.workshop_scan_compare));
			});
		}

		// 3. Save a BEFORE scan through the screen, the way the button does.
		try (ActivityScenario<WorkshopScanActivity> scan =
					 ActivityScenario.launch(intentFor(WorkshopScanActivity.class)))
		{
			scan.onActivity(activity ->
			{
				View save = activity.findViewById(R.id.workshop_scan_save);
				assertNotNull("Save button is missing", save);
				save.performClick();
			});
		}

		ScanSnapshotRepository snapshots =
				new ScanSnapshotRepository(repository.database());
		ScanSnapshot before = snapshots.latest(serviceId, ScanPhase.BEFORE);
		assertNotNull("Saving from the screen must store a before-scan", before);
		assertTrue("The code read in demo mode must be kept", before.codesJson.contains("P0087"));
		assertEquals("Demo data must be labelled as such", ScanSource.DEMO, before.source);

		// The live list is cleared by the next service selection; the copy must stay.
		ObdProt.tCodes.clear();

		// 3. Reopen the service screen: the saved scan must be visible there.
		try (ActivityScenario<WorkshopServiceActivity> reopened =
					 ActivityScenario.launch(intentFor(WorkshopServiceActivity.class)))
		{
			reopened.onActivity(activity ->
			{
				TextView list = activity.findViewById(R.id.workshop_scan_list);
				assertNotNull("Saved scans are not shown on the service screen", list);
				assertEquals("The saved scan must survive the live list being cleared",
						View.VISIBLE, list.getVisibility());
				String shown = String.valueOf(list.getText());
				assertTrue("The service screen must state which position was saved: " + shown,
						shown.contains("Sebelum perbaikan"));
				assertTrue("The service screen must state the provenance: " + shown,
						shown.contains("Data contoh"));
				assertTrue("The service screen must state how many codes were frozen: " + shown,
						shown.contains("kode kerusakan"));
				assertTrue("The code read in demo mode must be part of the saved snapshot",
						before.codesJson.contains("P0087"));
			});
		}

		// 4. Save an AFTER scan with a different code set, then compare.
		snapshots.save(serviceId, ScanPhase.AFTER, ScanSource.DEMO, "",
				"[{\"code\":\"P0102\"}]", "[]", "[]", "Sesudah perbaikan");

		try (ActivityScenario<WorkshopScanCompareActivity> compare =
					 ActivityScenario.launch(intentFor(WorkshopScanCompareActivity.class)))
		{
			compare.onActivity(activity ->
			{
				TextView summary = activity.findViewById(R.id.workshop_compare_summary);
				String text = String.valueOf(summary.getText());
				assertTrue("Comparison must report the removed code: " + text, text.contains("1"));
				TextView beforeView = activity.findViewById(R.id.workshop_compare_before);
				TextView afterView = activity.findViewById(R.id.workshop_compare_after);
				assertTrue("Before side must list its code",
						String.valueOf(beforeView.getText()).contains("P0087"));
				assertTrue("After side must list its code",
						String.valueOf(afterView.getText()).contains("P0102"));
			});
		}

		assertEquals("Two snapshots belong to this service",
				2, snapshots.forService(serviceId).size());
	}

	private Intent intentFor(Class<? extends Activity> target)
	{
		return new Intent(context, target).putExtra(WorkshopUi.EXTRA_SERVICE_ID, serviceId);
	}
}

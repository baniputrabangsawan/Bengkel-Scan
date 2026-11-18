/*
 * Copyright 2026 Bengkel Scan contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.fr3ts0n.ecu.gui.androbd.workshop;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.widget.EditText;
import android.widget.Spinner;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.workshop.ui.WorkshopDiagnosticEditActivity;
import com.fr3ts0n.ecu.gui.androbd.workshop.ui.WorkshopGuideActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/** Consumer-visible evidence guards and preservation of unsaved field work. */
@RunWith(AndroidJUnit4.class)
public class WorkshopDiagnosticUiInstrumentedTest
{
	private Context context;
	private WorkshopRepository workshop;
	private DiagnosticRepository diagnostics;
	private long customerId;
	private long serviceId;

	@Before
	public void setUp()
	{
		context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		workshop = new WorkshopRepository(WorkshopDatabase.getInstance(context));
		diagnostics = new DiagnosticRepository(WorkshopDatabase.getInstance(context));
		customerId = workshop.createCustomer("Diagnostic UI fixture", "", "Synthetic test data");
		long vehicleId = workshop.createVehicle(customerId, "TEST DIAG", "Fixture model", 2020,
				EngineType.DIESEL, "", null);
		serviceId = workshop.createService(vehicleId, "Fixture complaint");
	}

	@After
	public void tearDown()
	{
		if (customerId != 0) workshop.deleteCustomer(customerId);
		// The process-wide helper belongs to the app, not this test.
	}

	private Intent editIntent()
	{
		return new Intent(context, WorkshopDiagnosticEditActivity.class)
				.putExtra("workshop.service_id", serviceId)
				.putExtra("diagnostic.module", DiagnosticModule.SRS.name())
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
	}

	@Test
	public void confirmationNeedsEvidenceAndPreservesRepairAndVerification()
	{
		try (ActivityScenario<WorkshopDiagnosticEditActivity> screen = ActivityScenario.launch(editIntent()))
		{
			screen.onActivity(activity ->
			{
				((Spinner) activity.findViewById(R.id.diagnostic_read_status))
						.setSelection(DiagnosticReadStatus.READ.ordinal());
				((Spinner) activity.findViewById(R.id.diagnostic_conclusion))
						.setSelection(DiagnosticConclusion.CONFIRMED.ordinal());
				((EditText) activity.findViewById(R.id.diagnostic_scanner)).setText("External scanner fixture");
				((EditText) activity.findViewById(R.id.diagnostic_codes)).setText("B fixture code; stored");
				activity.findViewById(R.id.diagnostic_save).performClick();
				assertNotNull(((EditText) activity.findViewById(R.id.diagnostic_procedure)).getError());
			});
			assertNull(diagnostics.find(serviceId, DiagnosticModule.SRS));
			screen.onActivity(activity ->
			{
				((EditText) activity.findViewById(R.id.diagnostic_procedure)).setText("Manufacturer procedure fixture");
				((EditText) activity.findViewById(R.id.diagnostic_result)).setText("Recorded measurement fixture");
				((EditText) activity.findViewById(R.id.diagnostic_reference)).setText("Fixture manual, model 2020, revision A, page 12");
				((EditText) activity.findViewById(R.id.diagnostic_diagnosis)).setText("Mechanic finding fixture");
				((EditText) activity.findViewById(R.id.diagnostic_repair)).setText("Repair action fixture");
				((EditText) activity.findViewById(R.id.diagnostic_verification)).setText("Post-repair retest fixture");
				activity.findViewById(R.id.diagnostic_save).performClick();
			});
		}
		DiagnosticRecord saved = diagnostics.find(serviceId, DiagnosticModule.SRS);
		assertNotNull(saved);
		assertEquals(DiagnosticConclusion.CONFIRMED, saved.conclusion);
		assertEquals("External scanner fixture", saved.scanner);
		assertEquals("Repair action fixture", saved.repair);
		assertEquals("Post-repair retest fixture", saved.verification);
		assertNull(diagnostics.find(serviceId, DiagnosticModule.TRANSMISSION));
	}

	@Test
	public void unsavedEvidenceSurvivesRecreationAndOpeningGuides() throws Exception
	{
		Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
		try (ActivityScenario<WorkshopDiagnosticEditActivity> screen = ActivityScenario.launch(editIntent()))
		{
			screen.onActivity(activity ->
			{
				((EditText) activity.findViewById(R.id.diagnostic_scanner)).setText("Scanner retained");
				((EditText) activity.findViewById(R.id.diagnostic_result)).setText("Unsaved measurements");
				((Spinner) activity.findViewById(R.id.diagnostic_read_status))
						.setSelection(DiagnosticReadStatus.READ_FAILED.ordinal());
				((Spinner) activity.findViewById(R.id.diagnostic_conclusion))
						.setSelection(DiagnosticConclusion.HYPOTHESIS.ordinal());
			});
			screen.recreate();
			Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
					WorkshopGuideActivity.class.getName(), null, false);
			try
			{
				screen.onActivity(activity -> activity.findViewById(R.id.diagnostic_open_guide).performClick());
				android.app.Activity guide = monitor.waitForActivityWithTimeout(5000);
				assertNotNull("Guide must open from an unsaved diagnostic form", guide);
				instrumentation.runOnMainSync(guide::finish);
				instrumentation.waitForIdleSync();
				screen.onActivity(activity ->
				{
					assertEquals("Scanner retained", ((EditText) activity.findViewById(R.id.diagnostic_scanner)).getText().toString());
					assertEquals("Unsaved measurements", ((EditText) activity.findViewById(R.id.diagnostic_result)).getText().toString());
					assertEquals(DiagnosticReadStatus.READ_FAILED.ordinal(),
							((Spinner) activity.findViewById(R.id.diagnostic_read_status)).getSelectedItemPosition());
					assertEquals(DiagnosticConclusion.HYPOTHESIS.ordinal(),
							((Spinner) activity.findViewById(R.id.diagnostic_conclusion)).getSelectedItemPosition());
				});
			}
			finally
			{
				instrumentation.removeMonitor(monitor);
			}
		}
		assertNull("Opening guidance must not save or confirm a diagnosis",
				diagnostics.find(serviceId, DiagnosticModule.SRS));
	}
}

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
package com.fr3ts0n.ecu.gui.androbd.workshop.pdf;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisMediaStore;
import com.fr3ts0n.ecu.gui.androbd.workshop.EngineType;
import com.fr3ts0n.ecu.gui.androbd.workshop.InspectionRepository;
import com.fr3ts0n.ecu.gui.androbd.workshop.InspectionState;
import com.fr3ts0n.ecu.gui.androbd.workshop.PhotoOwner;
import com.fr3ts0n.ecu.gui.androbd.workshop.PhotoRepository;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopDatabase;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopRepository;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopSettingsRepository;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Number for F19: how long a report with many photos takes to build.
 *
 * <p>Run on the workshop phone so the measurement reflects the device the
 * mechanic actually holds; the result is printed, not asserted, because a wall
 * clock time is environment-dependent.</p>
 */
@RunWith(AndroidJUnit4.class)
public class PdfPerformanceInstrumentedTest
{
	private Context context;
	private WorkshopDatabase database;
	private WorkshopRepository workshop;
	private long customerId;
	private long serviceId;

	@Before
	public void setUp() throws IOException
	{
		context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		database = new WorkshopDatabase(context, "pdf-perf-" + System.nanoTime() + ".db");
		workshop = new WorkshopRepository(database);
		customerId = workshop.createCustomer("Pelanggan Ukur", "", "");
		long vehicleId = workshop.createVehicle(customerId, "B 99 UKR", "Truk ukur", 2018,
				EngineType.DIESEL, "", 400000);
		serviceId = workshop.createService(vehicleId, "Ukur waktu cetak", 400100);
		new WorkshopSettingsRepository(database).saveProfile("Bengkel Ukur", "", "", "", "");

		InspectionRepository inspections = new InspectionRepository(database);
		PhotoRepository photos = new PhotoRepository(database);
		DiagnosisMediaStore media = new DiagnosisMediaStore(context);
		for (int i = 0; i < 8; i++)
		{
			long item = inspections.create(serviceId, "Komponen " + i,
					InspectionState.ATTENTION, "Catatan singkat");
			File source = writePng(new File(context.getCacheDir(), "perf-" + i + ".png"));
			photos.add(PhotoOwner.INSPECTION, item, media.relativePath(media.importPhoto(
					android.net.Uri.fromFile(source))), "");
			assertTrue(source.delete());
		}
	}

	@After
	public void tearDown()
	{
		if (customerId > 0) workshop.deleteCustomer(customerId);
		String name = database.getDatabaseName();
		database.close();
		context.deleteDatabase(name);
	}

	@Test
	public void reportWithEightPhotosBuildsWithinAReasonableTime()
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		long start = System.nanoTime();
		try
		{
			WorkshopPdf.writeReport(context, workshop, serviceId, labels(), output);
		}
		catch (IOException error)
		{
			throw new AssertionError(error);
		}
		long millis = (System.nanoTime() - start) / 1_000_000;
		System.out.println("F19 pdf-report-8-photos ms=" + millis + " bytes=" + output.size());
		assertTrue("A report must be produced", output.size() > 1000);
		assertTrue("A report with eight photos must not take minutes on the workshop phone: "
				+ millis + " ms", millis < 30000);
	}

	private static WorkshopPdf.Labels labels()
	{
		return new WorkshopPdf.Labels("Laporan", "Nota", "Pelanggan", "Kendaraan", "Keluhan",
				"Masuk", "Pelanggan", "Kode", "Pemeriksaan", "Estimasi", "Disetujui", "Catatan",
				"-", "-", "-", "-", "Status", "Bayar", "Total", "Tertaut",
				"Hasil ini hanya saran. Keputusan akhir ada pada mekanik.",
				"Baik", "Perlu perhatian", "Rusak", "Data contoh", "",
				"Menunggu", "Sedang dikerjakan", "Selesai", "Sudah dibayar", "Belum dibayar");
	}

	private File writePng(File target)
	{
		Bitmap bitmap = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888);
		bitmap.eraseColor(Color.rgb(40, 110, 160));
		try (FileOutputStream output = new FileOutputStream(target))
		{
			bitmap.compress(Bitmap.CompressFormat.PNG, 90, output);
		}
		catch (IOException error)
		{
			throw new AssertionError(error);
		}
		finally
		{
			bitmap.recycle();
		}
		return target;
	}
}

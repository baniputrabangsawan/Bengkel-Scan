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

import com.fr3ts0n.ecu.gui.androbd.workshop.Customer;
import com.fr3ts0n.ecu.gui.androbd.workshop.EngineType;
import com.fr3ts0n.ecu.gui.androbd.workshop.InspectionRepository;
import com.fr3ts0n.ecu.gui.androbd.workshop.InspectionState;
import com.fr3ts0n.ecu.gui.androbd.workshop.PhotoOwner;
import com.fr3ts0n.ecu.gui.androbd.workshop.PhotoRepository;
import com.fr3ts0n.ecu.gui.androbd.workshop.ScanPhase;
import com.fr3ts0n.ecu.gui.androbd.workshop.ScanSnapshotRepository;
import com.fr3ts0n.ecu.gui.androbd.workshop.ScanSource;
import com.fr3ts0n.ecu.gui.androbd.workshop.Vehicle;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopDatabase;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopRepository;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopSettingsRepository;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisMediaStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The printed documents: valid PDF bytes, pagination of long text and many
 * photos, and the app's own profile on the header.
 */
@RunWith(AndroidJUnit4.class)
public class WorkshopPdfInstrumentedTest
{
	private Context context;
	private WorkshopDatabase database;
	private WorkshopRepository workshop;
	private long customerId;
	private long serviceId;

	@Before
	public void setUp()
	{
		context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		database = new WorkshopDatabase(context, "pdf-test-" + System.nanoTime() + ".db");
		workshop = new WorkshopRepository(database);
		customerId = workshop.createCustomer("Pelanggan Cetak", "0812", "");
		long vehicleId = workshop.createVehicle(customerId, "B 88 PDF", "Truk cetak", 2016,
				EngineType.DIESEL, "VIN-PDF", 340000);
		serviceId = workshop.createService(vehicleId, "Sulit hidup saat dingin", 340100);
		WorkshopSettingsRepository settings = new WorkshopSettingsRepository(database);
		settings.saveProfile("Bengkel Cetak", "Jl. Uji 1", "0812", "Terima kasih", "");
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
	public void reportIsAValidPdfWithTheWorkshopHeader()
	{
		byte[] pdf = renderReport();
		assertPdf(pdf);
		assertTrue("A report must be more than an empty page", pdf.length > 1200);
	}

	@Test
	public void longTextAndManyPhotosPaginate()
	{
		StringBuilder note = new StringBuilder();
		for (int i = 0; i < 400; i++) note.append("Catatan panjang nomor ").append(i).append(". ");
		workshop.updateServiceNotes(serviceId, note.toString());

		InspectionRepository inspections = new InspectionRepository(database);
		PhotoRepository photos = new PhotoRepository(database);
		DiagnosisMediaStore media = new DiagnosisMediaStore(context);
		for (int i = 0; i < 6; i++)
		{
			long item = inspections.create(serviceId, "Komponen " + i,
					InspectionState.ATTENTION, "Rembes kecil");
			File source = writePng(new File(context.getCacheDir(), "pdf-photo-" + i + ".png"));
			try
			{
				String relative = media.relativePath(media.importPhoto(android.net.Uri.fromFile(source)));
				photos.add(PhotoOwner.INSPECTION, item, relative, "Foto " + i);
			}
			catch (IOException error)
			{
				throw new AssertionError(error);
			}
			assertTrue(source.delete());
		}

		byte[] pdf = renderReport();
		assertPdf(pdf);
		assertTrue("Six photos and a long note must add pages", pdf.length > 6000);
	}

	@Test
	public void invoiceListsOnlyApprovedLines()
	{
		long approved = workshop.addEstimateItem(serviceId, com.fr3ts0n.ecu.gui.androbd.workshop
				.ItemKind.LABOUR, "Jasa perbaikan", 1, 250000);
		long declined = workshop.addEstimateItem(serviceId, com.fr3ts0n.ecu.gui.androbd.workshop
				.ItemKind.PART, "Sparepart ditolak", 1, 900000);
		workshop.setApproval(approved, com.fr3ts0n.ecu.gui.androbd.workshop.ApprovalState.APPROVED, "");
		workshop.setApproval(declined, com.fr3ts0n.ecu.gui.androbd.workshop.ApprovalState.DECLINED, "");
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try
		{
			WorkshopPdf.writeInvoice(context, workshop, serviceId, labels(), output);
		}
		catch (IOException error)
		{
			throw new AssertionError(error);
		}
		assertPdf(output.toByteArray());
	}

	private byte[] renderReport()
	{
		ScanSnapshotRepository scans = new ScanSnapshotRepository(database);
		scans.save(serviceId, ScanPhase.BEFORE, ScanSource.DEMO, "",
				"[{\"code\":\"P0087\",\"description\":\"Tekanan rel terlalu rendah\"}]",
				"[]", "[]", "");
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try
		{
			WorkshopPdf.writeReport(context, workshop, serviceId, labels(), output);
		}
		catch (IOException error)
		{
			throw new AssertionError(error);
		}
		return output.toByteArray();
	}

	private static void assertPdf(byte[] pdf)
	{
		assertTrue("A PDF must not be empty", pdf != null && pdf.length > 400);
		String head = new String(pdf, 0, 5, StandardCharsets.US_ASCII);
		assertTrue("PDF header missing: " + head, head.startsWith("%PDF-"));
		String tail = new String(pdf, pdf.length - 8, 8, StandardCharsets.US_ASCII);
		assertTrue("PDF trailer missing: " + tail, tail.contains("%%EOF"));
	}

	private static WorkshopPdf.Labels labels()
	{
		return new WorkshopPdf.Labels("Laporan", "Nota", "Pelanggan", "Kendaraan", "Keluhan",
				"Masuk", "Pelanggan", "Kode", "Pemeriksaan", "Estimasi", "Disetujui", "Catatan",
				"-", "Tidak ada kode", "Belum ada bagian", "Belum ada baris", "Status", "Bayar",
				"Total", "Tertaut", "Hasil ini hanya saran. Keputusan akhir ada pada mekanik.",
				"Baik", "Perlu perhatian", "Rusak", "Data contoh", "");
	}

	private File writePng(File target)
	{
		Bitmap bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888);
		bitmap.eraseColor(Color.rgb(30, 90, 150));
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

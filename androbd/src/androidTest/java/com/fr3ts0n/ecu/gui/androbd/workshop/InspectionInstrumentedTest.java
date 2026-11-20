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
import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisMediaStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Inspected parts and their photos: soft delete keeps history, and a photo is
 * only referenced once a private copy exists.
 */
@RunWith(AndroidJUnit4.class)
public class InspectionInstrumentedTest
{
	private Context context;
	private WorkshopDatabase database;
	private WorkshopRepository workshop;
	private InspectionRepository inspections;
	private PhotoRepository photos;
	private long serviceId;

	@Before
	public void setUp()
	{
		context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		database = new WorkshopDatabase(context, "inspection-test-" + System.nanoTime() + ".db");
		workshop = new WorkshopRepository(database);
		inspections = new InspectionRepository(database);
		photos = new PhotoRepository(database);
		long customer = workshop.createCustomer("Inspection fixture", "", "");
		long vehicle = workshop.createVehicle(customer, "B 55 IN", "Truk uji", 2014,
				EngineType.DIESEL, "", 320000);
		serviceId = workshop.createService(vehicle, "Periksa rem dan selang", 320100);
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

	@Test
	public void addingItemsKeepsTheMechanicOrderAndRequiresAName()
	{
		long first = inspections.create(serviceId, "Selang turbo", InspectionState.ATTENTION, "Rembes");
		long second = inspections.create(serviceId, "Kampas rem", InspectionState.BROKEN, "");
		assertEquals(2, inspections.forService(serviceId).size());
		assertEquals("Selang turbo", inspections.forService(serviceId).get(0).name);
		assertEquals("Kampas rem", inspections.forService(serviceId).get(1).name);
		assertEquals(InspectionState.BROKEN, inspections.find(second).state);

		try
		{
			inspections.create(serviceId, "  ", InspectionState.GOOD, "");
			org.junit.Assert.fail("Blank part name accepted");
		}
		catch (IllegalArgumentException expected)
		{
			assertEquals("inspection.name", expected.getMessage());
		}
		assertEquals(2, inspections.forService(serviceId).size());
	}

	@Test
	public void deletingHidesTheItemButKeepsThePhotoRows()
	{
		long item = inspections.create(serviceId, "Kampas rem", InspectionState.GOOD, "");
		photos.add(PhotoOwner.INSPECTION, item, "photos/example.jpg", "Kampas depan");
		assertEquals(1, photos.forOwner(PhotoOwner.INSPECTION, item).size());

		assertTrue(inspections.delete(item));
		assertNull("A deleted item must not be readable", inspections.find(item));
		assertTrue(inspections.forService(serviceId).isEmpty());
		assertEquals("The photo row stays for the printed report",
				1, photos.forOwner(PhotoOwner.INSPECTION, item).size());
		assertFalse("Editing a deleted item must fail",
				inspections.update(item, "Kampas rem", InspectionState.BROKEN, ""));
	}

	@Test
	public void everyPhotoNeedsAPrivateCopyFirst()
	{
		long item = inspections.create(serviceId, "Selang turbo", InspectionState.GOOD, "");
		DiagnosisMediaStore media = new DiagnosisMediaStore(context);
		File source = writePng(new File(context.getCacheDir(), "inspection-source.png"));

		try
		{
			File stored = media.importPhoto(android.net.Uri.fromFile(source));
			String relative = media.relativePath(stored);
			photos.add(PhotoOwner.INSPECTION, item, relative, "Selang");

			Photo saved = photos.forOwner(PhotoOwner.INSPECTION, item).get(0);
			assertEquals(relative, saved.relativePath);
			assertFalse("A stored path is relative, never absolute", saved.relativePath.startsWith("/"));
			assertTrue("The private copy exists", media.resolve(relative).isFile());
			assertTrue("The source may now be deleted", source.delete());
			assertTrue("The private copy survives the source being deleted",
					media.resolve(saved.relativePath).isFile());
			assertTrue(media.resolve(saved.relativePath).delete());
		}
		catch (IOException error)
		{
			throw new AssertionError(error);
		}
	}

	@Test
	public void unsafePhotoReferencesAreRefused()
	{
		long item = inspections.create(serviceId, "Selang turbo", InspectionState.GOOD, "");
		for (String bad : new String[]{"", "   ", "/etc/passwd", "../escape.jpg", "a/../b.jpg"})
		{
			try
			{
				photos.add(PhotoOwner.INSPECTION, item, bad, "");
				org.junit.Assert.fail("Unsafe photo reference accepted: " + bad);
			}
			catch (IllegalArgumentException expected)
			{
				assertEquals("photo.path.required", expected.getMessage());
			}
		}
		assertTrue(photos.forOwner(PhotoOwner.INSPECTION, item).isEmpty());
	}

	@Test
	public void photoOwnerMustBeKnown()
	{
		long item = inspections.create(serviceId, "Selang turbo", InspectionState.GOOD, "");
		assertEquals(PhotoOwner.INSPECTION, PhotoOwner.fromStored("INSPECTION"));
		try
		{
			PhotoOwner.fromStored("SOMETHING");
			org.junit.Assert.fail("Unknown owner accepted");
		}
		catch (IllegalArgumentException expected)
		{
			assertEquals("photo.owner.unknown", expected.getMessage());
		}
		assertNotNull(photos);
	}

	private File writePng(File target)
	{
		Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
		bitmap.eraseColor(Color.rgb(40, 120, 200));
		try (FileOutputStream output = new FileOutputStream(target))
		{
			assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
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

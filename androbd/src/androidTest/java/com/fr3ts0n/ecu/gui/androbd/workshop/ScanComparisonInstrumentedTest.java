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
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Before/after code counting on the real {@code org.json} implementation.
 *
 * <p>A code disappearing is evidence, never a verdict; these tests pin the
 * counting rules and the refusal of malformed input.</p>
 */
@RunWith(AndroidJUnit4.class)
public class ScanComparisonInstrumentedTest
{
	@Test
	public void comparesCodesByTextNotByPosition()
	{
		ScanComparison result = ScanComparison.compare(
				"[{\"code\":\"P0087\"},{\"code\":\"P0102\"}]",
				"[{\"code\":\"P0102\"},{\"code\":\"P0299\"}]");
		assertEquals("One code stopped being reported", 1, result.removed);
		assertEquals("One code started being reported", 1, result.added);
		assertEquals("P0102 is reported in both scans", 1, result.same);
		assertEquals("P0087", result.removedCodes.get(0));
		assertEquals("P0299", result.addedCodes.get(0));
	}

	@Test
	public void duplicateCodesCountOnce()
	{
		ScanComparison result = ScanComparison.compare(
				"[{\"code\":\"P0087\"},{\"code\":\"P0087\"}]",
				"[{\"code\":\"P0087\"}]");
		assertEquals(0, result.removed);
		assertEquals(0, result.added);
		assertEquals(1, result.same);
	}

	@Test
	public void emptyScansCompareAsNoChange()
	{
		ScanComparison result = ScanComparison.compare("[]", "[]");
		assertEquals(0, result.removed);
		assertEquals(0, result.added);
		assertEquals(0, result.same);
	}

	@Test
	public void malformedCodesAreRejectedInsteadOfCountedAsEmpty()
	{
		try
		{
			ScanComparison.compare("not json", "[]");
			fail("Malformed before-scan accepted");
		}
		catch (IllegalArgumentException expected)
		{
			assertEquals("scan.codes.invalid", expected.getMessage());
		}
		try
		{
			ScanComparison.compare("[]", null);
			fail("Missing after-scan accepted");
		}
		catch (IllegalArgumentException expected)
		{
			assertEquals("scan.codes.required", expected.getMessage());
		}
	}

	@Test
	public void rowsWithoutCodeTextDoNotCount()
	{
		ScanComparison result = ScanComparison.compare(
				"[{\"description\":\"no code\"}]", "[{\"code\":\"P0087\"}]");
		assertTrue("A row without a code is not a code", result.added == 1 && result.same == 0);
	}

	@Test
	public void codesJsonKeepsCodeDescriptionAndStatus()
	{
		com.fr3ts0n.ecu.EcuCodeItem item =
				new com.fr3ts0n.ecu.EcuCodeItem("P0087", "Tekanan rel terlalu rendah");
		item.put(com.fr3ts0n.ecu.EcuCodeItem.FID_STATUS,
				Integer.valueOf(com.fr3ts0n.ecu.prot.obd.ObdProt.OBD_SVC_READ_CODES));
		String json = ScanSnapshotWriter.codesJson(java.util.Collections.singletonList(item));
		assertTrue(json, json.contains("P0087"));
		assertTrue(json, json.contains("Tekanan rel terlalu rendah"));
		assertTrue("The stored/pending kind must survive", json.contains("STORED"));
		assertEquals(1, ScanSnapshotWriter.size(json));
	}
}

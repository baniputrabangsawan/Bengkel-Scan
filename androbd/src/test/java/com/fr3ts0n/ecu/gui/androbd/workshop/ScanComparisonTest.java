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

import org.junit.Test;

/**
 * Snapshot provenance without a device.
 *
 * <p>The JSON counting rules live in the instrumented suite, because
 * {@code org.json} is only a stub in plain JVM tests.</p>
 */
public class ScanComparisonTest
{
	@Test
	public void onlyOnlineCountsAsRealAndUnknownModesAreDemo()
	{
		assertEquals(ScanSource.REAL, ScanSnapshotWriter.currentSource("ONLINE"));
		assertEquals(ScanSource.DEMO, ScanSnapshotWriter.currentSource("DEMO"));
		assertEquals(ScanSource.FILE, ScanSnapshotWriter.currentSource("FILE"));
		assertEquals("Offline is not a vehicle reading",
				ScanSource.DEMO, ScanSnapshotWriter.currentSource("OFFLINE"));
		assertEquals("An unrecognised mode must never claim a real scan",
				ScanSource.DEMO, ScanSnapshotWriter.currentSource("SOMETHING"));
		assertEquals(ScanSource.DEMO, ScanSnapshotWriter.currentSource(null));
	}
}

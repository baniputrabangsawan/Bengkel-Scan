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

/** Where a saved scan was taken, so a demo reading can never pass as a real one. */
public enum ScanSource
{
	/** Read from an adapter connected to the vehicle. */
	REAL,
	/** Simulated data from Latihan (demo) mode. */
	DEMO,
	/** Loaded from a saved `.obd` file. */
	FILE;

	/** Parse a stored value, falling back to {@link #DEMO} so unknown text never claims a real scan. */
	public static ScanSource fromStored(String value)
	{
		if (value != null)
		{
			for (ScanSource source : values())
			{
				if (source.name().equals(value)) return source;
			}
		}
		return DEMO;
	}
}

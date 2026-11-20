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

/** Before or after the repair work, for the same service. */
public enum ScanPhase
{
	BEFORE,
	AFTER;

	public static ScanPhase fromStored(String value)
	{
		if (value != null)
		{
			for (ScanPhase phase : values())
			{
				if (phase.name().equals(value)) return phase;
			}
		}
		return BEFORE;
	}
}

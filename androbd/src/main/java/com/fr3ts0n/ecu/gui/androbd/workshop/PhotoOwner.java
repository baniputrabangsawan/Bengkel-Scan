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

/** Where a stored photo belongs. Photos never live outside the app-private media folder. */
public enum PhotoOwner
{
	INSPECTION,
	DIAGNOSIS,
	SERVICE;

	/** Parse a stored value; unknown text throws so a foreign row is never shown as ours. */
	public static PhotoOwner fromStored(String value)
	{
		for (PhotoOwner owner : values())
		{
			if (owner.name().equals(value)) return owner;
		}
		throw new IllegalArgumentException("photo.owner.unknown");
	}
}

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

/** Kind of an estimate line. */
public enum ItemKind
{
	LABOUR,
	PART;

	/** Parse a stored value, falling back to {@link #PART} for unknown text. */
	public static ItemKind fromStored(String value)
	{
		if (value != null)
		{
			for (ItemKind kind : values())
			{
				if (kind.name().equals(value)) return kind;
			}
		}
		return PART;
	}
}

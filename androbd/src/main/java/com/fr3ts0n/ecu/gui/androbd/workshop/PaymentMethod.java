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

/** How the customer paid, when known. */
public enum PaymentMethod
{
	/** Not recorded yet. */
	UNKNOWN,
	CASH,
	TRANSFER,
	OTHER;

	/** Parse a stored value, falling back to {@link #UNKNOWN} for unknown text. */
	public static PaymentMethod fromStored(String value)
	{
		if (value != null)
		{
			for (PaymentMethod method : values())
			{
				if (method.name().equals(value)) return method;
			}
		}
		return UNKNOWN;
	}
}

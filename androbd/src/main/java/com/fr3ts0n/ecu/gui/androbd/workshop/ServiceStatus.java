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

/**
 * Workshop workflow state of one service visit.
 *
 * <p>Stored by name in SQLite so that adding a state later cannot silently
 * renumber existing rows.</p>
 */
public enum ServiceStatus
{
	WAITING,
	IN_PROGRESS,
	DONE;

	/** Parse a stored value, falling back to {@link #WAITING} for unknown text. */
	public static ServiceStatus fromStored(String value)
	{
		if (value != null)
		{
			for (ServiceStatus status : values())
			{
				if (status.name().equals(value)) return status;
			}
		}
		return WAITING;
	}
}

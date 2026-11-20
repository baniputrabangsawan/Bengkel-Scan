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

/** One upcoming service for a vehicle. Absence of a reminder means nothing is scheduled. */
public final class Reminder
{
	public final long id;
	public final long vehicleId;
	/** Epoch millis of the due date, or {@code null} when only the odometer matters. */
	public final Long dueDate;
	public final Integer dueOdometerKm;
	public final String note;
	/** Epoch millis when the mechanic dismissed it, or {@code null}. */
	public final Long dismissedAt;
	public final long createdAt;

	Reminder(long id, long vehicleId, Long dueDate, Integer dueOdometerKm, String note,
			Long dismissedAt, long createdAt)
	{
		this.id = id;
		this.vehicleId = vehicleId;
		this.dueDate = dueDate;
		this.dueOdometerKm = dueOdometerKm;
		this.note = note;
		this.dismissedAt = dismissedAt;
		this.createdAt = createdAt;
	}

	public boolean open()
	{
		return dismissedAt == null;
	}
}

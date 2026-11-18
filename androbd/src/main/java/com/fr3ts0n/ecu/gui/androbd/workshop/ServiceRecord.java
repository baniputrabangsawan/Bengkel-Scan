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

/** Immutable service visit row. */
public final class ServiceRecord
{
	public final long id;
	public final long vehicleId;
	public final String complaint;
	public final ServiceStatus status;
	public final PaymentStatus paymentStatus;
	public final String notes;
	public final long createdAt;
	public final long updatedAt;
	/** Time the service was completed, or {@code null} while not done. */
	public final Long completedAt;

	ServiceRecord(long id, long vehicleId, String complaint, ServiceStatus status,
			PaymentStatus paymentStatus, String notes, long createdAt, long updatedAt,
			Long completedAt)
	{
		this.id = id;
		this.vehicleId = vehicleId;
		this.complaint = complaint;
		this.status = status;
		this.paymentStatus = paymentStatus;
		this.notes = notes;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
		this.completedAt = completedAt;
	}
}

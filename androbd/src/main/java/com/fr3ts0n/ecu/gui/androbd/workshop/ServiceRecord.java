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

/** One visit to the workshop for one vehicle. */
public final class ServiceRecord
{
	public final long id;
	public final long vehicleId;
	public final String complaint;
	public final ServiceStatus status;
	public final PaymentStatus paymentStatus;
	public final String notes;
	/** Odometer at drop-off, or {@code null} when the mechanic did not record it. */
	public final Integer odometerKm;
	public final PaymentMethod paymentMethod;
	/** Epoch millis when the payment was recorded, or {@code null}. */
	public final Long paidAt;
	public final long createdAt;
	public final long updatedAt;
	/** Time the service was completed, or {@code null} while not done. */
	public final Long completedAt;

	ServiceRecord(long id, long vehicleId, String complaint, ServiceStatus status,
			PaymentStatus paymentStatus, String notes, Integer odometerKm,
			PaymentMethod paymentMethod, Long paidAt, long createdAt, long updatedAt,
			Long completedAt)
	{
		this.id = id;
		this.vehicleId = vehicleId;
		this.complaint = complaint;
		this.status = status;
		this.paymentStatus = paymentStatus;
		this.notes = notes;
		this.odometerKm = odometerKm;
		this.paymentMethod = paymentMethod;
		this.paidAt = paidAt;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
		this.completedAt = completedAt;
	}

	/** Work is still open: waiting or in progress. */
	public boolean open()
	{
		return status != ServiceStatus.DONE;
	}
}

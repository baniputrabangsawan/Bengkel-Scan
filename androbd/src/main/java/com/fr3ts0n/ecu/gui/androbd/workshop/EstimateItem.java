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
 * One line of a cost estimate.
 *
 * <p>Prices are whole Rupiah; {@link #lineTotal()} rounds each line so the
 * printed total equals the sum of the lines the customer sees. The discount is
 * a whole Rupiah amount subtracted after rounding, never a percentage, so the
 * mechanic sees the same number the customer sees.</p>
 */
public final class EstimateItem
{
	public final long id;
	public final long serviceId;
	public final ItemKind kind;
	public final String description;
	public final double quantity;
	public final long unitPrice;
	/** Whole Rupiah taken off this line, never negative and never above the line gross. */
	public final long discount;
	public final ApprovalState approval;
	/** Inspection item this line repairs, or {@code null}. */
	public final Long inspectionItemId;
	public final int position;

	EstimateItem(long id, long serviceId, ItemKind kind, String description,
			double quantity, long unitPrice, long discount, ApprovalState approval,
			Long inspectionItemId, int position)
	{
		this.id = id;
		this.serviceId = serviceId;
		this.kind = kind;
		this.description = description;
		this.quantity = quantity;
		this.unitPrice = unitPrice;
		this.discount = discount;
		this.approval = approval;
		this.inspectionItemId = inspectionItemId;
		this.position = position;
	}

	/** Rounded line total in Rupiah, before the line discount. */
	public long grossTotal()
	{
		return Math.round(quantity * unitPrice);
	}

	/** Rounded line total in Rupiah, after the line discount. */
	public long lineTotal()
	{
		return Math.max(0, grossTotal() - discount);
	}

	/** Counts toward a work order only when the customer approved this line. */
	public boolean approved()
	{
		return approval == ApprovalState.APPROVED;
	}
}

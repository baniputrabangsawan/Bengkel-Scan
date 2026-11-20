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

import java.util.List;

/**
 * How much of one estimate the customer agreed to.
 *
 * <p>Only approved lines count toward the work order. A line that was declined
 * later and approved again is approved now; the history keeps both answers.</p>
 */
public final class EstimateApprovalSummary
{
	public final int approvedLines;
	public final int declinedLines;
	public final int pendingLines;
	public final long approvedTotal;
	public final long declinedTotal;

	private EstimateApprovalSummary(int approvedLines, int declinedLines, int pendingLines,
			long approvedTotal, long declinedTotal)
	{
		this.approvedLines = approvedLines;
		this.declinedLines = declinedLines;
		this.pendingLines = pendingLines;
		this.approvedTotal = approvedTotal;
		this.declinedTotal = declinedTotal;
	}

	public static EstimateApprovalSummary of(List<EstimateItem> items)
	{
		int approved = 0;
		int declined = 0;
		int pending = 0;
		long approvedTotal = 0;
		long declinedTotal = 0;
		if (items != null)
		{
			for (EstimateItem item : items)
			{
				if (item == null) continue;
				switch (item.approval)
				{
					case APPROVED:
						approved++;
						approvedTotal += item.lineTotal();
						break;
					case DECLINED:
						declined++;
						declinedTotal += item.lineTotal();
						break;
					case PENDING:
					default:
						pending++;
						break;
				}
			}
		}
		return new EstimateApprovalSummary(approved, declined, pending, approvedTotal, declinedTotal);
	}

	/** Every line answered and at least one approved, with none declined. */
	public boolean fullyApproved()
	{
		return approvedLines > 0 && pendingLines == 0 && declinedLines == 0;
	}

	/** Some but not all lines approved, or some declined. */
	public boolean partial()
	{
		return approvedLines > 0 && !fullyApproved();
	}

	public boolean none()
	{
		return approvedLines == 0;
	}

	public int total()
	{
		return approvedLines + declinedLines + pendingLines;
	}
}

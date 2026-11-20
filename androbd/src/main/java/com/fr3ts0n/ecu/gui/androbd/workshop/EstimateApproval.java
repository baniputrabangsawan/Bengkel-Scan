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

/** One recorded customer decision on one estimate line. */
public final class EstimateApproval
{
	public final long id;
	public final long estimateItemId;
	public final ApprovalState state;
	public final String note;
	public final long changedAt;

	EstimateApproval(long id, long estimateItemId, ApprovalState state, String note, long changedAt)
	{
		this.id = id;
		this.estimateItemId = estimateItemId;
		this.state = state;
		this.note = note;
		this.changedAt = changedAt;
	}
}

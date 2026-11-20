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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * Approval counting without a database: only approved lines are money the
 * customer agreed to, and a line answered twice counts once.
 */
public class EstimateApprovalSummaryTest
{
	private static EstimateItem line(long price, ApprovalState state)
	{
		return new EstimateItem(1, 1, ItemKind.PART, "Baris", 1, price, 0, state, null, 0);
	}

	@Test
	public void onlyApprovedLinesCountTowardTheAgreedTotal()
	{
		EstimateApprovalSummary summary = EstimateApprovalSummary.of(Arrays.asList(
				line(100000, ApprovalState.APPROVED),
				line(50000, ApprovalState.DECLINED),
				line(25000, ApprovalState.PENDING)));
		assertEquals(1, summary.approvedLines);
		assertEquals(1, summary.declinedLines);
		assertEquals(1, summary.pendingLines);
		assertEquals(100000, summary.approvedTotal);
		assertEquals(50000, summary.declinedTotal);
		assertTrue(summary.partial());
		assertFalse(summary.fullyApproved());
	}

	@Test
	public void fullyApprovedNeedsEveryLineAnsweredAndNoneDeclined()
	{
		assertTrue(EstimateApprovalSummary.of(Arrays.asList(
				line(1, ApprovalState.APPROVED),
				line(2, ApprovalState.APPROVED))).fullyApproved());
		assertFalse("A pending line means the customer has not agreed yet",
				EstimateApprovalSummary.of(Arrays.asList(
						line(1, ApprovalState.APPROVED),
						line(2, ApprovalState.PENDING))).fullyApproved());
		assertFalse(EstimateApprovalSummary.of(Arrays.asList(
				line(1, ApprovalState.APPROVED),
				line(2, ApprovalState.DECLINED))).fullyApproved());
	}

	@Test
	public void anEmptyEstimateIsNeitherApprovedNorPartial()
	{
		EstimateApprovalSummary summary = EstimateApprovalSummary.of(Collections.emptyList());
		assertTrue(summary.none());
		assertFalse(summary.partial());
		assertEquals(0, summary.total());
		assertEquals(0, summary.approvedTotal);
	}

	@Test
	public void discountsReduceWhatWasApproved()
	{
		EstimateItem discounted = new EstimateItem(1, 1, ItemKind.PART, "Baris", 1,
				100000, 20000, ApprovalState.APPROVED, null, 0);
		assertEquals(80000, EstimateApprovalSummary.of(
				Collections.singletonList(discounted)).approvedTotal);
	}
}

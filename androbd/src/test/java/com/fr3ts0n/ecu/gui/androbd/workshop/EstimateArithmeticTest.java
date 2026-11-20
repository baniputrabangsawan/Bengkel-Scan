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

/**
 * Estimate line arithmetic without a database or a device: a discount is a
 * whole Rupiah amount, so the printed line never goes negative and never
 * depends on a percentage being recomputed.
 */
public class EstimateArithmeticTest
{
	private static EstimateItem item(double quantity, long unitPrice, long discount,
			ApprovalState approval)
	{
		return new EstimateItem(1, 1, ItemKind.PART, "Baris uji", quantity, unitPrice,
				discount, approval, null, 0);
	}

	@Test
	public void grossTotalRoundsEachLineBeforeDiscount()
	{
		EstimateItem line = item(1.5, 100000, 0, ApprovalState.PENDING);
		assertEquals("1,5 x 100000 must round to whole Rupiah", 150000, line.grossTotal());
		assertEquals("A line without discount prints its gross", 150000, line.lineTotal());
	}

	@Test
	public void discountSubtractsAfterRounding()
	{
		EstimateItem line = item(2.5, 33333, 1000, ApprovalState.APPROVED);
		assertEquals("Rounding happens on the gross", 83333, line.grossTotal());
		assertEquals("Discount is a whole Rupiah amount, not a percentage", 82333, line.lineTotal());
	}

	@Test
	public void discountEqualToGrossPrintsZeroNotNegative()
	{
		EstimateItem line = item(1, 50000, 50000, ApprovalState.APPROVED);
		assertEquals("A fully discounted line prints zero", 0, line.lineTotal());
	}

	@Test
	public void approvalDecidesWhetherTheLineCounts()
	{
		assertTrue(item(1, 1, 0, ApprovalState.APPROVED).approved());
		assertFalse("Pending lines never count toward a work order",
				item(1, 1, 0, ApprovalState.PENDING).approved());
		assertFalse(item(1, 1, 0, ApprovalState.DECLINED).approved());
	}

	@Test
	public void unknownStoredStatesFallBackToSafeDefaults()
	{
		assertEquals(ApprovalState.PENDING, ApprovalState.fromStored("SOMETHING_NEW"));
		assertEquals(InspectionState.GOOD, InspectionState.fromStored(null));
		assertEquals(PaymentMethod.UNKNOWN, PaymentMethod.fromStored(""));
		assertEquals("A scan of unknown provenance must never claim to be real",
				ScanSource.DEMO, ScanSource.fromStored("MYSTERY"));
	}
}

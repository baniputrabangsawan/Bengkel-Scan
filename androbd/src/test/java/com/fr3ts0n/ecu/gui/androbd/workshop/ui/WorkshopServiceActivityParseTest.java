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
package com.fr3ts0n.ecu.gui.androbd.workshop.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The two parser helpers the estimate dialog uses, without a device.
 *
 * <p>"1,5" is an Indonesian decimal comma, and a Rupiah field may arrive with
 * thousand separators typed by the mechanic.</p>
 */
public class WorkshopServiceActivityParseTest
{
	@Test
	public void quantityAcceptsCommaOrDot()
	{
		assertEquals(1.5, WorkshopServiceActivity.parseQuantity("1,5"), 0.0001);
		assertEquals(1.5, WorkshopServiceActivity.parseQuantity("1.5"), 0.0001);
		assertEquals(2.0, WorkshopServiceActivity.parseQuantity(" 2 "), 0.0001);
		assertEquals("Text is not a quantity", 0.0, WorkshopServiceActivity.parseQuantity("dua"), 0.0001);
		assertEquals(0.0, WorkshopServiceActivity.parseQuantity(""), 0.0001);
	}

	@Test
	public void rupiahIgnoresSeparatorsAndKeepsTheFallback()
	{
		assertEquals(150000, WorkshopServiceActivity.parseRupiah("150.000", -1));
		assertEquals(150000, WorkshopServiceActivity.parseRupiah("Rp 150.000", -1));
		assertEquals(150000, WorkshopServiceActivity.parseRupiah("150 000", -1));
		assertEquals("A blank discount is zero, not an error", 0,
				WorkshopServiceActivity.parseRupiah("", 0));
		assertEquals(-1, WorkshopServiceActivity.parseRupiah("", -1));
	}
}

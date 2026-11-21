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

/** One row of a workshop list: record id plus the visible texts and an optional status chip. */
final class WorkshopRow
{
	final long id;
	final String title;
	final String subtitle;
	/** Right-aligned bold text, for rows that already read as a sentence (estimate lines). */
	final String trailing;
	/** Status chip label, for rows whose state carries the workflow or a safety decision. */
	final String chip;
	final RowTone tone;

	WorkshopRow(long id, String title, String subtitle, String trailing)
	{
		this(id, title, subtitle, trailing, null, RowTone.NEUTRAL);
	}

	WorkshopRow(long id, String title, String subtitle, String chip, RowTone tone)
	{
		this(id, title, subtitle, null, chip, tone);
	}

	private WorkshopRow(long id, String title, String subtitle, String trailing,
			String chip, RowTone tone)
	{
		this.id = id;
		this.title = title;
		this.subtitle = subtitle;
		this.trailing = trailing;
		this.chip = chip;
		this.tone = tone == null ? RowTone.NEUTRAL : tone;
	}
}
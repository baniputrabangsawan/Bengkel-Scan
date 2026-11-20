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

import java.util.Collections;
import java.util.List;

/** Reusable package of estimate lines, for example scheduled diesel service. */
public final class CannedJob
{
	public final long id;
	public final String name;
	public final String note;
	public final List<EstimateItem> items;

	CannedJob(long id, String name, String note, List<EstimateItem> items)
	{
		this.id = id;
		this.name = name;
		this.note = note;
		this.items = Collections.unmodifiableList(items);
	}
}

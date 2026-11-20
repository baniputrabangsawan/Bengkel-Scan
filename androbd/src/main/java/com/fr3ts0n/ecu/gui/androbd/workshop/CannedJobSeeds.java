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
 * Example job packages seeded once on a fresh database.
 *
 * <p>Prices are zero placeholders on purpose: the owner sets real prices, and a
 * printed estimate must never look like a quotation nobody agreed to. The names
 * come from the caller so they follow the app language.</p>
 */
public final class CannedJobSeeds
{
	/** Suffix-free marker written into the note so a seeded package is recognisable. */
	public static final String MARK = "[contoh]";

	/**
	 * Create the example packages when none exist yet.
	 *
	 * @param jobs   repository to write through
	 * @param names  package names, already localized
	 * @return number of packages created
	 */
	public static int seedIfEmpty(CannedJobRepository jobs, String[] names, String note)
	{
		if (jobs.list().size() > 0) return 0;
		int created = 0;
		for (String name : names)
		{
			if (name == null || name.trim().isEmpty()) continue;
			long id = jobs.create(name.trim() + " " + MARK, note);
			jobs.addItem(id, ItemKind.LABOUR, name.trim(), 1, 0);
			created++;
		}
		return created;
	}

	private CannedJobSeeds()
	{
	}
}

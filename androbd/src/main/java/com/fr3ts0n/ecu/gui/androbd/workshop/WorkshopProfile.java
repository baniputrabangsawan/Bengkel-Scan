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

/** Printed identity of the workshop: used on PDFs and share text. */
public final class WorkshopProfile
{
	public final String name;
	public final String address;
	public final String phone;
	/** Extra line under the totals on printed documents. */
	public final String footer;
	/** Relative or absolute path of the workshop logo the mechanic picked, or "". */
	public final String logoPath;
	public final long updatedAt;

	WorkshopProfile(String name, String address, String phone, String footer, String logoPath, long updatedAt)
	{
		this.name = name;
		this.address = address;
		this.phone = phone;
		this.footer = footer;
		this.logoPath = logoPath;
		this.updatedAt = updatedAt;
	}

	/** True when the workshop has filled in enough to print a document header. */
	public boolean printable()
	{
		return name != null && !name.trim().isEmpty();
	}
}

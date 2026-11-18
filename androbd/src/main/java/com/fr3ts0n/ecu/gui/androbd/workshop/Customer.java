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

/** Immutable customer row. */
public final class Customer
{
	public final long id;
	public final String name;
	public final String phone;
	public final String notes;
	public final long createdAt;

	Customer(long id, String name, String phone, String notes, long createdAt)
	{
		this.id = id;
		this.name = name;
		this.phone = phone;
		this.notes = notes;
		this.createdAt = createdAt;
	}
}

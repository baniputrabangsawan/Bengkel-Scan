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

/** A photo attached to an inspection item, a diagnosis case or a service. */
public final class Photo
{
	public final long id;
	public final PhotoOwner owner;
	public final long ownerId;
	/** Path relative to the app-private media root; never absolute. */
	public final String relativePath;
	public final String caption;
	public final long createdAt;

	Photo(long id, PhotoOwner owner, long ownerId, String relativePath, String caption, long createdAt)
	{
		this.id = id;
		this.owner = owner;
		this.ownerId = ownerId;
		this.relativePath = relativePath;
		this.caption = caption;
		this.createdAt = createdAt;
	}
}

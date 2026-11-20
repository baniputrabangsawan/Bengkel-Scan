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

/** One part or assembly checked during a service, with a note and optional photos. */
public final class InspectionItem
{
	public final long id;
	public final long serviceId;
	public final String name;
	public final InspectionState state;
	public final String note;
	/** Relative path of a voice note inside the app's private diagnosis folder, or "". */
	public final String voicePath;
	public final int position;
	public final long createdAt;
	public final long updatedAt;

	InspectionItem(long id, long serviceId, String name, InspectionState state,
			String note, String voicePath, int position, long createdAt, long updatedAt)
	{
		this.id = id;
		this.serviceId = serviceId;
		this.name = name;
		this.state = state;
		this.note = note;
		this.voicePath = voicePath;
		this.position = position;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	public boolean voiceNote()
	{
		return voicePath != null && !voicePath.isEmpty();
	}
}

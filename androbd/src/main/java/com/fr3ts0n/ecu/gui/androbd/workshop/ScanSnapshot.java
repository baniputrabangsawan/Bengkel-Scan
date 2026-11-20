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
 * Frozen copy of one OBD reading session, linked to a service.
 *
 * <p>The raw JSON is kept as the source of truth so a later app version can
 * still read an old snapshot; callers render it, they never rewrite it.</p>
 */
public final class ScanSnapshot
{
	public final long id;
	public final long serviceId;
	public final ScanPhase phase;
	public final ScanSource source;
	public final String adapter;
	public final String codesJson;
	public final String readingsJson;
	public final String freezeJson;
	public final String note;
	public final long createdAt;

	ScanSnapshot(long id, long serviceId, ScanPhase phase, ScanSource source, String adapter,
			String codesJson, String readingsJson, String freezeJson, String note, long createdAt)
	{
		this.id = id;
		this.serviceId = serviceId;
		this.phase = phase;
		this.source = source;
		this.adapter = adapter;
		this.codesJson = codesJson;
		this.readingsJson = readingsJson;
		this.freezeJson = freezeJson;
		this.note = note;
		this.createdAt = createdAt;
	}

	/** True only for data read from a connected adapter. */
	public boolean real()
	{
		return source == ScanSource.REAL;
	}
}

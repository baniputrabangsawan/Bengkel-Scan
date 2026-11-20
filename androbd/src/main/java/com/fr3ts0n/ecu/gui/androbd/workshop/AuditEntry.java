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

/** One recorded action that changes stored workshop data, for later review. */
public final class AuditEntry
{
	public final long id;
	/** Stable action key, for example {@code service.status} or {@code codes.cleared}. */
	public final String action;
	public final String targetKind;
	public final Long targetId;
	public final String detail;
	public final long createdAt;

	AuditEntry(long id, String action, String targetKind, Long targetId, String detail, long createdAt)
	{
		this.id = id;
		this.action = action;
		this.targetKind = targetKind;
		this.targetId = targetId;
		this.detail = detail;
		this.createdAt = createdAt;
	}
}

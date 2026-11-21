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

/**
 * Visual family of a status chip. Colour is never the only signal: every chip keeps its
 * Indonesian label, so a mechanic who cannot tell the two greens apart still reads
 * "Selesai" or "Lunas".
 */
enum RowTone
{
	/** Nothing has happened yet: Menunggu, Belum dijawab. */
	NEUTRAL,
	/** Being worked on: Sedang dikerjakan. */
	INFO,
	/** Finished or paid: Selesai, Lunas, Disetujui. */
	SUCCESS,
	/** Needs attention soon: akan jatuh tempo, perlu perhatian. */
	WARNING,
	/** Late or refused: terlewat, ditolak, rusak. */
	DANGER
}

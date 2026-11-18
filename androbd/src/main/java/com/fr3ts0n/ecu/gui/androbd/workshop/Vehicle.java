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
 * Immutable vehicle row.
 *
 * <p>Optional numbers use {@code null} instead of sentinel zero, because zero
 * is a legal model year placeholder in free text but not meaningful data.</p>
 */
public final class Vehicle
{
	public final long id;
	public final long customerId;
	public final String plate;
	public final String model;
	public final Integer year;
	public final EngineType engineType;
	public final String vin;
	public final Integer odometerKm;
	public final long createdAt;

	Vehicle(long id, long customerId, String plate, String model, Integer year,
			EngineType engineType, String vin, Integer odometerKm, long createdAt)
	{
		this.id = id;
		this.customerId = customerId;
		this.plate = plate;
		this.model = model;
		this.year = year;
		this.engineType = engineType;
		this.vin = vin;
		this.odometerKm = odometerKm;
		this.createdAt = createdAt;
	}
}

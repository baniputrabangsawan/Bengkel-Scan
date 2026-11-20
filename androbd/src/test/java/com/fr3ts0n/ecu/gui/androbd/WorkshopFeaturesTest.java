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
package com.fr3ts0n.ecu.gui.androbd;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The mechanic's advanced-mode switch may reveal extra views, but must never
 * reveal actuator tests or hide the destructive code-clearing entry.
 */
public class WorkshopFeaturesTest
{
	@Test
	public void advancedModeRevealsTheExtraViewsOnly()
	{
		assertFalse("Advanced views stay hidden by default",
				WorkshopFeatures.showMenuItem(R.id.day_night_mode, false));
		assertTrue(WorkshopFeatures.showMenuItem(R.id.day_night_mode, true));
		assertTrue(WorkshopFeatures.showMenuItem(R.id.save, true));
		assertTrue(WorkshopFeatures.showMenuItem(R.id.plugin_manager, true));
	}

	@Test
	public void actuatorControlsStayHiddenWhateverTheSwitchSays()
	{
		// The build-time flag is false; the mechanic's switch must not override it.
		assertFalse(WorkshopFeatures.showMenuItem(R.id.service_testcontrol, true));
		assertFalse(WorkshopFeatures.showMenuItem(R.id.service_testcontrol, false));
	}

	@Test
	public void clearingCodesIsUnaffectedByTheAdvancedSwitch()
	{
		boolean expected = WorkshopFeatures.SHOW_CLEAR_FAULT_CODES;
		assertTrue(!expected || WorkshopFeatures.showMenuItem(R.id.service_clearcodes, true));
		assertFalse("Advanced mode must not open what the build flag closes",
				!expected && WorkshopFeatures.showMenuItem(R.id.service_clearcodes, true));
	}

	@Test
	public void ordinaryDiagnosticEntriesAreAlwaysVisible()
	{
		assertTrue(WorkshopFeatures.showMenuItem(R.id.service_codes, false));
		assertTrue(WorkshopFeatures.showMenuItem(R.id.service_data, false));
	}
}

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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;

/** When a reminder counts as due, without a database or a device. */
public class ReminderStatusTest
{
	private static long at(int year, int month, int day, int hour)
	{
		Calendar calendar = Calendar.getInstance();
		calendar.set(year, month - 1, day, hour, 0, 0);
		calendar.set(Calendar.MILLISECOND, 0);
		return calendar.getTimeInMillis();
	}

	private static Reminder reminder(Long dueDate, Integer dueOdometerKm)
	{
		return new Reminder(1, 2, dueDate, dueOdometerKm, "", null, 0);
	}

	@Test
	public void nothingIsDueWithoutADateOrOdometer()
	{
		assertEquals(ReminderStatus.State.NOT_DUE,
				ReminderStatus.evaluate(reminder(null, null), 100000, at(2026, 5, 10, 9)));
	}

	@Test
	public void aPastDateIsOverdueAndTodayIsToday()
	{
		long now = at(2026, 5, 10, 9);
		assertEquals(ReminderStatus.State.OVERDUE,
				ReminderStatus.evaluate(reminder(at(2026, 5, 1, 8), null), null, now));
		assertEquals(ReminderStatus.State.TODAY,
				ReminderStatus.evaluate(reminder(at(2026, 5, 10, 8), null), null, now));
		assertEquals(ReminderStatus.State.NOT_DUE,
				ReminderStatus.evaluate(reminder(at(2026, 5, 20, 8), null), null, now));
	}

	@Test
	public void aKnownOdometerCanMakeItDue()
	{
		long now = at(2026, 5, 10, 9);
		assertEquals("Reached the target odometer", ReminderStatus.State.OVERDUE,
				ReminderStatus.evaluate(reminder(null, 340000), 340500, now));
		assertEquals(ReminderStatus.State.NOT_DUE,
				ReminderStatus.evaluate(reminder(null, 340000), 330000, now));
		assertEquals("An unknown odometer must not invent a due state",
				ReminderStatus.State.NOT_DUE,
				ReminderStatus.evaluate(reminder(null, 340000), null, now));
	}

	@Test
	public void dismissedRemindersAreNeverDue()
	{
		Reminder dismissed = new Reminder(1, 2, at(2026, 1, 1, 8), null, "",
				at(2026, 1, 2, 8), 0);
		assertEquals(ReminderStatus.State.NOT_DUE,
				ReminderStatus.evaluate(dismissed, null, at(2026, 5, 10, 9)));
	}

	@Test
	public void dueSoonLooksForwardOnly()
	{
		long now = at(2026, 5, 10, 9);
		assertTrue(ReminderStatus.dueWithinDays(reminder(at(2026, 5, 20, 8), null), now, 14));
		assertFalse(ReminderStatus.dueWithinDays(reminder(at(2026, 6, 20, 8), null), now, 14));
		assertFalse("A past date is overdue, not soon",
				ReminderStatus.dueWithinDays(reminder(at(2026, 5, 1, 8), null), now, 14));
	}

	@Test
	public void dateParsingAcceptsTheMechanicsFormatOnly()
	{
		Long dashed = com.fr3ts0n.ecu.gui.androbd.workshop.ui.WorkshopVehicleActivity
				.parseDate("20-05-2026");
		assertEquals("20-05-2026 must parse", Long.valueOf(at(2026, 5, 20, 8)), dashed);
		Long slashed = com.fr3ts0n.ecu.gui.androbd.workshop.ui.WorkshopVehicleActivity
				.parseDate("20/05/2026");
		assertEquals("20/05/2026 must parse", Long.valueOf(at(2026, 5, 20, 8)), slashed);
		assertNull(com.fr3ts0n.ecu.gui.androbd.workshop.ui.WorkshopVehicleActivity
				.parseDate(""));
		assertNull(com.fr3ts0n.ecu.gui.androbd.workshop.ui.WorkshopVehicleActivity
				.parseDate("besok"));
	}
}

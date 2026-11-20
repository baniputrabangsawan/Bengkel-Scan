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

import java.util.Calendar;

/**
 * Whether a reminder is due, judged only from the data the workshop recorded.
 *
 * <p>A reminder without a due date cannot be "due" by date, and a reminder with
 * an odometer is only judged against the vehicle's last known odometer. Nothing
 * here guesses a mileage the mechanic did not enter.</p>
 */
public final class ReminderStatus
{
	public enum State
	{
		NOT_DUE,
		TODAY,
		OVERDUE
	}

	private ReminderStatus()
	{
	}

	/**
	 * @param reminder     the reminder
	 * @param odometerKm   last recorded vehicle odometer, or {@code null} when unknown
	 * @param now          current time in millis
	 */
	public static State evaluate(Reminder reminder, Integer odometerKm, long now)
	{
		if (reminder == null || !reminder.open()) return State.NOT_DUE;
		boolean byDate = reminder.dueDate != null && now >= reminder.dueDate;
		boolean byOdometer = reminder.dueOdometerKm != null && odometerKm != null
				&& odometerKm >= reminder.dueOdometerKm;
		if (!byDate && !byOdometer) return State.NOT_DUE;
		// Same local day and not already past it: today, still worth a phone call.
		if (reminder.dueDate != null && now - reminder.dueDate >= 24L * 60 * 60 * 1000)
		{
			return State.OVERDUE;
		}
		if (reminder.dueDate != null && !sameDay(reminder.dueDate, now)) return State.OVERDUE;
		if (byOdometer && reminder.dueDate == null) return State.OVERDUE;
		return State.TODAY;
	}

	/** True when the reminder will become due within {@code days}. */
	public static boolean dueWithinDays(Reminder reminder, long now, int days)
	{
		if (reminder == null || !reminder.open() || reminder.dueDate == null) return false;
		return reminder.dueDate >= now && reminder.dueDate <= now + (long) days * 24 * 60 * 60 * 1000;
	}

	private static boolean sameDay(long a, long b)
	{
		Calendar first = Calendar.getInstance();
		first.setTimeInMillis(a);
		Calendar second = Calendar.getInstance();
		second.setTimeInMillis(b);
		return first.get(Calendar.YEAR) == second.get(Calendar.YEAR)
				&& first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR);
	}
}

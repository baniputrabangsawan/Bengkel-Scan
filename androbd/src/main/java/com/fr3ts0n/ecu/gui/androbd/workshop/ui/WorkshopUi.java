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

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.workshop.ServiceStatus;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopDatabase;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopRepository;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Shared helpers for the workshop data screens. */
public final class WorkshopUi
{
	public static final String EXTRA_CUSTOMER_ID = "workshop.customer_id";
	public static final String EXTRA_VEHICLE_ID = "workshop.vehicle_id";
	public static final String EXTRA_SERVICE_ID = "workshop.service_id";

	private WorkshopUi()
	{
	}

	/**
	 * Repository on the process-wide database. Screens never close it: closing
	 * would shut the database for every other screen and the OBD session.
	 */
	public static WorkshopRepository repository(Context context)
	{
		return new WorkshopRepository(WorkshopDatabase.getInstance(context));
	}

	public static void prepare(AppCompatActivity activity, CharSequence title)
	{
		ActionBar bar = activity.getSupportActionBar();
		if (bar != null)
		{
			bar.setTitle(title);
			bar.setDisplayHomeAsUpEnabled(true);
		}
	}

	public static void hideKeyboard(Activity activity)
	{
		android.view.inputmethod.InputMethodManager keyboard =
				(android.view.inputmethod.InputMethodManager)
						activity.getSystemService(Context.INPUT_METHOD_SERVICE);
		android.view.View focus = activity.getCurrentFocus();
		if (keyboard != null && focus != null)
		{
			keyboard.hideSoftInputFromWindow(focus.getWindowToken(), 0);
		}
	}

	public static String money(long rupiah)
	{
		NumberFormat format = NumberFormat.getIntegerInstance(new Locale("in", "ID"));
		return "Rp " + format.format(rupiah);
	}

	public static String dateTime(long millis)
	{
		return new SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault()).format(new Date(millis));
	}

	public static String orFallback(CharSequence value, String fallback)
	{
		return TextUtils.isEmpty(value) ? fallback : value.toString().trim();
	}

	public static int statusLabel(ServiceStatus status)
	{
		switch (status)
		{
			case IN_PROGRESS:
				return R.string.workshop_status_in_progress;
			case DONE:
				return R.string.workshop_status_done;
			case WAITING:
			default:
				return R.string.workshop_status_waiting;
		}
	}
}
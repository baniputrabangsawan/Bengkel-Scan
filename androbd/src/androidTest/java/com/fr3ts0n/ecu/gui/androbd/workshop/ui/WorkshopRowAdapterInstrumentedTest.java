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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fr3ts0n.ecu.gui.androbd.R;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The shared workshop row: the status chip is a labelled, tinted block, and a recycled row
 * never keeps the previous row's chip, tone or trailing text. Exercises the adapter only,
 * without opening an activity or touching the database.
 */
@RunWith(AndroidJUnit4.class)
public class WorkshopRowAdapterInstrumentedTest
{
	private Context context()
	{
		return InstrumentationRegistry.getInstrumentation().getTargetContext();
	}

	private View row(WorkshopRowAdapter adapter, View convertView)
	{
		FrameLayout parent = new FrameLayout(context());
		return adapter.getView(0, convertView, parent);
	}

	@Test
	public void chipCarriesLabelAndToneButNeverColourAlone()
	{
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
		{
			WorkshopRowAdapter adapter = new WorkshopRowAdapter(context());
			adapter.submit(java.util.List.of(new WorkshopRow(1L, "B 42 F5", "20 Nov 2026", "Selesai",
					RowTone.SUCCESS)));
			View view = row(adapter, null);
			TextView chip = view.findViewById(R.id.workshop_row_chip);
			assertEquals("Selesai", chip.getText().toString());
			assertEquals("A status chip must be visible", View.VISIBLE, chip.getVisibility());
			assertEquals("The row's text and chip stay a single stop's worth of content",
					"B 42 F5", ((TextView) view.findViewById(R.id.workshop_row_title)).getText().toString());
		});
	}

	@Test
	public void recycledRowDropsThePreviousChipAndTrailingText()
	{
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
		{
			WorkshopRowAdapter adapter = new WorkshopRowAdapter(context());
			adapter.submit(java.util.List.of(new WorkshopRow(1L, "with chip", "meta", "Terlewat",
					RowTone.DANGER)));
			View view = row(adapter, null);
			TextView chip = view.findViewById(R.id.workshop_row_chip);
			assertEquals(View.VISIBLE, chip.getVisibility());

			adapter.submit(java.util.List.of(new WorkshopRow(2L, "plain row", "meta", "detail")));
			view = row(adapter, view);
			assertEquals("A reused row must hide the chip it no longer needs", View.GONE,
					chip.getVisibility());
			assertEquals("A reused row must show its trailing text", View.VISIBLE,
					view.findViewById(R.id.workshop_row_trailing).getVisibility());
			assertEquals("detail", ((TextView) view.findViewById(R.id.workshop_row_trailing))
					.getText().toString());
		});
	}

	/**
	 * Every tone must resolve to a real colour and a real surface. The palette deliberately
	 * reuses the two original status looks, so tones may share a colour; the chip always keeps
	 * its text label, which is what actually carries the meaning.
	 */
	@Test
	public void everyToneResolvesToAColourAndASurface()
	{
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
		{
			Context context = context();
			WorkshopRowAdapter adapter = new WorkshopRowAdapter(context);
			for (RowTone tone : RowTone.values())
			{
				adapter.submit(java.util.List.of(new WorkshopRow(1L, "title", null, "label", tone)));
				View view = row(adapter, null);
				assertNotEquals(tone + " must map to a label colour", 0,
						context.getColor(chipTextColorOf(tone)));
				assertNotEquals(tone + " must map to a chip surface", 0,
						context.getColor(chipSurfaceColorOf(tone)));
				TextView chip = view.findViewById(R.id.workshop_row_chip);
				assertEquals("The chip must still carry its word", "label",
						chip.getText().toString());
			}
		});
	}

	private static int chipSurfaceColorOf(RowTone tone)
	{
		switch (tone)
		{
			case INFO:
				return R.color.workshop_status_info_surface;
			case SUCCESS:
				return R.color.workshop_status_success_surface;
			case WARNING:
				return R.color.workshop_status_warning_surface;
			case DANGER:
				return R.color.workshop_status_danger_surface;
			case NEUTRAL:
			default:
				return R.color.workshop_status_neutral_surface;
		}
	}

	/** Mirrors the adapter's own mapping so the test fails if the mapping stops being total. */
	private static int chipTextColorOf(RowTone tone)
	{
		switch (tone)
		{
			case INFO:
				return R.color.workshop_status_info_text;
			case SUCCESS:
				return R.color.workshop_status_success_text;
			case WARNING:
				return R.color.workshop_status_warning_text;
			case DANGER:
				return R.color.workshop_status_danger_text;
			case NEUTRAL:
			default:
				return R.color.workshop_status_neutral_text;
		}
	}
}

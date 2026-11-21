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

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.fr3ts0n.ecu.gui.androbd.R;

import java.util.ArrayList;
import java.util.List;

/** Recycles the shared two-line workshop row. */
final class WorkshopRowAdapter extends BaseAdapter
{
	private final LayoutInflater inflater;
	private final List<WorkshopRow> rows = new ArrayList<>();

	WorkshopRowAdapter(Context context)
	{
		inflater = LayoutInflater.from(context);
	}

	void submit(List<WorkshopRow> items)
	{
		rows.clear();
		if (items != null) rows.addAll(items);
		notifyDataSetChanged();
	}

	@Override
	public int getCount()
	{
		return rows.size();
	}

	@Override
	public WorkshopRow getItem(int position)
	{
		return rows.get(position);
	}

	@Override
	public long getItemId(int position)
	{
		return rows.get(position).id;
	}

	@Override
	public View getView(int position, View convertView, ViewGroup parent)
	{
		View view = convertView;
		Holder holder;
		if (view == null)
		{
			view = inflater.inflate(R.layout.workshop_list_item, parent, false);
			holder = new Holder(view);
			view.setTag(holder);
		}
		else
		{
			holder = (Holder) view.getTag();
		}
		WorkshopRow row = rows.get(position);
		holder.title.setText(row.title);
		holder.subtitle.setText(row.subtitle);
		holder.subtitle.setVisibility(row.subtitle == null || row.subtitle.isEmpty()
				? View.GONE : View.VISIBLE);
		holder.trailing.setText(row.trailing);
		holder.trailing.setVisibility(row.trailing == null ? View.GONE : View.VISIBLE);
		holder.chip.setText(row.chip);
		holder.chip.setVisibility(row.chip == null ? View.GONE : View.VISIBLE);
		holder.chip.setBackgroundResource(chipBackground(row.tone));
		holder.chip.setTextColor(holder.chip.getContext().getColor(chipTextColor(row.tone)));
		return view;
	}

	/** Cached child lookups: a recycled row must not search the hierarchy again. */
	private static final class Holder
	{
		final TextView title;
		final TextView subtitle;
		final TextView trailing;
		final TextView chip;

		Holder(View view)
		{
			title = view.findViewById(R.id.workshop_row_title);
			subtitle = view.findViewById(R.id.workshop_row_subtitle);
			trailing = view.findViewById(R.id.workshop_row_trailing);
			chip = view.findViewById(R.id.workshop_row_chip);
		}
	}

	private static int chipBackground(RowTone tone)
	{
		switch (tone)
		{
			case INFO:
				return R.drawable.ui_chip_info;
			case SUCCESS:
				return R.drawable.ui_chip_success;
			case WARNING:
				return R.drawable.ui_chip_warning;
			case DANGER:
				return R.drawable.ui_chip_danger;
			case NEUTRAL:
			default:
				return R.drawable.ui_chip_neutral;
		}
	}

	private static int chipTextColor(RowTone tone)
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
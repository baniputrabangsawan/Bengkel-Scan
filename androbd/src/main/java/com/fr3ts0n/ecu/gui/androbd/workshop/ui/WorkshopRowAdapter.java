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
		if (view == null)
		{
			view = inflater.inflate(R.layout.workshop_list_item, parent, false);
		}
		WorkshopRow row = rows.get(position);
		((TextView) view.findViewById(R.id.workshop_row_title)).setText(row.title);
		TextView subtitle = view.findViewById(R.id.workshop_row_subtitle);
		subtitle.setText(row.subtitle);
		subtitle.setVisibility(row.subtitle == null || row.subtitle.isEmpty()
				? View.GONE : View.VISIBLE);
		TextView trailing = view.findViewById(R.id.workshop_row_trailing);
		trailing.setText(row.trailing);
		trailing.setVisibility(row.trailing == null ? View.GONE : View.VISIBLE);
		return view;
	}
}
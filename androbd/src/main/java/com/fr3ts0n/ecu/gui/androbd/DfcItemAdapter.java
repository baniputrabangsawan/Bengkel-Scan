/*
 * (C) Copyright 2015 by fr3ts0n <erwin.scheuch-heilig@gmx.at>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston,
 * MA 02111-1307 USA
 */

package com.fr3ts0n.ecu.gui.androbd;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;

import com.fr3ts0n.ecu.EcuCodeItem;
import com.fr3ts0n.ecu.prot.obd.ObdProt;
import com.fr3ts0n.pvs.IndexedProcessVar;
import com.fr3ts0n.pvs.PvList;

import java.util.Collection;

/**
 * Adapter to display OBD DFCs
 *
 * @author erwin
 */
public class DfcItemAdapter extends ObdItemAdapter
{
	private static final class CodeViewHolder
	{
		final boolean compact;
		final TextView code;
		final TextView description;
		final TextView status;
		final TextView context;
		final ImageView icon;

		CodeViewHolder(View view, boolean compact)
		{
			this.compact = compact;
			code = view.findViewById(R.id.obd_units);
			description = view.findViewById(R.id.obd_label);
			status = view.findViewById(R.id.result_status);
			context = view.findViewById(R.id.result_context);
			icon = view.findViewById(R.id.obd_icon);
		}
	}

	public DfcItemAdapter(Context context, int resource, PvList pvs)
	{
		super(context, resource, pvs);
	}

	@Override
	public Collection<Object> getPreferredItems(PvList pvs)
	{
		return pvs.values();
	}

	/* (non-Javadoc)
	 * @see com.fr3ts0n.ecu.gui.androbd.ObdItemAdapter#getView(int, android.view.View, android.view.ViewGroup)
	 */
	@Override
	public View getView(int position, View v, ViewGroup parent)
	{
		return bindCode(position, v, parent, parent instanceof Spinner, false);
	}

	private View bindCode(int position, View view, ViewGroup parent,
	                      boolean compact, boolean dropDown)
	{
		IndexedProcessVar currPv = (IndexedProcessVar) getItem(position);
		CodeViewHolder holder;
		if (view == null || !(view.getTag() instanceof CodeViewHolder)
				|| ((CodeViewHolder) view.getTag()).compact != compact)
		{
			view = mInflater.inflate(compact ? R.layout.dtc_selection : R.layout.dtc_item,
					parent, false);
			holder = new CodeViewHolder(view, compact);
			view.setTag(holder);
		}
		else
		{
			holder = (CodeViewHolder) view.getTag();
		}

		String code = String.valueOf(currPv.get(EcuCodeItem.FID_CODE));
		boolean noCodes = "P0000".equals(code);
		Object status = currPv.get(EcuCodeItem.FID_STATUS);
		int service = status instanceof Number ? ((Number) status).intValue() : -1;
		int statusLabel = 0;
		int icon = R.drawable.ic_code;
		switch (service)
		{
			case ObdProt.OBD_SVC_READ_CODES:
				statusLabel = R.string.result_status_stored;
				break;
			case ObdProt.OBD_SVC_PENDINGCODES:
				statusLabel = R.string.result_status_pending;
				icon = R.drawable.ic_history;
				break;
			case ObdProt.OBD_SVC_PERMACODES:
				statusLabel = R.string.result_status_permanent;
				icon = android.R.drawable.ic_dialog_alert;
				break;
		}

		holder.code.setText(code);
		holder.code.setVisibility(noCodes ? View.GONE : View.VISIBLE);
		holder.icon.setImageResource(icon);
		holder.icon.setVisibility(noCodes ? View.GONE : View.VISIBLE);
		holder.status.setText(statusLabel == 0 ? "" : getContext().getString(statusLabel));
		holder.status.setVisibility(!noCodes && statusLabel != 0 ? View.VISIBLE : View.GONE);
		holder.description.setText(statusText(String.valueOf(currPv.get(EcuCodeItem.FID_DESCRIPT))));
		// Keep the selected value compact; the dialog retains the complete description.
		holder.description.setVisibility(!compact || dropDown || noCodes ? View.VISIBLE : View.GONE);
		holder.context.setVisibility(noCodes ? View.VISIBLE : View.GONE);
		return view;
	}

	/* (non-Javadoc)
	 * @see com.fr3ts0n.ecu.gui.androbd.ObdItemAdapter#getView(int, android.view.View, android.view.ViewGroup)
	 */
	@Override
	public View getDropDownView(int position, View v, ViewGroup parent)
	{
		return bindCode(position, v, parent, true, true);
	}
}

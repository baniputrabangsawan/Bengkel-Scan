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

import android.os.Bundle;
import android.view.MenuItem;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.fr3ts0n.ecu.gui.androbd.ModernUiUtils;
import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.SettingsActivity;
import com.fr3ts0n.ecu.gui.androbd.workshop.ScanComparison;
import com.fr3ts0n.ecu.gui.androbd.workshop.ScanPhase;
import com.fr3ts0n.ecu.gui.androbd.workshop.ScanSnapshot;
import com.fr3ts0n.ecu.gui.androbd.workshop.ScanSnapshotRepository;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopRepository;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Side-by-side fault codes of the newest before and after scans.
 *
 * <p>Counting codes that stopped or started being reported is evidence, not a
 * verdict: the repair could be unrelated, and an ECU may need drive cycles
 * before it forgets a code.</p>
 */
public class WorkshopScanCompareActivity extends AppCompatActivity
{
	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		SettingsActivity.applyLocale(this);
		super.onCreate(savedInstanceState);
		long serviceId = getIntent().getLongExtra(WorkshopUi.EXTRA_SERVICE_ID, -1);
		WorkshopRepository repository = WorkshopUi.repository(this);
		if (repository.findService(serviceId) == null)
		{
			finish();
			return;
		}
		setContentView(R.layout.workshop_scan_compare);
		ModernUiUtils.applyScreenInsets(this);
		WorkshopUi.prepare(this, getString(R.string.workshop_scan_compare_title));

		ScanSnapshotRepository snapshots = new ScanSnapshotRepository(repository.database());
		ScanSnapshot before = snapshots.latest(serviceId, ScanPhase.BEFORE);
		ScanSnapshot after = snapshots.latest(serviceId, ScanPhase.AFTER);
		TextView summary = findViewById(R.id.workshop_compare_summary);
		TextView beforeView = findViewById(R.id.workshop_compare_before);
		TextView afterView = findViewById(R.id.workshop_compare_after);

		if (before == null || after == null)
		{
			summary.setText(R.string.workshop_scan_compare_empty);
			beforeView.setText(describe(before));
			afterView.setText(describe(after));
			return;
		}
		ScanComparison comparison = ScanComparison.compare(before.codesJson, after.codesJson);
		summary.setText(getString(R.string.workshop_scan_compare_codes_removed, comparison.removed)
				+ "\n" + getString(R.string.workshop_scan_compare_codes_added, comparison.added)
				+ "\n" + getString(R.string.workshop_scan_compare_codes_same, comparison.same));
		beforeView.setText(describe(before) + "\n" + codesOf(before));
		afterView.setText(describe(after) + "\n" + codesOf(after));
	}

	private String describe(ScanSnapshot snapshot)
	{
		if (snapshot == null) return "";
		return WorkshopUi.dateTime(snapshot.createdAt) + " · "
				+ getString(WorkshopUi.scanSourceLabel(snapshot.source));
	}

	private static String codesOf(ScanSnapshot snapshot)
	{
		StringBuilder text = new StringBuilder();
		try
		{
			JSONArray array = new JSONArray(snapshot.codesJson);
			for (int i = 0; i < array.length(); i++)
			{
				JSONObject item = array.optJSONObject(i);
				if (item == null) continue;
				if (text.length() > 0) text.append('\n');
				text.append(item.optString("code", "")).append(" — ")
						.append(item.optString("description", ""));
			}
		}
		catch (JSONException ignored)
		{
			return "";
		}
		return text.length() == 0 ? "" : text.toString();
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item)
	{
		if (item.getItemId() == android.R.id.home)
		{
			finish();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}
}

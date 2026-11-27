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

import com.fr3ts0n.ecu.EcuCodeItem;
import com.fr3ts0n.ecu.EcuDataPv;
import com.fr3ts0n.ecu.prot.obd.ObdProt;
import com.fr3ts0n.pvs.IndexedProcessVar;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collection;
import java.util.Map;

/**
 * Freeze whatever the OBD layer currently holds into JSON for storage.
 *
 * <p>"Frozen" means copied: the adapters and protocol lists may be cleared by
 * the next service selection, so the caller must run this while the reading is
 * still on screen and pass the copies to the repository.</p>
 */
public final class ScanSnapshotWriter
{
	/** Raw fault codes with their status text exactly as the adapter showed them. */
	public static String codesJson(Collection<? extends IndexedProcessVar> codes)
	{
		JSONArray array = new JSONArray();
		if (codes != null)
		{
			for (IndexedProcessVar code : codes)
			{
				if (code == null) continue;
				// The protocol inserts a "no trouble codes set" placeholder whose code reads
				// P0000. Storing it would report one fault on a healthy vehicle, and the
				// before/after comparison would count it as a change.
				if ("P0000".equals(String.valueOf(code.get(EcuCodeItem.FID_CODE)))) continue;
				JSONObject item = new JSONObject();
				try
				{
					item.put("code", String.valueOf(code.get(EcuCodeItem.FID_CODE)));
					item.put("description", String.valueOf(code.get(EcuCodeItem.FID_DESCRIPT)));
					Object status = code.get(EcuCodeItem.FID_STATUS);
					item.put("status", status == null ? "" : String.valueOf(status));
					item.put("status_name", statusName(code));
					array.put(item);
				}
				catch (JSONException ignored)
				{
					// A JSONObject refusing a plain string value cannot happen here.
				}
			}
		}
		return array.toString();
	}

	/**
	 * Report kind of one code row.
	 *
	 * <p>The protocol stores the OBD service number (3 = stored, 7 = pending,
	 * 10 = permanent), not text. Keep the number and add a stable name so a
	 * later version can still tell them apart.</p>
	 */
	public static String statusName(IndexedProcessVar code)
	{
		Object status = code.get(EcuCodeItem.FID_STATUS);
		if (!(status instanceof Number)) return "UNKNOWN";
		switch (((Number) status).intValue())
		{
			case ObdProt.OBD_SVC_READ_CODES:
				return "STORED";
			case ObdProt.OBD_SVC_PENDINGCODES:
				return "PENDING";
			case ObdProt.OBD_SVC_PERMACODES:
				return "PERMANENT";
			default:
				return "UNKNOWN";
		}
	}

	/** Live readings: mnemonic, PID id and the value as the screen showed it. */
	public static String readingsJson(Collection<? extends IndexedProcessVar> readings)
	{
		JSONArray array = new JSONArray();
		if (readings != null)
		{
			for (IndexedProcessVar reading : readings)
			{
				if (reading == null) continue;
				JSONObject item = new JSONObject();
				try
				{
					item.put("pid", pidOf(reading));
					item.put("mnemonic", text(reading, EcuDataPv.FID_MNEMONIC));
					item.put("description", text(reading, EcuDataPv.FID_DESCRIPT));
					Object value = reading.get(EcuDataPv.FID_VALUE);
					item.put("value", value == null ? "" : String.valueOf(value));
					item.put("units", text(reading, EcuDataPv.FID_UNITS));
					array.put(item);
				}
				catch (JSONException ignored)
				{
					// Same: values are strings, not exotic objects.
				}
			}
		}
		return array.toString();
	}

	/**
	 * Provenance from the mode name the main screen reported.
	 *
	 * <p>Only an explicit ONLINE session counts as real. Anything unrecognised
	 * is treated as demo data, never as a vehicle reading.</p>
	 */
	public static ScanSource currentSource(String modeName)
	{
		if (modeName == null) return ScanSource.DEMO;
		switch (modeName)
		{
			case "FILE":
				return ScanSource.FILE;
			case "ONLINE":
				return ScanSource.REAL;
			case "DEMO":
			default:
				return ScanSource.DEMO;
		}
	}

	/** Counting helper for the screen: how many codes were read. */
	public static int size(String json)
	{
		try
		{
			return new JSONArray(json).length();
		}
		catch (JSONException ignored)
		{
			return 0;
		}
	}

	private static String pidOf(IndexedProcessVar reading)
	{
		Object pid = reading.get(EcuDataPv.FID_PID);
		return String.format("%02X", pid == null ? 0 : ((Number) pid).intValue());
	}

	private static String text(IndexedProcessVar reading, Object key)
	{
		Object value = reading.get(key);
		return value == null ? "" : String.valueOf(value);
	}

	/** Current live PIDs, copied. Empty when the OBD layer never initialised. */
	public static Collection<? extends IndexedProcessVar> liveReadings()
	{
		return ObdProt.PidPvs == null ? java.util.Collections.emptyList()
				: new java.util.ArrayList<>(ObdProt.PidPvs.values());
	}

	/** Current fault-code rows, copied. */
	public static Collection<? extends IndexedProcessVar> faultCodes()
	{
		return ObdProt.tCodes == null ? java.util.Collections.emptyList()
				: new java.util.ArrayList<>(ObdProt.tCodes.values());
	}

	private ScanSnapshotWriter()
	{
	}
}

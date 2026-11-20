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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Compare the fault codes of a before and an after scan.
 *
 * <p>Only codes are compared, by code text: a disappearing code is not proof
 * of a successful repair, and a new code is not proof of damage caused by it.
 * The UI must say so; this class deliberately reports counts only.</p>
 */
public final class ScanComparison
{
	public final int removed;
	public final int added;
	public final int same;
	public final List<String> removedCodes;
	public final List<String> addedCodes;

	private ScanComparison(int removed, int added, int same, List<String> removedCodes,
			List<String> addedCodes)
	{
		this.removed = removed;
		this.added = added;
		this.same = same;
		this.removedCodes = Collections.unmodifiableList(removedCodes);
		this.addedCodes = Collections.unmodifiableList(addedCodes);
	}

	/**
	 * Compare two stored code arrays.
	 *
	 * @param beforeJson codes of the before scan
	 * @param afterJson  codes of the after scan
	 * @throws IllegalArgumentException when either side is not a JSON array
	 */
	public static ScanComparison compare(String beforeJson, String afterJson)
	{
		Set<String> before = codes(beforeJson);
		Set<String> after = codes(afterJson);
		java.util.List<String> removed = new java.util.ArrayList<>();
		java.util.List<String> added = new java.util.ArrayList<>();
		for (String code : before) if (!after.contains(code)) removed.add(code);
		for (String code : after) if (!before.contains(code)) added.add(code);
		Set<String> common = new LinkedHashSet<>(before);
		common.retainAll(after);
		return new ScanComparison(removed.size(), added.size(), common.size(), removed, added);
	}

	private static Set<String> codes(String json)
	{
		if (json == null) throw new IllegalArgumentException("scan.codes.required");
		Set<String> codes = new LinkedHashSet<>();
		try
		{
			JSONArray array = new JSONArray(json);
			for (int i = 0; i < array.length(); i++)
			{
				JSONObject item = array.optJSONObject(i);
				if (item == null) continue;
				String code = item.optString("code", "").trim();
				if (!code.isEmpty()) codes.add(code);
			}
		}
		catch (JSONException ignored)
		{
			throw new IllegalArgumentException("scan.codes.invalid");
		}
		return codes;
	}

}

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

import android.content.ContentValues;
import android.database.Cursor;

import java.util.ArrayList;
import java.util.List;

/**
 * Photo references attached to inspection items, diagnosis cases or services.
 *
 * <p>Row insert and file storage are two steps. Store the file first with
 * {@code DiagnosisMediaStore}, then record the relative path here; a missing
 * file is reported by the caller, never silently hidden.</p>
 */
public final class PhotoRepository
{
	private static final String[] COLUMNS = {"id", "owner_kind", "owner_id", "relative_path",
			"caption", "created_at"};

	private final WorkshopDatabase database;

	public PhotoRepository(WorkshopDatabase database)
	{
		this.database = database;
	}

	/**
	 * Reference an already copied photo.
	 *
	 * <p>The path must stay inside the app's private media folder: absolute
	 * paths, traversal and control characters are refused here rather than in
	 * each screen, so a future caller cannot smuggle one in.</p>
	 */
	public long add(PhotoOwner owner, long ownerId, String relativePath, String caption)
	{
		if (owner == null) throw new IllegalArgumentException("photo.owner.required");
		if (relativePath == null || relativePath.trim().isEmpty()
				|| relativePath.startsWith("/") || relativePath.contains("\\")
				|| relativePath.matches("(?s).*[\\p{Cntrl}:?#%].*"))
		{
			throw new IllegalArgumentException("photo.path.required");
		}
		for (String segment : relativePath.split("/", -1))
		{
			if (segment.isEmpty() || segment.equals(".") || segment.equals(".."))
			{
				throw new IllegalArgumentException("photo.path.required");
			}
		}
		ContentValues values = new ContentValues();
		values.put("owner_kind", owner.name());
		values.put("owner_id", ownerId);
		values.put("relative_path", relativePath.trim());
		values.put("caption", caption == null ? "" : caption.trim());
		values.put("created_at", System.currentTimeMillis());
		return database.getWritableDatabase().insertOrThrow("photos", null, values);
	}

	public List<Photo> forOwner(PhotoOwner owner, long ownerId)
	{
		List<Photo> photos = new ArrayList<>();
		try (Cursor cursor = database.getReadableDatabase().query("photos", COLUMNS,
				"owner_kind = ? AND owner_id = ?",
				new String[]{owner.name(), String.valueOf(ownerId)},
				null, null, "created_at ASC, id ASC"))
		{
			while (cursor.moveToNext())
			{
				photos.add(new Photo(cursor.getLong(0), PhotoOwner.fromStored(cursor.getString(1)),
						cursor.getLong(2), cursor.getString(3), cursor.getString(4),
						cursor.getLong(5)));
			}
		}
		return photos;
	}

	public boolean delete(long id)
	{
		return database.getWritableDatabase().delete("photos",
				"id = ?", new String[]{String.valueOf(id)}) > 0;
	}

	/** Drop every reference for an owner, for example when a diagnosis case is discarded. */
	public int deleteForOwner(PhotoOwner owner, long ownerId)
	{
		return database.getWritableDatabase().delete("photos", "owner_kind = ? AND owner_id = ?",
				new String[]{owner.name(), String.valueOf(ownerId)});
	}
}

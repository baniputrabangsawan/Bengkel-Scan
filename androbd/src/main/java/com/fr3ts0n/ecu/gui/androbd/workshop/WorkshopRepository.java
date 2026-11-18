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
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Read/write access to workshop customers, vehicles, services and estimates.
 *
 * <p>Every public method returns plain immutable models; no cursor or
 * {@link SQLiteDatabase} escapes this class. Call these off the UI thread and
 * serialize writes; SQLite serializes statements but not multi-step logic.</p>
 */
public final class WorkshopRepository
{
	private final WorkshopDatabase database;

	public WorkshopRepository(WorkshopDatabase database)
	{
		this.database = database;
	}

	public WorkshopDatabase database()
	{
		return database;
	}

	/** Close the underlying helper (tests and backups). */
	public void close()
	{
		database.close();
	}

	// ---------------------------------------------------------------- customers

	public long createCustomer(String name, String phone, String notes)
	{
		ContentValues values = new ContentValues();
		values.put("name", requireText(name, "name"));
		values.put("phone", textOrEmpty(phone));
		values.put("notes", textOrEmpty(notes));
		values.put("created_at", System.currentTimeMillis());
		return database.getWritableDatabase().insertOrThrow("customers", null, values);
	}

	public Customer findCustomer(long id)
	{
		try (Cursor cursor = database.getReadableDatabase().query("customers",
				CUSTOMER_COLUMNS, "id = ?", new String[]{String.valueOf(id)},
				null, null, null))
		{
			return cursor.moveToFirst() ? readCustomer(cursor) : null;
		}
	}

	/** Search by customer name or phone; an empty query lists all customers. */
	public List<Customer> searchCustomers(String query)
	{
		String like = likePattern(query);
		String selection = like == null ? null : "name LIKE ? OR phone LIKE ?";
		String[] args = like == null ? null : new String[]{like, like};
		return readCustomers(selection, args, "name COLLATE NOCASE");
	}

	public List<Customer> listCustomers()
	{
		return readCustomers(null, null, "name COLLATE NOCASE");
	}

	public boolean updateCustomer(long id, String name, String phone, String notes)
	{
		ContentValues values = new ContentValues();
		values.put("name", requireText(name, "name"));
		values.put("phone", textOrEmpty(phone));
		values.put("notes", textOrEmpty(notes));
		return database.getWritableDatabase().update("customers", values,
				"id = ?", new String[]{String.valueOf(id)}) > 0;
	}

	/** Deletes the customer and, by cascade, all vehicles, services and items. */
	public boolean deleteCustomer(long id)
	{
		return database.getWritableDatabase().delete("customers",
				"id = ?", new String[]{String.valueOf(id)}) > 0;
	}

	// ----------------------------------------------------------------- vehicles

	public long createVehicle(long customerId, String plate, String model, Integer year,
			EngineType engineType, String vin, Integer odometerKm)
	{
		ContentValues values = new ContentValues();
		values.put("customer_id", customerId);
		values.put("plate", normalizePlate(plate));
		values.put("plate_key", plateKey(plate));
		values.put("model", textOrEmpty(model));
		if (year == null) values.putNull("year"); else values.put("year", year);
		values.put("engine_type", (engineType == null ? EngineType.DIESEL : engineType).name());
		values.put("vin", textOrEmpty(vin));
		if (odometerKm == null) values.putNull("odometer_km"); else values.put("odometer_km", odometerKm);
		values.put("created_at", System.currentTimeMillis());
		return database.getWritableDatabase().insertOrThrow("vehicles", null, values);
	}

	public Vehicle findVehicle(long id)
	{
		try (Cursor cursor = database.getReadableDatabase().query("vehicles",
				VEHICLE_COLUMNS, "id = ?", new String[]{String.valueOf(id)},
				null, null, null))
		{
			return cursor.moveToFirst() ? readVehicle(cursor) : null;
		}
	}

	public List<Vehicle> vehiclesForCustomer(long customerId)
	{
		return readVehicles("customer_id = ?",
				new String[]{String.valueOf(customerId)}, "plate COLLATE NOCASE");
	}

	/** Plate search used by the new-service flow; spacing and case are ignored. */
	public List<Vehicle> findVehiclesByPlate(String plateQuery)
	{
		String like = likePattern(plateKey(plateQuery));
		return readVehicles(like == null ? null : "plate_key LIKE ?",
				like == null ? null : new String[]{like}, "plate COLLATE NOCASE");
	}

	public boolean updateVehicle(long id, String plate, String model, Integer year,
			EngineType engineType, String vin, Integer odometerKm)
	{
		ContentValues values = new ContentValues();
		values.put("plate", normalizePlate(plate));
		values.put("plate_key", plateKey(plate));
		values.put("model", textOrEmpty(model));
		if (year == null) values.putNull("year"); else values.put("year", year);
		values.put("engine_type", (engineType == null ? EngineType.DIESEL : engineType).name());
		values.put("vin", textOrEmpty(vin));
		if (odometerKm == null) values.putNull("odometer_km"); else values.put("odometer_km", odometerKm);
		return database.getWritableDatabase().update("vehicles", values,
				"id = ?", new String[]{String.valueOf(id)}) > 0;
	}

	// ----------------------------------------------------------------- services

	public long createService(long vehicleId, String complaint)
	{
		long now = System.currentTimeMillis();
		ContentValues values = new ContentValues();
		values.put("vehicle_id", vehicleId);
		values.put("complaint", textOrEmpty(complaint));
		values.put("status", ServiceStatus.WAITING.name());
		values.put("payment_status", PaymentStatus.UNPAID.name());
		values.put("notes", "");
		values.put("created_at", now);
		values.put("updated_at", now);
		values.putNull("completed_at");
		return database.getWritableDatabase().insertOrThrow("services", null, values);
	}

	public ServiceRecord findService(long id)
	{
		try (Cursor cursor = database.getReadableDatabase().query("services",
				SERVICE_COLUMNS, "id = ?", new String[]{String.valueOf(id)},
				null, null, null))
		{
			return cursor.moveToFirst() ? readService(cursor) : null;
		}
	}

	/**
	 * Change workflow state. Moving to {@link ServiceStatus#DONE} stamps
	 * {@code completed_at}; moving away from it clears the stamp.
	 */
	public boolean updateServiceStatus(long id, ServiceStatus status)
	{
		long now = System.currentTimeMillis();
		ContentValues values = new ContentValues();
		values.put("status", status.name());
		values.put("updated_at", now);
		if (status == ServiceStatus.DONE)
		{
			values.put("completed_at", now);
		}
		else
		{
			values.putNull("completed_at");
		}
		return database.getWritableDatabase().update("services", values,
				"id = ?", new String[]{String.valueOf(id)}) > 0;
	}

	public boolean setPaymentStatus(long id, PaymentStatus status)
	{
		ContentValues values = new ContentValues();
		values.put("payment_status", status.name());
		values.put("updated_at", System.currentTimeMillis());
		return database.getWritableDatabase().update("services", values,
				"id = ?", new String[]{String.valueOf(id)}) > 0;
	}

	public boolean updateServiceNotes(long id, String notes)
	{
		ContentValues values = new ContentValues();
		values.put("notes", textOrEmpty(notes));
		values.put("updated_at", System.currentTimeMillis());
		return database.getWritableDatabase().update("services", values,
				"id = ?", new String[]{String.valueOf(id)}) > 0;
	}

	public List<ServiceRecord> servicesForVehicle(long vehicleId)
	{
		return readServices("vehicle_id = ?",
				new String[]{String.valueOf(vehicleId)}, "created_at DESC");
	}

	/** Services created within {@code [dayStart, dayEnd)}. */
	public List<ServiceRecord> servicesForDay(long dayStart, long dayEnd)
	{
		return readServices("created_at >= ? AND created_at < ?",
				new String[]{String.valueOf(dayStart), String.valueOf(dayEnd)},
				"created_at DESC");
	}

	/** All services with the given status, newest first. */
	public List<ServiceRecord> servicesWithStatus(ServiceStatus status)
	{
		return readServices("status = ?", new String[]{status.name()}, "created_at DESC");
	}

	// ------------------------------------------------------------ estimate items

	public long addEstimateItem(long serviceId, ItemKind kind, String description,
			double quantity, long unitPrice)
	{
		if (quantity <= 0) throw new IllegalArgumentException("quantity must be > 0");
		if (unitPrice < 0) throw new IllegalArgumentException("unitPrice must be >= 0");
		ContentValues values = new ContentValues();
		values.put("service_id", serviceId);
		values.put("kind", (kind == null ? ItemKind.PART : kind).name());
		values.put("description", requireText(description, "description"));
		values.put("quantity", quantity);
		values.put("unit_price", unitPrice);
		values.put("position", nextItemPosition(serviceId));
		return database.getWritableDatabase().insertOrThrow("estimate_items", null, values);
	}

	public List<EstimateItem> estimateItems(long serviceId)
	{
		List<EstimateItem> items = new ArrayList<>();
		try (Cursor cursor = database.getReadableDatabase().query("estimate_items",
				ITEM_COLUMNS, "service_id = ?", new String[]{String.valueOf(serviceId)},
				null, null, "position ASC, id ASC"))
		{
			while (cursor.moveToNext()) items.add(readItem(cursor));
		}
		return items;
	}

	public boolean deleteEstimateItem(long itemId)
	{
		return database.getWritableDatabase().delete("estimate_items",
				"id = ?", new String[]{String.valueOf(itemId)}) > 0;
	}

	/** Sum of rounded line totals in whole Rupiah. */
	public long estimateTotal(long serviceId)
	{
		long total = 0;
		for (EstimateItem item : estimateItems(serviceId)) total += item.lineTotal();
		return total;
	}

	private int nextItemPosition(long serviceId)
	{
		try (Cursor cursor = database.getReadableDatabase().rawQuery(
				"SELECT COALESCE(MAX(position), -1) + 1 FROM estimate_items WHERE service_id = ?",
				new String[]{String.valueOf(serviceId)}))
		{
			return cursor.moveToFirst() ? cursor.getInt(0) : 0;
		}
	}

	// ------------------------------------------------------------------- helpers

	private static final String[] CUSTOMER_COLUMNS = {"id", "name", "phone", "notes", "created_at"};
	private static final String[] VEHICLE_COLUMNS = {"id", "customer_id", "plate", "model",
			"year", "engine_type", "vin", "odometer_km", "created_at"};
	private static final String[] SERVICE_COLUMNS = {"id", "vehicle_id", "complaint", "status",
			"payment_status", "notes", "created_at", "updated_at", "completed_at"};
	private static final String[] ITEM_COLUMNS = {"id", "service_id", "kind", "description",
			"quantity", "unit_price", "position"};

	private List<Customer> readCustomers(String selection, String[] args, String orderBy)
	{
		List<Customer> customers = new ArrayList<>();
		try (Cursor cursor = database.getReadableDatabase().query("customers",
				CUSTOMER_COLUMNS, selection, args, null, null, orderBy))
		{
			while (cursor.moveToNext()) customers.add(readCustomer(cursor));
		}
		return customers;
	}

	private List<Vehicle> readVehicles(String selection, String[] args, String orderBy)
	{
		List<Vehicle> vehicles = new ArrayList<>();
		try (Cursor cursor = database.getReadableDatabase().query("vehicles",
				VEHICLE_COLUMNS, selection, args, null, null, orderBy))
		{
			while (cursor.moveToNext()) vehicles.add(readVehicle(cursor));
		}
		return vehicles;
	}

	private List<ServiceRecord> readServices(String selection, String[] args, String orderBy)
	{
		List<ServiceRecord> services = new ArrayList<>();
		try (Cursor cursor = database.getReadableDatabase().query("services",
				SERVICE_COLUMNS, selection, args, null, null, orderBy))
		{
			while (cursor.moveToNext()) services.add(readService(cursor));
		}
		return services;
	}

	private static Customer readCustomer(Cursor c)
	{
		return new Customer(c.getLong(0), c.getString(1), c.getString(2),
				c.getString(3), c.getLong(4));
	}

	private static Vehicle readVehicle(Cursor c)
	{
		return new Vehicle(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3),
				c.isNull(4) ? null : c.getInt(4), EngineType.fromStored(c.getString(5)),
				c.getString(6), c.isNull(7) ? null : c.getInt(7), c.getLong(8));
	}

	private static ServiceRecord readService(Cursor c)
	{
		return new ServiceRecord(c.getLong(0), c.getLong(1), c.getString(2),
				ServiceStatus.fromStored(c.getString(3)),
				PaymentStatus.fromStored(c.getString(4)), c.getString(5),
				c.getLong(6), c.getLong(7), c.isNull(8) ? null : c.getLong(8));
	}

	private static EstimateItem readItem(Cursor c)
	{
		return new EstimateItem(c.getLong(0), c.getLong(1), ItemKind.fromStored(c.getString(2)),
				c.getString(3), c.getDouble(4), c.getLong(5), c.getInt(6));
	}

	/**
	 * Normalize a plate for storage and lookup: trim, collapse inner runs of
	 * whitespace, and upper-case. Indonesian plates are ASCII.
	 */
	public static String normalizePlate(String plate)
	{
		if (plate == null) return "";
		return plate.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
	}

	/**
	 * Compact lookup key for a plate: upper-case letters and digits only, so
	 * "b 1234 xyz" and "B1234XYZ" find the same vehicle.
	 */
	public static String plateKey(String plate)
	{
		if (plate == null) return "";
		return plate.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
	}

	private static String likePattern(String value)
	{
		if (value == null) return null;
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : "%" + trimmed + "%";
	}

	private static String requireText(String value, String field)
	{
		if (value == null || value.trim().isEmpty())
		{
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return value.trim();
	}

	private static String textOrEmpty(String value)
	{
		return value == null ? "" : value.trim();
	}
}

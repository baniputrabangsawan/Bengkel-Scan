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
		return createService(vehicleId, complaint, null);
	}

	/** Create a service, optionally recording the odometer at drop-off. */
	public long createService(long vehicleId, String complaint, Integer odometerKm)
	{
		long now = System.currentTimeMillis();
		ContentValues values = new ContentValues();
		values.put("vehicle_id", vehicleId);
		values.put("complaint", textOrEmpty(complaint));
		values.put("status", ServiceStatus.WAITING.name());
		values.put("payment_status", PaymentStatus.UNPAID.name());
		values.put("notes", "");
		if (odometerKm == null) values.putNull("odometer_km");
		else values.put("odometer_km", odometerKm);
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
	 * {@code completed_at}; moving away from it clears the stamp so a corrected
	 * service never keeps a completion time it did not reach.
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
		return setPayment(id, status, null, null);
	}

	/**
	 * Record payment state, method and time in one write.
	 *
	 * <p>Marking {@link PaymentStatus#PAID} stamps {@code paid_at} when the
	 * caller supplies no time; reverting to unpaid clears it.</p>
	 */
	public boolean setPayment(long id, PaymentStatus status, PaymentMethod method, Long paidAt)
	{
		return setPayment(id, status, method, paidAt, null);
	}

	/**
	 * Record what was received.
	 *
	 * @param amount whole Rupiah received; when given, the status follows from it
	 *               against the agreed total, so a short payment is never marked paid
	 */
	public boolean setPayment(long id, PaymentStatus status, PaymentMethod method, Long paidAt,
			Long amount)
	{
		ContentValues values = new ContentValues();
		values.put("payment_status", status.name());
		if (method != null) values.put("payment_method", method.name());
		if (amount != null)
		{
			values.put("paid_amount", Math.max(0, amount));
			if (status == PaymentStatus.PAID && amount <= 0)
			{
				values.put("payment_status", PaymentStatus.UNPAID.name());
			}
		}
		if (status == PaymentStatus.PAID)
		{
			values.put("paid_at", paidAt == null ? System.currentTimeMillis() : paidAt);
		}
		else
		{
			values.putNull("paid_at");
			if (amount == null) values.put("paid_amount", 0);
		}
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

	/** How many jobs are still open. Counts in SQL instead of reading and parsing every row. */
	public int countOpenServices()
	{
		return countServices("status != ?", new String[]{ServiceStatus.DONE.name()});
	}

	/** How many jobs were created inside the window. */
	public int countServicesForDay(long dayStart, long dayEnd)
	{
		return countServices("created_at >= ? AND created_at < ?",
				new String[]{String.valueOf(dayStart), String.valueOf(dayEnd)});
	}

	private int countServices(String selection, String[] args)
	{
		try (Cursor cursor = database.getReadableDatabase().rawQuery(
				"SELECT COUNT(*) FROM services WHERE " + selection, args))
		{
			return cursor.moveToFirst() ? cursor.getInt(0) : 0;
		}
	}

	/** Every service job, newest first. */
	public List<ServiceRecord> allServices()
	{
		return readServices(null, null, "created_at DESC");
	}

	/** Jobs not yet done: waiting or in progress, oldest first so the queue reads top-down. */
	public List<ServiceRecord> openServices()
	{
		return readServices("status != ?", new String[]{ServiceStatus.DONE.name()}, "created_at ASC");
	}

	/** Jobs whose payment is still outstanding. */
	public List<ServiceRecord> unpaidServices()
	{
		return readServices("payment_status = ?", new String[]{PaymentStatus.UNPAID.name()},
				"created_at DESC");
	}

	public long addEstimateItem(long serviceId, ItemKind kind, String description,
			double quantity, long unitPrice)
	{
		return addEstimateItem(serviceId, kind, description, quantity, unitPrice, 0, null);
	}

	/**
	 * Add one estimate line.
	 *
	 * @param discount whole Rupiah taken off the line, never above the gross
	 * @param inspectionItemId inspection item this line repairs, or {@code null}
	 * @throws IllegalArgumentException when the discount exceeds the line gross
	 */
	public long addEstimateItem(long serviceId, ItemKind kind, String description,
			double quantity, long unitPrice, long discount, Long inspectionItemId)
	{
		if (quantity <= 0) throw new IllegalArgumentException("estimate.quantity");
		if (unitPrice < 0) throw new IllegalArgumentException("estimate.unit_price");
		if (discount < 0) throw new IllegalArgumentException("estimate.discount");
		if (discount > Math.round(quantity * unitPrice)) throw new IllegalArgumentException("estimate.discount_above_total");
		ContentValues values = new ContentValues();
		values.put("service_id", serviceId);
		values.put("kind", (kind == null ? ItemKind.PART : kind).name());
		values.put("description", requireText(description, "description"));
		values.put("quantity", quantity);
		values.put("unit_price", unitPrice);
		values.put("discount", discount);
		values.put("approval", ApprovalState.PENDING.name());
		if (inspectionItemId == null) values.putNull("inspection_item_id");
		else values.put("inspection_item_id", inspectionItemId);
		values.put("position", nextItemPosition(serviceId));
		return database.getWritableDatabase().insertOrThrow("estimate_items", null, values);
	}

	/** Update an existing line, keeping its position and approval history. */
	public boolean updateEstimateItem(long itemId, ItemKind kind, String description,
			double quantity, long unitPrice, long discount)
	{
		if (quantity <= 0) throw new IllegalArgumentException("estimate.quantity");
		if (unitPrice < 0) throw new IllegalArgumentException("estimate.unit_price");
		if (discount < 0) throw new IllegalArgumentException("estimate.discount");
		if (discount > Math.round(quantity * unitPrice)) throw new IllegalArgumentException("estimate.discount_above_total");
		ContentValues values = new ContentValues();
		values.put("kind", (kind == null ? ItemKind.PART : kind).name());
		values.put("description", requireText(description, "description"));
		values.put("quantity", quantity);
		values.put("unit_price", unitPrice);
		values.put("discount", discount);
		return database.getWritableDatabase().update("estimate_items", values,
				"id = ?", new String[]{String.valueOf(itemId)}) > 0;
	}

	/**
	 * Record a customer decision on one line.
	 *
	 * <p>Every change is appended to {@code estimate_approvals}; the item keeps
	 * only the latest state so a list can be rendered without a history join.</p>
	 */
	public boolean setApproval(long itemId, ApprovalState state, String note)
	{
		if (state == null) throw new IllegalArgumentException("estimate.approval.required");
		long now = System.currentTimeMillis();
		ContentValues history = new ContentValues();
		history.put("estimate_item_id", itemId);
		history.put("state", state.name());
		history.put("note", note == null ? "" : note.trim());
		history.put("changed_at", now);
		ContentValues values = new ContentValues();
		values.put("approval", state.name());
		SQLiteDatabase db = database.getWritableDatabase();
		db.beginTransaction();
		try
		{
			if (db.update("estimate_items", values, "id = ?",
					new String[]{String.valueOf(itemId)}) <= 0)
			{
				throw new IllegalArgumentException("estimate.item.unknown");
			}
			db.insertOrThrow("estimate_approvals", null, history);
			db.setTransactionSuccessful();
			return true;
		}
		finally
		{
			db.endTransaction();
		}
	}

	/** Approval changes for one line, oldest first. */
	public List<EstimateApproval> approvals(long itemId)
	{
		List<EstimateApproval> entries = new ArrayList<>();
		try (Cursor cursor = database.getReadableDatabase().query("estimate_approvals",
				new String[]{"id", "estimate_item_id", "state", "note", "changed_at"},
				"estimate_item_id = ?", new String[]{String.valueOf(itemId)},
				null, null, "changed_at ASC, id ASC"))
		{
			while (cursor.moveToNext())
			{
				entries.add(new EstimateApproval(cursor.getLong(0), cursor.getLong(1),
						ApprovalState.fromStored(cursor.getString(2)), cursor.getString(3),
						cursor.getLong(4)));
			}
		}
		return entries;
	}

	public List<EstimateItem> estimateItems(long serviceId)
	{
		return readItems("service_id = ?", new String[]{String.valueOf(serviceId)});
	}

	/** Only the lines the customer approved; these count toward a work order. */
	public List<EstimateItem> approvedItems(long serviceId)
	{
		return readItems("service_id = ? AND approval = ?",
				new String[]{String.valueOf(serviceId), ApprovalState.APPROVED.name()});
	}

	public boolean deleteEstimateItem(long itemId)
	{
		return database.getWritableDatabase().delete("estimate_items",
				"id = ?", new String[]{String.valueOf(itemId)}) > 0;
	}

	/** Sum of rounded line totals in whole Rupiah, all lines. */
	public long estimateTotal(long serviceId)
	{
		long total = 0;
		for (EstimateItem item : estimateItems(serviceId)) total += item.lineTotal();
		return total;
	}

	/** Sum of approved lines only; what the customer agreed to pay. */
	public long approvedTotal(long serviceId)
	{
		long total = 0;
		for (EstimateItem item : approvedItems(serviceId)) total += item.lineTotal();
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
			"payment_status", "notes", "odometer_km", "payment_method", "paid_at", "paid_amount",
			"created_at", "updated_at", "completed_at"};
	private static final String[] ITEM_COLUMNS = {"id", "service_id", "kind", "description",
			"quantity", "unit_price", "discount", "approval", "inspection_item_id", "position"};

	private List<EstimateItem> readItems(String selection, String[] args)
	{
		List<EstimateItem> items = new ArrayList<>();
		try (Cursor cursor = database.getReadableDatabase().query("estimate_items",
				ITEM_COLUMNS, selection, args, null, null, "position ASC, id ASC"))
		{
			while (cursor.moveToNext()) items.add(readItem(cursor));
		}
		return items;
	}

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
				c.isNull(6) ? null : c.getInt(6),
				c.isNull(7) ? PaymentMethod.UNKNOWN : PaymentMethod.fromStored(c.getString(7)),
				c.isNull(8) ? null : c.getLong(8), c.getLong(9),
				c.getLong(10), c.getLong(11), c.isNull(12) ? null : c.getLong(12));
	}

	private static EstimateItem readItem(Cursor c)
	{
		return new EstimateItem(c.getLong(0), c.getLong(1), ItemKind.fromStored(c.getString(2)),
				c.getString(3), c.getDouble(4), c.getLong(5), c.getLong(6),
				ApprovalState.fromStored(c.getString(7)), c.isNull(8) ? null : c.getLong(8),
				c.getInt(9));
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

package com.fr3ts0n.ecu.gui.androbd.workshop;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

/** Evidence access. As with WorkshopRepository, serialize writes off the UI thread. */
public final class DiagnosticRepository
{
	static final String[] COLUMNS = {"service_id", "module", "read_status", "conclusion",
			"scanner", "codes", "symptoms", "observations", "test_procedure", "test_result",
			"reference", "diagnosis", "repair", "verification", "updated_at"};
	private final WorkshopDatabase database;

	public DiagnosticRepository(WorkshopDatabase database)
	{
		if (database == null) throw new IllegalArgumentException("diagnostic.database.required");
		this.database = database;
	}

	/** An absent record stays absent; it is never synthesized as normal or confirmed. */
	public DiagnosticRecord find(long serviceId, DiagnosticModule module)
	{
		if (module == null) throw new IllegalArgumentException("diagnostic.module.required");
		try (Cursor cursor = database.getReadableDatabase().query("diagnostics", COLUMNS,
				"service_id = ? AND module = ?",
				new String[]{String.valueOf(serviceId), module.name()}, null, null, null))
		{
			return cursor.moveToFirst() ? read(cursor) : null;
		}
	}

	/** Save exactly the entered evidence; invalid confirmation is rejected, not downgraded. */
	public void save(DiagnosticRecord record)
	{
		validate(record);
		ContentValues values = new ContentValues();
		values.put("service_id", record.serviceId);
		values.put("module", record.module.name());
		values.put("read_status", record.readStatus.name());
		values.put("conclusion", record.conclusion.name());
		values.put("scanner", record.scanner);
		values.put("codes", record.codes);
		values.put("symptoms", record.symptoms);
		values.put("observations", record.observations);
		values.put("test_procedure", record.testProcedure);
		values.put("test_result", record.testResult);
		values.put("reference", record.reference);
		values.put("diagnosis", record.diagnosis);
		values.put("repair", record.repair);
		values.put("verification", record.verification);
		values.put("updated_at", record.updatedAt);
		SQLiteDatabase db = database.getWritableDatabase();
		db.beginTransaction();
		try
		{
			int updated = db.update("diagnostics", values, "service_id = ? AND module = ?",
					new String[]{String.valueOf(record.serviceId), record.module.name()});
			if (updated == 0) db.insertOrThrow("diagnostics", null, values);
			db.setTransactionSuccessful();
		}
		finally
		{
			db.endTransaction();
		}
	}

	public List<DiagnosticRecord> forService(long serviceId)
	{
		List<DiagnosticRecord> records = new ArrayList<>();
		try (Cursor cursor = database.getReadableDatabase().query("diagnostics", COLUMNS,
				"service_id = ?", new String[]{String.valueOf(serviceId)}, null, null, "module ASC"))
		{
			while (cursor.moveToNext()) records.add(read(cursor));
		}
		return records;
	}

	/** Stable reason keys are for diagnostics; callers show localized validation copy. */
	static void validate(DiagnosticRecord record)
	{
		if (record == null) throw new IllegalArgumentException("diagnostic.record.required");
		if (record.serviceId <= 0) throw new IllegalArgumentException("diagnostic.service.required");
		if (record.module == null) throw new IllegalArgumentException("diagnostic.module.required");
		if (record.readStatus == null) throw new IllegalArgumentException("diagnostic.read_status.required");
		if (record.conclusion == null) throw new IllegalArgumentException("diagnostic.conclusion.required");
		if (record.readStatus == DiagnosticReadStatus.READ && blank(record.scanner))
		{
			throw new IllegalArgumentException("diagnostic.read.scanner_required");
		}
		if (record.conclusion == DiagnosticConclusion.CONFIRMED)
		{
			if (record.readStatus != DiagnosticReadStatus.READ)
				throw new IllegalArgumentException("diagnostic.confirmed.read_required");
			if (blank(record.diagnosis))
				throw new IllegalArgumentException("diagnostic.confirmed.diagnosis_required");
			if (blank(record.testProcedure))
				throw new IllegalArgumentException("diagnostic.confirmed.procedure_required");
			if (blank(record.testResult))
				throw new IllegalArgumentException("diagnostic.confirmed.result_required");
			if (blank(record.reference))
				throw new IllegalArgumentException("diagnostic.confirmed.reference_required");
		}
	}

	static DiagnosticRecord read(Cursor c)
	{
		DiagnosticRecord record = new DiagnosticRecord(c.getLong(0),
				DiagnosticModule.fromStored(c.getString(1)),
				DiagnosticReadStatus.fromStored(c.getString(2)),
				DiagnosticConclusion.fromStored(c.getString(3)), c.getString(4), c.getString(5),
				c.getString(6), c.getString(7), c.getString(8), c.getString(9), c.getString(10),
				c.getString(11), c.getString(12), c.getString(13), c.getLong(14));
		validate(record);
		return record;
	}

	private static boolean blank(String value)
	{
		return value.trim().isEmpty();
	}
}

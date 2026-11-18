package com.fr3ts0n.ecu.gui.androbd.diagnosis.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.fr3ts0n.ecu.gui.androbd.workshop.ItemKind;
import com.fr3ts0n.ecu.gui.androbd.workshop.ServiceRecord;
import com.fr3ts0n.ecu.gui.androbd.workshop.Vehicle;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopDatabase;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopRepository;

/** Explicitly requested estimate proposal; does not confirm findings or approve work. */
public final class DiagnosisServiceLink
{
    private final WorkshopDatabase database;
    private final WorkshopRepository workshop;
    private final DiagnosisRepository cases;

    public DiagnosisServiceLink(Context context)
    {
        this(context, WorkshopDatabase.getInstance(context));
    }

    DiagnosisServiceLink(Context context, WorkshopDatabase database)
    {
        this.database = database;
        workshop = new WorkshopRepository(database);
        cases = new DiagnosisRepository(context, database);
    }

    public void linkService(long caseId, long serviceId)
    {
        cases.linkService(caseId, serviceId);
    }

    public long addEstimate(long caseId, ItemKind kind, String description, double quantity, long unitPrice)
    {
        if (kind == null) throw new IllegalArgumentException("estimate_kind_required");
        if (description == null || description.trim().isEmpty())
            throw new IllegalArgumentException("estimate_description_required");
        if (Double.isNaN(quantity) || Double.isInfinite(quantity) || quantity <= 0)
            throw new IllegalArgumentException("estimate_quantity_invalid");
        if (unitPrice < 0) throw new IllegalArgumentException("estimate_price_invalid");

        SQLiteDatabase db = database.getWritableDatabase();
        db.beginTransaction();
        try
        {
            long serviceId;
            Long existingItemId;
            try (Cursor row = db.query("diagnosis_cases", new String[]{"service_id", "state", "estimate_item_id"},
                    "id=?", new String[]{String.valueOf(caseId)}, null, null, null))
            {
                if (!row.moveToFirst()) throw new IllegalArgumentException("unknown_case");
                if (!"FINAL".equals(row.getString(1))) throw new IllegalArgumentException("final_case_required");
                if (row.isNull(0)) throw new IllegalArgumentException("service_link_required");
                serviceId = row.getLong(0);
                existingItemId = row.isNull(2) ? null : row.getLong(2);
            }
            ServiceRecord service = workshop.findService(serviceId);
            if (service == null) throw new IllegalArgumentException("unknown_service");
            Vehicle vehicle = workshop.findVehicle(service.vehicleId);
            if (vehicle == null || workshop.findCustomer(vehicle.customerId) == null)
                throw new IllegalArgumentException("invalid_service_link");

            if (existingItemId != null)
            {
                try (Cursor item = db.query("estimate_items", new String[]{"service_id"}, "id=?",
                        new String[]{String.valueOf(existingItemId)}, null, null, null))
                {
                    if (!item.moveToFirst() || item.getLong(0) != serviceId)
                        throw new IllegalStateException("invalid_estimate_link");
                }
                db.setTransactionSuccessful();
                return existingItemId;
            }

            // Both repositories share this helper and participate in this transaction.
            long itemId = workshop.addEstimateItem(serviceId, kind, description.trim(), quantity, unitPrice);
            ContentValues values = new ContentValues();
            values.put("estimate_item_id", itemId);
            values.put("updated_at", System.currentTimeMillis());
            int updated = db.update("diagnosis_cases", values,
                    "id=? AND service_id=? AND state='FINAL' AND estimate_item_id IS NULL",
                    new String[]{String.valueOf(caseId), String.valueOf(serviceId)});
            if (updated != 1) throw new IllegalStateException("case_link_changed");
            db.setTransactionSuccessful();
            return itemId;
        }
        finally { db.endTransaction(); }
    }
}

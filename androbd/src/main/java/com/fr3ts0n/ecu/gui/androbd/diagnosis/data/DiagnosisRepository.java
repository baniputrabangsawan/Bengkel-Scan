package com.fr3ts0n.ecu.gui.androbd.diagnosis.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.CheckStatus;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisScorer;
import com.fr3ts0n.ecu.gui.androbd.workshop.ServiceRecord;
import com.fr3ts0n.ecu.gui.androbd.workshop.Vehicle;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopDatabase;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopRepository;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Local guided cases, independent of scanner readings and automatic conclusions. */
public final class DiagnosisRepository
{
    private final WorkshopDatabase database;
    private static final String[] CASE_COLUMNS = {"id", "service_id", "complaint_id", "complaint_title", "knowledge_revision", "knowledge_json", "answers_json", "final_cause_id", "final_diagnosis", "mechanic_confirmed", "state", "notes", "vehicle_model", "vehicle_year", "engine_type", "created_at", "updated_at", "estimate_item_id"};

    public DiagnosisRepository(Context context)
    {
        this(context, WorkshopDatabase.getInstance(context));
    }

    public DiagnosisRepository(Context context, WorkshopDatabase database)
    {
        this.database = database;
    }

    public static void createTables(SQLiteDatabase db)
    {
        db.execSQL("CREATE TABLE diagnosis_cases (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "service_id INTEGER REFERENCES services(id) ON DELETE CASCADE,"
                + "complaint_id TEXT NOT NULL, complaint_title TEXT NOT NULL, knowledge_revision TEXT NOT NULL, knowledge_json TEXT NOT NULL,"
                + "answers_json TEXT NOT NULL DEFAULT '{}', final_cause_id TEXT NOT NULL DEFAULT '', final_diagnosis TEXT NOT NULL DEFAULT '',"
                + "mechanic_confirmed INTEGER NOT NULL DEFAULT 0 CHECK(mechanic_confirmed IN (0,1)),"
                + "state TEXT NOT NULL DEFAULT 'DRAFT' CHECK(state IN ('DRAFT','FINAL')), notes TEXT NOT NULL DEFAULT '',"
                + "vehicle_model TEXT NOT NULL DEFAULT '', model_key TEXT NOT NULL DEFAULT '', vehicle_year INTEGER, engine_type TEXT NOT NULL DEFAULT '',"
                + "estimate_item_id INTEGER REFERENCES estimate_items(id) ON DELETE SET NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,"
                + "CHECK(mechanic_confirmed=0 OR (state='FINAL' AND length(trim(final_diagnosis))>0)))");
        db.execSQL("CREATE INDEX idx_diagnosis_service ON diagnosis_cases(service_id)");
        db.execSQL("CREATE INDEX idx_diagnosis_similar ON diagnosis_cases(complaint_id,model_key,vehicle_year,engine_type,state,mechanic_confirmed)");
        db.execSQL("CREATE TABLE diagnosis_checks (case_id INTEGER NOT NULL REFERENCES diagnosis_cases(id) ON DELETE CASCADE,"
                + "cause_id TEXT NOT NULL,status TEXT NOT NULL CHECK(status IN ('NOT_TESTED','CONFIRMED','RULED_OUT')),evidence TEXT NOT NULL DEFAULT '',"
                + "PRIMARY KEY(case_id,cause_id),CHECK(status!='CONFIRMED' OR length(trim(evidence))>0))");
        db.execSQL("CREATE TABLE diagnosis_photos (id INTEGER PRIMARY KEY AUTOINCREMENT,case_id INTEGER NOT NULL REFERENCES diagnosis_cases(id) ON DELETE CASCADE,"
                + "relative_path TEXT NOT NULL,caption TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX idx_diagnosis_photos_case ON diagnosis_photos(case_id)");
    }

    public CaseRecord create(DiagnosisKnowledge knowledge, DiagnosisKnowledge.Complaint complaint, Long serviceId)
    {
        if (knowledge.findComplaint(complaint.id) == null) throw new IllegalArgumentException("unknown_complaint");
        ArrayList<DiagnosisKnowledge.Sound> sounds = new ArrayList<>();
        java.util.HashSet<String> soundIds = new java.util.HashSet<>();
        for (DiagnosisKnowledge.Cause cause : complaint.causes) soundIds.addAll(cause.soundIds);
        for (DiagnosisKnowledge.Sound sound : knowledge.sounds) if (soundIds.contains(sound.id)) sounds.add(sound);
        DiagnosisKnowledge snapshot = new DiagnosisKnowledge(knowledge.schemaVersion, knowledge.revision, Collections.singletonList(complaint), sounds);
        ContentValues values = new ContentValues();
        if (serviceId != null) values.put("service_id", serviceId);
        values.put("complaint_id", complaint.id);
        values.put("complaint_title", complaint.title);
        values.put("knowledge_revision", knowledge.revision);
        values.put("knowledge_json", DiagnosisKnowledgeLoader.encode(snapshot));
        long now = System.currentTimeMillis();
        values.put("created_at", now);
        values.put("updated_at", now);
        if (serviceId != null) vehicleValues(values, serviceId);
        long id = database.getWritableDatabase().insertOrThrow("diagnosis_cases", null, values);
        return get(id);
    }

    public CaseRecord get(long id)
    {
        SQLiteDatabase db = database.getReadableDatabase();
        try (Cursor cursor = db.query("diagnosis_cases", CASE_COLUMNS, "id=?", args(id), null, null, null))
        {
            return cursor.moveToFirst() ? read(db, cursor) : null;
        }
    }

    public void saveProgress(long id, Map<String, String> answers, Map<String, Check> checks)
    {
        CaseRecord current = require(id);
        validateProgress(current.complaint, answers, checks);
        SQLiteDatabase db = database.getWritableDatabase();
        db.beginTransaction();
        try
        {
            ContentValues values = new ContentValues();
            values.put("answers_json", new JSONObject(answers).toString());
            values.put("state", "DRAFT");
            values.put("mechanic_confirmed", 0);
            values.put("updated_at", System.currentTimeMillis());
            db.update("diagnosis_cases", values, "id=?", args(id));
            db.delete("diagnosis_checks", "case_id=?", args(id));
            for (Check check : checks.values())
            {
                ContentValues row = new ContentValues();
                row.put("case_id", id);
                row.put("cause_id", check.causeId);
                row.put("status", check.status.name());
                row.put("evidence", check.evidence);
                db.insertOrThrow("diagnosis_checks", null, row);
            }
            db.setTransactionSuccessful();
        }
        finally { db.endTransaction(); }
    }

    public void saveFinal(long id, String causeId, String finalDiagnosis, boolean mechanicConfirmed, String notes)
    {
        CaseRecord current = require(id);
        causeId = clean(causeId);
        finalDiagnosis = clean(finalDiagnosis);
        notes = clean(notes);
        if (finalDiagnosis.isEmpty()) throw new IllegalArgumentException("diagnosis_required");
        if (!causeId.isEmpty() && current.complaint.findCause(causeId) == null) throw new IllegalArgumentException("unknown_cause");
        if (mechanicConfirmed)
        {
            Check check = current.checks.get(causeId);
            if (causeId.isEmpty() ? notes.isEmpty() : check == null || check.status != CheckStatus.CONFIRMED || check.evidence.trim().isEmpty())
                throw new IllegalArgumentException("confirmation_evidence_required");
        }
        if (current.answers.size() != current.complaint.questions.size()) throw new IllegalArgumentException("answers_required");
        ContentValues values = new ContentValues();
        values.put("final_cause_id", causeId);
        values.put("final_diagnosis", finalDiagnosis);
        values.put("mechanic_confirmed", mechanicConfirmed ? 1 : 0);
        values.put("state", "FINAL");
        values.put("notes", notes);
        values.put("updated_at", System.currentTimeMillis());
        database.getWritableDatabase().update("diagnosis_cases", values, "id=?", args(id));
    }

    public void updateVehicle(long id, String model, Integer year, String engineType)
    {
        require(id);
        if (year != null && year <= 0) throw new IllegalArgumentException("invalid_year");
        ContentValues values = new ContentValues();
        values.put("vehicle_model", clean(model));
        values.put("model_key", modelKey(model));
        if (year == null) values.putNull("vehicle_year"); else values.put("vehicle_year", year);
        values.put("engine_type", clean(engineType));
        values.put("updated_at", System.currentTimeMillis());
        database.getWritableDatabase().update("diagnosis_cases", values, "id=?", args(id));
    }

    public void linkService(long caseId, long serviceId)
    {
        CaseRecord current = require(caseId);
        if (current.estimateItemId != null && (current.serviceId == null || current.serviceId != serviceId))
            throw new IllegalArgumentException("estimate_already_linked");
        ContentValues values = new ContentValues();
        values.put("service_id", serviceId);
        vehicleValues(values, serviceId);
        values.put("updated_at", System.currentTimeMillis());
        database.getWritableDatabase().update("diagnosis_cases", values, "id=?", args(caseId));
    }

    private void vehicleValues(ContentValues values, long serviceId)
    {
        WorkshopRepository workshop = new WorkshopRepository(database);
        ServiceRecord service = workshop.findService(serviceId);
        if (service == null) throw new IllegalArgumentException("unknown_service");
        Vehicle vehicle = workshop.findVehicle(service.vehicleId);
        if (vehicle == null) throw new IllegalArgumentException("unknown_vehicle");
        values.put("vehicle_model", vehicle.model);
        values.put("model_key", modelKey(vehicle.model));
        if (vehicle.year == null) values.putNull("vehicle_year"); else values.put("vehicle_year", vehicle.year);
        values.put("engine_type", vehicle.engineType.name());
    }

    public void addPhoto(long caseId, String relativePath, String caption)
    {
        require(caseId);
        if (!safePhotoPath(relativePath)) throw new IllegalArgumentException("unsafe_photo_path");
        ContentValues values = new ContentValues();
        values.put("case_id", caseId);
        values.put("relative_path", relativePath);
        values.put("caption", clean(caption));
        database.getWritableDatabase().insertOrThrow("diagnosis_photos", null, values);
    }

    public List<CaseRecord> cases() { return list(null, null); }
    public List<CaseRecord> confirmedCases() { return list("state='FINAL' AND mechanic_confirmed=1", null); }

    private List<CaseRecord> list(String selection, String[] arguments)
    {
        SQLiteDatabase db = database.getReadableDatabase();
        List<CaseRecord> result = new ArrayList<>();
        try (Cursor cursor = db.query("diagnosis_cases", CASE_COLUMNS, selection, arguments, null, null, "updated_at DESC,id DESC"))
        {
            while (cursor.moveToNext()) result.add(read(db, cursor));
        }
        return result;
    }

    private CaseRecord require(long id)
    {
        CaseRecord result = get(id);
        if (result == null) throw new IllegalArgumentException("unknown_case");
        return result;
    }

    private static void validateProgress(DiagnosisKnowledge.Complaint complaint, Map<String, String> answers, Map<String, Check> checks)
    {
        Map<String, CheckStatus> states = new LinkedHashMap<>();
        for (Map.Entry<String, Check> entry : checks.entrySet())
        {
            Check check = entry.getValue();
            if (check == null || !entry.getKey().equals(check.causeId) || check.status == null || complaint.findCause(check.causeId) == null)
                throw new IllegalArgumentException("invalid_check");
            if (check.status == CheckStatus.CONFIRMED && clean(check.evidence).isEmpty())
                throw new IllegalArgumentException("confirmation_evidence_required");
            states.put(check.causeId, check.status);
        }
        DiagnosisScorer.rank(complaint, answers, states);
    }

    public static void validateDatabase(SQLiteDatabase db)
    {
        try (Cursor rows = db.query("diagnosis_cases", CASE_COLUMNS, null, null, null, null, null))
        {
            while (rows.moveToNext())
            {
                CaseRecord row = read(db, rows);
                validateProgress(row.complaint, row.answers, row.checks);
                if (row.mechanicConfirmed)
                {
                    Check check = row.checks.get(row.finalCauseId);
                    if (!row.isFinal || row.finalDiagnosis.trim().isEmpty()
                            || (row.finalCauseId.isEmpty() ? row.notes.trim().isEmpty() : check == null || check.status != CheckStatus.CONFIRMED))
                        throw new IllegalArgumentException("invalid_final_evidence");
                }
                if (row.isFinal && row.answers.size() != row.complaint.questions.size()) throw new IllegalArgumentException("incomplete_final_case");
                for (Photo photo : row.photos) if (!safePhotoPath(photo.relativePath)) throw new IllegalArgumentException("unsafe_photo_path");
            }
        }
        try (Cursor ignored = db.rawQuery("SELECT case_id,cause_id,status,evidence FROM diagnosis_checks LIMIT 0", null)) { ignored.moveToFirst(); }
        try (Cursor ignored = db.rawQuery("SELECT id,case_id,relative_path,caption FROM diagnosis_photos LIMIT 0", null)) { ignored.moveToFirst(); }
    }

    private static CaseRecord read(SQLiteDatabase db, Cursor cursor)
    {
        try
        {
            long id = cursor.getLong(0);
            DiagnosisKnowledge knowledge = DiagnosisKnowledgeLoader.parse(cursor.getString(5));
            DiagnosisKnowledge.Complaint complaint = knowledge.findComplaint(cursor.getString(2));
            if (complaint == null) throw new IllegalArgumentException("invalid_case_complaint");
            Map<String, String> answers = new LinkedHashMap<>();
            JSONObject json = new JSONObject(cursor.getString(6));
            java.util.Iterator<String> keys = json.keys();
            while (keys.hasNext())
            {
                String key = keys.next();
                if (!(json.get(key) instanceof String)) throw new IllegalArgumentException("invalid_answer");
                answers.put(key, json.getString(key));
            }
            Map<String, Check> checks = new LinkedHashMap<>();
            try (Cursor rows = db.query("diagnosis_checks", new String[]{"cause_id", "status", "evidence"}, "case_id=?", args(id), null, null, null))
            {
                while (rows.moveToNext()) checks.put(rows.getString(0), new Check(rows.getString(0), CheckStatus.valueOf(rows.getString(1)), rows.getString(2)));
            }
            List<Photo> photos = new ArrayList<>();
            try (Cursor rows = db.query("diagnosis_photos", new String[]{"id", "relative_path", "caption"}, "case_id=?", args(id), null, null, "id ASC"))
            {
                while (rows.moveToNext()) photos.add(new Photo(rows.getLong(0), rows.getString(1), rows.getString(2)));
            }
            return new CaseRecord(id, cursor.isNull(1) ? null : cursor.getLong(1), cursor.getString(2), cursor.getString(3), cursor.getString(4), knowledge, complaint,
                    answers, checks, cursor.getString(7), cursor.getString(8), cursor.getString(11), cursor.getInt(9) == 1, "FINAL".equals(cursor.getString(10)),
                    cursor.getString(12), cursor.isNull(13) ? null : cursor.getInt(13), cursor.getString(14), cursor.getLong(15), cursor.getLong(16), photos,
                    cursor.isNull(17) ? null : cursor.getLong(17));
        }
        catch (IOException | JSONException e) { throw new IllegalArgumentException("invalid_case_snapshot", e); }
    }

    public static String modelKey(String value) { return clean(value).replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static String[] args(long id) { return new String[]{String.valueOf(id)}; }
    private static boolean safePhotoPath(String path)
    {
        return path != null && path.startsWith("photos/") && !path.contains("..") && !path.contains("\\") && path.indexOf('/', 7) < 0;
    }

    public static final class Check
    {
        public final String causeId;
        public final CheckStatus status;
        public final String evidence;
        public Check(String causeId, CheckStatus status, String evidence) { this.causeId = causeId; this.status = status; this.evidence = clean(evidence); }
    }

    public static final class Photo
    {
        public final long id;
        public final String relativePath, caption;
        Photo(long id, String relativePath, String caption) { this.id = id; this.relativePath = relativePath; this.caption = caption; }
    }

    public static final class CaseRecord
    {
        public final long id;
        public final Long serviceId, estimateItemId;
        public final String complaintId, complaintTitle, knowledgeRevision;
        public final DiagnosisKnowledge knowledge;
        public final DiagnosisKnowledge.Complaint complaint;
        public final Map<String, String> answers;
        public final Map<String, Check> checks;
        public final String finalCauseId, finalDiagnosis, notes, vehicleModel, engineType;
        public final boolean mechanicConfirmed, isFinal;
        public final Integer vehicleYear;
        public final long createdAt, updatedAt;
        public final List<Photo> photos;
        CaseRecord(long id, Long serviceId, String complaintId, String complaintTitle, String knowledgeRevision, DiagnosisKnowledge knowledge,
                DiagnosisKnowledge.Complaint complaint, Map<String, String> answers, Map<String, Check> checks, String finalCauseId, String finalDiagnosis,
                String notes, boolean mechanicConfirmed, boolean isFinal, String vehicleModel, Integer vehicleYear, String engineType, long createdAt,
                long updatedAt, List<Photo> photos, Long estimateItemId)
        {
            this.id = id; this.serviceId = serviceId; this.complaintId = complaintId; this.complaintTitle = complaintTitle; this.knowledgeRevision = knowledgeRevision;
            this.knowledge = knowledge; this.complaint = complaint; this.answers = Collections.unmodifiableMap(answers); this.checks = Collections.unmodifiableMap(checks);
            this.finalCauseId = finalCauseId; this.finalDiagnosis = finalDiagnosis; this.notes = notes; this.mechanicConfirmed = mechanicConfirmed; this.isFinal = isFinal;
            this.vehicleModel = vehicleModel; this.vehicleYear = vehicleYear; this.engineType = engineType; this.createdAt = createdAt; this.updatedAt = updatedAt;
            this.photos = Collections.unmodifiableList(photos); this.estimateItemId = estimateItemId;
        }
    }
}

package com.fr3ts0n.ecu.gui.androbd.diagnosis.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Counts local confirmed final assessments, never checked candidate causes. */
public final class DiagnosisCaseHistory
{
    private DiagnosisCaseHistory() { }

    public static final class Entry
    {
        public final long id, updatedAt;
        public final String complaintId, vehicleModel, engineType, finalCauseId, finalDiagnosis;
        public final Integer vehicleYear;
        public final boolean isFinal, mechanicConfirmed;

        public Entry(long id, String complaintId, String vehicleModel, Integer vehicleYear,
                String engineType, boolean isFinal, boolean mechanicConfirmed,
                String finalCauseId, String finalDiagnosis, long updatedAt)
        {
            this.id = id;
            this.complaintId = complaintId;
            this.vehicleModel = vehicleModel;
            this.vehicleYear = vehicleYear;
            this.engineType = engineType;
            this.isFinal = isFinal;
            this.mechanicConfirmed = mechanicConfirmed;
            this.finalCauseId = finalCauseId;
            this.finalDiagnosis = finalDiagnosis;
            this.updatedAt = updatedAt;
        }
    }

    public static final class Group
    {
        public final String causeId, diagnosis;
        public final int count;
        public final long firstConfirmedAt, lastConfirmedAt;

        private Group(Entry entry)
        {
            this(clean(entry.finalCauseId), clean(entry.finalDiagnosis), 1, entry.updatedAt, entry.updatedAt);
        }

        private Group(String causeId, String diagnosis, int count, long first, long last)
        {
            this.causeId = causeId;
            this.diagnosis = diagnosis;
            this.count = count;
            this.firstConfirmedAt = first;
            this.lastConfirmedAt = last;
        }
    }

    public static final class Summary
    {
        public final boolean identityComplete;
        public final int count;
        public final List<Group> groups;

        private Summary(boolean identityComplete, int count, List<Group> groups)
        {
            this.identityComplete = identityComplete;
            this.count = count;
            this.groups = Collections.unmodifiableList(groups);
        }
    }

    public static Summary summarize(Entry current, Iterable<Entry> history)
    {
        if (!complete(current)) return new Summary(false, 0, new ArrayList<Group>());
        String model = normalize(current.vehicleModel);
        String engine = normalize(current.engineType);
        Map<String, Group> groups = new LinkedHashMap<>();
        int count = 0;
        for (Entry entry : history)
        {
            if (!complete(entry) || entry.id == current.id || !entry.isFinal || !entry.mechanicConfirmed
                    || !current.complaintId.equals(entry.complaintId)
                    || !model.equals(normalize(entry.vehicleModel))
                    || !current.vehicleYear.equals(entry.vehicleYear)
                    || !engine.equals(normalize(entry.engineType))
                    || clean(entry.finalDiagnosis).isEmpty()) continue;
            String cause = clean(entry.finalCauseId);
            String key = cause.isEmpty() ? "custom:" + normalize(entry.finalDiagnosis) : "cause:" + cause;
            Group previous = groups.get(key);
            groups.put(key, previous == null ? new Group(entry)
                    : new Group(previous.causeId, previous.diagnosis, previous.count + 1,
                            Math.min(previous.firstConfirmedAt, entry.updatedAt),
                            Math.max(previous.lastConfirmedAt, entry.updatedAt)));
            count++;
        }
        return new Summary(true, count, new ArrayList<>(groups.values()));
    }

    private static boolean complete(Entry entry)
    {
        return entry != null && !clean(entry.complaintId).isEmpty()
                && !normalize(entry.vehicleModel).isEmpty() && entry.vehicleYear != null
                && entry.vehicleYear > 0 && !normalize(entry.engineType).isEmpty();
    }

    private static String normalize(String value)
    {
        return clean(value).replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}

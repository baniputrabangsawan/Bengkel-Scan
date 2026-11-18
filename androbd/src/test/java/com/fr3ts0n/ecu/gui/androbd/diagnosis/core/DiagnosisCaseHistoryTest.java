package com.fr3ts0n.ecu.gui.androbd.diagnosis.core;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DiagnosisCaseHistoryTest
{
    private static DiagnosisCaseHistory.Entry entry(long id, String complaint, String model,
            Integer year, String engine, boolean finished, boolean confirmed, String cause,
            String diagnosis, long date)
    {
        return new DiagnosisCaseHistory.Entry(id, complaint, model, year, engine, finished,
                confirmed, cause, diagnosis, date);
    }

    private static DiagnosisCaseHistory.Entry current()
    {
        return entry(1, "cold_start", "Truck A", 2015, "Diesel X", false, false, "", "", 100);
    }

    @Test public void matchesNormalizedExactVehicleAndGroupsOnlyFinalCause()
    {
        DiagnosisCaseHistory.Summary summary = DiagnosisCaseHistory.summarize(current(), Arrays.asList(
                entry(2, "cold_start", "  TRUCK   A ", 2015, " diesel  X ", true, true, "battery", "Baterai", 300),
                entry(3, "cold_start", "Truck A", 2015, "Diesel X", true, true, "battery", "Baterai lemah", 200),
                entry(4, "cold_start", "Truck A", 2015, "Diesel X", true, true, "fuel", "Bahan bakar", 400)));
        assertTrue(summary.identityComplete);
        assertEquals(3, summary.count);
        assertEquals(2, summary.groups.size());
        assertEquals("battery", summary.groups.get(0).causeId);
        assertEquals(2, summary.groups.get(0).count);
        assertEquals(200, summary.groups.get(0).firstConfirmedAt);
        assertEquals(300, summary.groups.get(0).lastConfirmedAt);
    }

    @Test public void excludesCurrentDraftUnconfirmedAndDifferentIdentities()
    {
        DiagnosisCaseHistory.Summary summary = DiagnosisCaseHistory.summarize(current(), Arrays.asList(
                entry(1, "cold_start", "Truck A", 2015, "Diesel X", true, true, "a", "A", 1),
                entry(2, "cold_start", "Truck A", 2015, "Diesel X", false, true, "a", "A", 1),
                entry(3, "cold_start", "Truck A", 2015, "Diesel X", true, false, "a", "A", 1),
                entry(4, "hot_start", "Truck A", 2015, "Diesel X", true, true, "a", "A", 1),
                entry(5, "cold_start", "Truck AB", 2015, "Diesel X", true, true, "a", "A", 1),
                entry(6, "cold_start", "Truck A", 2016, "Diesel X", true, true, "a", "A", 1),
                entry(7, "cold_start", "Truck A", 2015, "Diesel Y", true, true, "a", "A", 1),
                entry(8, "cold_start", "Truck A", 2015, "Diesel X", true, true, "a", " ", 1)));
        assertEquals(0, summary.count);
    }

    @Test public void missingIdentitiesNeverMatchEvenEachOther()
    {
        for (DiagnosisCaseHistory.Entry incomplete : Arrays.asList(
                entry(2, "", "Truck A", 2015, "Diesel X", true, true, "a", "A", 1),
                entry(2, "cold_start", null, 2015, "Diesel X", true, true, "a", "A", 1),
                entry(2, "cold_start", "Truck A", null, "Diesel X", true, true, "a", "A", 1),
                entry(2, "cold_start", "Truck A", 0, "Diesel X", true, true, "a", "A", 1),
                entry(2, "cold_start", "Truck A", 2015, "  ", true, true, "a", "A", 1)))
        {
            assertFalse(DiagnosisCaseHistory.summarize(incomplete, Collections.singletonList(incomplete)).identityComplete);
            assertEquals(0, DiagnosisCaseHistory.summarize(current(), Collections.singletonList(incomplete)).count);
        }
    }

    @Test public void groupsCustomNormalizedTextSeparatelyFromCauseIds()
    {
        DiagnosisCaseHistory.Summary summary = DiagnosisCaseHistory.summarize(current(), Arrays.asList(
                entry(2, "cold_start", "Truck A", 2015, "Diesel X", true, true, "", " Sambungan   longgar ", 30),
                entry(3, "cold_start", "Truck A", 2015, "Diesel X", true, true, null, "sambungan LONGGAR", 10),
                entry(4, "cold_start", "Truck A", 2015, "Diesel X", true, true, "sambungan longgar", "Sambungan longgar", 20)));
        assertEquals(3, summary.count);
        assertEquals(2, summary.groups.size());
        assertEquals(2, summary.groups.get(0).count);
        assertEquals("", summary.groups.get(0).causeId);
        assertEquals(10, summary.groups.get(0).firstConfirmedAt);
        assertEquals(30, summary.groups.get(0).lastConfirmedAt);
    }
}

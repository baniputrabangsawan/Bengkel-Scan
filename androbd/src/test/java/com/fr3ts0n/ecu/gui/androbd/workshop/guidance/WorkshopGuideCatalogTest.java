package com.fr3ts0n.ecu.gui.androbd.workshop.guidance;

import org.junit.Test;

import static org.junit.Assert.*;

/** Matching never turns a generic safety fallback into a code-specific procedure. */
public class WorkshopGuideCatalogTest
{
    private final WorkshopGuideCatalog catalog = new WorkshopGuideCatalog(id -> "");

    @Test public void lowerCaseCodeWithDescriptionMatchesOnlyItsOwnGuide()
    {
        WorkshopGuideCatalog.Result result = catalog.search("ENGINE", "p0299 underboost stored");
        assertEquals(1, result.guides.size());
        assertEquals("underboost", result.guides.get(0).id);
        assertTrue(result.unmatchedCodes.isEmpty());
        assertNull(result.fallback);
    }

    @Test public void descriptionDelimiterDoesNotLoseCodeAndLongSubcodeDoesNotCollapse()
    {
        assertEquals("underboost", catalog.search(null, "p0299: underboost").guides.get(0).id);
        assertTrue(catalog.search(null, "P0299-ABC").guides.isEmpty());
    }

    @Test public void unrelatedModuleCannotReturnEngineCodeGuide()
    {
        WorkshopGuideCatalog.Result result = catalog.search("SRS", "P0299");
        assertTrue(result.guides.isEmpty());
        assertEquals("P0299", result.unmatchedCodes.get(0));
        assertEquals("unsupported", result.fallback.id);
    }

    @Test public void manufacturerSpecificCodeIsNotGenericUnderboost()
    {
        WorkshopGuideCatalog.Result result = catalog.search(null, "P1299");
        assertTrue(result.guides.isEmpty());
        assertEquals("P1299", result.unmatchedCodes.get(0));
        assertNotNull(result.fallback);
    }

    @Test public void extendedSubcodeNeedsItsOwnReference()
    {
        WorkshopGuideCatalog.Result result = catalog.search("ENGINE", "P0299-12");
        assertTrue(result.guides.isEmpty());
        assertEquals("P0299-12", result.unmatchedCodes.get(0));
    }

    @Test public void mixedCodesRetainUnresolvedCodesWithoutHidingKnownGuide()
    {
        WorkshopGuideCatalog.Result result = catalog.search(null, "P0299\nB1234\np0299");
        assertEquals(1, result.guides.size());
        assertEquals(1, result.unmatchedCodes.size());
        assertEquals("B1234", result.unmatchedCodes.get(0));
        assertNotNull(result.fallback);
    }

    @Test public void nullModuleBrowsesAllButSpecificModuleDoesNot()
    {
        assertEquals(4, catalog.search(null, "").guides.size());
        WorkshopGuideCatalog.Result srs = catalog.search("SRS", "");
        assertEquals(1, srs.guides.size());
        assertEquals("srs", srs.guides.get(0).id);
        assertTrue(catalog.search("ABS", "").guides.isEmpty());
        assertTrue(catalog.search("not-a-module", "").guides.isEmpty());
    }

    @Test public void emptyCodesNeverMeanAllClear()
    {
        WorkshopGuideCatalog.Result result = catalog.search("BODY", null);
        assertTrue(result.guides.isEmpty());
        assertNotNull(result.fallback);
    }

    @Test public void symptomsUseLocalizedSearchWordsAndModuleFilter()
    {
        WorkshopGuideCatalog localized = new WorkshopGuideCatalog(id ->
                id == com.fr3ts0n.ecu.gui.androbd.R.string.guide_overheat_keywords
                        ? "engine mesin panas overheating coolant" : "");
        assertEquals("overheating", localized.search(null, "mesin panas").guides.get(0).id);
        assertTrue(localized.search("SRS", "mesin panas").guides.isEmpty());
    }

    @Test public void codeInsideLongIdentifierDoesNotMatch()
    {
        assertTrue(catalog.search(null, "XP0299 P02990").guides.isEmpty());
    }
}

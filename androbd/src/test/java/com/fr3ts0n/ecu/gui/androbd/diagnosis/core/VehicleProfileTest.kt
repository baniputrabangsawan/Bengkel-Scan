package com.fr3ts0n.ecu.gui.androbd.diagnosis.core

import org.junit.Assert.*
import org.junit.Test

class VehicleProfileTest {
    @Test fun unknownDefaultsDoNotInventDieselOrCylinderCount() {
        val profile = VehicleProfile()
        assertTrue(profile.invalidFields().isEmpty())
        val context = profile.context(null, emptySet(), null, emptySet())
        assertNull(context.fuelType)
        assertNull(context.fuelSystem)
        assertNull(context.cylinderCount)
        assertNull(context.mileageKm)
        assertTrue(context.flags.isEmpty())
    }
    @Test fun unsupportedPropulsionCannotProduceCombustionEngineClaims() {
        assertFalse(VehicleProfile(fuelType = "electric").supportsDraftCauseRanking())
        assertFalse(VehicleProfile(fuelType = "hybrid").supportsDraftCauseRanking())
        assertTrue(VehicleProfile(fuelType = "petrol").supportsDraftCauseRanking())
        assertTrue(VehicleProfile(fuelType = "diesel").supportsDraftCauseRanking())
    }

    @Test fun oldCarburetedVehicleDoesNotNeedObdOrKnownMileage() {
        val profile = VehicleProfile(make = "Toyota", model = "Kijang", year = "1990", fuelType = "petrol",
            fuelSystem = "carburetor", cylinderCount = "4", transmission = "manual")
        val context = profile.context("cold", setOf("idle"), "under_hood", setOf("fades_when_warm"))
        assertEquals("carburetor", context.fuelSystem)
        assertEquals("petrol", context.fuelType)
        assertNull(context.mileageKm)
        assertEquals(setOf("fades_when_warm"), context.flags)
    }

    @Test fun zeroMileageIsValidButNegativeAndOverflowValuesAreNot() {
        assertEquals(0, VehicleProfile(mileageKm = "0").context(null, emptySet(), null, emptySet()).mileageKm)
        listOf("-1", "2147483648", "1.5", "abc").forEach {
            assertTrue(VehicleProfile(mileageKm = it).invalidFields().contains("mileage"))
        }
    }

    @Test fun yearAndCylinderCountMustBePositiveOrUnknown() {
        listOf("0", "-1", "x", "2147483648").forEach {
            assertTrue(VehicleProfile(year = it).invalidFields().contains("year"))
            assertTrue(VehicleProfile(cylinderCount = it).invalidFields().contains("cylinders"))
        }
        assertTrue(VehicleProfile(cylinderCount = "3", year = "2026").invalidFields().isEmpty())
    }

    @Test fun invalidSavedEnumsCannotBecomeValidFacts() {
        assertTrue(VehicleProfile(fuelType = "steam").invalidFields().contains("fuel"))
        assertTrue(VehicleProfile(fuelSystem = "guessed").invalidFields().contains("fuel_system"))
        assertTrue(VehicleProfile(powerSteeringType = "guessed").invalidFields().contains("steering"))
        assertTrue(VehicleProfile(transmission = "guessed").invalidFields().contains("transmission"))
        assertThrows(IllegalArgumentException::class.java) {
            VehicleProfile(fuelType = "steam").context(null, emptySet(), null, emptySet())
        }
    }

    @Test fun makeAndModelNeverInferEngineFacts() {
        val context = VehicleProfile(make = "Daihatsu", model = "Ayla", year = "2020")
            .context(null, emptySet(), null, emptySet())
        assertNull(context.cylinderCount)
        assertNull(context.fuelType)
        assertNull(context.engineTempState)
    }
}

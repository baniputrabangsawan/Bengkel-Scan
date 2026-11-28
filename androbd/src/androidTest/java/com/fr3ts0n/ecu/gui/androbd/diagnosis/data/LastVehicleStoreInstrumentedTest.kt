package com.fr3ts0n.ecu.gui.androbd.diagnosis.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.VehicleProfile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LastVehicleStoreInstrumentedTest {
    @Test fun onlyAnExplicitSaveIsRememberedAndRevocationRemovesIt() = isolated { context ->
        val store = LastVehicleStore(context)
        assertNull(store.read())
        val profile = VehicleProfile(make = "Toyota", model = "Kijang", year = "1992",
            fuelType = "petrol", fuelSystem = "carburetor", cylinderCount = "4", transmission = "manual", mileageKm = "240000")
        store.save(profile)
        assertEquals(profile, LastVehicleStore(context).read())
        store.clear()
        assertNull(LastVehicleStore(context).read())
        assertTrue(context.noBackupFilesDir.listFiles().orEmpty().isEmpty())
    }

    @Test fun corruptFuelFactCannotSilentlyBecomeDieselOnRecall() = isolated { context ->
        val store = LastVehicleStore(context)
        store.save(VehicleProfile(fuelType = "petrol"))
        val file = File(context.noBackupFilesDir, "diagnosis-last-vehicle.json")
        file.writeText(JSONObject(file.readText()).put("fuel_type", "corrupt").toString())
        assertThrows(IllegalArgumentException::class.java) { store.read() }
    }

    private fun isolated(test: (Context) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "last-vehicle-test-${UUID.randomUUID()}")
        check(directory.mkdirs())
        val context = object : ContextWrapper(app) { override fun getNoBackupFilesDir(): File = directory }
        try { test(context) } finally { directory.deleteRecursively() }
    }
}

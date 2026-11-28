package com.fr3ts0n.ecu.gui.androbd.diagnosis.data

import android.content.Context
import android.util.AtomicFile
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.VehicleProfile
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Explicit opt-in only. noBackupFilesDir excludes this profile from Android cloud backup. */
class LastVehicleStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "diagnosis-last-vehicle.json"))

    /** Synchronous disk access: call from the ViewModel's I/O dispatcher. */
    fun read(): VehicleProfile? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return file.openRead().use { input ->
            val bytes = input.readNBytesCompat(64 * 1024)
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            require(json.getInt("version") == 1 && json.getBoolean("consented")) { "vehicle.invalid_saved_profile" }
            val profile = VehicleProfile(json.getString("make"), json.getString("model"), json.getString("year"),
                json.getString("fuel_type"), json.getString("cylinder_count"), json.getString("transmission"),
                json.getString("mileage_km"), json.getString("fuel_system"), json.getString("power_steering_type"))
            require(profile.invalidFields().isEmpty()) { "vehicle.invalid_saved_profile" }
            profile
        }
    }

    fun save(profile: VehicleProfile) {
        require(profile.invalidFields().isEmpty()) { "vehicle.invalid_profile" }
        val bytes = JSONObject().put("version", 1).put("consented", true)
            .put("make", profile.make).put("model", profile.model).put("year", profile.year)
            .put("fuel_type", profile.fuelType).put("cylinder_count", profile.cylinderCount)
            .put("transmission", profile.transmission).put("mileage_km", profile.mileageKm)
            .put("fuel_system", profile.fuelSystem).put("power_steering_type", profile.powerSteeringType)
            .toString().toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    fun clear() {
        file.delete()
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists() || File(file.baseFile.path + ".new").exists()) {
            throw IOException("vehicle.cannot_revoke_storage")
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val size = read(buffer)
            if (size == -1) break
            require(size > 0 && bytes.size() + size <= limit) { "vehicle.invalid_saved_profile" }
            bytes.write(buffer, 0, size)
        }
        return bytes.toByteArray()
    }
}

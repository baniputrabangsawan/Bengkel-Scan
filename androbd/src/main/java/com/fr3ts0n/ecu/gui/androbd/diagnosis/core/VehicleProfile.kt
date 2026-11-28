package com.fr3ts0n.ecu.gui.androbd.diagnosis.core

/** Only stable vehicle facts may be remembered; complaints, conditions and flags are transient. */
data class VehicleProfile(
    val make: String = "",
    val model: String = "",
    val year: String = "",
    val fuelType: String = "",
    val cylinderCount: String = "",
    val transmission: String = "",
    val mileageKm: String = "",
    val fuelSystem: String = "",
    val powerSteeringType: String = "",
) {
    fun invalidFields(): Set<String> = buildSet {
        if (year.isNotBlank() && (year.toIntOrNull() ?: 0) <= 0) add("year")
        if (mileageKm.isNotBlank() && (mileageKm.toIntOrNull() ?: -1) < 0) add("mileage")
        if (cylinderCount.isNotBlank() && (cylinderCount.toIntOrNull() ?: 0) <= 0) add("cylinders")
        if (fuelType !in setOf("", "petrol", "diesel", "hybrid", "electric")) add("fuel")
        if (transmission !in setOf("", "manual", "automatic", "cvt", "other")) add("transmission")
        if (fuelSystem !in setOf("", "carburetor", "injection")) add("fuel_system")
        if (powerSteeringType !in setOf("", "hydraulic", "eps", "none")) add("steering")
    }

    /** This draft catalog has no validated electric/hybrid applicability model. Keep safety checks, abstain on causes. */
    fun supportsDraftCauseRanking(): Boolean = fuelType != "electric" && fuelType != "hybrid"

    fun context(
        temperature: String?, triggers: Set<String>, location: String?, flags: Set<String>,
        temperaturePattern: String? = null,
    ): VehicleContext {
        require(invalidFields().isEmpty()) { "triage.invalid_vehicle_profile" }
        return VehicleContext(fuelType.takeIf(String::isNotBlank), cylinderCount.toIntOrNull(), mileageKm.toIntOrNull(),
            temperature, fuelSystem.takeIf(String::isNotBlank), powerSteeringType.takeIf(String::isNotBlank),
            triggers, location, flags, temperaturePattern)
    }
}

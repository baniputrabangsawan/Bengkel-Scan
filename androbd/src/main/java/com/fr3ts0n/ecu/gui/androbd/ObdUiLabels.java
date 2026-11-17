package com.fr3ts0n.ecu.gui.androbd;

import android.content.Context;

/**
 * Android presentation labels for read-only entries in the standard OBD PID CSV.
 * Mnemonics, source descriptions, values and units remain library-owned.
 */
final class ObdUiLabels
{
    private ObdUiLabels()
    {
    }

    /** Preserve unknown mnemonics and custom descriptions, including null fallbacks. */
    static String label(Context context, String mnemonic, String fallback)
    {
        if (mnemonic == null)
        {
            return fallback;
        }

        switch (mnemonic)
        {
            case "number_fault_codes":
                if (!"Number of Fault Codes".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_number_fault_codes);
            case "status_mil":
                if (!"MIL status".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_status_mil);
            case "status_misfires":
                if (!"Misfire status".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_status_misfires);
            case "status_fuel_system":
                if (!"Fuel system test status".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_status_fuel_system);
            case "status_component_test":
                if (!"Component test status".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_status_component_test);
            case "status_ignition_monitoring":
                if (!"Ignition monitoring status".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_status_ignition_monitoring);
            case "status_catalyst_test":
                if (!"Catalyst test status".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_status_catalyst_test);
            case "status_catalyst_test_nox_monitor":
                if (!"Heated Cat / Nox Monitor test status".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_status_catalyst_test_nox_monitor);
            case "status_evaporative_system_test":
                if (!"Evaporative system test status".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_status_evaporative_system_test);
            case "status_secondary_air_system_test":
                if (!"Secondary air sys test status".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_status_secondary_air_system_test);
            case "status_ac_refrigerant_test":
                if (!"A/C refrigerant test status".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_status_ac_refrigerant_test);
            case "status_oxygen_sensor_test":
                if (!"Oxygen Sensor test status".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_status_oxygen_sensor_test);
            case "status_oxygen_sensor_heater_test":
                if (!"Oxygen Sensor Heater test status".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_status_oxygen_sensor_heater_test);
            case "status_egr_system_test":
                if (!"EGR System test status".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_status_egr_system_test);
            case "location_fault_code":
                if (!"DTC location".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_location_fault_code);
            case "status_fuel_system_1":
                if (!"Fuel System 1 Status".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_status_fuel_system_1);
            case "status_fuel_system_2":
                if (!"Fuel System 2 Status".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_status_fuel_system_2);
            case "engine_load_calculated":
                if (!"Calculated Load Value".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_engine_load_calculated);
            case "engine_coolant_temperature":
                if (!"Coolant Temperature".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_engine_coolant_temperature);
            case "fuel_trim_short_b1":
                if (!"Short Term Fuel Trim (Bank 1)".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_fuel_trim_short_b1);
            case "fuel_trim_long_b1":
                if (!"Long Term Fuel Trim (Bank 1)".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_fuel_trim_long_b1);
            case "fuel_trim_short_b2":
                if (!"Short Term Fuel Trim (Bank 2)".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_fuel_trim_short_b2);
            case "fuel_trim_long_b2":
                if (!"Long Term Fuel Trim (Bank 2)".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_fuel_trim_long_b2);
            case "fuel_pressure":
                if (!"Fuel Pressure (gauge)".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_fuel_pressure);
            case "intake_manifold_pressure":
                if (!"Intake Manifold Pressure".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_intake_manifold_pressure);
            case "engine_speed":
                if (!"Engine RPM".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_engine_speed);
            case "vehicle_speed":
                if (!"Vehicle Speed".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_vehicle_speed);
            case "ignition_timing_advance_cyl1":
                if (!"Timing Advance (Cyl. #1)".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_ignition_timing_advance_cyl1);
            case "intake_air_temperature":
                if (!"Intake Air Temperature".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_intake_air_temperature);
            case "mass_airflow":
                if (!"Air Flow Rate (MAF sensor)".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_mass_airflow);
            case "throttle_position_abs":
                if (!"Absolute Throttle Position".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_throttle_position_abs);
            case "status_secondary_air_system":
                if (!"Secondary air status".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_status_secondary_air_system);
            case "number_oxygen_sensors":
                if (!"Oxygen sensors present".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_number_oxygen_sensors);
            case "o2_sensor_voltage_b1s1":
                if (!"O2 Sensor B1S1".equals(fallback) && !"O2 Sensor Voltage B1S1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_sensor_voltage_b1s1);
            case "o2_sensor_fuel_trim_b1s1":
                if (!"O2 Sensor fuel trim B1S1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_sensor_fuel_trim_b1s1);
            case "o2_sensor_voltage_b1s2":
                if (!"O2 Sensor B1S2".equals(fallback) && !"O2 Sensor Voltage B1S2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_sensor_voltage_b1s2);
            case "o2_sensor_fuel_trim_b1s2":
                if (!"O2 Sensor fuel trim B1S2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_sensor_fuel_trim_b1s2);
            case "o2_sensor_voltage_b1s3":
                if (!"O2 Sensor B1S3".equals(fallback) && !"O2 Sensor Voltage B1S3".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_sensor_voltage_b1s3);
            case "o2_sensor_fuel_trim_b1s3":
                if (!"O2 Sensor fuel trim B1S3".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_sensor_fuel_trim_b1s3);
            case "o2_sensor_voltage_b1s4":
                if (!"O2 Sensor B1S4".equals(fallback) && !"O2 Sensor Voltage B1S4".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_sensor_voltage_b1s4);
            case "o2_sensor_fuel_trim_b1s4":
                if (!"O2 Sensor fuel trim B1S4".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_sensor_fuel_trim_b1s4);
            case "o2_sensor_voltage_b2s1":
                if (!"O2 Sensor B2S1".equals(fallback) && !"O2 Sensor Voltage B2S1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_sensor_voltage_b2s1);
            case "o2_sensor_fuel_trim_b2s1":
                if (!"O2 Sensor fuel trim B2S1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_sensor_fuel_trim_b2s1);
            case "o2_sensor_voltage_b2s2":
                if (!"O2 Sensor B2S2".equals(fallback) && !"O2 Sensor Voltage B2S2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_sensor_voltage_b2s2);
            case "o2_sensor_fuel_trim_b2s2":
                if (!"O2 Sensor fuel trim B2S2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_sensor_fuel_trim_b2s2);
            case "o2_sensor_voltage_b2s3":
                if (!"O2 Sensor B2S3".equals(fallback) && !"O2 Sensor Voltage B2S3".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_sensor_voltage_b2s3);
            case "o2_sensor_fuel_trim_b2s3":
                if (!"O2 Sensor fuel trim B2S3".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_sensor_fuel_trim_b2s3);
            case "o2_sensor_voltage_b2s4":
                if (!"O2 Sensor B2S4".equals(fallback) && !"O2 Sensor Voltage B2S4".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_sensor_voltage_b2s4);
            case "o2_sensor_fuel_trim_b2s4":
                if (!"O2 Sensor fuel trim B2S4".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_sensor_fuel_trim_b2s4);
            case "obd_type":
                if (!"OBD conforms to".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_obd_type);
            case "map_oxygen_sensors_present":
                if (!"Oxygen sensors present".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_map_oxygen_sensors_present);
            case "status_power_take_off":
                if (!"Power Take-Off Status".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_status_power_take_off);
            case "running_time":
                if (!"Time Since Engine Start".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_running_time);
            case "distance_sine_mil_active":
                if (!"Distance since MIL activated".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_distance_sine_mil_active);
            case "fuel_pressure_rel":
                if (!"Fuel Rail Pressure (rel. to manifold vacuum)".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_fuel_pressure_rel);
            case "fuel_pressure_wr":
                if (!"Fuel Pressure (gauge)".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_fuel_pressure_wr);
            case "o2_wr_lambda_b1s1":
                if (!"O2 Sensor B1S1 lambda".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_wr_lambda_b1s1);
            case "o2_wr_voltage_b1s1":
                if (!"O2 Sensor B1S1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_wr_voltage_b1s1);
            case "o2_wr_lambda_b1s2":
                if (!"O2 Sensor B1S2 lambda".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_wr_lambda_b1s2);
            case "o2_wr_voltage_b1s2":
                if (!"O2 Sensor B1S2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_wr_voltage_b1s2);
            case "o2_wr_lambda_b1s3":
                if (!"O2 Sensor B1S3 lambda".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_wr_lambda_b1s3);
            case "o2_wr_voltage_b1s3":
                if (!"O2 Sensor B1S3".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_wr_voltage_b1s3);
            case "o2_wr_lambda_b1s4":
                if (!"O2 Sensor B1S4 lambda".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_wr_lambda_b1s4);
            case "o2_wr_voltage_b1s4":
                if (!"O2 Sensor B1S4".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_wr_voltage_b1s4);
            case "o2_wr_lambda_b2s1":
                if (!"O2 Sensor B2S1 lambda".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_wr_lambda_b2s1);
            case "o2_wr_voltage_b2s1":
                if (!"O2 Sensor B2S1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_wr_voltage_b2s1);
            case "o2_wr_lambda_b2s2":
                if (!"O2 Sensor B2S2 lambda".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_wr_lambda_b2s2);
            case "o2_wr_voltage_b2s2":
                if (!"O2 Sensor B2S2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_wr_voltage_b2s2);
            case "o2_wr_lambda_b2s3":
                if (!"O2 Sensor B2S3 lambda".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_wr_lambda_b2s3);
            case "o2_wr_voltage_b2s3":
                if (!"O2 Sensor B2S3".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_wr_voltage_b2s3);
            case "o2_wr_lambda_b2s4":
                if (!"O2 Sensor B2S4 lambda".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_wr_lambda_b2s4);
            case "o2_wr_voltage_b2s4":
                if (!"O2 Sensor B2S4".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_wr_voltage_b2s4);
            case "egr_ratio_commanded":
                if (!"Commanded EGR".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_egr_ratio_commanded);
            case "egr_error":
                if (!"EGR Error".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_egr_error);
            case "evaporative_purge_ratio":
                if (!"Commanded Evaporative Purge".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_evaporative_purge_ratio);
            case "fuel_level":
                if (!"Fuel Level Input".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_fuel_level);
            case "counts_warmups_since_ecu_reset":
                if (!"Warm-ups since ECU reset".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_counts_warmups_since_ecu_reset);
            case "distance_since_ecu_reset":
                if (!"Distance since ECU reset".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_distance_since_ecu_reset);
            case "pressure_vapor_evaporative_purge":
                if (!"Evap System Vapor Pressure".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_pressure_vapor_evaporative_purge);
            case "barometric_pressure":
                if (!"Barometric Pressure (absolute)".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_barometric_pressure);
            case "o2_wr_lambda_s1":
                if (!"O2 Sensor 1 lambda".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_wr_lambda_s1);
            case "o2_wr_current_s1":
                if (!"O2 Sensor 1 current".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_wr_current_s1);
            case "o2_wr_lambda_s2":
                if (!"O2 Sensor 2 lambda".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_wr_lambda_s2);
            case "o2_wr_current_s2":
                if (!"O2 Sensor 2 current".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_wr_current_s2);
            case "o2_wr_lambda_s3":
                if (!"O2 Sensor 3 lambda".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_wr_lambda_s3);
            case "o2_wr_current_s3":
                if (!"O2 Sensor 3 current".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_wr_current_s3);
            case "o2_wr_lambda_s4":
                if (!"O2 Sensor 4 lambda".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_wr_lambda_s4);
            case "o2_wr_current_s4":
                if (!"O2 Sensor 4 current".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_wr_current_s4);
            case "o2_wr_lambda_s5":
                if (!"O2 Sensor 5 lambda".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_wr_lambda_s5);
            case "o2_wr_current_s5":
                if (!"O2 Sensor 5 current".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_wr_current_s5);
            case "o2_wr_lambda_s6":
                if (!"O2 Sensor 6 lambda".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_wr_lambda_s6);
            case "o2_wr_current_s6":
                if (!"O2 Sensor 6 current".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_wr_current_s6);
            case "o2_wr_lambda_s7":
                if (!"O2 Sensor 7 lambda".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_wr_lambda_s7);
            case "o2_wr_current_s7":
                if (!"O2 Sensor 7 current".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_wr_current_s7);
            case "o2_wr_lambda_s8":
                if (!"O2 Sensor 8 lambda".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_wr_lambda_s8);
            case "o2_wr_current_s8":
                if (!"O2 Sensor 8 current".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_wr_current_s8);
            case "cat_temperature_b1s1":
                if (!"CAT Temperature B1S1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_cat_temperature_b1s1);
            case "cat_temperature_b2s1":
                if (!"CAT Temperature B2S1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_cat_temperature_b2s1);
            case "cat_temperature_b1s2":
                if (!"CAT Temperature B1S2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_cat_temperature_b1s2);
            case "cat_temperature_b2s2":
                if (!"CAT Temperature B2S2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_cat_temperature_b2s2);
            case "status_misfires_41":
                if (!"Misfire status".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_status_misfires_41);
            case "status_fuel_system_41":
                if (!"Fuel system test status".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_status_fuel_system_41);
            case "status_component_test_41":
                if (!"Component test status".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_status_component_test_41);
            case "status_ignition_monitoring_41":
                if (!"Ignition monitoring status".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_status_ignition_monitoring_41);
            case "status_catalyst_test_41":
                if (!"Catalyst test status".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_status_catalyst_test_41);
            case "status_catalyst_test_nox_monitor_41":
                if (!"Heated Cat / Nox Monitor test status".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_status_catalyst_test_nox_monitor_41);
            case "status_evaporative_system_test_41":
                if (!"Evaporative system test status".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_status_evaporative_system_test_41);
            case "status_secondary_air_system_test_41":
                if (!"Secondary air sys test status".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_status_secondary_air_system_test_41);
            case "status_ac_refrigerant_test_41":
                if (!"A/C refrigerant test status".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_status_ac_refrigerant_test_41);
            case "status_oxygen_sensor_test_41":
                if (!"Oxygen Sensor test status".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_status_oxygen_sensor_test_41);
            case "status_oxygen_sensor_heater_test_41":
                if (!"Oxygen Sensor Heater test status".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_status_oxygen_sensor_heater_test_41);
            case "status_egr_system_test_41":
                if (!"EGR System test status".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_status_egr_system_test_41);
            case "ecu_voltage":
                if (!"ECU voltage".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_ecu_voltage);
            case "engine_load":
                if (!"Absolute Engine Load".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_engine_load);
            case "equiv_ratio_commanded":
                if (!"Commanded Equivalence Ratio".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_equiv_ratio_commanded);
            case "throttle_position_rel":
                if (!"Relative Throttle Position".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_throttle_position_rel);
            case "ambient_air_temperature":
                if (!"Ambient Air Temperature".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_ambient_air_temperature);
            case "throttle_position_abs_b":
                if (!"Absolute Throttle Position B".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_throttle_position_abs_b);
            case "throttle_position_c":
                if (!"Absolute Throttle Position C".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_throttle_position_c);
            case "throttle_position_d":
                if (!"Accelerator Pedal Position D".equals(fallback) && !"Absolute Throttle Position D".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_throttle_position_d);
            case "throttle_position_e":
                if (!"Accelerator Pedal Position E".equals(fallback) && !"Absolute Throttle Position E".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_throttle_position_e);
            case "throttle_position_f":
                if (!"Accelerator Pedal Position F".equals(fallback) && !"Absolute Throttle Position F".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_throttle_position_f);
            case "commanded_throttle_position":
                if (!"Comm. Throttle Actuator Cntrl".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_commanded_throttle_position);
            case "time_since_mil_on":
                if (!"Engine running while MIL on".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_time_since_mil_on);
            case "time_since_ecu_reset":
                if (!"Time since DTCs cleared".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_time_since_ecu_reset);
            case "equiv_ratio_max":
                if (!"Maximum equivalence ratio".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_equiv_ratio_max);
            case "o2_sensor_voltage_max":
                if (!"Maximum oxygen sensor voltage".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_sensor_voltage_max);
            case "o2_sensor_current_max":
                if (!"Maximum oxygen sensor current".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_sensor_current_max);
            case "fuel_pressure_max":
                if (!"Maximum intake manifold absolute pressure".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_fuel_pressure_max);
            case "mass_airflow_max":
                if (!"Maximum air flow rate from mass air flow sensor".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_mass_airflow_max);
            case "fuel_type":
                if (!"Fuel type".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_fuel_type);
            case "ethanol_fuel_percentage":
                if (!"Ethanol fuel %".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_ethanol_fuel_percentage);
            case "pressure_vapor_evaporative_purge_absolute":
                if (!"Absolute Evap system Vapor Pressure".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_pressure_vapor_evaporative_purge_absolute);
            case "pressure_vapor_evaporative_purge_rel":
                if (!"Evap System Vapor Pressure".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_pressure_vapor_evaporative_purge_rel);
            case "fuel_trim_short_sec_b1":
                if (!"Short term secondary oxygen sensor trim bank 1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_fuel_trim_short_sec_b1);
            case "fuel_trim_short_sec_b3":
                if (!"Short term secondary oxygen sensor trim bank 3".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_fuel_trim_short_sec_b3);
            case "fuel_trim_long_sec_b1":
                if (!"Long term secondary oxygen sensor trim bank 1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_fuel_trim_long_sec_b1);
            case "fuel_trim_long_sec_b3":
                if (!"Long term secondary oxygen sensor trim bank 3".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_fuel_trim_long_sec_b3);
            case "fuel_trim_short_sec_b2":
                if (!"Short term secondary oxygen sensor trim bank 2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_fuel_trim_short_sec_b2);
            case "fuel_trim_short_sec_b4":
                if (!"Short term secondary oxygen sensor trim bank 4".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_fuel_trim_short_sec_b4);
            case "fuel_trim_long_sec_b2":
                if (!"Long term secondary oxygen sensor trim bank 2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_fuel_trim_long_sec_b2);
            case "fuel_trim_long_sec_b4":
                if (!"Long term secondary oxygen sensor trim bank 4".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_fuel_trim_long_sec_b4);
            case "fuel_pressure_abs":
                if (!"Fuel rail pressure (absolute)".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_fuel_pressure_abs);
            case "accelerator_pedal_position":
                if (!"Relative accelerator pedal position".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_accelerator_pedal_position);
            case "time_remaining_life_battery_pack":
                if (!"Hybrid battery pack remaining life".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_time_remaining_life_battery_pack);
            case "engine_oil_temperature":
                if (!"Engine oil temperature".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_engine_oil_temperature);
            case "fuel_injection_timing":
                if (!"Fuel injection timing".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_fuel_injection_timing);
            case "engine_fuel_rate":
                if (!"Engine fuel rate".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_engine_fuel_rate);
            case "obd_requirements":
                if (!"OBD requirements".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_obd_requirements);
            case "engine_torque_demand":
                if (!"Torque Driver demand".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_engine_torque_demand);
            case "engine_torque":
                if (!"Torque Engine".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_engine_torque);
            case "engine_torque_reference":
                if (!"Torque Engine reference".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_engine_torque_reference);
            case "engine_torque_idle":
                if (!"Torque Idle".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_engine_torque_idle);
            case "engine_torque_point_1":
                if (!"Torque Engine Point 1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_engine_torque_point_1);
            case "engine_torque_point_2":
                if (!"Torque Engine Point 2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_engine_torque_point_2);
            case "engine_torque_point_3":
                if (!"Torque Engine Point 3".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_engine_torque_point_3);
            case "engine_torque_point_4":
                if (!"Torque Engine Point 4".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_engine_torque_point_4);
            case "engine_torque_point_5":
                if (!"Torque Engine Point 5".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_engine_torque_point_5);
            case "status_pto":
                if (!"Power take off (PTO) status".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_status_pto);
            case "status_at_gear":
                if (!"AT Drive Status".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_status_at_gear);
            case "status_mt_gear":
                if (!"MT Drive status".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_status_mt_gear);
            case "status_glowplug_lamp":
                if (!"Glowplug lamp status".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_status_glowplug_lamp);
            case "airmass_maf_a":
                if (!"Air mass (MAF A)".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_airmass_maf_a);
            case "airmass_maf_b":
                if (!"Air mass (MAF B)".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_airmass_maf_b);
            case "engine_coolant_temp_1":
                if (!"Coolant Temperature 1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_engine_coolant_temp_1);
            case "engine_coolant_temp_2":
                if (!"Coolant Temperature 2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_engine_coolant_temp_2);
            case "intake_air_temp_b1s1":
                if (!"Intake Air Temperature (Bank 1, Sensor 1)".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_intake_air_temp_b1s1);
            case "intake_air_temp_b1s2":
                if (!"Intake Air Temperature (Bank 1, Sensor 2)".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_intake_air_temp_b1s2);
            case "intake_air_temp_b1s3":
                if (!"Intake Air Temperature (Bank 1, Sensor 3)".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_intake_air_temp_b1s3);
            case "intake_air_temp_b2s1":
                if (!"Intake Air Temperature (Bank 2, Sensor 1)".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_intake_air_temp_b2s1);
            case "intake_air_temp_b2s2":
                if (!"Intake Air Temperature (Bank 2, Sensor 2)".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_intake_air_temp_b2s2);
            case "intake_air_temp_b2s3":
                if (!"Intake Air Temperature (Bank 2, Sensor 3)".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_intake_air_temp_b2s3);
            case "egr_a_dutycycle":
                if (!"Commanded EGR A duty cycle".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_egr_a_dutycycle);
            case "egr_a_position":
                if (!"Commanded EGR A position".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_egr_a_position);
            case "egr_a_error":
                if (!"Commanded EGR A error".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_egr_a_error);
            case "egr_b_dutycycle":
                if (!"Commanded EGR B duty cycle".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_egr_b_dutycycle);
            case "egr_b_position":
                if (!"Commanded EGR B position".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_egr_b_position);
            case "egr_b_error":
                if (!"Commanded EGR B error".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_egr_b_error);
            case "intake_air_control_a":
                if (!"Intake Air Flow Control A".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_intake_air_control_a);
            case "intake_air_position_a":
                if (!"Intake Air Flow Position A".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_intake_air_position_a);
            case "intake_air_control_b":
                if (!"Intake Air Flow Control B".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_intake_air_control_b);
            case "intake_air_position_b":
                if (!"Intake Air Flow Position B".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_intake_air_position_b);
            case "egr_temp_a":
                if (!"EGR temperature (Sensor A)".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_egr_temp_a);
            case "egr_temp_c":
                if (!"EGR temperature (Sensor C)".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_egr_temp_c);
            case "egr_temp_b":
                if (!"EGR temperature (Sensor B)".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_egr_temp_b);
            case "egr_temp_d":
                if (!"EGR temperature (Sensor D)".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_egr_temp_d);
            case "throttle_control_a":
                if (!"Throttle Control A".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_throttle_control_a);
            case "throttle_position_a":
                if (!"Throttle Position A".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_throttle_position_a);
            case "throttle_control_b":
                if (!"Throttle Control B".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_throttle_control_b);
            case "throttle_position_b":
                if (!"Throttle Position B".equals(fallback) && !"Absolute Throttle Position B".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_throttle_position_b);
            case "rail_pressure_cmd_a":
                if (!"Commanded Fuel Rail pressure A".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rail_pressure_cmd_a);
            case "rail_pressure_a":
                if (!"Fuel Rail pressure A".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rail_pressure_a);
            case "rail_temp_a":
                if (!"Fuel Rail temperature A".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rail_temp_a);
            case "rail_pressure_cmd_b":
                if (!"Commanded Fuel Rail pressure B".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rail_pressure_cmd_b);
            case "rail_pressure_b":
                if (!"Fuel Rail pressure B".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rail_pressure_b);
            case "rail_temp_b":
                if (!"Fuel Rail temperature B".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rail_temp_b);
            case "inject_pressure_cmd_a":
                if (!"Commanded Injection Control pressure A".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_inject_pressure_cmd_a);
            case "inject_pressure_a":
                if (!"Injection Control pressure A".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_inject_pressure_a);
            case "inject_pressure_cmd_b":
                if (!"Commanded Injection Control pressure B".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_inject_pressure_cmd_b);
            case "inject_pressure_b":
                if (!"Injection Control pressure B".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_inject_pressure_b);
            case "charger_pressure_inlet_a":
                if (!"Turbocharger Compressor Inlet Pressure A".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_charger_pressure_inlet_a);
            case "charger_pressure_inlet_b":
                if (!"Turbocharger Compressor Inlet Pressure B".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_charger_pressure_inlet_b);
            case "boost_pressure_cmd_a":
                if (!"Commanded Boost pressure A".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_boost_pressure_cmd_a);
            case "boost_pressure_a":
                if (!"Boost pressure A".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_boost_pressure_a);
            case "boost_pressure_cmd_b":
                if (!"Commanded Boost pressure B".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_boost_pressure_cmd_b);
            case "boost_pressure_b":
                if (!"Boost pressure B".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_boost_pressure_b);
            case "boost_status_a":
                if (!"Boost Control status A".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_boost_status_a);
            case "boost_status_b":
                if (!"Boost Control status B".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_boost_status_b);
            case "vtg_position_cmd_a":
                if (!"Variable Geometry Turbo Commanded Position A".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_vtg_position_cmd_a);
            case "vtg_position_a":
                if (!"Variable Geometry Turbo Position A".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_vtg_position_a);
            case "vtg_position_cmd_b":
                if (!"Variable Geometry Turbo Commanded Position B".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_vtg_position_cmd_b);
            case "vtg_position_b":
                if (!"Variable Geometry Turbo Position B".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_vtg_position_b);
            case "vtg_status_a":
                if (!"Boost Control status A".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_vtg_status_a);
            case "vtg_status_b":
                if (!"Boost Control status B".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_vtg_status_b);
            case "wastegate_position_cmd_a":
                if (!"Wastegate Commanded Position A".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_wastegate_position_cmd_a);
            case "wastegate_position_a":
                if (!"Wastegate Position A".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_wastegate_position_a);
            case "wastegate_position_cmd_b":
                if (!"Wastegate Commanded Position B".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_wastegate_position_cmd_b);
            case "wastegate_position_b":
                if (!"Wastegate Position B".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_wastegate_position_b);
            case "exhaust_pressure_b1":
                if (!"Exhaust pressure B1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_exhaust_pressure_b1);
            case "exhaust_pressure_b2":
                if (!"Exhaust pressure B2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_exhaust_pressure_b2);
            case "rpm_charger_a":
                if (!"RPM Turbo Charger A".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rpm_charger_a);
            case "rpm_charger_b":
                if (!"RPM Turbo Charger B".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rpm_charger_b);
            case "temp_charger_a_compressor_in":
                if (!"Turbocharger A Compressor Inlet Temperature".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_temp_charger_a_compressor_in);
            case "temp_charger_a_compressor_out":
                if (!"Turbocharger A Compressor Outlet Temperature".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_temp_charger_a_compressor_out);
            case "temp_charger_a_turbine_in":
                if (!"Turbocharger A Turbine Inlet Temperature".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_temp_charger_a_turbine_in);
            case "temp_charger_a_turbine_out":
                if (!"Turbocharger A Turbine Outlet Temperature".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_temp_charger_a_turbine_out);
            case "temp_charger_b_compressor_in":
                if (!"Turbocharger B Compressor Inlet Temperature".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_temp_charger_b_compressor_in);
            case "temp_charger_b_compressor_out":
                if (!"Turbocharger B Compressor Outlet Temperature".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_temp_charger_b_compressor_out);
            case "temp_charger_b_turbine_in":
                if (!"Turbocharger B Turbine Inlet Temperature".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_temp_charger_b_turbine_in);
            case "temp_charger_b_turbine_out":
                if (!"Turbocharger B Turbine Outlet Temperature".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_temp_charger_b_turbine_out);
            case "charge_air_temp_b1s1":
                if (!"Charge Air Cooler Temperature B1S1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_charge_air_temp_b1s1);
            case "charge_air_temp_b1s2":
                if (!"Charge Air Cooler Temperature B1S2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_charge_air_temp_b1s2);
            case "charge_air_temp_b2s1":
                if (!"Charge Air Cooler Temperature B2S1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_charge_air_temp_b2s1);
            case "charge_air_temp_b2s2":
                if (!"Charge Air Cooler Temperature B2S2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_charge_air_temp_b2s2);
            case "map_exhaust_temperature_sensors_b1":
                if (!"Exhaust Temperature Sensors B1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_map_exhaust_temperature_sensors_b1);
            case "exhaust_temperature_b1s1":
                if (!"Exhaust Temperature B1S1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_exhaust_temperature_b1s1);
            case "exhaust_temperature_b1s2":
                if (!"Exhaust Temperature B1S2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_exhaust_temperature_b1s2);
            case "exhaust_temperature_b1s3":
                if (!"Exhaust Temperature B1S3".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_exhaust_temperature_b1s3);
            case "exhaust_temperature_b1s4":
                if (!"Exhaust Temperature B1S4".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_exhaust_temperature_b1s4);
            case "map_exhaust_temperature_sensors_b2":
                if (!"Exhaust Temperature Sensors B2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_map_exhaust_temperature_sensors_b2);
            case "exhaust_temperature_b2s1":
                if (!"Exhaust Temperature B2S1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_exhaust_temperature_b2s1);
            case "exhaust_temperature_b2s2":
                if (!"Exhaust Temperature B2S2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_exhaust_temperature_b2s2);
            case "exhaust_temperature_b2s3":
                if (!"Exhaust Temperature B2S3".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_exhaust_temperature_b2s3);
            case "exhaust_temperature_b2s4":
                if (!"Exhaust Temperature B2S4".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_exhaust_temperature_b2s4);
            case "dpf_b1_pressure_delta":
                if (!"DPF Delta Pressure B1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_dpf_b1_pressure_delta);
            case "dpf_b1_pressure_in":
                if (!"DPF Inlet Pressure B1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_dpf_b1_pressure_in);
            case "dpf_b1_pressure_out":
                if (!"DPF Outlet Pressure B1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_dpf_b1_pressure_out);
            case "dpf_b2_pressure_delta":
                if (!"DPF Delta Pressure B2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_dpf_b2_pressure_delta);
            case "dpf_b2_pressure_in":
                if (!"DPF Inlet Pressure B2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_dpf_b2_pressure_in);
            case "dpf_b2_pressure_out":
                if (!"DPF Outlet Pressure B2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_dpf_b2_pressure_out);
            case "dpf_b1_temperature_in":
                if (!"DPF Inlet temperature B1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_dpf_b1_temperature_in);
            case "dpf_b1_temperature_out":
                if (!"DPF Outlet temperature B1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_dpf_b1_temperature_out);
            case "dpf_b2_temperature_in":
                if (!"DPF Inlet temperature B2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_dpf_b2_temperature_in);
            case "dpf_b2_temperature_out":
                if (!"DPF Outlet temperature B2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_dpf_b2_temperature_out);
            case "rt_eiaecd_1t1":
                if (!"Runtime EI-AECD #1 Timer 1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_1t1);
            case "rt_eiaecd_1t2":
                if (!"Runtime EI-AECD #1 Timer 2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_1t2);
            case "rt_eiaecd_2t1":
                if (!"Runtime EI-AECD #2 Timer 1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_2t1);
            case "rt_eiaecd_2t2":
                if (!"Runtime EI-AECD #2 Timer 2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_2t2);
            case "rt_eiaecd_3t1":
                if (!"Runtime EI-AECD #3 Timer 1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_3t1);
            case "rt_eiaecd_3t2":
                if (!"Runtime EI-AECD #3 Timer 2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_3t2);
            case "rt_eiaecd_4t1":
                if (!"Runtime EI-AECD #4 Timer 1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_4t1);
            case "rt_eiaecd_4t2":
                if (!"Runtime EI-AECD #4 Timer 2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_4t2);
            case "rt_eiaecd_5t1":
                if (!"Runtime EI-AECD #5 Timer 1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_5t1);
            case "rt_eiaecd_5t2":
                if (!"Runtime EI-AECD #5 Timer 2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_5t2);
            case "rt_eiaecd_6t1":
                if (!"Runtime EI-AECD #6 Timer 1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_6t1);
            case "rt_eiaecd_6t2":
                if (!"Runtime EI-AECD #6 Timer 2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_6t2);
            case "rt_eiaecd_7t1":
                if (!"Runtime EI-AECD #7 Timer 1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_7t1);
            case "rt_eiaecd_7t2":
                if (!"Runtime EI-AECD #7 Timer 2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_7t2);
            case "rt_eiaecd_8t1":
                if (!"Runtime EI-AECD #8 Timer 1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_8t1);
            case "rt_eiaecd_8t2":
                if (!"Runtime EI-AECD #8 Timer 2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_8t2);
            case "rt_eiaecd_9t1":
                if (!"Runtime EI-AECD #9 Timer 1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_9t1);
            case "rt_eiaecd_9t2":
                if (!"Runtime EI-AECD #9 Timer 2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_9t2);
            case "rt_eiaecd_10t1":
                if (!"Runtime EI-AECD #10 Timer 1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_10t1);
            case "rt_eiaecd_10t2":
                if (!"Runtime EI-AECD #10 Timer 2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_10t2);
            case "ppm_nox_b1s1":
                if (!"NOx Concentration B1S1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_ppm_nox_b1s1);
            case "ppm_nox_b1s2":
                if (!"NOx Concentration B1S2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_ppm_nox_b1s2);
            case "ppm_nox_b2s1":
                if (!"NOx Concentration B2S1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_ppm_nox_b2s1);
            case "ppm_nox_b2s2":
                if (!"NOx Concentration B2S2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_ppm_nox_b2s2);
            case "temp_manifold_surface":
                if (!"Intake Manifold Surface Temperature".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_temp_manifold_surface);
            case "avg_reagent_consumption":
                if (!"Average Reagent consumption".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_avg_reagent_consumption);
            case "avg_demad_reagent_consumption":
                if (!"Average Reagent consumption demand".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_avg_demad_reagent_consumption);
            case "level_reagent_tank":
                if (!"Reagent Tank level".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_level_reagent_tank);
            case "rt_nox_warning":
                if (!"Runtime w/ active NOx warning".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_nox_warning);
            case "particle_mass_b1":
                if (!"Particulate Mass concentration B1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_particle_mass_b1);
            case "particle_mass_b2":
                if (!"Particulate Mass concentration B2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_particle_mass_b2);
            case "press_manifold_abs_a":
                if (!"Intake Manifold Absolute Pressure A".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_press_manifold_abs_a);
            case "press_manifold_abs_b":
                if (!"Intake Manifold Absolute Pressure B".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_press_manifold_abs_b);
            case "state_scr_act":
                if (!"SCR system state actual".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_state_scr_act);
            case "state_scr_10k":
                if (!"SCR system state 00-10k km".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_state_scr_10k);
            case "state_scr_20k":
                if (!"SCR system state 10-20k km".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_state_scr_20k);
            case "state_scr_30k":
                if (!"SCR system state 20-30k km".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_state_scr_30k);
            case "state_scr_40k":
                if (!"SCR system state 30-40k km".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_state_scr_40k);
            case "dist_scr_induce_act":
                if (!"SCR Distance w/ inducement active (current 10k)".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_dist_scr_induce_act);
            case "dist_scr_act":
                if (!"SCR Distance (current 10k)".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_dist_scr_act);
            case "dist_induce_20k":
                if (!"SCR Distance w/ inducement active (10-20k km)".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_dist_induce_20k);
            case "dist_induce_30k":
                if (!"SCR Distance w/ inducement active (20-30k km)".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_dist_induce_30k);
            case "dist_induce_40k":
                if (!"SCR Distance w/ inducement active (30-40k km)".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_dist_induce_40k);
            case "rt_eiaecd_11t1":
                if (!"Runtime EI-AECD #11 Timer 1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_11t1);
            case "rt_eiaecd_11t2":
                if (!"Runtime EI-AECD #11 Timer 2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_11t2);
            case "rt_eiaecd_12t1":
                if (!"Runtime EI-AECD #12 Timer 1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_12t1);
            case "rt_eiaecd_12t2":
                if (!"Runtime EI-AECD #12 Timer 2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_12t2);
            case "rt_eiaecd_13t1":
                if (!"Runtime EI-AECD #13 Timer 1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_13t1);
            case "rt_eiaecd_13t2":
                if (!"Runtime EI-AECD #13 Timer 2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_13t2);
            case "rt_eiaecd_14t1":
                if (!"Runtime EI-AECD #14 Timer 1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_14t1);
            case "rt_eiaecd_14t2":
                if (!"Runtime EI-AECD #14 Timer 2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_14t2);
            case "rt_eiaecd_15t1":
                if (!"Runtime EI-AECD #15 Timer 1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_15t1);
            case "rt_eiaecd_15t2":
                if (!"Runtime EI-AECD #15 Timer 2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_15t2);
            case "rt_eiaecd_16t1":
                if (!"Runtime EI-AECD #16 Timer 1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_16t1);
            case "rt_eiaecd_16t2":
                if (!"Runtime EI-AECD #16 Timer 2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_16t2);
            case "rt_eiaecd_17t1":
                if (!"Runtime EI-AECD #17 Timer 1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_17t1);
            case "rt_eiaecd_17t2":
                if (!"Runtime EI-AECD #17 Timer 2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_17t2);
            case "rt_eiaecd_18t1":
                if (!"Runtime EI-AECD #18 Timer 1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_18t1);
            case "rt_eiaecd_18t2":
                if (!"Runtime EI-AECD #18 Timer 2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_18t2);
            case "rt_eiaecd_19t1":
                if (!"Runtime EI-AECD #19 Timer 1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_19t1);
            case "rt_eiaecd_19t2":
                if (!"Runtime EI-AECD #19 Timer 2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_19t2);
            case "rt_eiaecd_20t1":
                if (!"Runtime EI-AECD #20 Timer 1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_20t1);
            case "rt_eiaecd_20t2":
                if (!"Runtime EI-AECD #20 Timer 2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_rt_eiaecd_20t2);
            case "state_dpf":
                if (!"DPF Reneneration Status".equals(fallback) && !"DPF Regeneration Status".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_state_dpf);
            case "load_dpf":
                if (!"Normalized Trigger for DPF Regeneration".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_load_dpf);
            case "time_dpf_regen":
                if (!"Avg. Time between DPF Regenerations".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_time_dpf_regen);
            case "dist_dpf_regen":
                if (!"Avg. Distance between DPF Regenerations".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_dist_dpf_regen);
            case "o2_percent_b1s1":
                if (!"O2 Sensor Concentration B1S1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_percent_b1s1);
            case "o2_percent_b1s2":
                if (!"O2 Sensor Concentration B1S2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_percent_b1s2);
            case "o2_percent_b2s1":
                if (!"O2 Sensor Concentration B2S1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_percent_b2s1);
            case "o2_percent_b2s2":
                if (!"O2 Sensor Concentration B2S2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_percent_b2s2);
            case "o2_lambda_b1s1":
                if (!"O2 Sensor Lambda B1S1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_lambda_b1s1);
            case "o2_lambda_b1s2":
                if (!"O2 Sensor Lambda B1S2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_lambda_b1s2);
            case "o2_lambda_b2s1":
                if (!"O2 Sensor Lambda B2S1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_lambda_b2s1);
            case "o2_lambda_b2s2":
                if (!"O2 Sensor Lambda B2S2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_lambda_b2s2);
            case "throttle_position":
                if (!"Throttle position".equals(fallback) && !"Absolute Throttle Position".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_throttle_position);
            case "percent_friction":
                if (!"Engine Torque Friction".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_percent_friction);
            case "state_pm_b1":
                if (!"PM Sensor status B1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_state_pm_b1);
            case "percent_dpf_b1":
                if (!"PM Sensor DPF load B1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_percent_dpf_b1);
            case "state_pm_b2":
                if (!"PM Sensor status B2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_state_pm_b2);
            case "percent_dpf_b2":
                if (!"PM Sensor DPF load B2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_percent_dpf_b2);
            case "strategy_mil":
                if (!"MIL display strategy".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_strategy_mil);
            case "state_mil":
                if (!"MIL display state".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_state_mil);
            case "state_monitor":
                if (!"Emission system readyness".equals(fallback) && !"Emission system readiness".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_state_monitor);
            case "hours_mil_on":
                if (!"Operating Hours w/ MIL active".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_hours_mil_on);
            case "ecu_state_mil":
                if (!"ECU MIL display state".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_ecu_state_mil);
            case "ecu_hours_mil_on":
                if (!"ECU Operating Hours w/ MIL active".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_ecu_hours_mil_on);
            case "ecu_hours_max_mil_on":
                if (!"ECU Max. Operating Hours w/ MIL active".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_ecu_hours_max_mil_on);
            case "state_fuel_press_ctrl":
                if (!"Status Fuel pressure control".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_state_fuel_press_ctrl);
            case "state_inject_quantity_ctrl":
                if (!"Status Fuel Injection quantity control".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_state_inject_quantity_ctrl);
            case "state_inject_timing_ctrl":
                if (!"Status Fuel Injection timing control".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_state_inject_timing_ctrl);
            case "state_fuel_balance_ctrl":
                if (!"Status Fuel Balance/Contribution control".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_state_fuel_balance_ctrl);
            case "state_fuel_press_ctrl_2":
                if (!"Status Fuel pressure control 2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_state_fuel_press_ctrl_2);
            case "state_inject_quantity_ctrl_2":
                if (!"Status Fuel Injection quantity control 2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_state_inject_quantity_ctrl_2);
            case "state_inject_timing_ctrl_2":
                if (!"Status Fuel Injection timing control 2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_state_inject_timing_ctrl_2);
            case "state_fuel_balance_ctrl_2":
                if (!"Status Fuel Balance/Contribution control 2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_state_fuel_balance_ctrl_2);
            case "hours_mil_on_cont":
                if (!"Continuous Operating Hours w/ MIL active".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_hours_mil_on_cont);
            case "state_nox_warn_sys":
                if (!"NOx warning system state".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_state_nox_warn_sys);
            case "state_lvl1_induce":
                if (!"Level 1 Inducement status".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_state_lvl1_induce);
            case "state_lvl2_induce":
                if (!"Level 2 Inducement status".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_state_lvl2_induce);
            case "state_lvl3_induce":
                if (!"Level 3 Inducement status".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_state_lvl3_induce);
            case "hours_reagent_quality":
                if (!"Reagent Quality counter".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_hours_reagent_quality);
            case "hours_reagent_consum":
                if (!"Reagent Consumption counter".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_hours_reagent_consum);
            case "hours_dosing_activity":
                if (!"Dosing Activity counter".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_hours_dosing_activity);
            case "hours_egr_valve":
                if (!"EGR Valve counter".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_hours_egr_valve);
            case "hours_monitor_sys":
                if (!"Monitoring System counter".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_hours_monitor_sys);
            case "exhaust_temperature_b1s5":
                if (!"Exhaust Temperature B1S5".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_exhaust_temperature_b1s5);
            case "exhaust_temperature_b1s6":
                if (!"Exhaust Temperature B1S6".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_exhaust_temperature_b1s6);
            case "exhaust_temperature_b1s7":
                if (!"Exhaust Temperature B1S7".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_exhaust_temperature_b1s7);
            case "exhaust_temperature_b1s8":
                if (!"Exhaust Temperature B1S8".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_exhaust_temperature_b1s8);
            case "exhaust_temperature_b2s5":
                if (!"Exhaust Temperature B2S5".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_exhaust_temperature_b2s5);
            case "exhaust_temperature_b2s6":
                if (!"Exhaust Temperature B2S6".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_exhaust_temperature_b2s6);
            case "exhaust_temperature_b2s7":
                if (!"Exhaust Temperature B2S7".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_exhaust_temperature_b2s7);
            case "exhaust_temperature_b2s8":
                if (!"Exhaust Temperature B2S8".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_exhaust_temperature_b2s8);
            case "o2_percent_b1s3":
                if (!"O2 Sensor Concentration B1S3".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_percent_b1s3);
            case "o2_percent_b1s4":
                if (!"O2 Sensor Concentration B1S4".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_percent_b1s4);
            case "o2_percent_b2s3":
                if (!"O2 Sensor Concentration B2S3".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_percent_b2s3);
            case "o2_percent_b2s4":
                if (!"O2 Sensor Concentration B2S4".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_percent_b2s4);
            case "o2_lambda_b1s3":
                if (!"O2 Sensor Lambda B1S3".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_lambda_b1s3);
            case "o2_lambda_b1s4":
                if (!"O2 Sensor Lambda B1S4".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_lambda_b1s4);
            case "o2_lambda_b2s3":
                if (!"O2 Sensor Lambda B2S3".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_lambda_b2s3);
            case "o2_lambda_b2s4":
                if (!"O2 Sensor Lambda B2S4".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2_lambda_b2s4);
            case "odometer_reading":
                if (!"Odometer".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_odometer_reading);
            case "status_engine_idle_request":
                if (!"Engine IDLE Request".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_status_engine_idle_request);
            case "status_engine_stop_request":
                if (!"Engine STOP Request".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_status_engine_stop_request);
            case "counts_vin_length":
                if (!"Message count VIN".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_counts_vin_length);
            case "vin_numitems":
                if (!"Number of VIN items".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_vin_numitems);
            case "vehicle_identification_number":
                if (!"Vehicle identification number".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_vehicle_identification_number);
            case "counts_calibration_identifier_length":
                if (!"Message count CAL-ID".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_counts_calibration_identifier_length);
            case "calid_numitems":
                if (!"Number of CAL-ID items".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_calid_numitems);
            case "calibration_identifier":
                if (!"Calibration identifier".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_calibration_identifier);
            case "calibration_identifier2":
                if (!"Calibration identifier 2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_calibration_identifier2);
            case "counts_calibration_verification":
                if (!"Message count CVN".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_counts_calibration_verification);
            case "calibration_verification":
                if (!"Calibration verification".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_calibration_verification);
            case "counts_ipt":
                if (!"Message count IPT".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_counts_ipt);
            case "OBDCOMP":
                if (!"OBD Monitoring Conditions Encountered Counts".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_obdcomp);
            case "IGNCNTR":
                if (!"Ignition Counter".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_igncntr);
            case "CATCOMP1":
                if (!"Catalyst Monitor Completion Counts Bank 1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_catcomp1);
            case "CATCOND1":
                if (!"Catalyst Monitor Conditions Encountered Counts Bank 1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_catcond1);
            case "CATCOMP2":
                if (!"Catalyst Monitor Completion Counts Bank 2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_catcomp2);
            case "CATCOND2":
                if (!"Catalyst Monitor Conditions Encountered Counts Bank 2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_catcond2);
            case "O2SCOMP1":
                if (!"O2 Sensor Monitor Completion Counts Bank 1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2scomp1);
            case "O2SCOND1":
                if (!"O2 Sensor Monitor Conditions Encountered Counts Bank 1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2scond1);
            case "O2SCOMP2":
                if (!"O2 Sensor Monitor Completion Counts Bank 2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2scomp2);
            case "O2SCOND2":
                if (!"O2 Sensor Monitor Conditions Encountered Counts Bank 2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_o2scond2);
            case "EGRCOMP":
                if (!"EGR Monitor Completion Condition Counts".equals(fallback) && !"EGR and/or VVT Monitor Completion Condition Counts".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_egrcomp);
            case "EGRCOND":
                if (!"EGR Monitor Conditions Encountered Counts".equals(fallback) && !"EGR and/or VVT Monitor Conditions Encountered Counts".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_egrcond);
            case "AIRCOMP":
                if (!"AIR Monitor Completion Condition Counts (Secondary Air)".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_aircomp);
            case "AIRCOND":
                if (!"AIR Monitor Conditions Encountered Counts (Secondary Air)".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_aircond);
            case "EVAPCOMP":
                if (!"EVAP Monitor Completion Condition Counts".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_evapcomp);
            case "EVAPCOND":
                if (!"EVAP Monitor Conditions Encountered Counts".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_evapcond);
            case "SO2SCOMP1":
                if (!"Secondary O2 Sensor Monitor Completion Counts Bank 1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_so2scomp1);
            case "SO2SCOND1":
                if (!"Secondary O2 Sensor Monitor Conditions Encountered Counts Bank 1".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_so2scond1);
            case "SO2SCOMP2":
                if (!"Secondary O2 Sensor Monitor Completion Counts Bank 2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_so2scomp2);
            case "SO2SCOND2":
                if (!"Secondary O2 Sensor Monitor Conditions Encountered Counts Bank 2".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_so2scond2);
            case "counts_ecu_name_length":
                if (!"ECU name length".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_counts_ecu_name_length);
            case "ecu_name":
                if (!"ECU name".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_ecu_name);
            case "OBDCOND":
                if (!"OBD Monitoring Conditions Encountered Counts".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_obdcond);
            case "IGNCNTR2":
                if (!"Ignition Counter".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_igncntr2);
            case "HCCATCOMP":
                if (!"NMHC Catalyst Monitor Completion Condition Counts".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_hccatcomp);
            case "HCCATCOND":
                if (!"NMHC Catalyst Monitor Conditions Encountered Counts".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_hccatcond);
            case "NCATCOMP":
                if (!"NOx/SCR Catalyst Monitor Completion Condition Counts".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_ncatcomp);
            case "NCATCOND":
                if (!"NOx/SCR Catalyst Monitor Conditions Encountered Counts".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_ncatcond);
            case "NADSCOMP":
                if (!"NOx Adsorber Monitor Completion Condition Counts".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_nadscomp);
            case "NADSCOND":
                if (!"NOx Adsorber Monitor Conditions Encountered Counts".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_nadscond);
            case "PMCOMP":
                if (!"PM Filter Monitor Completion Condition Counts".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_pmcomp);
            case "PMCOND":
                if (!"PM Filter Monitor Conditions Encountered Counts".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_pmcond);
            case "EGSCOMP":
                if (!"Exhaust Gas Sensor Monitor Completion Condition Counts".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_egscomp);
            case "EGSCOND":
                if (!"Exhaust Gas Sensor Monitor Conditions Encountered Counts".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_egscond);
            case "EGRCOMP1":
                if (!"EGR and/or VVT Monitor Completion Condition Counts".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_egrcomp1);
            case "EGRCOND1":
                if (!"EGR and/or VVT Monitor Conditions Encountered Counts".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_egrcond1);
            case "BPCOMP":
                if (!"Boost Pressure Monitor Completion Condition Counts".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_bpcomp);
            case "BPCOND":
                if (!"Boost Pressure Monitor Conditions Encountered Counts".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_bpcond);
            case "FUELCOMP":
                if (!"Fuel Monitor Completion Condition Counts".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_fuelcomp);
            case "FUELCOND":
                if (!"Fuel Monitor Conditions Encountered Counts".equals(fallback)) return fallback;
                return context.getString(R.string.obd_label_fuelcond);
            default:
                return fallback;
        }
    }
}

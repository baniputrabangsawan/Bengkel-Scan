package com.fr3ts0n.ecu.gui.androbd;

import android.content.Context;
import android.content.res.Configuration;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Localizes standard status text, never sensor numbers, units, or raw ECU data. */
final class ObdUiValues
{
    private static final int[] TEXT_IDS = {
        R.string.obd_value_active, R.string.obd_value_active_regen,
        R.string.obd_value_available, R.string.obd_value_incomplete,
        R.string.obd_value_compression_monitor, R.string.obd_value_on,
        R.string.obd_value_off, R.string.obd_value_inactive,
        R.string.obd_value_enabled, R.string.obd_value_error,
        R.string.obd_value_fault_present, R.string.obd_value_not_available,
        R.string.obd_value_measuring, R.string.obd_value_reserved,
        R.string.obd_value_regenerating, R.string.obd_value_dpf_regenerating,
        R.string.obd_value_continuous, R.string.obd_value_on_demand,
        R.string.obd_value_electric, R.string.obd_value_gasoline,
        R.string.obd_value_hybrid_gasoline, R.string.obd_value_hybrid_electric,
        R.string.obd_value_hybrid_running, R.string.obd_value_bifuel_diesel,
        R.string.obd_value_bifuel_gasoline, R.string.obd_value_bifuel_electricity,
        R.string.obd_value_bifuel_running, R.string.obd_value_open_loop,
        R.string.obd_value_closed_loop, R.string.obd_value_cold_open_loop,
        R.string.obd_value_load_open_loop, R.string.obd_value_fault_open_loop,
        R.string.obd_value_sensor_closed_loop, R.string.obd_value_fault_closed_loop,
        R.string.obd_value_upstream, R.string.obd_value_downstream,
        R.string.obd_value_atmosphere, R.string.obd_value_reagent_low,
        R.string.obd_value_reagent_incorrect, R.string.obd_value_reagent_consumption,
        R.string.obd_value_nox_high, R.string.obd_value_nox_regenerating,
        R.string.obd_value_nox_desulfurizing, R.string.obd_value_pump_diagnostics,
        R.string.obd_value_not_obd, R.string.obd_value_no_codes,
        R.string.obd_value_unknown_code
    };

    final String language;
    private final Map<String, String> translations = new HashMap<>();

    ObdUiValues(Context context)
    {
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        language = configuration.locale.getLanguage();
        configuration.setLocale(Locale.ENGLISH);
        Context english = context.createConfigurationContext(configuration);
        for (int id : TEXT_IDS)
        {
            translations.put(english.getString(id), context.getString(id));
        }
    }

    String status(String value)
    {
        if (!"id".equals(language) && !"in".equals(language)) return value;
        int newline = value.indexOf('\n');
        if (newline < 0) return line(value);
        StringBuilder result = new StringBuilder(value.length());
        int start = 0;
        while (newline >= 0)
        {
            result.append(line(value.substring(start, newline))).append('\n');
            start = newline + 1;
            newline = value.indexOf('\n', start);
        }
        return result.append(line(value.substring(start))).toString();
    }

    private String line(String value)
    {
        // Preserve the original (*) / (  ) bit markers, not just the translated label.
        int markerEnd = value.startsWith("(") ? value.indexOf(")  ") : -1;
        if (markerEnd >= 0)
        {
            String label = value.substring(markerEnd + 3);
            String translated = translations.get(label);
            return translated == null ? value : value.substring(0, markerEnd + 3) + translated;
        }
        String translated = translations.get(value);
        return translated == null ? value : translated;
    }
}
